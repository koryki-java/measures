package ai.koryki.measure.generator.northwind;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.koryki.antlr.Range;
import ai.koryki.catalog.CatalogLoader;
import ai.koryki.catalog.domain.Model;
import ai.koryki.catalog.schema.Schema;
import ai.koryki.databases.cases.ListWithSqlResult;
import ai.koryki.databases.cases.StableFormat;
import ai.koryki.databases.northwind.duckdb.NorthwindDuckdb;
import ai.koryki.databases.northwind.duckdb.NorthwindService;
import ai.koryki.duckdb.SqlQueryRenderer;
import ai.koryki.kql.Engine;
import ai.koryki.kql.EngineBuilder;
import ai.koryki.kql.HeaderInfo;
import ai.koryki.measure.catalog.Measure;
import ai.koryki.measure.catalog.MeasureCatalog;
import ai.koryki.measure.generator.AggRewriter;
import ai.koryki.measure.generator.FixturesTest;
import ai.koryki.measure.generator.MeasureQueryRewriter;
import ai.koryki.measure.generator.Rewritten;
import java.io.IOException;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The measures corpus of koryki-java/northwind, on its DuckDB database. */
class AggRewriterFixturesTest extends FixturesTest {

    private AggRewriter rewriter;
    private Engine<HeaderInfo, ListWithSqlResult<HeaderInfo>> engine;

    @BeforeAll
    void setup() throws IOException {
        Schema schema = CatalogLoader.db(NorthwindService.DB);
        Model model = CatalogLoader.model(NorthwindService.MODEL, Locale.ENGLISH);
        rewriter = rewriter(schema, model, measures(getClass(), NorthwindService.DB, schema));
        engine =
                EngineBuilder.headers(
                                NorthwindDuckdb.<ListWithSqlResult<HeaderInfo>>northwind(),
                                NorthwindService.resolver(),
                                () -> new SqlQueryRenderer(ZoneId.of("UTC")))
                        .valueFormat(new StableFormat(Locale.ROOT))
                        .build();
    }

    /**
     * A query whose links all lead to one row of the other table needs no layers: the plain query
     * gives the same rows. One that can multiply the rows of the measure's table keeps them.
     */
    @Test
    void aQueryThatCannotFanOutIsWrittenWithoutLayers() {
        // orders -> customers is many to one, in either way of writing it
        for (String q :
                List.of(
                        "FIND customers c, c VIA same_customer orders o FETCH c.country, agg(o.total_freight) freight",
                        "FIND orders o, o VIA same_customer customers c FETCH c.country, agg(o.total_freight) freight, agg(o.order_count) n",
                        "FIND customers c, c VIA same_customer orders o FETCH agg(o.total_freight) freight",
                        "FIND customers c, c VIA same_customer orders o FILTER c.country = 'Germany' FETCH c.city, agg(o.total_freight) freight",
                        "FIND customers c, c VIA same_customer orders o FILTER agg(o.total_freight) > 5000 FETCH c.country, agg(o.total_freight) freight",
                        "FIND customers c, c VIA same_customer orders o FETCH c.country, round(agg(o.total_freight) / agg(o.order_count), 2) per_order DESC LIMIT 5",
                        "FIND customers c, c VIA same_customer orders o FETCH c.country, upper(c.city) city, agg(o.total_freight) * 2 twice")) {
            assertPlainOrLayered(q, true);
        }
    }

    @Test
    void aQueryThatCanFanOutKeepsTheLayers() {
        for (String q :
                List.of(
                        // two tables with measures
                        "FIND customers c, c VIA same_customer orders o, o VIA same_order order_details od FETCH c.country, agg(o.total_freight) freight, agg(od.units) units",
                        // the share-of-total follow-up: a windowed total over the groups
                        "FIND customers c, c VIA same_customer orders o, o VIA same_order order_details od FETCH c.country, agg(o.total_freight) freight, agg(o.total_freight) / sum(agg(o.total_freight)) OVER () share, agg(od.units) units",
                        // order_details -> orders is many to one, but orders is the home here
                        "FIND orders o, o VIA same_order order_details od FETCH o.ship_country, agg(o.total_freight) freight",
                        // orders -> order_details is one to many
                        "FIND orders o, o VIA same_order order_details od FETCH od.product_id, agg(o.total_freight) freight",
                        // a group key that reads no column is grouped by as it is, which
                        // PostgreSQL and SQL Server refuse
                        "FIND orders o FETCH 'total' metric, agg(o.total_freight) freight",
                        // an outer link
                        "FIND customers c, c + orders o VIA same_customer FETCH c.country, agg(o.total_freight) freight")) {
            assertPlainOrLayered(q, false);
        }
    }

    @Override
    protected AggRewriter rewriter() {
        return rewriter;
    }

    @Override
    protected Engine<HeaderInfo, ListWithSqlResult<HeaderInfo>> engine() {
        return engine;
    }

    private static final String QUERY =
            "FIND customers c, c VIA same_customer orders o, o VIA same_order order_details od\n"
                    + "FILTER c.country = 'Germany' AND od.product_id < 30\n"
                    + "FETCH c.country, agg(o.total_freight) freight, agg(od.units) units";

