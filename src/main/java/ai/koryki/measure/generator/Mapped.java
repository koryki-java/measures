package ai.koryki.measure.generator;

import java.util.ArrayList;
import java.util.List;

/**
 * A piece of working text and, for each of its characters, the span of the user's original query it
 * stands for: {@code from[i]..to[i]} (inclusive offsets). A character copied from the query maps to
 * itself ({@code from == to}); a generated one maps to the span of what caused it, such as the
 * whole {@code agg(...)} call.
 *
 * <p>The rewriter works in several passes over text it wrote itself, so the origin cannot be read
 * from a parse tree: it travels with the text.
 */
final class Mapped {

    /** Marks the start of a run of text whose origin is given; see {@link #mark}. */
    static final char OPEN = '';

    static final char HEAD_END = '';
    static final char CLOSE = '';

    final String text;
    final int[] from;
    final int[] to;

    private Mapped(String text, int[] from, int[] to) {
        this.text = text;
        this.from = from;
        this.to = to;
    }

    int length() {
        return text.length();
    }

    /** Text copied from the original at {@code base}: each character stands for itself. */
    static Mapped identity(String text, int base) {
        int[] f = new int[text.length()];
        for (int i = 0; i < f.length; i++) {
            f[i] = base + i;
        }
        return new Mapped(text, f, f.clone());
    }

    /** Generated text: every character stands for the span {@code from..to}. */
    static Mapped generated(String text, int from, int to) {
        int[] f = new int[text.length()];
        int[] t = new int[text.length()];
        java.util.Arrays.fill(f, from);
        java.util.Arrays.fill(t, to);
        return new Mapped(text, f, t);
    }

    Mapped slice(int a, int b) {
        return new Mapped(
                text.substring(a, b),
                java.util.Arrays.copyOfRange(from, a, b),
                java.util.Arrays.copyOfRange(to, a, b));
    }

    static Mapped concat(List<Mapped> parts) {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (Mapped p : parts) {
            n += p.length();
        }
        int[] f = new int[n];
        int[] t = new int[n];
        int at = 0;
        for (Mapped p : parts) {
            sb.append(p.text);
            System.arraycopy(p.from, 0, f, at, p.length());
            System.arraycopy(p.to, 0, t, at, p.length());
            at += p.length();
        }
        return new Mapped(sb.toString(), f, t);
    }

    static Mapped concat(Mapped... parts) {
        return concat(List.of(parts));
    }

    /** The span of the original that the characters {@code a..b} (inclusive) stand for. */
    int[] span(int a, int b) {
        int lo = Integer.MAX_VALUE;
        int hi = Integer.MIN_VALUE;
        for (int i = a; i <= b; i++) {
            lo = Math.min(lo, from[i]);
            hi = Math.max(hi, to[i]);
        }
        return new int[] {lo, hi};
    }

    /**
     * Text with origin marks put in by the rewriter while it builds: {@code kind} is {@code V}
     * (verbatim: the characters are those of {@code start..stop} of the input, one for one) or
     * {@code A} (generated: all of it stands for the span {@code start..stop} of the input). The
     * marks are plain concatenation-safe characters, so the builder can join, repeat and nest
     * strings without thinking about them.
     */
    static String mark(char kind, int start, int stop, String text) {
        if (text.isEmpty()) {
            return "";
        }
        return OPEN + "" + kind + start + "," + stop + HEAD_END + text + CLOSE;
    }

    /**
     * Resolves the marks against the input the text was made from. Text outside any mark stands for
     * the whole input.
     */
    static Mapped decode(String marked, Mapped input) {
        int[] whole = input.length() == 0 ? new int[] {0, 0} : input.span(0, input.length() - 1);
        StringBuilder sb = new StringBuilder();
        List<Integer> f = new ArrayList<>();
        List<Integer> t = new ArrayList<>();
        // frames: kind, start, stop, characters emitted so far
        List<int[]> stack = new ArrayList<>();
        for (int i = 0; i < marked.length(); i++) {
            char c = marked.charAt(i);
            if (c == OPEN) {
                int end = marked.indexOf(HEAD_END, i);
                char kind = marked.charAt(i + 1);
                String[] se = marked.substring(i + 2, end).split(",");
                stack.add(new int[] {kind, Integer.parseInt(se[0]), Integer.parseInt(se[1]), 0});
                i = end;
            } else if (c == CLOSE) {
                stack.remove(stack.size() - 1);
            } else {
                sb.append(c);
                if (stack.isEmpty()) {
                    f.add(whole[0]);
                    t.add(whole[1]);
                } else {
                    int[] top = stack.get(stack.size() - 1);
                    if (top[0] == 'V') {
                        int k = Math.min(top[1] + top[3]++, top[2]);
                        f.add(input.from[k]);
                        t.add(input.to[k]);
                    } else {
                        int[] s = input.span(top[1], top[2]);
                        f.add(s[0]);
                        t.add(s[1]);
                    }
                }
            }
        }
        return new Mapped(
                sb.toString(),
                f.stream().mapToInt(Integer::intValue).toArray(),
                t.stream().mapToInt(Integer::intValue).toArray());
    }
}
