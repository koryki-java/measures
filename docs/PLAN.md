# Ossie support for koryki: plan

## Context

Apache Ossie is a YAML semantic-model interchange format. It has a query layer (Layer 2) in which a *measure*
is a metric attached to a dataset and read only through `AGG(measure)`. Evaluated this way, a measure counts every
source row once, however the query joins or filters (no fan-out, no chasm trap).
The official converters (apache/ossie-converters) convert metadata only. Nothing in the Apache project generates
production SQL for `AGG()`; the repo ships a spec, query compliance cases and a roadmap item "reference compiler".

koryki already has a catalog (`ai.koryki.catalog`), a KQL to IQL to SQL pipeline for 8 dialects, an Ossie expression
grammar (the local `ossie-expression` project was removed; the official `OssieExpression.g4` is in apache/ossie `core-spec/grammar`), and in `derivation` fan-out/chasm-trap *validators*
that warn about the problem that `AGG()` solves. The goal: import and export Ossie models, add measures to the
catalog, evaluate them with `agg()` in KQL, and recognise measures in result columns.

## Decisions 

| Question | Decision |
|---|---|
| Licence of `ossie-converter` | Apache-2.0, upstreamable. Depends on public `koryki-core` only, never on `derivation`. |
| Measure definition | A data-only `Measure` in the project **`koryki-java/measures`** (Apache-2.0, on top of `koryki-core`), kept in a **sidecar file** beside `db.json`. Not in core, and not as an extension of core's classes. See "Where measures are kept". |
| AGG rewrite | A **dialect-independent preprocessor** in front of SQL generation. **Contract: KQL in, KQL out** (`WITH` blocks), returned with provenance: `Rewritten { kql, output alias -> measure, violations }`. IQL stays a possible second backend, not the contract. Decided: a package of this project, `ai.koryki.measure.generator` (definition and evaluation change together), next to `ai.koryki.measure.catalog` (the data classes; what is done with them is in `ai.koryki.measure`). |
| Core changes | **None needed so far.** The rewriter uses only the public `KQLReader`, `LinkResolver` and `Schema`. A reserved `agg` function in core that rejects un-rewritten input with a clear message is optional. |
| Untrusted expression | `Measure.expression` is catalog text. Before the rewriter puts it into KQL it parses it as an expression of its own and checks that the whole text was consumed, so that it cannot add clauses (see core `docs/INJECTION.md`). |

## Where measures are kept

Three places were weighed. Measured, not assumed: a spike extended core's `Table` from outside core.

| | Core (`Table.measures`) | Extended catalog (subclass in `measures`) | Sidecar (chosen) |
|---|---|---|---|
| One file, one load | yes | yes | no, two files |
| Dangling reference possible | no | no | yes: a renamed table leaves its measures behind; the loader refuses it |
| Survives `db.json` being written again by core's classes | n/a | **no**: core's `SchemaCopy` and any read-and-write with `Schema` drop the extension silently | yes |
| Ties the project to core internals | the concept, in core's public API on Central | **yes**: a subclass of `Table` plus a Jackson mix-in | no, only `Schema` is read |
| Needs a core release to change | **yes** | no | no |
| Core's loader still reads the file | yes | **yes**: Jackson 3 ignores the unknown property (measured) | n/a |

**Correction.** Earlier notes said an older core jar would reject a catalog that has `measures`. That is wrong:
core's loader ignores unknown properties. The sidecar is not chosen for that reason.

**What the spike showed.** A subclass `MeasureTable extends Table` with a mix-in
`@JsonDeserialize(as = MeasureTable.class)` makes a mapper read the same `db.json` into extended tables, and
writes them back with `measures`. So an extended catalog is feasible, at about 30 lines. What decided against it
is the silent loss in core's own copy and write paths and the inheritance from a core class. In core's main code
only `Relation` is copied today (`LinkResolver`), so the loss is a risk for tools and later code, not a bug now.

**Kept open on purpose.** `MeasureCatalog` hides where measures are stored. If one file proves worth the
coupling, an extended-catalog reader can be added to `MeasureCatalogLoader` without changing its users.

