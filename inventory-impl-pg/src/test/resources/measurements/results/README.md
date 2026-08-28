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
