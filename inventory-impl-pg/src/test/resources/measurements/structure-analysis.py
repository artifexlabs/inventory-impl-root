#!/usr/bin/env -S uv run --quiet --script
# /// script
# requires-python = ">=3.11"
# dependencies = []
# ///
"""
structure-analysis — PLAN.md Phase 23 stage 1's question, asked of a WHOLE medium
from its find-shaped manifest, without a database and without a JVM.

    ./structure-analysis.py fold  manifest.tsv.gz --root /mnt/mediaX --db structure.sqlite
    ./structure-analysis.py report structure.sqlite [--floor 8] [--top 40]
    ./structure-analysis.py dump  fixture.tsv --root /fixture      # every dir, for conformance

WHY THIS EXISTS. `DataTree.build` holds a medium's entries in memory (Phase 23
recorded "the streaming fold" as not adopted). 179,316,708 entries do not fit a
JVM, so this script folds the tree as a STREAM: a manifest produced by `find`
is depth-first, which means once a directory's last descendant has been read
the directory is complete and can be hashed and forgotten. Memory is
O(depth + widest directory), and the run is one pass over the gzip.

THE HASHES MUST BE BIT-IDENTICAL TO `MerkleHash` in inventory-api, or every
answer here is fiction. The rules copied from that class, deliberately in the
same order and with the same comment:

    structure(f) = H("blob" ‖ len4(name) ‖ name ‖ size_be64)
    structure(D) = H("tree" ‖ for c in sorted(children, unsigned bytes): len4(c) ‖ c)

  * domain separation: leaves "blob", nodes "tree" (git-style)
  * every variable-length field is 4-byte big-endian length-prefixed
  * children sorted by their HASH BYTES, unsigned — Python's bytes comparison
    is exactly that; a directory's own NAME is in NO digest (a subtree
    contributes its hash unqualified), which is what lets a moved AND renamed
    folder match its twin
  * an empty directory is H("tree") over nothing — a real, distinct value

`dump` prints every directory the way `DataTree.build` would, and
StructureConformance.java runs the real Java code over the same fixture. The
two outputs must be identical before `fold` is trusted on the real listing;
results/README.md records when that check was last run and against which
inventory-api version.
"""
from __future__ import annotations

import argparse
from bisect import bisect_left
import gzip
import hashlib
import os
import sqlite3
import struct
import sys
import time

BLOB = b"blob"
TREE = b"tree"


def lp(b: bytes) -> bytes:
    """Four-byte big-endian length, then the bytes — MerkleHash.writeLengthPrefixed."""
    return struct.pack(">I", len(b)) + b


def structure_of_file(name: str, size: int) -> bytes:
    return hashlib.sha256(BLOB + lp(name.encode("utf-8")) + struct.pack(">q", size)).digest()


def structure_of_dir(children: list[bytes]) -> bytes:
    children.sort()  # bytes compare unsigned-lexicographically: MerkleHash.UNSIGNED
    h = hashlib.sha256(TREE)
    for c in children:
        h.update(lp(c))
    return h.digest()


# ---------------------------------------------------------------------------
# the streaming fold
# ---------------------------------------------------------------------------

SEP = b"\x00"   # path separator in the sorted key space: NUL cannot occur in a file name, and it
                # sorts below every other byte, so a directory's whole subtree is CONTIGUOUS. "/" itself
                # would not do: "/" sorts after "-", which would interleave "a/x" with a sibling "a-b".


def key_of(rel: str) -> bytes:
    return rel.encode("utf-8").replace(b"/", SEP)


def collect_empties(manifest: str, root: str) -> list[bytes] | None:
    """Pre-pass: the empty-directory lines a `find -type d -empty` appended AFTER every file line.
    They cannot be folded in stream order (their ancestors may already be closed), so they are
    gathered first — as sorted keys, ~120 bytes each; 11.7M of them fit — and injected as each
    ancestor closes. Names are decoded the way the server would (decode_name), so a key under a
    frame whose name carried invalid UTF-8 still matches that frame. Returns None when none."""
    root_b = root.rstrip("/").encode("utf-8") + b"/"
    keys: list[bytes] = []
    for raw in open_lines(manifest):
        if not raw.startswith(b"\t\t"):
            continue
        parts = raw.rstrip(b"\r\n").split(b"\t", 2)
        if len(parts) != 3 or not parts[2].startswith(root_b):
            continue
        rel, _ = decode_name(parts[2][len(root_b):].rstrip(b"/"))
        if rel:
            keys.append(key_of(rel))
    keys.sort()
    return keys or None


