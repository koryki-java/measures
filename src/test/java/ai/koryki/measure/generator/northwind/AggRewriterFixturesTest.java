package ai.koryki.measure.generator.northwind;

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
import java.io.IOException;
import java.time.ZoneId;
import java.util.Locale;
import org.junit.jupiter.api.BeforeAll;

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
}
