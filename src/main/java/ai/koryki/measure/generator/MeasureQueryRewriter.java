package ai.koryki.measure.generator;

import ai.koryki.iql.validate.ValidateException;
import ai.koryki.iql.validate.Violation;
import ai.koryki.kql.QueryRewriter;

/**
 * The measures as a {@link QueryRewriter}: attach it to the resolver of a catalog ({@code
 * resolver.queryRewriter(new MeasureQueryRewriter(rewriter))}) and every way into the transpiler
 * reads {@code agg(alias.measure)}, with the ranges of violations and exceptions in the query that
 * was asked.
 *
 * <p>A query the rewriter refuses raises a {@link ValidateException} whose violations carry the
 * range of their cause in the user's query.
 */
public final class MeasureQueryRewriter implements QueryRewriter {

    /** The category of a violation that is the rewriter's refusal. */
    public static final String CATEGORY = "measure";

    private final AggRewriter rewriter;

    public MeasureQueryRewriter(AggRewriter rewriter) {
        this.rewriter = rewriter;
    }

    @Override
    public Rewrite rewrite(String kql) {
        Rewritten rewritten = rewriter.rewrite(kql);
        if (!rewritten.ok()) {
            throw new ValidateException(
                    rewritten.problems().stream()
                            .map(p -> new Violation(CATEGORY, "agg", p.range(), p.message()))
                            .toList());
        }
        if (rewritten.map() == null || rewritten.kql().equals(kql)) {
            // no agg(...) read a measure: it was in a comment or a string
            return Rewrite.unchanged(kql);
        }
        return new Rewrite(rewritten.kql(), rewritten.map()::toOriginal);
    }
}
