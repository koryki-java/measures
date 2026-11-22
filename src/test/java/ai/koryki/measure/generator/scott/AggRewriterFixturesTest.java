package ai.koryki.measure.generator.scott;

import ai.koryki.catalog.CatalogLoader;
import ai.koryki.catalog.domain.Model;
import ai.koryki.catalog.schema.Schema;
import ai.koryki.databases.cases.ListWithSqlResult;
import ai.koryki.databases.cases.StableFormat;
import ai.koryki.duckdb.DuckdbDatabase;
import ai.koryki.duckdb.SqlQueryRenderer;
import ai.koryki.iql.LinkResolver;
import ai.koryki.kql.Engine;
import ai.koryki.kql.EngineBuilder;
import ai.koryki.kql.HeaderInfo;
import ai.koryki.measure.generator.AggRewriter;
import ai.koryki.measure.generator.FixturesTest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.Locale;
import org.junit.jupiter.api.BeforeAll;

/**
 * The measures corpus of koryki-java/scott, on the SCOTT database of Apache Ossie's query
 * compliance cases.
 */
class AggRewriterFixturesTest extends FixturesTest {

    private static final String CATALOG = "/ai/koryki/duckdb/databases/scott";

    private AggRewriter rewriter;
    private Engine<HeaderInfo, ListWithSqlResult<HeaderInfo>> engine;

    @BeforeAll
    void setup() throws IOException {
        Locale locale = Locale.ENGLISH;
        Schema schema = CatalogLoader.db(CATALOG);
        Model model = CatalogLoader.model(CATALOG + "/model", locale);
        rewriter = rewriter(schema, model, measures(getClass(), CATALOG, schema));

        // The database is a resource of the jar: a copy of its own for this process.
        Path file = Files.createTempFile("scott-", ".duckdb");
        file.toFile().deleteOnExit();
        var database =
                new DuckdbDatabase<ListWithSqlResult<HeaderInfo>>(
                        "scottdb",
                        DuckdbDatabase.fromResource(CATALOG + "/scottdb.duckdb", file),
                        ZoneId.of("UTC"));
        engine =
                EngineBuilder.headers(
                                database,
                                new LinkResolver(locale, schema, model, true),
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
