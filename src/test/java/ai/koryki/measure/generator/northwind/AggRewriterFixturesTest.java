package ai.koryki.measure.generator.northwind;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
import ai.koryki.measure.generator.AggRewriter;
import ai.koryki.measure.generator.FixturesTest;
import ai.koryki.measure.generator.Rewritten;
import java.io.IOException;
import java.time.ZoneId;
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
}
