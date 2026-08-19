# inventory-impl-root

Aggregator for the inventory implementation modules, released together as
**one version** (artifact separation without version separation):

- **inventory-impl** — core: domain implementations, in-memory twins, bus
  verticles, label/QR/catalog machinery, `Gtin`/`Ulid`.
- **inventory-impl-pg** — the `Pg*` Postgres backends plus the Liquibase
  changelogs (shipped inside the jar under `db/`), depends on core.

The memory/Pg parity tests run in a single reactor pass here, which is the
reason the modules share a repo and a version. Consumers that talk to
Postgres depend on `inventory-impl-pg` (core arrives transitively);
memory-only consumers depend on `inventory-impl`.

Part of the inventory workspace — see `MAVEN_RELEASES.md` in
[inventory-root](https://github.com/mykelalvis/inventory-root) for the
extraction/release plan, and `PLAN.md` there for project history.

## Build

```
mvn clean install
```

Requires `io.artifexlabs.inventory:inventory-parent` (and its parent,
`io.artifexlabs.parents:artifex-maven-parent`, which resolves from Maven
Central) to be resolvable — in the workspace, `just libs` builds everything
in the right order.