    /** The range of {@code text} in the query's line {@code line} (1-based), stop exclusive. */
    private static Range at(int line, String text) {
        String l = QUERY.split("\n")[line - 1];
        int col = l.indexOf(text) + 1;
        return new Range(line, col, line, col + text.length());
    }

    private static int offset(String text, String part, int occurrence) {
        int at = -1;
        for (int i = 0; i < occurrence; i++) {
            at = text.indexOf(part, at + 1);
        }
        return at;
    }

    @Test
    void aCopiedPieceMapsBackExactly() {
        Rewritten r = rewriter.rewrite(QUERY);
        String kql = r.kql();
        // the row filter is copied into each layer: both copies point at the one the user wrote
        for (int copy = 1; copy <= 2; copy++) {
            int from = offset(kql, "od.product_id < 30", copy);
            assertEquals(
                    at(2, "od.product_id < 30"),
                    r.map().toOriginal(from, from + "od.product_id < 30".length()));
        }
    }

    @Test
    void aGeneratedPieceMapsToWhatCausedIt() {
        Rewritten r = rewriter.rewrite(QUERY);
        String kql = r.kql();
        int from = offset(kql, "max(t.v1)", 1);
        assertEquals(
                at(3, "agg(o.total_freight)"),
                r.map().toOriginal(from, from + "max(t.v1)".length()));
        from = offset(kql, "max(t.v2)", 1);
        assertEquals(at(3, "agg(od.units)"), r.map().toOriginal(from, from + "max(t.v2)".length()));
        // a part of a generated piece is the whole of what caused it
        from = offset(kql, "max(t.v2)", 1) + 4;
        assertEquals(at(3, "agg(od.units)"), r.map().toOriginal(from, from + 1));
    }

    @Test
    void aRangeOfCoreIsCarriedBack() {
        Rewritten r = rewriter.rewrite(QUERY);
        String kql = r.kql();
        // as core would report it: line and column of the rewritten text, stop exclusive
        int from = offset(kql, "od.product_id < 30", 2);
        int line = 1 + (int) kql.substring(0, from).chars().filter(c -> c == '\n').count();
        int col = from - kql.lastIndexOf('\n', from - 1);
        Range inRewritten = new Range(line, col, line, col + "od.product_id".length());
        assertEquals(at(2, "od.product_id"), r.map().toOriginal(inRewritten));
    }

    private static final String HEAD =
            "FIND customers c, c VIA same_customer orders o, o VIA same_order order_details od\n";

    private Range refusedAt(String kql) {
        Rewritten r = rewriter.rewrite(kql);
        assertEquals(1, r.problems().size(), () -> String.valueOf(r.violations()));
        assertEquals(r.violations().get(0), r.problems().get(0).message());
        return r.problems().get(0).range();
    }

    @Test
    void aRefusalPointsAtTheCallThatCausedIt() {
        // line 2: FETCH c.country, agg(o.nothing)
        assertEquals(new Range(2, 18, 2, 32), refusedAt(HEAD + "FETCH c.country, agg(o.nothing)"));
    }

    @Test
    void aRefusalPointsAtTheFetchItem() {
        String item = "agg(o.total_freight) + o.freight";
        assertEquals(
                new Range(2, 18, 2, 18 + item.length()),
                refusedAt(HEAD + "FETCH c.country, " + item));
    }

    @Test
    void aRefusalWithoutAPlaceIsTheWholeClause() {
        // FETCH DISTINCT: the fetch clause
        Range r = refusedAt(HEAD + "FETCH DISTINCT c.country, agg(o.total_freight)");
        assertEquals(2, r.getStart().getLine());
    }

    @Test
    void aSyntaxErrorPointsAtTheToken() {
        Range r = refusedAt(HEAD + "FETCH c.country, agg(o.total_freight");
        assertEquals(2, r.getStart().getLine());
    }

    @Test
    void aTailThatWasNotRewrittenMapsToItself() {
        String tail = "VISUALIZE country AS x\nDRAW bar";
        String all = QUERY + "\n" + tail;
        Rewritten r = rewriter.rewrite(QUERY);
        var map = r.map().withTail(all, QUERY.length() + 1, "\n");
        String kql = r.kql() + "\n" + tail;
        assertEquals(kql, map.rewritten());
        int from = kql.indexOf("DRAW bar");
        assertEquals(new Range(5, 1, 5, 9), map.toOriginal(from, from + "DRAW bar".length()));
        // what was in front of it is unchanged
        int call = kql.indexOf("max(t.v1)");
        assertEquals(at(3, "agg(o.total_freight)"), map.toOriginal(call, call + 9));
    }

    private ai.koryki.kql.Generator<HeaderInfo> generator() {
        Schema schema = CatalogLoader.db(NorthwindService.DB);
        ai.koryki.iql.LinkResolver resolver =
                NorthwindService.resolver().queryRewriter(new MeasureQueryRewriter(rewriter));
        return new ai.koryki.kql.Generator<>(
                resolver, new SqlQueryRenderer(ZoneId.of("UTC")), HeaderInfo::new);
    }

