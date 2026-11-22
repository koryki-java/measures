package ai.koryki.measure.generator;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.koryki.catalog.domain.Model;
import ai.koryki.catalog.schema.Schema;
import ai.koryki.databases.FileAsserter;
import ai.koryki.databases.cases.Fixtures;
import ai.koryki.databases.cases.ListWithSqlResult;
import ai.koryki.databases.cases.TestUtil;
import ai.koryki.kql.Engine;
import ai.koryki.kql.HeaderInfo;
import ai.koryki.measure.catalog.MeasureCatalog;
import ai.koryki.measure.catalog.MeasureCatalogLoader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The rewriter against a shared fixture corpus, schema {@code measures} (see SPEC.md of
 * koryki-java/northwind). One file per case, as in core's engine tests:
 *
 * <pre>
 * queries/kql/measures/&lt;name&gt;.kql                    the query, with agg()
 * expected/kql/measures/rewrite/&lt;name&gt;.kql           what the rewriter returns
 * expected/kql/measures/provenance/&lt;name&gt;.txt        output column : table.measure
 * expected/kql/measures/refusal/&lt;name&gt;.txt           why a query named invalid* is refused
 * expected/kql/measures/csv/&lt;name&gt;.csv, .json        the result of the rewritten query, and its types
 * expected/duckdb/measures/sql/&lt;name&gt;.sql            its SQL on DuckDB
 * </pre>
 *
 * The rewritten query is an ordinary KQL query: it is run, and its result and SQL are checked, by
 * core's {@link TestUtil}, so the goldens have the shape every other schema has. Goldens are
 * written only with {@code -Dfixtures.write=true}.
 *
 * <p>A subclass says which catalog, measures and database the corpus is about. Which corpus is read
 * is the {@code test.root} of the Gradle task that runs it.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class FixturesTest {

    private static final String SCHEMA = "measures";

    /** The rewriter for the catalog the corpus is about. */
    protected abstract AggRewriter rewriter();

    /** An engine over the database the corpus is about. */
    protected abstract Engine<HeaderInfo, ListWithSqlResult<HeaderInfo>> engine();

    /** The measures of a catalog: the {@code measures.json} beside its {@code db.json}. */
    protected static MeasureCatalog measures(Class<?> from, String catalogPath, Schema schema)
            throws IOException {
        try (InputStream in = from.getResourceAsStream(catalogPath + "/measures.json")) {
            assertTrue(in != null, catalogPath + "/measures.json is not on the classpath");
            return MeasureCatalogLoader.read(in, schema);
        }
    }

    protected static AggRewriter rewriter(Schema schema, Model model, MeasureCatalog measures) {
        return new AggRewriter(schema, model, measures);
    }

    Stream<Path> queries() throws IOException {
        try (Stream<Path> walk = Files.walk(Fixtures.queries(SCHEMA))) {
            // collected: the walk must be closed before the tests run
            return walk.filter(p -> p.toString().endsWith(".kql")).sorted().toList().stream();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("queries")
    void eachQuery(Path file) throws IOException {
        Path root = Fixtures.queries(SCHEMA);
        String name = strip(root.relativize(file).toString());
        String kql = Files.readString(file);
        Rewritten r = rewriter().rewrite(kql);

        if (name.startsWith("invalid")) {
            assertFalse(r.ok(), name + " must be refused");
            assertNull(r.kql());
            golden(String.join("\n", r.violations()), kindDir("refusal", name, ".txt"));
            return;
        }
        assertTrue(r.ok(), () -> name + " was refused: " + r.violations());
        if (!kql.contains("agg(")) {
            assertSame(kql, r.kql(), "a query without agg is returned as it is");
        }

        Path rewritten = kindDir("rewrite", name, ".kql");
        golden(r.kql(), rewritten);
        golden(
                r.measures().entrySet().stream()
                        .map(e -> e.getKey() + " : " + e.getValue())
                        .collect(Collectors.joining("\n")),
                kindDir("provenance", name, ".txt"));

        // The rewritten query is a query like any other: result, types and SQL are core's goldens.
        TestUtil.test(
                rewritten,
                ".kql",
                engine(),
                Fixtures.expected("kql", SCHEMA).resolve("rewrite"),
                Fixtures.expectedCsv(SCHEMA),
                Fixtures.expectedSql("duckdb", SCHEMA),
                Fixtures.expectedViolations("duckdb", SCHEMA),
                "duckdb",
                true);
    }

    private static Path kindDir(String kind, String name, String suffix) {
        return Fixtures.expected("kql", SCHEMA).resolve(kind).resolve(name + suffix);
    }

    private static void golden(String actual, Path file) throws IOException {
        if (Files.isRegularFile(file)) {
            FileAsserter.scriptAssert(Files.readString(file), actual, file.toString());
        } else {
            Fixtures.writeOrFail(actual, file.toFile());
        }
    }

    private static String strip(String name) {
        return name.substring(0, name.length() - ".kql".length());
    }
}
