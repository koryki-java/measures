# The KQL the rewriter emits

`ai.koryki.measure.generator.AggRewriter` implements the pattern below (first version, DuckDB tests in
`AggRewriterTest`, each answer compared with a plain truth query). The rest of this page is the spike that
it rests on, and the list of what it does not do yet.

## Spike results

Status: **spike, six of eight dialects.** Everything below was run against the Northwind DuckDB with koryki-core 0.4.2 and
compared with a plain query that has no fan-out. The spike code was thrown away; the patterns are here.

Goal: rewrite a KQL query that reads measures with `agg(...)` into KQL without `agg`, so that each measure counts
each of its source rows once per group (Ossie G2), on every dialect, with nothing but KQL constructs that core
already renders.

## The pattern that works

One **distinct layer** per source table of the measures, one **aggregate block** per layer, a **pad-and-union
block**, and an **outer merge**. The query is: customers, their orders, the lines of the orders; two measures,
`freight` (sum of `orders.freight`) and `units` (sum of `order_details.quantity`), grouped by country.

```
WITH d1 AS (
 FIND customers c, c VIA same_customer orders o, o VIA same_order order_details od
 FETCH DISTINCT o.order_id oid, c.country k, o.freight f
),
d2 AS (
 FIND customers c, c VIA same_customer orders o, o VIA same_order order_details od
 FETCH DISTINCT od.order_id oid, od.product_id pid, c.country k, od.quantity q
),
m1 AS (FIND d1 x FETCH x.k k, sum(x.f) v1),
m2 AS (FIND d2 y FETCH y.k k, sum(y.q) v2),
u AS (
 FIND m1 a
 FETCH a.k k, a.v1 v1, to_integer(NULL) v2
 UNIONALL
 FIND m2 b
 FETCH b.k k, to_decimal(NULL, 18, 4) v1, b.v2 v2
)
FIND u t
FETCH t.k, max(t.v1) freight, max(t.v2) units
```

1. **Distinct layer** `dN`: the *whole* original `FIND` tree and its filters, then `FETCH DISTINCT` of the identity of
   the measure's table (its primary key, all columns, e.g. `order_id, product_id` for `order_details`), the group keys
   and the arguments of the aggregate. This is Ossie's reference strategy (section 9) without `ANY_VALUE`: the
   arguments are functionally determined by the identity, so one row per identity is exact.
2. **Aggregate block** `mN`: the measure's aggregate over its distinct layer, grouped by the keys. Measures of one
   table share a layer (`sum`, `count`, `avg` of `orders` all read `d1`).
3. **Pad-and-union** `u`: every block is padded with typed NULLs for the columns of the others, joined by
   `UNIONALL`. The padding sits in a level of its own so that no aggregate select has a constant to group by.
4. **Outer merge**: group by the keys, `max` of each measure column. Each key has one row per measure, the others
   are NULL, so `max` returns the value (`max` also works for text and dates).

## What was verified (DuckDB)

| Case | Result |
|---|---|
| Plain fan-out `sum(o.freight)` over customers, orders, lines | **wrong**: Argentina 1772.67, the truth is 598.58 |
| The pattern above, freight and units per country | equals the truth queries on all rows shown (Argentina 598.58 / 339) |
| `sum`, `count`, `avg` of the same table, per country | all equal the truth (Argentina 598.58, 16, 37.41125) |
| Nullable group key (`region`) | the NULL group is kept, 38239.05 freight and 30841 units, as the truth |
| No group key (grand total) | one row, freight 64942.69, as the truth |
| Integer measures | stay integers when the padding is `to_integer(NULL)` |
| Expression as aggregate argument over a distinct layer | works (`unit_price * quantity`) |

## Filters, HAVING, ordering (DuckDB)

Germany, freight (orders) and units (order lines), compared with plain truth queries.

| Case | How it is written | Result |
|---|---|---|
| Filter on a dimension | the original `FILTER` is copied into **every** distinct layer | equal (11283.28 / 9213) |
| Filter on another dataset than the measure (`od.product_id < 30`) | the same copy; the `DISTINCT` layer counts each order once | freight 8594.65 equals the distinct truth; the plain fan-out query gives **13539.01** |
| Measure with its own condition | `case(cond, value)` inside the aggregate, over the distinct layer: `sum(case(x.f > 100, x.f))`, `count(case(x.f > 100, x.oid))` | equal (7863.75, 6987, count 32) |
| Filter on measures (HAVING) | on the **outermost** `FIND`: `FILTER max(t.v1) > 10000 AND max(t.v2) > 9250` | equal (USA only); renders as `HAVING` |
| Order and limit on a measure | on the outermost `FIND`: `max(t.v1) freight DESC` ... `LIMIT 3` | equal (USA, Germany, Austria) |

