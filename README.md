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
| `ai.koryki.measure.catalog` | The measure definitions: `Measure`, the sidecar file, loading and checking against a catalog |
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
Measure revenue = measures.find("orders", "revenue").orElseThrow();
```

`MeasureCheck` refuses a table the schema lacks, a measure without a name or expression, a name used
twice or equal to a column, two concepts, and a concept the schema cannot resolve. It does not parse the
expression, and it does not ask for a primary key: the identity of the rows is for whoever evaluates the
measure to ask for.

### Rewrite

```java
AggRewriter rewriter = new AggRewriter(schema, model, measures);
Rewritten r = rewriter.rewrite(
    "FIND customers c, c VIA same_customer orders o, o VIA same_order order_details od "
        + "FETCH c.country, agg(o.total_freight) freight, agg(od.units) units");
r.kql();        // plain KQL: run it with any koryki engine, on any dialect
r.measures();   // {freight=orders.total_freight, units=order_details.units}
r.violations(); // why a query was refused (then kql() is null)
```

Supported: one `FIND` with inner and optional (`+`) links, group keys, `agg` in `FETCH` (also inside scalar
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