def empty_children(keys: list[bytes], parent_key: bytes):
    """The immediate child names, under `parent_key`, of every empty directory below it — each
    yielded once, with its own subtree skipped by bisect, so a close costs O(children · log n)."""
    prefix = parent_key + SEP if parent_key else b""
    lo = bisect_left(keys, prefix) if prefix else 0
    hi = bisect_left(keys, parent_key + b"\x01") if parent_key else len(keys)
    i = lo
    while i < hi:
        comp = keys[i][len(prefix):].split(SEP, 1)[0]
        yield comp.decode("utf-8")
        i = bisect_left(keys, prefix + comp + b"\x01", i, hi)


class Frame:
    __slots__ = ("path", "depth", "children", "files", "bytes", "closed")

    def __init__(self, path: str, depth: int):
        self.path = path            # "" for the root
        self.depth = depth
        self.children: list[bytes] = []
        self.files = 0
        self.bytes = 0
        self.closed: set[str] = set()   # subdirectory names already finalized (DFS violation detector)


def decode_name(b: bytes) -> tuple[str, bool]:
    """What the SERVER would store for these bytes. A path reaches the api as a Java String, so a name
    that is not valid UTF-8 arrives with U+FFFD replacement characters and MerkleHash hashes that —
    the fold must do the same to stay bit-identical. The flag says it happened: such a file's stored
    path is no longer its path on disk, so no hasher can ever open it. Counted and reported."""
    try:
        return b.decode("utf-8"), False
    except UnicodeDecodeError:
        return b.decode("utf-8", "replace"), True


def open_lines(path: str):
    opener = gzip.open if path.endswith(".gz") else open
    with opener(path, "rb") as fh:
        for raw in fh:
            yield raw


def fold(manifest: str, root: str, on_dir, floor: int, empties: list[bytes] | None, progress: bool = True):
    """Stream the manifest; call on_dir(path, depth, hash, files, bytes, parent) for every
    directory whose subtree holds >= floor files (the root always). Returns totals."""
    root_b = root.rstrip("/").encode("utf-8") + b"/"
    stack: list[Frame] = [Frame("", 0)]
    n = 0
    violations = 0
    skipped = 0
    empty_lines = 0
    bad_utf8 = 0
    t0 = time.time()

    def virtual(path: str, depth: int, parent: str) -> bytes:
        """A directory that exists only because of trailing-slash lines beneath it (or IS one):
        no files anywhere below, so its hash is a tree over its own virtual children — and an
        empty directory is H("tree") over nothing, a real, distinct value."""
        kids = [virtual(path + "/" + name, depth + 1, path) for name in empty_children(empties, key_of(path))]
        h = structure_of_dir(kids)
        if floor <= 0:
            on_dir(path, depth, h, 0, 0, parent)
        return h

    def close_top():
        nonlocal stack
        top = stack.pop()
        # subdirectories that never opened as frames because nothing below them is a file:
        # empty directories, and chains of directories holding only empty directories
        if empties is not None:
            for name in empty_children(empties, key_of(top.path)):
                if name not in top.closed:
                    top.children.append(virtual(top.path + "/" + name if top.path else name, top.depth + 1, top.path))
        h = structure_of_dir(top.children)
        parent = stack[-1] if stack else None
        if parent is not None:
            parent.children.append(h)
            parent.files += top.files
            parent.bytes += top.bytes
            parent.closed.add(top.path.rsplit("/", 1)[-1])
        if top.files >= floor or parent is None:
            on_dir(top.path, top.depth, h, top.files, top.bytes, parent.path if parent else None)

    for raw in open_lines(manifest):
        n += 1
        if progress and n % 5_000_000 == 0:
            print(f"    ...{n:,} lines, depth {len(stack)-1}, {time.time()-t0:,.0f}s", file=sys.stderr, flush=True)
        line = raw.rstrip(b"\r\n")
        if not line:
            continue
        parts = line.split(b"\t", 2)   # the server's rule: a tab INSIDE a name stays in the path
        if len(parts) < 3:
            skipped += 1               # ...and a line the server would REFUSE the whole manifest for
            continue
        size_b, _mtime, path_b = parts
        if not path_b.startswith(root_b):
            skipped += 1
            continue
        rel = path_b[len(root_b):]
        if size_b == b"":
            # an empty-directory line; folded from the pre-pass trie, not from here
            empty_lines += 1
            continue
        size = int(size_b)
        rel_s, bad = decode_name(rel)
        if bad:
            bad_utf8 += 1
        parent, _, name = rel_s.rpartition("/")

        # unwind to the deepest open ancestor of `parent`
        while stack and stack[-1].path != parent and not (parent + "/").startswith(stack[-1].path + "/" if stack[-1].path else ""):
            close_top()
        # descend: open every component between the stack top and `parent`
        top = stack[-1]
        if parent != top.path:
            remainder = parent[len(top.path) + 1:] if top.path else parent
            for comp in remainder.split("/"):
                if comp in top.closed:
                    violations += 1   # DFS order broken: this directory was already finalized
                child = Frame(top.path + "/" + comp if top.path else comp, top.depth + 1)
                stack.append(child)
                top = child
        top.children.append(structure_of_file(name, size))
        top.files += 1
        top.bytes += size

    while stack:
        close_top()
    return {"lines": n, "skipped": skipped, "dfs_violations": violations, "empty_lines": empty_lines,
            "bad_utf8": bad_utf8, "seconds": time.time() - t0}