Rules that follow:

- A measure's own condition goes **into its aggregate**, never into the layer's `FILTER`: the layer is shared by all
  measures of the table, and a `FILTER` there would change them all.
- The sort direction follows the header: `expr header DESC`. `expr DESC header` is a syntax error.
- `WITH` cannot be nested: `FIND (WITH ...)` and a `WITH` inside a block are refused. HAVING, ordering and limit
  therefore have to be written on the outermost `FIND`, and the rewriter has to merge the `WITH` blocks the user
  query already has with its own.
- A threshold above every group returns no rows without an error (a first test used 20000 and looked broken).

## Dialects

The query with sum, count and avg was run on each dialect through the core Northwind engine and compared per country
with two plain truth queries (all 21 countries, tolerance 1e-3). Run once, 2026-11-22.

| Dialect | Result |
|---|---|
| DuckDB | equal (earlier runs) |
| PostgreSQL 17, MariaDB 11.8, Oracle Free, Trino, SQLite | **equal**, 21 of 21 rows, no mismatch |
| SQL Server | **not run**: the host is unreachable from here, the test skipped |
| Snowflake | **not run**: cloud account, excluded |

`AggRewriter` output (7 queries: two tables with a fan-out join, shared layer with count and avg, a filter on another
table, HAVING with sort and limit, `agg(a) / agg(b)`, a measure with its own `case`, no group key) was run on each
dialect through the core Northwind engine and compared with the DuckDB result, rounded to two decimals, run once
2026-11-22:

| Dialect | AggRewriter output |
|---|---|
| PostgreSQL 17, MariaDB 11.8, Oracle Free, Trino, SQLite | **equal** on all 7 queries |
| SQL Server | **not run**: host unreachable, test skipped |
| Snowflake | **not run**: cloud account, excluded |

Typed NULL padding with `to_integer(NULL)` and `to_decimal(NULL, 18, 4)` renders and runs on all six (text: see ROLLUP).
The spike test was a copy of each module's Northwind engine test; it is not kept.

## What does not work, and must never be emitted

- **Joining the aggregate blocks.** `FIND m1 a, a [k=k] m2 b` fails: the columns of `[..=..]` are resolved against the
  *entity* the block reads, not against the aliases of its `FETCH` ("unknown column 'k' on orders"), and a block whose
  source is itself a block fails with "unknown start entity: d1". A join on the group key is also not null-safe.
- **Two blocks without a link.** `FIND m1 a, m2 b` is not an error: koryki **infers** a join (here on `order_id`) and
  groups by it. A grand total of two blocks returned 830 rows. The rewriter must never emit a `FIND` with two or
  more sources and no explicit link.
- **Padding inside the aggregate block.** `FETCH x.k k, sum(x.f) v1, to_decimal(NULL, 18, 4) v2` renders
  `GROUP BY ..., CAST(NULL AS DECIMAL(18, 4))`. A constant in `GROUP BY` is refused by some dialects (SQL Server, not
  tried) and turns a grand total over no rows into zero rows instead of one row of NULLs.

## Open points

1. **Types.** The padding needs the result type of every measure: its concept (`typeFamily`), or the type core infers
   for the expression (`ai.koryki.iql.typing`). Typed NULL spellings exist for decimals and integers
   (`to_decimal(NULL, p, s)`, `to_integer(NULL)`); text, date, timestamp and boolean were not tried.
2. **Dialects.** SQL Server and Snowflake still have to run before anything is promised for them.
3. **Identity must be reachable.** KQL names only columns the model exposes as attributes. A measure whose table has
   a primary key column that is not an attribute cannot get a distinct layer. Refuse it, with that reason.
4. **Not tried:** filters, HAVING, ordering and limit on dialects other than DuckDB; group
   keys that are expressions (`month(o.order_date)`); raw aggregates mixed
   with `agg()` in one query (refuse); large data (each layer repeats
   the whole join tree and does a `DISTINCT`).
