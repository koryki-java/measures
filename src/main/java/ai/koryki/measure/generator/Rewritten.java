package ai.koryki.measure.generator;

import java.util.List;
import java.util.Map;

/**
 * The answer of {@link AggRewriter#rewrite}: the KQL to run, and where its measure columns come
 * from.
 *
 * @param kql the query without {@code agg}; the input itself when it had none; {@code null} when
 *     the query was refused
 * @param measures output column header to the measure it carries, as {@code table.measure}; only
 *     columns that are exactly one {@code agg(...)} appear
 * @param derived the output columns computed from measures through an expression ({@code
 *     round(agg(o.avg_freight), 2)}, a ratio of two), by name, with the measures read, as {@code
 *     table.measure} in the order of their first use. Not in {@link #measures()}: such a column is
 *     not the measure's value. A column without a header has no name to key it, so no entry
 * @param violations why the query was refused; empty when it was not
 * @param problems the same reasons as {@link #violations()}, each with its range in the input
 *     query; empty when the query was not refused
 * @param map where each part of {@link #kql()} comes from in the input, to carry the ranges of
 *     core's violations and exceptions back to the user's query; {@code null} when the query was
 *     refused
 */
public record Rewritten(
        String kql,
        Map<String, String> measures,
        Map<String, List<String>> derived,
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

    /** A result with a source map and no problems with a place. */
    public Rewritten(
            String kql,
            Map<String, String> measures,
            Map<String, List<String>> derived,
            List<String> violations,
            SourceMap map) {
        this(kql, measures, derived, violations, map, List.of());
    }

    /** A result with no source map. */
    public Rewritten(
            String kql,
            Map<String, String> measures,
            Map<String, List<String>> derived,
            List<String> violations) {
        this(kql, measures, derived, violations, null);
    }

    /** A result without columns computed from measures: {@link #derived()} is empty. */
    public Rewritten(String kql, Map<String, String> measures, List<String> violations) {
        this(kql, measures, Map.of(), violations);
    }

    public Rewritten {
        // in the order of the query's columns: copyOf would shuffle them
        measures = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(measures));
        derived = java.util.Collections.unmodifiableMap(copyOfLists(derived));
        violations = List.copyOf(violations);
        problems = problems == null ? List.of() : List.copyOf(problems);
    }

    private static Map<String, List<String>> copyOfLists(Map<String, List<String>> in) {
        Map<String, List<String>> out = new java.util.LinkedHashMap<>();
        in.forEach((k, v) -> out.put(k, List.copyOf(v)));
        return out;
    }

    public boolean ok() {
        return violations.isEmpty();
    }

    static Rewritten unchanged(String kql) {
        return new Rewritten(
                kql, Map.of(), Map.of(), List.of(), kql == null ? null : SourceMap.identity(kql));
    }

    static Rewritten refused(String why, ai.koryki.antlr.Range range) {
        return new Rewritten(
                null, Map.of(), Map.of(), List.of(why), null, List.of(new Problem(why, range)));
    }
}
