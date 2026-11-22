package ai.koryki.measure.generator;

import ai.koryki.antlr.Position;
import ai.koryki.antlr.Range;
import java.util.ArrayList;
import java.util.List;

/**
 * Where the text of a rewritten query comes from in the query the user wrote.
 *
 * <p>The rewritten KQL is what core validates and renders, so a {@code Violation} or a {@code
 * RangeException} carries a {@link Range} into <em>that</em> text. {@link #toOriginal(Range)} turns
 * it into the range the user can see: a piece copied from the query (a filter, a key, the sources
 * and links) maps exactly, character for character; a piece the rewriter made (the layer and
 * aggregate blocks, {@code max(t.v1)}) maps to what caused it, mostly the {@code agg(...)} call or
 * the select that holds it.
 */
public final class SourceMap {

    /**
     * The rewritten characters {@code start..end} (end exclusive) and the original they stand for.
     */
    record Span(int start, int end, int from, int to, boolean linear) {}

    private final String original;
    private final String rewritten;
    private final List<Span> spans;

    SourceMap(String original, String rewritten, List<Span> spans) {
        this.original = original;
        this.rewritten = rewritten;
        this.spans = List.copyOf(spans);
    }

    /** The map of a query that was not rewritten: every character stands for itself. */
    public static SourceMap identity(String kql) {
        return new SourceMap(kql, kql, List.of(new Span(0, kql.length(), 0, 0, true)));
    }

    static SourceMap of(String original, Mapped rewritten) {
        List<Span> out = new ArrayList<>();
        int n = rewritten.length();
        int i = 0;
        while (i < n) {
            int j = i + 1;
            boolean linear = false;
            if (j < n
                    && rewritten.from[j] == rewritten.from[i] + 1
                    && rewritten.to[j] == rewritten.to[i] + 1
                    && rewritten.from[i] == rewritten.to[i]) {
                linear = true;
                while (j < n
                        && rewritten.from[j] == rewritten.from[j - 1] + 1
                        && rewritten.to[j] == rewritten.from[j]) {
                    j++;
                }
            } else {
                while (j < n
                        && rewritten.from[j] == rewritten.from[i]
                        && rewritten.to[j] == rewritten.to[i]) {
                    j++;
                }
            }
            out.add(new Span(i, j, rewritten.from[i], rewritten.to[i], linear));
            i = j;
        }
        return new SourceMap(original, rewritten.text, out);
    }

    /**
     * Consistency, for the tests: the spans cover the whole rewritten text without a gap, and a
     * span that claims to be a copy holds the same characters as the original it points to.
     */
    void check() {
        int at = 0;
        for (Span s : spans) {
            if (s.start() != at) {
                throw new IllegalStateException("gap or overlap at " + at + ": " + s);
            }
            if (s.linear()) {
                for (int i = s.start(); i < s.end(); i++) {
                    char o = original.charAt(s.from() + i - s.start());
                    if (o != rewritten.charAt(i)) {
                        throw new IllegalStateException(
                                "copy differs at "
                                        + i
                                        + ": '"
                                        + rewritten.charAt(i)
                                        + "' vs '"
                                        + o
                                        + "'");
                    }
                }
            }
            if (s.from() < 0 || s.to() >= Math.max(1, original.length()) || s.from() > s.to()) {
                throw new IllegalStateException("outside the original: " + s);
            }
            at = s.end();
        }
        if (at != rewritten.length()) {
            throw new IllegalStateException("spans end at " + at + " of " + rewritten.length());
        }
    }

    /** The rewritten query this map belongs to. */
    public String rewritten() {
        return rewritten;
    }

    /**
     * The range of the user's query for a range of the rewritten one (stop exclusive, as core's
     * ranges are). A range that touches several pieces maps to the span that covers all of them.
     */
    public Range toOriginal(Range range) {
        int start = offset(rewritten, range.getStart());
        int stop = offset(rewritten, range.getStop());
        return toOriginal(start, Math.max(start, stop));
    }

    /** The same for the offsets {@code start..end} (end exclusive) of the rewritten text. */
    public Range toOriginal(int start, int end) {
        int lo = Integer.MAX_VALUE;
        int hi = Integer.MIN_VALUE;
        int last = Math.max(end - 1, start);
        for (Span s : spans) {
            if (s.end() <= start || s.start() > last) {
                continue;
            }
            if (s.linear()) {
                int a = Math.max(start, s.start());
                int b = Math.min(last, s.end() - 1);
                lo = Math.min(lo, s.from() + (a - s.start()));
                hi = Math.max(hi, s.from() + (b - s.start()));
            } else {
                lo = Math.min(lo, s.from());
                hi = Math.max(hi, s.to());
            }
        }
        if (lo > hi) {
            lo = 0;
            hi = Math.max(0, original.length() - 1);
        }
        return new Range(position(original, lo), position(original, hi + 1));
    }

    /** Line and column (both 1-based) of an offset. */
    private static Position position(String text, int offset) {
        int line = 1;
        int lineStart = 0;
        int end = Math.min(offset, text.length());
        for (int i = 0; i < end; i++) {
            if (text.charAt(i) == '\n') {
                line++;
                lineStart = i + 1;
            }
        }
        return new Position(line, offset - lineStart + 1);
    }

    private static int offset(String text, Position p) {
        int line = 1;
        int i = 0;
        while (line < p.getLine() && i < text.length()) {
            if (text.charAt(i++) == '\n') {
                line++;
            }
        }
        return Math.min(text.length(), i + p.getPos() - 1);
    }
}
