package ai.koryki.measure.generator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.koryki.catalog.CatalogLoader;
import ai.koryki.catalog.domain.Model;
import ai.koryki.catalog.schema.Schema;
import ai.koryki.databases.northwind.duckdb.NorthwindDuckdb;
import ai.koryki.databases.northwind.duckdb.NorthwindService;
import ai.koryki.duckdb.SqlQueryRenderer;
import ai.koryki.jdbc.ListResult;
import ai.koryki.kql.Engine;
import ai.koryki.kql.EngineBuilder;
import ai.koryki.kql.HeaderInfo;
import ai.koryki.measure.catalog.MeasureCatalog;
import ai.koryki.measure.catalog.MeasureCatalogLoader;
import java.math.BigDecimal;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The rewriter against Northwind on DuckDB: every answer is compared with a plain truth query. */
class AggRewriterTest {

    private static final String MEASURES =
            """
            { "tables": {
              "orders": [
                { "name": "total_freight", "expression": "sum(freight)", "concept": {"typeFamily": "DECIMAL"} },
                { "name": "order_count", "expression": "count(order_id)" },
                { "name": "avg_freight", "expression": "avg(freight)", "concept": {"typeFamily": "DECIMAL"} },
                { "name": "big_freight", "expression": "sum(case(freight > 100, freight))", "concept": {"typeFamily": "DECIMAL"} }
              ],
              "order_details": [
                { "name": "units", "expression": "sum(quantity)", "concept": {"typeFamily": "INTEGER"} },
                { "name": "revenue", "expression": "sum(unit_price * quantity)", "concept": {"typeFamily": "DECIMAL"} }
              ]
            } }
            """;

    private static Engine<HeaderInfo, ListResult<HeaderInfo>> engine;
    private static AggRewriter rewriter;

    private static final String PATH =
            "FIND customers c, c VIA same_customer orders o, o VIA same_order order_details od";

    @BeforeAll
    static void setup() throws Exception {
        Schema schema = CatalogLoader.db(NorthwindService.DB);
        Model model = CatalogLoader.model(NorthwindService.MODEL, Locale.ENGLISH);
        MeasureCatalog measures = MeasureCatalogLoader.read(MEASURES, schema);
        rewriter = new AggRewriter(schema, model, measures);
        engine =
                EngineBuilder.headers(
                                NorthwindDuckdb.<ListResult<HeaderInfo>>northwind(),
                                NorthwindService.resolver(),
                                () -> new SqlQueryRenderer(ZoneId.of("UTC")))
                        .build();
    }

    /** Rows by the first column; the other columns as numbers where they are. */
    private static Map<String, List<Object>> run(String kql) {
        ListResult<HeaderInfo> r = engine.executeKQL(kql, ListResult::new);
        Map<String, List<Object>> out = new TreeMap<>();
        for (List<Object> row : r.getRows()) {
            out.put(String.valueOf(row.get(0)), row.subList(1, row.size()));
        }
        return out;
    }

    private static void sameNumbers(
            Map<String, List<Object>> expected, Map<String, List<Object>> actual) {
        assertEquals(expected.keySet(), actual.keySet());
        for (var e : expected.entrySet()) {
            List<Object> a = actual.get(e.getKey());
            assertEquals(e.getValue().size(), a.size(), e.getKey());
            for (int i = 0; i < a.size(); i++) {
                BigDecimal x = new BigDecimal(String.valueOf(e.getValue().get(i)));
                BigDecimal y = new BigDecimal(String.valueOf(a.get(i)));
                assertTrue(
                        x.subtract(y).abs().doubleValue() < 1e-3,
                        e.getKey() + " col " + i + ": " + x + " vs " + y);
            }
        }
    }

    private static String rewritten(String kql) {
        Rewritten r = rewriter.rewrite(kql);
        assertTrue(r.ok(), () -> String.valueOf(r.violations()));
        return r.kql();
    }

