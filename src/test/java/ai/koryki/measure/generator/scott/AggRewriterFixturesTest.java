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
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The measures corpus of koryki-java/scott, on the SCOTT database of Apache Ossie's query
 * compliance cases.
 */
class AggRewriterFixturesTest extends FixturesTest {

    private static final String CATALOG = "/ai/koryki/duckdb/databases/scott";

    private Schema schema;
    private Model model;
    private ai.koryki.measure.catalog.MeasureCatalog measures;
    private AggRewriter rewriter;
    private Engine<HeaderInfo, ListWithSqlResult<HeaderInfo>> engine;

    @BeforeAll
    void setup() throws IOException {
        Locale locale = Locale.ENGLISH;
        schema = CatalogLoader.db(CATALOG);
        model = CatalogLoader.model(CATALOG + "/model", locale);
        measures = measures(getClass(), CATALOG, schema);
        rewriter = rewriter(schema, model, measures);

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

    private static final String BY_DEPARTMENT =
            "FIND emp e, e VIA same_department dept d FETCH d.dname dname, agg(e.sum_sal) sumsal";

    /** A catalog whose dept has no primary key, and what it says instead of one. */
    private AggRewriter withDeptKey(
            java.util.function.Consumer<ai.koryki.catalog.schema.Table> key) {
        Schema copy = ai.koryki.catalog.SchemaCopy.deepCopy(schema);
        var dept =
                copy.getTables().stream()
                        .filter(t -> t.getName().equalsIgnoreCase("dept"))
                        .findFirst()
                        .orElseThrow();
        dept.getColumns().forEach(c -> c.setPkPos(0));
        key.accept(dept);
        return new AggRewriter(copy, model, measures);
    }

    private static ai.koryki.catalog.schema.Index index(boolean unique, String where) {
        var i =
                new ai.koryki.catalog.schema.Index(
                        "dept_deptno",
                        null,
                        List.of(new ai.koryki.catalog.schema.IndexKey("deptno", null, null)));
        i.setUnique(unique);
        i.setWhere(where);
        return i;
    }

    @Test
    void aUniqueIndexOfTheOtherTableCountsAsItsKey() {
        assertPlainOrLayered(
                withDeptKey(t -> t.setIndexes(List.of(index(true, null)))), BY_DEPARTMENT, true);
    }

    @Test
    void whatIsNoKeyOfAllRowsLeavesTheLayers() {
        // not known; a plain index; a partial unique index: none proves one row per join
        assertPlainOrLayered(withDeptKey(t -> {}), BY_DEPARTMENT, false);
        assertPlainOrLayered(
                withDeptKey(t -> t.setIndexes(List.of(index(false, null)))), BY_DEPARTMENT, false);
        assertPlainOrLayered(
                withDeptKey(t -> t.setIndexes(List.of(index(true, "deptno > 10")))),
                BY_DEPARTMENT,
                false);
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
