# The KQL the rewriter emits: spike results

Status: **spike, DuckDB only.** Everything below was run against the Northwind DuckDB with koryki-core 0.4.2 and
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
2. **Dialects.** Only DuckDB. The other seven (`UNION ALL` of typed NULLs, CTE column lists, `DISTINCT` in a CTE) have
   to run before anything is promised.
3. **Identity must be reachable.** KQL names only columns the model exposes as attributes. A measure whose table has
   a primary key column that is not an attribute cannot get a distinct layer. Refuse it, with that reason.
4. **Not tried:** a filter or ordering on a measure (`FILTER agg(..) > n`, `ORDER`, `LIMIT`) on the outer query; group
   keys that are expressions (`month(o.order_date)`); outer links (`+`) in the original query; raw aggregates mixed
   with `agg()` in one query (refuse); measures of one table with different filters; large data (each layer repeats
   the whole join tree and does a `DISTINCT`).
5. **Where the input is parsed.** The generator reads the original KQL with `KQLReader`, so that the names of the
   original (aliases, links, the join tree) can be copied into the layers. Not yet tried.