# ---------------------------------------------------------------------------
# commands
# ---------------------------------------------------------------------------

def cmd_fold(a):
    if os.path.exists(a.db):
        os.remove(a.db)
    db = sqlite3.connect(a.db)
    db.execute("PRAGMA journal_mode=OFF")
    db.execute("PRAGMA synchronous=OFF")
    db.execute("CREATE TABLE dirs (path TEXT NOT NULL, parent TEXT, depth INT NOT NULL, hash BLOB NOT NULL,"
               " files INT NOT NULL, bytes INT NOT NULL)")
    batch = []

    def on_dir(path, depth, h, files, byts, parent):
        batch.append((path, parent, depth, h, files, byts))
        if len(batch) >= 20_000:
            db.executemany("INSERT INTO dirs VALUES (?,?,?,?,?,?)", batch)
            batch.clear()

    print(f"  folding {a.manifest} under {a.root} (floor {a.floor})", file=sys.stderr, flush=True)
    empties = None
    if a.empty_dirs:
        print("  pre-pass: collecting empty-directory lines", file=sys.stderr, flush=True)
        empties = collect_empties(a.manifest, a.root)
        print(f"  pre-pass: {'none found' if empties is None else 'collected'}", file=sys.stderr, flush=True)
    totals = fold(a.manifest, a.root, on_dir, a.floor, empties)
    if totals["empty_lines"] and empties is None:
        print(f"  !! {totals['empty_lines']:,} empty-directory lines were present but --empty-dirs was not given;"
              " directory hashes above them are WRONG. Re-run with --empty-dirs.", file=sys.stderr)
    if batch:
        db.executemany("INSERT INTO dirs VALUES (?,?,?,?,?,?)", batch)
    db.commit()
    print("  indexing", file=sys.stderr, flush=True)
    db.execute("CREATE INDEX ix_hash ON dirs(hash)")
    db.execute("CREATE INDEX ix_path ON dirs(path)")
    db.execute("CREATE TABLE meta (k TEXT PRIMARY KEY, v TEXT)")
    db.executemany("INSERT INTO meta VALUES (?,?)", [
        ("manifest", a.manifest), ("root", a.root), ("floor", str(a.floor)),
        ("lines", str(totals["lines"])), ("skipped", str(totals["skipped"])),
        ("dfs_violations", str(totals["dfs_violations"])), ("seconds", f"{totals['seconds']:.0f}"),
        ("bad_utf8", str(totals["bad_utf8"])),
    ])
    db.commit()
    n_dirs = db.execute("SELECT count(*) FROM dirs").fetchone()[0]
    print(f"  done: {totals['lines']:,} lines, {n_dirs:,} directories kept (>= {a.floor} files), "
          f"{totals['dfs_violations']} DFS violations, {totals['skipped']} skipped lines, "
          f"{totals['bad_utf8']:,} names not valid UTF-8 (stored with U+FFFD, unopenable by any hasher), "
          f"{totals['seconds']:,.0f}s", file=sys.stderr)
    if totals["dfs_violations"]:
        print("  !! DFS violations mean the listing is not depth-first and the hashes above them are WRONG",
              file=sys.stderr)