    @Test
    void theShapeIsTheDocumentedOne() {
        String q =
                PATH
                        + " FILTER c.country <> 'Brazil' AND agg(o.total_freight) > 10000"
                        + " FETCH c.country, agg(o.total_freight) f DESC, agg(od.units) u LIMIT 5";
        assertEquals(
                """
                WITH
                aggd1 AS (
                 FIND customers c, c VIA same_customer orders o, o VIA same_order order_details od
                 FILTER c.country <> 'Brazil'
                 FETCH DISTINCT o.order_id i1, c.country k1, o.freight c1),
                aggm1 AS (FIND aggd1 x FETCH x.k1 k1, sum(x.c1) v1),
                aggd2 AS (
                 FIND customers c, c VIA same_customer orders o, o VIA same_order order_details od
                 FILTER c.country <> 'Brazil'
                 FETCH DISTINCT od.order_id i1, od.product_id i2, c.country k1, od.quantity c1),
                aggm2 AS (FIND aggd2 x FETCH x.k1 k1, sum(x.c1) v2),
                aggu AS (FIND aggm1 a FETCH a.k1 k1, a.v1 v1, to_integer(NULL) v2
                 UNIONALL
                 FIND aggm2 a FETCH a.k1 k1, to_decimal(NULL, 18, 4) v1, a.v2 v2)
                FIND aggu t
                 FILTER max(t.v1) > 10000
                 FETCH t.k1 country, max(t.v1) f DESC, max(t.v2) u
                 LIMIT 5""",
                rewritten(q));
    }

    @Test
    void aQueryWithoutAggIsReturnedAsItIs() {
        String kql = PATH + " FETCH c.country, count(c.customer_id)";
        Rewritten r = rewriter.rewrite(kql);
        assertTrue(r.ok());
        assertSame(kql, r.kql());
    }

    @Test
    void measuresOfTwoTablesAreNotMultipliedByTheJoin() {
        String q = PATH + " FETCH c.country, agg(o.total_freight) freight, agg(od.units) units";
        Map<String, List<Object>> got = run(rewritten(q));

        Map<String, List<Object>> freight =
                run(
                        "FIND customers c, c VIA same_customer orders o FETCH c.country, sum(o.freight)");
        Map<String, List<Object>> units = run(PATH + " FETCH c.country, sum(od.quantity)");
        Map<String, List<Object>> expected = new TreeMap<>();
        freight.forEach((k, v) -> expected.put(k, List.of(v.get(0), units.get(k).get(0))));
        sameNumbers(expected, got);

        // the plain query is wrong, which is what agg is for
        Map<String, List<Object>> naive = run(PATH + " FETCH c.country, sum(o.freight)");
        assertTrue(
                new BigDecimal(String.valueOf(naive.get("Argentina").get(0)))
                                .compareTo(
                                        new BigDecimal(
                                                String.valueOf(freight.get("Argentina").get(0))))
                        > 0);
    }

    @Test
    void severalMeasuresOfOneTableShareTheirLayer() {
        String q =
                PATH
                        + " FETCH c.country, agg(o.total_freight) freight, agg(o.order_count) orders, agg(o.avg_freight) av";
        String out = rewritten(q);
        assertEquals(1, out.split("aggd1 AS", -1).length - 1);
        assertFalse(out.contains("aggd2"));
        Map<String, List<Object>> expected =
                run(
                        "FIND customers c, c VIA same_customer orders o FETCH c.country, sum(o.freight), count(o.order_id), avg(o.freight)");
        sameNumbers(expected, run(out));
    }

    @Test
    void aFilterOnAnotherTableRestrictsTheRowsAndCountsEachOnce() {
        String q =
                PATH
                        + " FILTER c.country = 'Germany' AND od.product_id < 30 FETCH c.country, agg(o.total_freight) f, agg(od.units) u";
        Map<String, List<Object>> got = run(rewritten(q));
        var f =
                run(
                        "WITH t AS ("
                                + PATH
                                + " FILTER c.country = 'Germany' AND od.product_id < 30"
                                + " FETCH DISTINCT o.order_id oid, o.freight fr) FIND t x FETCH 'Germany', sum(x.fr)");
        var u =
                run(
                        PATH
                                + " FILTER c.country = 'Germany' AND od.product_id < 30 FETCH c.country, sum(od.quantity)");
        sameNumbers(
                Map.of("Germany", List.of(f.get("Germany").get(0), u.get("Germany").get(0))), got);
    }

    @Test
    void aPredicateOnAMeasureBecomesHavingAndSortAndLimitStay() {
        String q =
                PATH
                        + " FILTER c.country <> 'Brazil' AND agg(o.total_freight) > 10000 FETCH c.country, agg(o.total_freight) freight DESC LIMIT 5";
        Map<String, List<Object>> got = run(rewritten(q));
        var expected =
                run(
                        "FIND customers c, c VIA same_customer orders o FILTER c.country <> 'Brazil' AND sum(o.freight) > 10000"
                                + " FETCH c.country, sum(o.freight) DESC LIMIT 5");
        sameNumbers(expected, got);
        assertFalse(got.isEmpty());
    }

