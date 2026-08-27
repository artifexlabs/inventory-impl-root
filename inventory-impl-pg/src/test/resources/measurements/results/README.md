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