def cmd_dump(a):
    rows = []
    fold(a.manifest, a.root, lambda p, d, h, f, b, parent: rows.append((p, d, h.hex(), f, b)), 0,
         collect_empties(a.manifest, a.root), progress=False)
    for p, d, h, f, b in sorted(rows):
        print(f"{p}\t{d}\t{h}\t{f}\t{b}")


def human(n: int) -> str:
    for unit in ("B", "KB", "MB", "GB", "TB", "PB"):
        if n < 1024:
            return f"{n:,.1f} {unit}" if unit != "B" else f"{n} B"
        n /= 1024
    return f"{n:,.1f} EB"


def cmd_report(a):
    db = sqlite3.connect(a.db)
    meta = dict(db.execute("SELECT k, v FROM meta"))
    root = db.execute("SELECT files, bytes FROM dirs WHERE path = ''").fetchone()
    W = 96
    print("=" * W)
    print("  STRUCTURE ANALYSIS — duplicated sections of one medium (PLAN.md Phase 23, stage 1 question)")
    print("=" * W)
    print(f"  manifest       {meta['manifest']}")
    print(f"  root           {meta['root']}")
    print(f"  files          {root[0]:,}")
    print(f"  bytes          {root[1]:,}  ({human(root[1])})")
    print(f"  directories    {db.execute('SELECT count(*) FROM dirs').fetchone()[0]:,} with >= {meta['floor']} files "
          f"(smaller ones folded but not kept — the floor, see DataSystem.SectionQuery.sane)")
    print(f"  fold           {int(meta['lines']):,} lines in {meta['seconds']}s; DFS violations {meta['dfs_violations']}; "
          f"skipped {meta['skipped']}; names not valid UTF-8 {int(meta.get('bad_utf8', 0)):,} "
          f"(stored with U+FFFD — a known gap: no hasher can open them by their stored path)")

    # ---- snapshot generations ------------------------------------------
    gens = db.execute("""
        SELECT path, hash, files, bytes FROM dirs
         WHERE path GLOB '.snapshots/*/snapshot' AND depth = 3 ORDER BY CAST(substr(path, 12, instr(substr(path, 12), '/') - 1) AS INT)""").fetchall()
    print()
    print(f"  SNAPSHOT GENERATIONS: {len(gens)}")
    if gens:
        distinct = len({g[1] for g in gens})
        print(f"    structurally distinct generations: {distinct} of {len(gens)}")
        prev = None
        same_as_prev = 0
        for p, h, f, b in gens:
            if prev is not None and h == prev:
                same_as_prev += 1
            prev = h
        print(f"    generations structurally IDENTICAL to the previous one: {same_as_prev}")
        for p, h, f, b in gens[-5:]:
            print(f"    {p:40s} {f:>14,} files {human(b):>12}  {h.hex()[:16]}")
        if len(gens) > 5:
            print(f"    ... ({len(gens) - 5} earlier generations)")
    live = db.execute("SELECT count(*), coalesce(sum(files),0), coalesce(sum(bytes),0) FROM dirs WHERE depth = 1 AND path <> '.snapshots'").fetchone()
    print(f"  top-level entries OUTSIDE .snapshots: {live[0]:,} directories, {live[1]:,} files, {human(live[2])}")

    # ---- duplicated sections, maximal only --------------------------------
    def duplicates(scope_glob: str | None, label: str):
        where = "WHERE d.path GLOB ?" if scope_glob else ""
        args = (scope_glob,) if scope_glob else ()
        db.execute("DROP TABLE IF EXISTS grp")
        db.execute(f"""
            CREATE TEMP TABLE grp AS
            SELECT hash, count(*) AS places, max(files) AS files, max(bytes) AS bytes
              FROM dirs d {where} GROUP BY hash HAVING count(*) > 1""", args)
        db.execute("CREATE INDEX IF NOT EXISTS ix_grp ON grp(hash)")
        # maximal: a duplicated directory whose PARENT is not itself duplicated —
        # otherwise every child of a copied folder is reported again
        rows = db.execute(f"""
            SELECT g.hash, g.places, g.files, g.bytes
              FROM grp g
             WHERE EXISTS (
               SELECT 1 FROM dirs d
                WHERE d.hash = g.hash {('AND d.path GLOB ?') if scope_glob else ''}
                  AND NOT EXISTS (SELECT 1 FROM dirs p JOIN grp pg ON pg.hash = p.hash WHERE p.path = d.parent))
             ORDER BY g.bytes * (g.places - 1) DESC LIMIT ?""", args + (a.top,)).fetchall()
        total = db.execute("SELECT count(*), coalesce(sum(bytes * (places - 1)), 0) FROM grp").fetchone()
        print()
        print(f"  DUPLICATED SECTIONS — {label}")
        print(f"    duplicated identities (>= floor): {total[0]:,}; naive redundant bytes across ALL of them "
              f"(nested copies counted repeatedly): {human(total[1])}")
        print(f"    top {a.top} MAXIMAL sections by redundant bytes (parent not itself duplicated):")
        print(f"    {'places':>6} {'files':>12} {'bytes/copy':>12} {'redundant':>12}  where")
        for h, places, files, byts in rows:
            wheres = [r[0] for r in db.execute(
                f"SELECT path FROM dirs WHERE hash = ? {('AND path GLOB ?') if scope_glob else ''} ORDER BY path LIMIT 4",
                (h,) + args)]
            first = wheres[0] if wheres else "?"
            print(f"    {places:>6} {files:>12,} {human(byts):>12} {human(byts * (places - 1)):>12}  {first}")
            for w in wheres[1:]:
                print(f"    {'':>6} {'':>12} {'':>12} {'':>12}  {w}")
            if places > len(wheres):
                print(f"    {'':>6} {'':>12} {'':>12} {'':>12}  ... and {places - len(wheres)} more")

    duplicates(None, "whole medium (generations of a snapshot will dominate; that is real, and it is the boring answer)")
    if gens:
        latest = gens[-1][0]
        duplicates(latest + "/*", f"WITHIN the latest generation only ({latest}) — the interesting answer")
        # a snapshot taken mid-way is small; the latest FULL generation is the one to act on
        fullest = max(gens, key=lambda g: g[2])
        if fullest[0] != latest and fullest[2] > 3 * gens[-1][2]:
            duplicates(fullest[0] + "/*", f"WITHIN the latest FULL generation ({fullest[0]}, {fullest[2]:,} files) — "
                       f"the latest one holds only {gens[-1][2]:,}")
    if live[0]:
        duplicates("[!.]*", "outside .snapshots only")


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    sub = ap.add_subparsers(dest="cmd", required=True)
    f = sub.add_parser("fold", help="stream the manifest into a sqlite of directory rows")
    f.add_argument("manifest")
    f.add_argument("--root", required=True, help="the prefix every path carries, e.g. /mnt/mediaX")
    f.add_argument("--db", default="structure.sqlite")
    f.add_argument("--floor", type=int, default=8, help="keep directories with at least this many files")
    f.add_argument("--empty-dirs", action="store_true",
                   help="the listing has `find -type d -empty` lines appended; pre-pass to fold them correctly")
    f.set_defaults(fn=cmd_fold)
    d = sub.add_parser("dump", help="every directory (floor 0), sorted, for conformance against DataTree.build")
    d.add_argument("manifest")
    d.add_argument("--root", required=True)
    d.set_defaults(fn=cmd_dump)
    r = sub.add_parser("report", help="the duplicated-sections answer from a folded sqlite")
    r.add_argument("db")
    r.add_argument("--top", type=int, default=40)
    r.set_defaults(fn=cmd_report)
    a = ap.parse_args()
    a.fn(a)


if __name__ == "__main__":
    main()