    @Test
    void aMeasureCanBeUsedInsideAScalarExpression() {
        String q =
                PATH
                        + " FILTER c.country = 'Germany' FETCH c.country, agg(o.total_freight) / agg(o.order_count) per_order";
        Map<String, List<Object>> got = run(rewritten(q));
        var expected =
                run(
                        "FIND customers c, c VIA same_customer orders o FILTER c.country = 'Germany' FETCH c.country, sum(o.freight) / count(o.order_id)");
        sameNumbers(expected, got);
    }

    @Test
    void aMeasureWithItsOwnConditionKeepsItInsideTheAggregate() {
        String q =
                PATH
                        + " FILTER c.country = 'Germany' FETCH c.country, agg(o.big_freight) big, agg(o.total_freight) all_freight";
        Map<String, List<Object>> got = run(rewritten(q));
        var big =
                run(
                        "FIND customers c, c VIA same_customer orders o FILTER c.country = 'Germany' AND o.freight > 100 FETCH c.country, sum(o.freight)");
        var all =
                run(
                        "FIND customers c, c VIA same_customer orders o FILTER c.country = 'Germany' FETCH c.country, sum(o.freight)");
        sameNumbers(
                Map.of("Germany", List.of(big.get("Germany").get(0), all.get("Germany").get(0))),
                got);
    }

    @Test
    void noGroupKeyGivesOneRow() {
        String q = PATH + " FETCH agg(o.total_freight) freight, agg(od.units) units";
        ListResult<HeaderInfo> r = engine.executeKQL(rewritten(q), ListResult::new);
        assertEquals(1, r.getRows().size());
        var expected =
                engine.executeKQL(
                        "FIND orders o FETCH sum(o.freight)",
                        (java.util.function.Supplier<ListResult<HeaderInfo>>) ListResult::new);
        assertEquals(
                0,
                new BigDecimal(String.valueOf(r.getRows().get(0).get(0)))
                        .compareTo(
                                new BigDecimal(String.valueOf(expected.getRows().get(0).get(0)))));
    }

    @Test
    void theOutputColumnsKeepTheirNamesAndSaySWhichMeasureTheyCarry() {
        String q =
                PATH
                        + " FETCH c.country, o.ship_city city, agg(o.total_freight), agg(od.units) u, agg(o.total_freight) / 2 half";
        Rewritten r = rewriter.rewrite(q);
        assertTrue(r.ok(), () -> String.valueOf(r.violations()));
        assertEquals(
                Map.of("total_freight", "orders.total_freight", "u", "order_details.units"),
                r.measures());
        ListResult<HeaderInfo> res = engine.executeKQL(r.kql(), ListResult::new);
        List<String> names = new ArrayList<>();
        res.getInfos().forEach(i -> names.add(i.getHeader()));
        assertEquals(List.of("country", "city", "total_freight", "u", "half"), names);
    }

    @Test
    void whatIsNotSupportedIsRefusedWithAReason() {
        refused(
                "WITH a AS (FIND orders o FETCH o.order_id) FIND orders o FETCH agg(o.total_freight)",
                "WITH");
        refused(PATH + " FETCH c.country, agg(o.nothing)", "no measure 'nothing'");
        refused(PATH + " FETCH c.country, agg(z.freight)", "unknown alias");
        refused(PATH + " FETCH c.country, agg(o.total_freight), sum(o.freight)", "plain aggregate");
        refused(PATH + " FETCH c.country, agg(o.total_freight) + o.freight", "also reads a column");
        refused(
                "FIND customers c, c VIA same_customer + orders o FETCH c.country, agg(o.total_freight)",
                "outer link");
        refused(PATH + " FETCH DISTINCT c.country, agg(o.total_freight)", "DISTINCT");
        refused(PATH + " FETCH c.country, agg(o.total_freight, od.units)", "one measure");
        refused(PATH + " FETCH c.country, agg(o.total_freight", "syntax");
        refused(PATH + " FILTER agg(o.total_freight) > 1", "FETCH");
    }

    private static void refused(String kql, String why) {
        Rewritten r = rewriter.rewrite(kql);
        assertFalse(r.ok(), kql);
        assertNull(r.kql());
        assertNotNull(r.violations());
        assertTrue(
                r.violations().get(0).contains(why),
                r.violations().get(0) + " should mention " + why);
    }
}