5. **Where the input is parsed.** Done: `KQLReader` parses the query and the rewriter splices the *text* of the
   source graph, the row filters and the group keys, so what the user wrote is what is copied.

## Outer links

An optional link (`+`, and `FULL`) is copied into every layer with the rest of the graph. A layer then holds a NULL-padded
row (its identity is NULL) for each row that found no partner. It adds nothing to a measure: `sum`, `avg` and
`count(x)` skip NULLs, and `count()` is written `count(x.i1)`, the count of the identity. A group whose rows are
all padded stays, with 0 for a count and NULL for the other measures (Ossie b17). Only when the query has a `+`
is `count()` rewritten, so the plain output does not change. Verified on DuckDB (the Northwind `outer_link` and the
SCOTT cases b10, b11, b15, b17 against Ossie's rows) and on PostgreSQL, MariaDB, Oracle, Trino, SQLite and SQL
Server for `outer_link`. A FULL join pads both sides, so both are handled like `+` (Ossie b12); a CROSS
join pads nothing and needs no handling (b13). A range or other conditioned relationship of the catalog is
rewritten as any link (b06, b07): the layers copy the join. A RIGHT join is a LEFT join with the sides swapped; a filter in the ON clause is not
expressible in KQL.

## ROLLUP

`ROLLUP` is evaluated once per grouping set: all the keys, one fewer from the right, ... none. Each layer has one
aggregate block per set (`aggm1s2`, `aggm1s1`, `aggm1s0`), padded to the full key list with typed NULLs and a
constant `g` that numbers the set, joined by `UNIONALL`. A block `aggr` merges with `max` per `(g, keys)` and the
outer query drops `g`. Two things follow: a total row is the measure over all rows (the average of all employees,
not the average of the department averages, Ossie a09), and a group whose key really is NULL stays apart from its
subtotal. The output has no `ROLLUP`, so it also runs on SQLite, which has none, and on MariaDB with sorting.
The keys must be plain columns (their type pads the totals); an expression key is refused. Checked: Ossie a09 on
DuckDB, the Northwind `rollup` (two layers, region NULL for some countries) against independent `GROUP BY ROLLUP`
queries on DuckDB (all 56 rows), and its SQL on PostgreSQL, MariaDB, Oracle, Trino, SQLite and SQL Server.
`to_text(NULL)` is ambiguous for core's validator; the text padding is `to_text(to_integer(NULL))`.

## WITH blocks, sub-queries and set operations

A query is rewritten select by select, innermost first: a scalar sub-query, a block of the user's own `WITH`, a branch
of a set operation, then the main query. Each select that reads a measure directly gets its blocks (`q1aggd1`,
`q1aggm1`, ..., the prefix `q<n>` when there is more than one) put in front of the block or main query that holds
it, and is itself replaced by the query that reads them. Blocks of the user stay where they are; the blocks they
read come first (the order matters, KQL sees a block only after it is defined). A block, a scalar sub-query and an
`IN (FIND block ...)` can all be read from the main query. Two consequences: a block with `agg` and a sort and limit
can be a filter ("the two departments with the largest payroll", Ossie b03), and an outer aggregate over `agg`
values is a plain aggregate over a block (Ossie a12). Provenance lists the output columns of the main query only,
and for a set operation the first branch that names a header. Checked: Ossie a11, b03, a12; the Northwind
`with_block`, `subquery_scalar` (against plain SQL) and `union_branches`, on six dialects. A sub-query that reads an
alias of the main query (correlated) is not supported.

## What the first version refuses

A bracketed query, `FETCH DISTINCT`, a `ROLLUP` over an expression key,
a plain aggregate next to `agg`, a `FETCH` item that mixes `agg` with a column, `agg` with `OVER`, a correlated `agg` sub-query (it cannot be rewritten on its own),
`agg` without a `FETCH`, an unknown alias or measure, a table without a primary key, a column of the key or of a
measure that is no attribute of the entity, a measure with a result type the padding is not written for (needs a
concept: INTEGER, DECIMAL, FLOAT, TEXT, DATE, TIMESTAMP, BOOLEAN; a `count` needs none).

Not checked yet: the typed NULLs other than integer and decimal, SQL Server and Snowflake, and the entity-to-table name
mapping beyond `Entity.table`.
