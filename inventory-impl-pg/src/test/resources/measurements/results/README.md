# Measurement runs

Each run is captured here so results are comparable across trees and dates.
Name them `<tree>-<state>-<date>.txt`, e.g. `nas120t-partial-2026-08-27.txt`.

## Reading a PARTIAL find with care

`find` walks depth-first, so a partial output file is **not** a random slice of the
filesystem — it is whichever subtrees it reached first, complete, and none of the
rest. The script's reservoir samples uniformly *from the file*, but it cannot undo
a bias already baked into the file.

That matters most for **Q2 (does the dictionary earn its keep?)**, which is a
question about *component vocabulary saturation*:

- A partial tree has a **narrower vocabulary** than the whole (fewer distinct
  directory and file names), so components repeat more.
- Higher reuse makes `path_elements` look **better** than it will on the full tree.
- So a partial run gives an **optimistic** Q2 answer. Treat it as a lower bound on
  vocabulary and an upper bound on the dictionary's payoff.

Q1 (trgm) and Q3 (findMirrorsOf) are much less sensitive: they turn on row counts
and query shape, not on vocabulary breadth.

## Making runs comparable

Record for every run: the source file, the `population` and `sampled` lines the
script prints, the `--scales` used, and roughly what fraction of the tree the
`find` had covered. The interesting number across runs is not any single latency
but how **vocab(N)** moves — `rows^k` where k<1 means saturating. If k rises as the
tree gets more complete, the dictionary's payoff is smaller than the partial run
suggested.

## Runs on record

**mediaX-partial-2026-08-27** — 11,532,568 paths (the find was still running),
reservoir 5M (43.4%), scales 500k/2M/5M.

**mediaX-full-2026-08-28** — the completed find: **179,316,708 paths**
(15.5x the partial), read straight from `paths-002.tgz`, reservoir 5M (2.8%),
same scales and seed for a like-for-like diff. What moved and what did not:

- **Every shipped decision held.** trgm at the biggest scale: 873 ms seq scan
  → 35 ms (24.8x, WORTH IT; partial said 745→40). The composite mirror index
  is still useless (1.1x; the per-file mirror query it served is gone —
  Phase 23 replaced it with the aggregate `findOverlappingMedia`). Q2's
  storage picture is within a few percent of the partial run at every scale —
  the README's warning that a partial tree flatters the dictionary turned out
  not to bite: the full tree's vocabulary saturates the same way.
- **Depth deepened slightly**: avg 16.21 components vs 15.62.
- **One number to plan around, not a decision change**: the trgm index cost
  26.7 s / 152 MB at 566k rows and 319 s / 1.25 GB at 5.65M. Linear
  extrapolation to the real 179M-row medium is ~3 hours of build and
  ~40 GB of index — a one-time cost per re-describe-from-scratch, but the
  first time the catalog run will feel it.

## Structure analysis (structure-analysis.py)

The other question — not "which index shape" but **"where are the duplicated
sections of this medium"** — asked of the whole listing without a database or
a JVM. `fold` streams a find-shaped manifest (size, mtime, path; the RUNBOOK
recipe) into directory rows with structure hashes; `report` answers from them.

**The hashes must be bit-identical to `MerkleHash` / `DataTree.build`** or the
answers are fiction, so `StructureConformance.java` runs the real api code over
`fixture.tsv` and `dump` prints the Python fold's view of the same tree. Run
both and diff whenever either side changes:

```sh
(cd ../../../../../inventory-api && mvn -q dependency:build-classpath -Dmdep.outputFile=/tmp/api-cp.txt)
java -cp ../../../../../inventory-api/target/classes:$(cat /tmp/api-cp.txt) StructureConformance.java fixture.tsv /fixture > /tmp/java.tsv
./structure-analysis.py dump fixture.tsv --root /fixture > /tmp/py.tsv
diff /tmp/java.tsv /tmp/py.tsv && echo conformant
```

| date | inventory-api | result |
| --- | --- | --- |
| 2026-08-29 | 0.2.1-SNAPSHOT (develop `8c9a9d3`) | IDENTICAL — 17 directories: unicode names, three empty directories, a chain of directories holding only empty directories, a relocated duplicate subtree |

A manifest that includes the RUNBOOK's second `find` (empty directories,
`\t\t<path>/` lines appended after every file) needs `fold --empty-dirs`: those
lines cannot be folded in stream order because their ancestors may already be
closed, so a pre-pass gathers them first. `fold` warns if it saw such lines
without the flag.

**mediaX-structure-2026-08-29** — the first structure-hash answer over the
WHOLE medium, from `manifest.tsv.gz` (the RUNBOOK's two-find recipe: files
with size and mtime, then empty directories), folded in 1,088 s with
`--empty-dirs`. Cross-checked: an independent `awk` pass agrees to the byte
(131,553,483 lines; 11,701,972 empty directories; 1,523,412,856,759,774 bytes).

- **What the medium is.** 119,851,226 files, 1.4 PB *logical*, in **13
  snapshot generations** of ~9.96M files / ~113 TB each — the 120 TB
  filesystem seen thirteen times, which is why the "whole medium" section is
  dominated by generation-to-generation copies (11 structurally distinct
  generations; 2 identical to their predecessor). Nothing sits outside
  `.snapshots`. The latest generation (5625) is partial: 313,727 files.
- **Correction to the earlier runs' framing**: `paths-002.tgz` (179.3M
  paths) listed files AND directories — the medium is ~120M files across
  ~59M directories, roughly two files per directory, and **one directory in
  five is empty**.
- **The interesting answer — within the latest FULL generation (5620):**
  81,513 duplicated identities at the floor of 8 files, ~19.3 TB of naive
  logical redundancy, and the maximal sections are plainly actionable: a
  calibre library present in three backup drives (310 GB per copy), an
  `ExtremeSSD1` copied whole into `mediaD/BackupOfExtremeSSD1` (324 GB), a
  `MusicFiles` tree on two drives (299 GB), `_BACKUP/Books/OLD` nested inside
  its own copy (239 GB), the same Blade Runner set in `HELD` and `MEDIAB2`
  (128 GB), calibre `tmpV`/`V`/`T` staging areas copied five and six times.
  Structure only — names and sizes — so a strong signal, not proof; the
  hasher's content pass upgrades each to MERKLE.
- **Data-quality findings that affect ingest**: 297 malformed lines from
  file names containing **newlines** (the server's parser would refuse the
  whole manifest at line 385,432 — RUNBOOK now says so); 12 names containing
  tabs (fine; the parser splits on the first two tabs only); **410 names not
  valid UTF-8**, which any Java path stores with U+FFFD — those files can
  never be opened by their stored path, a gap Phase 25's scan format must
  address.
- **The fold itself**: `find` output is depth-first — 0 DFS violations across
  131.5M lines — so a stack of open directories folds the tree in one pass
  with memory O(depth + widest directory). This is the streaming fold Phase
  23 recorded as "not adopted" for the server, proven here in Python against
  the Java reference (conformance table above) and now a known quantity for
  Phase 25 step 0.
