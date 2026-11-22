package ai.koryki.measure.generator;

import java.util.List;

/**
 * What an output column of the rewritten query takes from measures: the measures it reads, as
 * {@code table.measure} in the order of their first use, and, where it is computed from them, the
 * expression the user wrote.
 *
 * <p>A column that is exactly one {@code agg(...)} carries the value of that measure. A column
 * computed through an expression ({@code round(agg(o.avg_freight), 2)}, a ratio of two) does not:
 * it is not the measure's value, and {@link #shown()} says what it is.
 *
 * @param measures the measures read, as {@code table.measure}; never empty
 * @param shown the expression as written, with every {@code agg(...)} call as {@code
 *     {table.measure}} ({@code round({orders.avg_freight}, 2)}); {@code null} for a column that is
 *     exactly one measure
 */
public record OutputColumn(List<String> measures, String shown) {

    public OutputColumn {
        measures = List.copyOf(measures);
    }

    /** A column that is exactly one measure. */
    public static OutputColumn measure(String tableDotMeasure) {
        return new OutputColumn(List.of(tableDotMeasure), null);
    }

    /** Whether the column is the value of one measure, not computed from measures. */
    public boolean isMeasure() {
        return shown == null;
    }

    /** The measure of a column that is exactly one: {@code table.measure}; else {@code null}. */
    public String measure() {
        return isMeasure() ? measures.get(0) : null;
    }
}
