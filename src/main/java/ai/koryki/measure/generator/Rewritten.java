package ai.koryki.measure.generator;

import java.util.List;
import java.util.Map;

/**
 * The answer of {@link AggRewriter#rewrite}: the KQL to run, and where its measure columns come
 * from.
 *
 * @param kql the query without {@code agg}; the input itself when it had none; {@code null} when
 *     the query was refused
 * @param columns the output columns of the main query that read measures, by header: the measure a
 *     column carries, or the measures an expression over them reads. A column without a header has
 *     no name to key it, so no entry; a column that reads no measure is not here
 * @param violations why the query was refused; empty when it was not
 * @param map where each part of {@link #kql()} comes from in the input, to carry the ranges of
 *     core's violations and exceptions back to the user's query; {@code null} when the query was
 *     refused
 * @param problems the same reasons as {@link #violations()}, each with its range in the input
 *     query; empty when the query was not refused
 */
public record Rewritten(
        String kql,
        Map<String, OutputColumn> columns,
        List<String> violations,
        SourceMap map,
        List<Problem> problems) {

    /**
     * One reason a query was refused, and where in the query the user wrote it.
     *
     * @param message the reason
     * @param range the place, in the user's query (stop exclusive, as core's ranges are)
     */
    public record Problem(String message, ai.koryki.antlr.Range range) {}

    public Rewritten {
        // in the order of the query's columns: copyOf would shuffle them
        columns = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(columns));
        violations = List.copyOf(violations);
        problems = problems == null ? List.of() : List.copyOf(problems);
    }

    public boolean ok() {
        return violations.isEmpty();
    }

    static Rewritten unchanged(String kql) {
        return new Rewritten(
                kql, Map.of(), List.of(), kql == null ? null : SourceMap.identity(kql), List.of());
    }

    static Rewritten refused(String why, ai.koryki.antlr.Range range) {
        return new Rewritten(null, Map.of(), List.of(why), null, List.of(new Problem(why, range)));
    }
}
