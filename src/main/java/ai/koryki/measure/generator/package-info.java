/**
 * Rewrites a KQL query that reads measures with {@code agg(...)} into KQL without {@code agg}, so
 * that every measure counts each of its source rows once per group. KQL in, KQL out; no dialect is
 * involved. The pattern is recorded in {@code docs/REWRITE.md}.
 */
package ai.koryki.measure.generator;
