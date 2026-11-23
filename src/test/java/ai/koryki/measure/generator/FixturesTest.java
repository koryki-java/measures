package ai.koryki.measure.generator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.koryki.catalog.domain.Model;
import ai.koryki.catalog.schema.Schema;
import ai.koryki.databases.FileAsserter;
import ai.koryki.databases.cases.CSVAssert;
import ai.koryki.databases.cases.Fixtures;
import ai.koryki.databases.cases.ListWithSqlResult;
import ai.koryki.databases.cases.TestUtil;
import ai.koryki.kql.Engine;
import ai.koryki.kql.HeaderInfo;
import ai.koryki.measure.MeasureCatalogLoader;
import ai.koryki.measure.catalog.MeasureCatalog;
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
 * expected/kql/measures/provenance/&lt;name&gt;.txt        output column : table.measure, and
 * output column ~ table.measure, ... for a column computed from measures
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

        if (r.map() != null) {
            r.map().check();
        }

        sameRowsAsLayers(name, kql, r.kql());

        Path rewritten = kindDir("rewrite", name, ".kql");
        golden(r.kql(), rewritten);
        golden(
                Stream.concat(
                                r.columns().entrySet().stream()
                                        .filter(e -> e.getValue().isMeasure())
                                        .map(e -> e.getKey() + " : " + e.getValue().measure()),
                                r.columns().entrySet().stream()
                                        .filter(e -> !e.getValue().isMeasure())
                                        .map(
                                                e ->
                                                        e.getKey()
                                                                + " ~ "
                                                                + String.join(
                                                                        ", ",
                                                                        e.getValue().measures())))
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

    /**
     * The plain form of a query that cannot fan out must give the rows of the general form, the
     * layers: every query of the corpus is run both ways on the database.
     */
    /**
     * One query, both ways. {@code plain}: whether it must be written without the layers (it cannot
     * fan out) or must keep them (it can).
     */
    protected final void assertPlainOrLayered(String kql, boolean plain) {
        assertPlainOrLayered(rewriter(), kql, plain);
    }

    /** The same, with a rewriter over another catalog than the corpus's own. */
    protected final void assertPlainOrLayered(AggRewriter rewriter, String kql, boolean plain) {
        Rewritten r = rewriter.rewrite(kql);
        assertTrue(r.ok(), () -> kql + " was refused: " + r.violations());
        assertEquals(plain, !r.kql().contains("aggd"), kql + "\n" + r.kql());
        sameRowsAsLayers(rewriter, "inline", kql, r.kql());
    }

    private void sameRowsAsLayers(String name, String kql, String shortcut) {
        sameRowsAsLayers(rewriter(), name, kql, shortcut);
    }

    private void sameRowsAsLayers(AggRewriter rewriter, String name, String kql, String shortcut) {
        Rewritten general = rewriter.layered().rewrite(kql);
        assertTrue(general.ok(), name);
        if (general.kql().equals(shortcut)) {
            return; // no shortcut was taken
        }
        String a = rows(shortcut, name);
        String b = rows(general.kql(), name);
        assertEquals(b, a, name + ": the plain form and the layers differ");
    }

    private String rows(String kql, String name) {
        String csv = new CSVAssert<>(engine(), kql, name).getCsv();
        String[] lines = csv.split("\\R");
        // the header as it is, the rows in any order: a group by has no order of its own
        return lines[0]
                + "\n"
                + Stream.of(lines).skip(1).sorted().collect(Collectors.joining("\n"));
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
