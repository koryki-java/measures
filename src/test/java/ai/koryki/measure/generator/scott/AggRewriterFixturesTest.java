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
    void aWindowedTotalOfAMeasureIsAWindowOverTheGroups() {
        // the share-of-total follow-up: the windowed sum adds up the groups, not the rows
        String share =
                "FIND dept d, d emp e FETCH d.dname dname, agg(e.emp_count) employees,"
                        + " agg(e.emp_count) / sum(agg(e.emp_count)) OVER () share";
        assertPlainOrLayered(share, true);
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

    /** A derived measure (spike): a stored ratio of two measures of two tables. */
    private AggRewriter withDerived(String expression) {
        var copy = new ai.koryki.measure.catalog.MeasureCatalog();
        measures.getTables()
                .forEach((k, v) -> copy.getTables().put(k, new java.util.ArrayList<>(v)));
        ai.koryki.measure.Measures.add(
                copy, "emp", new ai.koryki.measure.catalog.Measure("sal_per_dept", expression));
        return new AggRewriter(schema, model, copy);
    }

    @Test
    void aDerivedMeasureIsItsExpressionOverTheMeasuresItReads() {
        AggRewriter r = withDerived("to_double(agg(emp.sum_sal)) / agg(dept.dept_count)");
        String stored =
                "FIND dept d, d VIA same_department emp e FETCH agg(e.sal_per_dept) sal_per_dept";
        String written =
                "FIND dept d, d VIA same_department emp e"
                        + " FETCH (to_double(agg(e.sum_sal)) / agg(d.dept_count)) sal_per_dept";
        var a = r.rewrite(stored);
        var b = r.rewrite(written);
        org.junit.jupiter.api.Assertions.assertTrue(a.ok(), () -> "" + a.violations());
        org.junit.jupiter.api.Assertions.assertEquals(b.kql(), a.kql());
        assertPlainOrLayered(r, stored, false);
    }

    @Test
    void theTypeOfAMeasureIsWhatItsExpressionGives() {
        AggRewriter r = withDerived("to_double(agg(emp.sum_sal)) / agg(dept.dept_count)");
        org.junit.jupiter.api.Assertions.assertEquals(
                "FLOAT", r.typeOf("emp", "sal_per_dept").getTypeFamily().name());
        org.junit.jupiter.api.Assertions.assertEquals(
                "INTEGER", r.typeOf("emp", "emp_count").getTypeFamily().name());
        // a declared concept of another kind than the expression
        var bad =
                new ai.koryki.measure.catalog.Measure(
                        "bad", "agg(emp.sum_sal) / agg(emp.emp_count)");
        var c = new ai.koryki.catalog.schema.Concept();
        c.setTypeFamily("TEXT");
        bad.setConcept(c);
        var copy = new ai.koryki.measure.catalog.MeasureCatalog();
        measures.getTables()
                .forEach((k, v) -> copy.getTables().put(k, new java.util.ArrayList<>(v)));
        ai.koryki.measure.Measures.add(copy, "emp", bad);
        var rb = new AggRewriter(schema, model, copy);
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> rb.checkDeclaredType("emp", "bad"));
        r.checkDeclaredType("emp", "sal_per_dept");
    }

    @Test
    void aDerivedMeasureNeedsItsEntityOnceInTheQuery() {
        AggRewriter r = withDerived("to_double(agg(emp.sum_sal)) / agg(dept.dept_count)");
        var none = r.rewrite("FIND emp e FETCH agg(e.sal_per_dept) x");
        org.junit.jupiter.api.Assertions.assertFalse(none.ok());
    }

    private AggRewriter withJoinMeasure(String expression, String via, String entity) {
        var copy = new ai.koryki.measure.catalog.MeasureCatalog();
        measures.getTables()
                .forEach((k, v) -> copy.getTables().put(k, new java.util.ArrayList<>(v)));
        var m = new ai.koryki.measure.catalog.Measure("sal_x", expression);
        m.setJoins(List.of(new ai.koryki.measure.catalog.MeasureJoin("d", entity, via)));
        var c = new ai.koryki.catalog.schema.Concept();
        c.setTypeFamily("DECIMAL");
        m.setConcept(c);
        ai.koryki.measure.Measures.add(copy, "emp", m);
        return new AggRewriter(schema, model, copy);
    }

    private String csv(String kql) {
        String[] lines =
                new ai.koryki.databases.cases.CSVAssert<>(engine, kql, "probe")
                        .getCsv()
                        .split("\\R");
        return lines[0]
                + "\n"
                + java.util.stream.Stream.of(lines)
                        .skip(1)
                        .sorted()
                        .collect(java.util.stream.Collectors.joining("\n"));
    }

    private void sameRowsAsPlainQuery(AggRewriter r, String withAgg, String plain) {
        var rw = r.rewrite(withAgg);
        org.junit.jupiter.api.Assertions.assertTrue(rw.ok(), () -> "" + rw.violations());
        org.junit.jupiter.api.Assertions.assertEquals(csv(plain), csv(rw.kql()), rw.kql());
    }

    @Test
    void aMeasureCanReadColumnsOfATableItMeetsOnce() {
        AggRewriter r = withJoinMeasure("sum(sal * d.deptno)", "same_department", "dept");
        // the joined table need not be in the query
        sameRowsAsPlainQuery(
                r,
                "FIND emp e FETCH agg(e.sal_x) x",
                "FIND emp e, e VIA same_department dept d FETCH sum(e.sal * d.deptno) x");
        // or be, as a dimension
        sameRowsAsPlainQuery(
                r,
                "FIND dept d, d VIA same_department emp e FETCH d.dname dname, agg(e.sal_x) x",
                "FIND dept d, d VIA same_department emp e"
                        + " FETCH d.dname dname, sum(e.sal * d.deptno) x");
        // a fan-out of the query does not count an employee twice: as with the employees alone
        sameRowsAsPlainQuery(
                r,
                "FIND emp e, e VIA same_owner + pet p FETCH e.job job, agg(e.sal_x) x",
                "FIND emp e, e VIA same_department dept d FETCH e.job job,"
                        + " sum(e.sal * d.deptno) x");
    }

    @Test
    void aMeasureCannotJoinATableItMeetsManyTimes() {
        AggRewriter r = withJoinMeasure("sum(d.age)", "same_owner", "pet");
        var rw = r.rewrite("FIND emp e FETCH agg(e.sal_x) x");
        org.junit.jupiter.api.Assertions.assertFalse(rw.ok());
        org.junit.jupiter.api.Assertions.assertTrue(
                rw.violations().toString().contains("does not lead to one row"),
                rw.violations().toString());
    }

    @Test
    void aDerivedMeasureIsBoundToTheAliasItIsReadThroughInASelfJoin() {
        AggRewriter r = withDerived("to_double(agg(emp.sum_sal)) / agg(dept.dept_count)");
        var rw =
                r.rewrite(
                        "FIND dept d, d VIA same_department emp e, e VIA same_manager emp m"
                                + " FETCH agg(e.sal_per_dept) x");
        org.junit.jupiter.api.Assertions.assertTrue(rw.ok(), () -> "" + rw.violations());
        var by =
                r.rewrite(
                        "FIND dept d, d VIA same_department emp e, e VIA same_manager emp m"
                                + " FETCH to_double(agg(e.sum_sal)) / agg(d.dept_count) x");
        org.junit.jupiter.api.Assertions.assertEquals(csv(by.kql()), csv(rw.kql()));
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
