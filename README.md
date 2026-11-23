# koryki measures

Named aggregates of the tables of a [koryki](https://koryki.ai) catalog: the total revenue of `orders`,
defined once, meaning the same in every query.

A measure is **not a column**. It has no value per row and the database does not know it. It is an
unevaluated aggregate; a query that reads it asks for its value in the context of that query, and the
rows it aggregates are the rows of its table, each counted once, however the query joins or filters.
How a measure is evaluated is not part of this project's model class; see the [plan](docs/PLAN.md).

## Two parts

| Package | Part |
|---|---|
| `ai.koryki.measure.catalog` | The measure data classes, and nothing else: `Measure`, `MeasureCatalog` (getters and setters, no logic, as core's `catalog.schema`) |
| `ai.koryki.measure` | What is done with them, as core's `catalog` does with its classes: `MeasureCatalogLoader` (read, write), `MeasureCheck` (against a schema), `MeasureCopy`, `Measures` (look up, add) |
| `ai.koryki.measure.generator` | `AggRewriter`: KQL with `agg(alias.measure)` in, plain KQL out, with the columns' provenance. The pattern and its limits are in [REWRITE.md](docs/REWRITE.md) |

## Why beside the catalog, not in it

The measures are kept in a file of their own, next to the catalog, and are checked against it when they
are read. Three reasons, in the order of their weight:

- **A catalog that is written again keeps them.** Core's classes know nothing of measures, so a
  `db.json` that is read by core and written back (by a tool that introspects a database, say) would
  lose any measure it carried. A separate file is not touched.
- **No inheritance from core.** Measures in `db.json` would need a subclass of core's `Table` and a
  mapper that builds it. That works (it was tried: core reads such a file and ignores the property),
  but it ties this project to the internals of a core class, and core's own copies of a table
  (`SchemaCopy`) would drop the extension.
- **Release independence.** A change of the measure model needs no release of core.

What it costs: a table that is renamed in the catalog leaves its measures behind. The loader refuses
that, at read, naming the table.

## The file

```json
{
  "tables": {
    "orders": [
      {
        "name": "revenue",
        "label": "Revenue",
        "expression": "sum(unit_price * quantity)",
        "conceptRef": "money"
      }
    ]
  }
}
```

| Field | Description |
|---|---|
| `name` | Unique among the measures of the table, and never the name of one of its columns (any case) |
| `label`, `comment`, `description` | The wording |
| `expression` | One aggregate expression over the columns of the table, in KQL. Required. Not trusted: parse it as an expression of its own before putting it into a query |
| `concept`, `conceptRef` | The concept of the value: its own, or the name of one in the catalog's concepts. At most one of the two |

## Use

```java
Schema schema = CatalogLoader.db("/my/catalog");
MeasureCatalog measures = MeasureCatalogLoader.read(in, schema); // refused if it does not fit
Measure revenue = Measures.find(measures, "orders", "revenue").orElseThrow();
```

`MeasureCheck` refuses a table the schema lacks, a measure without a name or expression, a name used
twice or equal to a column, two concepts, and a concept the schema cannot resolve. It does not parse the
expression, and it does not ask for a primary key: the identity of the rows is for whoever evaluates the
measure to ask for.

### Ranges

Core validates and renders the **rewritten** KQL, so the `Range` of a `Violation` or a `RangeException`
points into text the user never wrote. `Rewritten.map()` carries it back:

```java
Range shown = r.map().toOriginal(violation.getRange());
```

A piece copied from the query (sources, links, filters, group keys, `LIMIT`) maps exactly, character for
character. A piece the rewriter made (the layer and aggregate blocks, `max(t.v1)`) maps to what caused it:
the `agg(...)` call, the fetch item, or else the select. The map is built across all passes, so it also
holds for `WITH` blocks, sub-queries and set operations. A query without `agg` maps to itself.

A refusal has its range too: `r.problems()` pairs each message of `r.violations()` with the `Range` of the
`agg(...)` call, fetch item, clause or (for a syntax error) token in the **user's** query, so nothing needs to be
mapped. A refusal that names nothing is put on the part the rewriter was working on, at worst the whole query.

The name `agg` is reserved: core's `FunctionValidator` refuses an `agg(...)` that reaches it
un-rewritten, at the call, instead of passing it on to the database.

### Rewrite

```java
AggRewriter rewriter = new AggRewriter(schema, model, measures);
Rewritten r = rewriter.rewrite(
    "FIND customers c, c VIA same_customer orders o, o VIA same_order order_details od "
        + "FETCH c.country, agg(o.total_freight) freight, agg(od.units) units");
r.kql();        // plain KQL: run it with any koryki engine, on any dialect
r.columns();    // the output columns that read measures, by header:
                // {freight=OutputColumn[measures=[orders.total_freight], shown=null],
                //  per_order=OutputColumn[measures=[orders.total_freight, orders.order_count],
                //                         shown={orders.total_freight} / {orders.order_count}]}
r.violations(); // why a query was refused (then kql() is null)
r.map();        // SourceMap: carries a Range of the rewritten kql() back to the query the user wrote
```

Supported: `FIND` queries (also as `WITH` blocks, scalar sub-queries and branches of a set operation) with inner and optional (`+`) links, group keys, `agg` in `FETCH` (also inside scalar
expressions) and in `AND` conjuncts of `FILTER` (they become `HAVING`), sort and limit, and `ROLLUP` over plain columns. Everything
else is refused with a reason, never guessed at; see [REWRITE.md](docs/REWRITE.md).

## Tests

File-based, against the shared fixture corpora of the repositories that own the catalogs (`measures.json` and the
queries live there, the goldens too): `koryki-java/northwind` (`./gradlew test`) and `koryki-java/tpcds`
(`./gradlew tpcdsTest`, the measures of Apache Ossie's TPC-DS model). `./gradlew check` runs both; `-Dfixtures.write=true`
writes missing goldens. A checkout is found as `-Dtest.root` / `-Dtpcds.root`, a Gradle property of the same name, or
the sibling directory. The catalog snapshots come from `mavenLocal` first (`publishToMavenLocal` in those repositories).

## Build

```bash
./gradlew build
```

Builds against `koryki-core` from Central (or `mavenLocal`, for a core change not yet published).

## Licence

Apache License 2.0, see [LICENSE](LICENSE).