    @Test
    void theHookTranspilesAQueryThatReadsAMeasure() {
        String sql = generator().toSql(QUERY);
        org.junit.jupiter.api.Assertions.assertTrue(sql.contains("aggd1"), sql);
    }

    @Test
    void theHookReportsARefusalAtTheCall() {
        var violations = generator().validateKQL(HEAD + "FETCH c.country, agg(o.nothing) x");
        assertEquals(1, violations.size());
        assertEquals(new Range(2, 18, 2, 32), violations.get(0).getRange());
    }

    @Test
    void theHookReportsAViolationOfTheRewrittenQueryInTheOriginal() {
        var violations =
                generator()
                        .validateKQL(
                                HEAD
                                        + "FILTER o.no_such_column > 1\n"
                                        + "FETCH c.country, agg(o.total_freight) f");
        org.junit.jupiter.api.Assertions.assertFalse(violations.isEmpty());
        for (var v : violations) {
            assertEquals(2, v.getRange().getStart().getLine(), v.toString());
        }
    }

    @Test
    void formattingShowsTheQueryThatWasAsked() {
        String formatted = generator().formatKQL(QUERY);
        org.junit.jupiter.api.Assertions.assertTrue(formatted.contains("agg("), formatted);
        org.junit.jupiter.api.Assertions.assertFalse(formatted.contains("aggd1"), formatted);
    }

    @Test
    void aDerivedColumnKeepsTheExpressionAsWritten() {
        Rewritten r =
                rewriter.rewrite(
                        HEAD
                                + "FETCH c.country, round(agg(o.total_freight), 2) f,"
                                + " agg(o.total_freight) / agg(o.order_count) per_order");
        assertEquals("round({orders.total_freight}, 2)", r.columns().get("f").shown());
        assertEquals(
                "{orders.total_freight} / {orders.order_count}",
                r.columns().get("per_order").shown());
        // a column that is exactly one measure is in measures(), not here
        assertEquals(2, r.columns().size());
        assertEquals(
                List.of("orders.total_freight", "orders.order_count"),
                r.columns().get("per_order").measures());
    }

    @Test
    void aPlainAggregateNextToAMeasureIsRefused() {
        // count() would count the rows of the rewriter's own block, not the orders
        Rewritten r = rewriter.rewrite(HEAD + "FETCH c.country, agg(o.total_freight) + count() x");
        assertEquals(1, r.violations().size());
        assertTrue(r.violations().get(0).contains("plain aggregate"), r.violations().toString());
        // and in a predicate on a measure
        r =
                rewriter.rewrite(
                        HEAD
                                + "FILTER agg(o.total_freight) > count()\n"
                                + "FETCH c.country, agg(o.order_count) n");
        assertEquals(1, r.violations().size());
    }

    @Test
    void aNameTheRewriterGeneratesIsReserved() {
        String q =
                "WITH aggd1 AS (FIND customers c FETCH c.country country)\n"
                        + "FIND customers c, c VIA same_customer orders o\n"
                        + "FETCH c.country, agg(o.total_freight) f";
        Rewritten r = rewriter.rewrite(q);
        assertEquals(1, r.violations().size());
        assertTrue(r.violations().get(0).contains("aggd1"), r.violations().toString());
        // at the name the user wrote
        assertEquals(new Range(1, 6, 1, 11), r.problems().get(0).range());
    }

    @Test
    void aCharacterTheMarkersUseIsRefusedNotThrown() {
        Rewritten r =
                rewriter.rewrite(
                        HEAD
                                + "FILTER c.country = 'a\uE002b'\n"
                                + "FETCH c.country, agg(o.total_freight) f");
        assertEquals(1, r.violations().size());
        assertTrue(r.violations().get(0).contains("U+E002"), r.violations().toString());
    }

    @Test
    void aColumnThatIsExactlyOneMeasureSaysSo() {
        Rewritten r = rewriter.rewrite(HEAD + "FETCH c.country, agg(o.total_freight) f");
        assertTrue(r.columns().get("f").isMeasure());
        assertEquals("orders.total_freight", r.columns().get("f").measure());
        // a key is not a column of measures
        assertEquals(1, r.columns().size());
    }

    @Test
    void aBugOfTheRewriterIsAnAnswerNotAnException() {
        MeasureCatalog broken =
                new MeasureCatalog() {
                    @Override
                    public java.util.Optional<Measure> find(String table, String name) {
                        throw new IllegalStateException("boom");
                    }
                };
        Rewritten r =
                new AggRewriter(
                                CatalogLoader.db(NorthwindService.DB),
                                CatalogLoader.model(NorthwindService.MODEL, Locale.ENGLISH),
                                broken)
                        .rewrite(HEAD + "FETCH c.country, agg(o.total_freight) f");
        assertEquals(1, r.violations().size());
        assertTrue(r.violations().get(0).contains("internal error"), r.violations().toString());
    }
}
