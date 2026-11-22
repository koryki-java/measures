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
 * @param violations why the query was refused; empty when it was not
 */
public record Rewritten(String kql, Map<String, String> measures, List<String> violations) {

    public Rewritten {
        // in the order of the query's columns: copyOf would shuffle them
        measures = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(measures));
        violations = List.copyOf(violations);
    }

    public boolean ok() {
        return violations.isEmpty();
    }

    static Rewritten unchanged(String kql) {
        return new Rewritten(kql, Map.of(), List.of());
    }

    static Rewritten refused(String why) {
        return new Rewritten(null, Map.of(), List.of(why));
    }
}
