package ai.koryki.measure.generator;

import ai.koryki.catalog.domain.Attribute;
import ai.koryki.catalog.domain.Entity;
import ai.koryki.catalog.domain.Model;
import ai.koryki.catalog.schema.Column;
import ai.koryki.catalog.schema.Concept;
import ai.koryki.catalog.schema.Schema;
import ai.koryki.catalog.schema.Table;
import ai.koryki.catalog.types.ConceptResolver;
import ai.koryki.iql.functions.FunctionCatalog;
import ai.koryki.iql.functions.FunctionDefinition;
import ai.koryki.iql.functions.FunctionKind;
import ai.koryki.iql.functions.StandardFunctions;
import ai.koryki.kql.KQLParser;
import ai.koryki.kql.KQLReader;
import ai.koryki.measure.catalog.Measure;
import ai.koryki.measure.catalog.MeasureCatalog;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.misc.Interval;
import org.antlr.v4.runtime.tree.ParseTree;

/**
 * Rewrites a KQL query that reads measures with {@code agg(alias.measure)} into KQL without {@code
 * agg}: KQL in, KQL out, no dialect involved. Each measure counts each row of its table once per
 * group, however the query joins or filters.
 *
 * <p>The shape, per table alias that has a measure in the query (see {@code docs/REWRITE.md}):
 *
 * <ol>
 *   <li>a <em>distinct layer</em>: the original {@code FIND} tree and its row filters, with {@code
 *       FETCH DISTINCT} of the row identity, the group keys and the columns the measures read;
 *   <li>an <em>aggregate block</em>: the measure expressions over that layer, grouped by the keys;
 *   <li>a <em>union block</em> that pads the aggregate blocks with typed NULLs and joins them;
 *   <li>the outer query: the keys, and {@code max} of every measure column.
 * </ol>
 *
 * <p>Supported now: each {@code SELECT} that reads a measure (also in a {@code WITH} block, a
 * scalar sub-query or a branch of a set operation, see {@code Driver}), with inner and optional
 * ({@code +}) links, group keys as plain expressions, {@code agg} in {@code FETCH} (also inside
 * scalar expressions) and in top-level {@code AND} conjuncts of {@code FILTER} (those become {@code
 * HAVING}), sort and limit. Anything else is refused with a violation that says why; the query is
 * never guessed at.
 *
 * <p>With an optional link a layer holds NULL-padded rows (their identity is NULL). They add
 * nothing to a measure: {@code sum}, {@code avg} and {@code count(x)} skip the NULLs, and {@code
 * count()} is written as the count of the identity. A group whose rows are all padded stays, with
 * the empty-input value of each measure (0 for a count, NULL for the others).
 *
 * <p>The measure expression comes from a file and is not trusted. It is parsed as an expression of
 * its own and only its bare column names are replaced; its text is never pasted into the query
 * unparsed.
 */
public final class AggRewriter {

    private static final Pattern AGG = Pattern.compile("(?<![a-z0-9])agg\\s*\\(");

    private final Model model;
    private final Schema schema;
    private final MeasureCatalog measures;
    private final ConceptResolver concepts;
    private final FunctionCatalog functions;

    /** With koryki's standard function catalog: what is an aggregate is decided there. */
    public AggRewriter(Schema schema, Model model, MeasureCatalog measures) {
        this(schema, model, measures, StandardFunctions.canonical());
    }

    /**
     * @param functions the function catalog that says which functions are aggregates; pass the one
     *     of the dialect in use if it differs from the standard one
     */
    public AggRewriter(
            Schema schema, Model model, MeasureCatalog measures, FunctionCatalog functions) {
        this.functions = functions;
        this.schema = schema;
        this.model = model;
        this.measures = measures;
        this.concepts = new ConceptResolver(schema);
    }

    private KQLParser.QueryContext parse(String text) {
        try {
            KQLReader reader = new KQLReader(text, true);
            KQLParser.QueryContext ctx = reader.getQuery();
            if (!reader.getPanic().isEmpty()) {
                var at = reader.getPanic().get(0);
                throw new Refusal("syntax error in the query").panic(at.getLine(), at.getCol());
            }
            return ctx;
        } catch (Refusal r) {
            throw r;
        } catch (Exception e) {
            Refusal r = new Refusal("syntax error: " + firstLine(e.getMessage()));
            if (e instanceof ai.koryki.antlr.PositionException pe) {
                r.panic(pe.getLine(), pe.getPos());
            } else if (e instanceof ai.koryki.antlr.RangeException re && re.getRange() != null) {
                r.panic(re.getRange().getStart().getLine(), re.getRange().getStart().getPos());
            }
            throw r;
        }
    }

    public Rewritten rewrite(String kql) {
        if (kql == null || !AGG.matcher(kql).find()) {
            return Rewritten.unchanged(kql);
        }
        try {
            int cut = visualizeStart(kql);
            if (cut < 0) {
                return new Driver(kql).run();
            }
            // A VISUALIZE clause is not part of what is rewritten: cut off before, put back after.
            String data = kql.substring(0, cut).stripTrailing();
            if (!AGG.matcher(data).find()) {
                return Rewritten.unchanged(kql);
            }
            Rewritten r = new Driver(data).run();
            return new Rewritten(
                    r.kql() + "\n" + kql.substring(cut),
                    r.measures(),
                    r.derived(),
                    r.violations(),
                    r.map().withTail(kql, cut, "\n"),
                    r.problems(),
                    r.shown());
        } catch (Refusal r) {
            if (!r.located()) {
                r.from = 0;
                r.to = Math.max(0, kql.length() - 1);
            }
            return Rewritten.refused(r.getMessage(), SourceMap.range(kql, r.from, r.to));
        }
    }

    /**
     * Where the VISUALIZE clause starts, or -1 where there is none or the query will not parse. The
     * clause belongs to the larger grammar of koryki-kqlvisualize, which this project does not
     * compile against, so it is found by the name of its node.
     */
    private int visualizeStart(String kql) {
        try {
            KQLParser.QueryContext q = parse(kql);
            for (int i = 0; i < q.getChildCount(); i++) {
                if (q.getChild(i) instanceof ParserRuleContext c
                        && "VisualizeClauseContext".equals(c.getClass().getSimpleName())) {
                    return c.getStart().getStartIndex();
                }
            }
        } catch (Refusal r) {
            // reported with its position by the rewrite itself
        }
        return -1;
    }

    /**
     * Whether the catalog knows the function as an aggregate (kind AGGREGATE, not a window
     * function).
     */
    private boolean isAggregate(String name) {
        for (FunctionDefinition d : functions.overloads(name)) {
            if (d.getKind() == FunctionKind.AGGREGATE) {
                return true;
            }
        }
        return false;
    }

    private static final class Refusal extends RuntimeException {
        // where it is in the input of the Run that threw it (relative) or, once known, in the
        // user's query (absolute); -1 when not yet known
        int from = -1;
        int to = -1;
        // line and column (1-based) of the token the parser stopped at; 0 when unknown
        int panicFrom = -1;
        int panicTo = -1;

        Refusal(String message) {
            super(message, null, false, false);
        }

        Refusal panic(int line, int col) {
            panicFrom = line;
            panicTo = col;
            return this;
        }

        boolean located() {
            return from >= 0;
        }

        /** Where it is, in the user's query: the span of {@code ctx} of the input {@code in}. */
        void locate(ParserRuleContext ctx, Mapped in) {
            if (located()) {
                return;
            }
            int[] span;
            if (in.length() == 0) {
                span = new int[] {0, 0};
            } else if (ctx == null || ctx.start == null || ctx.stop == null) {
                span = in.span(0, in.length() - 1);
            } else {
                span =
                        in.span(
                                Math.min(ctx.start.getStartIndex(), in.length() - 1),
                                Math.min(ctx.stop.getStopIndex(), in.length() - 1));
            }
            from = span[0];
            to = span[1];
        }
    }

    // ---------------------------------------------------------------------------------------

    /** One table alias of the query that has measures: its layer and aggregate block. */
    private static final class Layer {
        final int n;
        final String alias;
        final Entity entity;
        final Table table;
        final List<String> identity = new ArrayList<>(); // attribute names
        final Map<String, String> args = new LinkedHashMap<>(); // attribute -> cN
        final List<Use> uses = new ArrayList<>();

        Layer(int n, String alias, Entity entity, Table table) {
            this.n = n;
            this.alias = alias;
            this.entity = entity;
            this.table = table;
        }

        String arg(String attribute) {
            return args.computeIfAbsent(attribute, a -> "c" + (args.size() + 1));
        }
    }

    /** One measure read through one alias, and its output column {@code vN}. */
    private static final class Use {
        final Layer layer;
        final Measure measure;
        final int n;
        String expression; // over the layer: bare names replaced by x.cN
        String nullOf;
        KQLParser.FunctionContext call; // the first agg(...) that reads it, for the source map

        Use(Layer layer, Measure measure, int n) {
            this.layer = layer;
            this.measure = measure;
            this.n = n;
        }

        String column() {
            return "v" + n;
        }
    }

    private final class Run {
        private final String kql;
        private final Mapped in;
        private KQLParser.SelectContext select;
        // what it is working on: where a refusal that names nothing is put
        private ParserRuleContext focus;
        private final Map<String, String> entityOfAlias = new LinkedHashMap<>();
        private final Map<String, Layer> layers = new LinkedHashMap<>();
        private final Map<String, Use> uses = new LinkedHashMap<>();
        private final Map<String, String> provenance = new LinkedHashMap<>();
        private final Map<String, List<String>> derived = new LinkedHashMap<>();
        private final Map<String, String> shown = new LinkedHashMap<>();
        // An optional link (+) in the source graph: a layer then holds NULL-padded rows.
        private boolean outer;
        // FETCH ROLLUP: one evaluation per grouping set, the keys dropped from the right.
        private boolean rollup;
        private final List<KQLParser.FieldContext> keyFields = new ArrayList<>();

        // the names of its blocks, so that several selects of one query do not clash
        private final String prefix;
        // what it produced: the blocks, and the query that reads them
        private List<String> blocks;
        private String finalQuery;

        Run(Mapped in, String prefix) {
            this.in = in;
            this.kql = in.text;
            this.prefix = prefix;
        }

        Mapped decode(String marked) {
            return Mapped.decode(marked, in);
        }

        Rewritten run() {
            try {
                return run0();
            } catch (Refusal r) {
                r.locate(focus != null ? focus : select, in);
                throw r;
            }
        }

        private Rewritten run0() {
            KQLParser.QueryContext query = parse(kql);
            focus = query;
            if (query.WITH() != null) {
                throw new Refusal("a query with its own WITH blocks is not supported yet");
            }
            if (query.set() == null || query.set().select() == null) {
                throw new Refusal("a set operation or a bracketed query is not supported yet");
            }
            select = query.set().select();
            focus = select;
            sources();

            KQLParser.FetchClauseContext fetch = select.fetchClause();
            focus = fetch != null ? fetch : select;
            if (fetch == null) {
                throw new Refusal("agg() needs a FETCH clause");
            }
            if (fetch.DISTINCT() != null) {
                throw new Refusal("FETCH DISTINCT is not supported with agg() yet");
            }
            rollup = fetch.ROLLUP() != null;

            // The filter: row predicates go into the layers, predicates on measures become HAVING.
            List<String> rows = new ArrayList<>();
            List<KQLParser.Logical_expressionContext> having = new ArrayList<>();
            if (select.filterClause() != null) {
                List<KQLParser.Logical_expressionContext> parts = new ArrayList<>();
                conjuncts(select.filterClause().logical_expression(), parts);
                for (var part : parts) {
                    if (aggCalls(part).isEmpty()) {
                        String text = copy(part);
                        rows.add(part.unary_logical_expression() != null ? text : "(" + text + ")");
                    } else {
                        having.add(part);
                    }
                }
            }

            // The fetch items: keys, and items that read measures.
            List<KQLParser.FetchItemContext> items = fetch.fetchItem();
            List<String> keys = new ArrayList<>(); // expression text per key
            Map<KQLParser.FetchItemContext, Integer> keyOf = new LinkedHashMap<>();
            boolean any = !having.isEmpty();
            for (var item : items) {
                focus = item;
                var e = item.expression();
                if (!aggCalls(e).isEmpty()) {
                    any = true;
                    noFieldsOutsideAgg(e, "a FETCH item that reads a measure also reads a column");
                    for (var call : aggCalls(e)) {
                        use(call);
                    }
                } else {
                    if (rawAggregate(e)) {
                        throw new Refusal(
                                "a plain aggregate next to agg() is not supported: '"
                                        + text(e)
                                        + "'");
                    }
                    keyOf.put(item, keys.size());
                    keys.add(copy(e));
                    keyFields.add(e.field() != null && e.getChildCount() == 1 ? e.field() : null);
                }
            }
            for (var h : having) {
                focus = h;
                noFieldsOutsideAgg(h, "a predicate on a measure also reads a column");
                for (var call : aggCalls(h)) {
                    use(call);
                }
            }
            focus = select;
            if (!any || uses.isEmpty()) {
                throw new Refusal("agg() was not found in a FETCH item or in the FILTER");
            }
            if (select.fetchClause() == null) {
                throw new Refusal("agg() needs a FETCH clause");
            }

            if (rollup && keys.isEmpty()) {
                throw new Refusal("ROLLUP needs at least one group key");
            }
            focus = select;
            prepare();
            return new Rewritten(
                    build(items, keyOf, keys, rows, having),
                    provenance,
                    derived,
                    List.of(),
                    null,
                    List.of(),
                    shown);
        }

        // ----- the input -----

        private void sources() {
            List<KQLParser.SourceContext> all = new ArrayList<>();
            all.add(select.source());
            for (var link : select.link()) {
                // + (LEFT) and FULL pad the missing side with NULLs; CROSS pads nothing
                if (link.kind != null && link.kind.getType() != KQLParser.CROSS) {
                    outer = true;
                }
                all.add(link.source());
            }
            for (var s : all) {
                if (s.name != null) {
                    entityOfAlias.put(s.alias.getText(), s.name.getText());
                }
            }
        }

        // ----- agg calls -----

        private List<KQLParser.FunctionContext> aggCalls(ParseTree root) {
            List<KQLParser.FunctionContext> out = new ArrayList<>();
            collect(root, KQLParser.FunctionContext.class, out);
            out.removeIf(f -> !"agg".equals(f.func.getText()));
            return out;
        }

        private boolean rawAggregate(ParseTree root) {
            List<KQLParser.FunctionContext> all = new ArrayList<>();
            collect(root, KQLParser.FunctionContext.class, all);
            return all.stream().anyMatch(f -> isAggregate(f.func.getText()) && f.window() == null);
        }

        private void noFieldsOutsideAgg(ParseTree root, String message) {
            if (fieldOutsideAgg(root)) {
                throw new Refusal(message + ": " + text((ParserRuleContext) root));
            }
        }

        private boolean fieldOutsideAgg(ParseTree t) {
            if (t instanceof KQLParser.FunctionContext f && "agg".equals(f.func.getText())) {
                return false;
            }
            if (t instanceof KQLParser.FieldContext) {
                return true;
            }
            for (int i = 0; i < t.getChildCount(); i++) {
                if (fieldOutsideAgg(t.getChild(i))) {
                    return true;
                }
            }
            return false;
        }

        private void use(KQLParser.FunctionContext call) {
            focus = call;
            if (call.window() != null) {
                throw new Refusal("agg() takes no OVER clause");
            }
            for (ParseTree p = call.getParent(); p != null && p != select; p = p.getParent()) {
                if (p instanceof KQLParser.SelectContext) {
                    throw new Refusal("agg() inside a sub-query is not supported yet");
                }
                if (p instanceof KQLParser.FunctionContext f
                        && !"agg".equals(f.func.getText())
                        && isAggregate(f.func.getText())) {
                    throw new Refusal("agg() inside the aggregate " + f.func.getText() + "()");
                }
            }
            if (call.argument().size() != 1
                    || call.argument(0).expression() == null
                    || call.argument(0).expression().field() == null
                    || call.argument(0).expression().getChildCount() != 1) {
                throw new Refusal(
                        "agg() takes one measure, written alias.name: '" + text(call) + "'");
            }
            KQLParser.FieldContext field = call.argument(0).expression().field();
            String alias = field.alias.getText();
            String name = field.name.getText();
            String entityName = entityOfAlias.get(alias);
            if (entityName == null) {
                throw new Refusal("agg(" + alias + "." + name + "): unknown alias '" + alias + "'");
            }
            Entity entity =
                    model.getEntity(entityName)
                            .orElseThrow(() -> new Refusal("unknown entity '" + entityName + "'"));
            String tableName = entity.getTable() != null ? entity.getTable() : entity.getName();
            Optional<Measure> measure = measures.find(tableName, name);
            if (measure.isEmpty()) {
                throw new Refusal(
                        "agg("
                                + alias
                                + "."
                                + name
                                + "): table '"
                                + tableName
                                + "' has no measure '"
                                + name
                                + "'");
            }
            Layer layer =
                    layers.computeIfAbsent(
                            alias, a -> new Layer(layers.size() + 1, a, entity, table(tableName)));
            uses.computeIfAbsent(
                    alias + "." + name,
                    k -> {
                        Use u = new Use(layer, measure.get(), uses.size() + 1);
                        u.call = call;
                        layer.uses.add(u);
                        return u;
                    });
        }

        private Table table(String name) {
            return schema.getTables().stream()
                    .filter(t -> name.equals(t.getName()))
                    .findFirst()
                    .orElseThrow(() -> new Refusal("the catalog has no table '" + name + "'"));
        }

        // ----- measures -> layers -----

        private void prepare() {
            for (Layer layer : layers.values()) {
                focus = layer.uses.isEmpty() ? select : layer.uses.get(0).call;
                List<Column> pk =
                        layer.table.getColumns().stream()
                                .filter(c -> c.getPkPos() > 0)
                                .sorted(Comparator.comparingInt(Column::getPkPos))
                                .toList();
                if (pk.isEmpty()) {
                    throw new Refusal(
                            "table '"
                                    + layer.table.getName()
                                    + "' has no primary key: the rows its measures count have no"
                                    + " identity");
                }
                for (Column c : pk) {
                    layer.identity.add(attribute(layer, c.getName()).getName());
                }
                for (Use u : layer.uses) {
                    KQLParser.ExpressionContext e = parsed(u.measure);
                    u.expression = expression(layer, e);
                    u.nullOf = nullOf(layer, u, e);
                }
            }
        }

        private Attribute attribute(Layer layer, String column) {
            return layer.entity.getAttributes().stream()
                    .filter(a -> column.equals(a.getColumn() != null ? a.getColumn() : a.getName()))
                    .findFirst()
                    .orElseThrow(
                            () ->
                                    new Refusal(
                                            "column '"
                                                    + column
                                                    + "' of table '"
                                                    + layer.table.getName()
                                                    + "' is not an attribute of entity '"
                                                    + layer.entity.getName()
                                                    + "'"));
        }

        private KQLParser.ExpressionContext parsed(Measure m) {
            String where = "measure '" + m.getName() + "': ";
            KQLParser.QueryContext q;
            try {
                q = parse("FIND t x FETCH " + m.getExpression());
            } catch (Refusal r) {
                throw new Refusal(where + r.getMessage());
            }
            var select = q.set() == null ? null : q.set().select();
            if (select == null
                    || select.fetchClause() == null
                    || select.fetchClause().fetchItem().size() != 1
                    || select.filterClause() != null
                    || select.limitClause() != null
                    || !select.link().isEmpty()) {
                throw new Refusal(where + "the expression is not one expression");
            }
            var e = select.fetchClause().fetchItem(0).expression();
            List<KQLParser.FieldContext> fields = new ArrayList<>();
            collect(e, KQLParser.FieldContext.class, fields);
            if (!fields.isEmpty()) {
                throw new Refusal(where + "write bare column names, not alias.name");
            }
            if (!aggCalls(e).isEmpty()) {
                throw new Refusal(where + "a measure cannot read another measure");
            }
            List<KQLParser.FunctionContext> fns = new ArrayList<>();
            collect(e, KQLParser.FunctionContext.class, fns);
            if (fns.stream().noneMatch(f -> isAggregate(f.func.getText()))) {
                throw new Refusal(where + "the expression has no aggregate function");
            }
            return e;
        }

        /** The measure expression with its bare column names replaced by columns of the layer. */
        private String expression(Layer layer, KQLParser.ExpressionContext e) {
            // The pieces of the text to replace: a bare column name becomes a column of the layer;
            // with an optional link in the query, count() becomes the count of the identity, so
            // that
            // the NULL-padded row of the layer (its identity is NULL) is not counted.
            record Piece(int from, int to, String with) {}
            List<Piece> pieces = new ArrayList<>();
            int base = e.start.getStartIndex();
            List<KQLParser.ParamContext> params = new ArrayList<>();
            collect(e, KQLParser.ParamContext.class, params);
            for (var p : params) {
                String col = layer.arg(attribute(layer, p.getText()).getName());
                pieces.add(
                        new Piece(
                                p.start.getStartIndex() - base,
                                p.stop.getStopIndex() - base + 1,
                                "x." + col));
            }
            if (outer) {
                List<KQLParser.FunctionContext> fns = new ArrayList<>();
                collect(e, KQLParser.FunctionContext.class, fns);
                for (var f : fns) {
                    if ("count".equals(f.func.getText()) && f.argument().isEmpty()) {
                        pieces.add(
                                new Piece(
                                        f.start.getStartIndex() - base,
                                        f.stop.getStopIndex() - base + 1,
                                        "count(x.i1)"));
                    }
                }
            }
            pieces.sort(Comparator.comparingInt(Piece::from));
            String src = text(e);
            StringBuilder sb = new StringBuilder();
            int at = 0;
            for (var p : pieces) {
                sb.append(src, at, p.from()).append(p.with());
                at = p.to();
            }
            return sb.append(src.substring(at)).toString();
        }

        private String nullOf(Layer layer, Use u, KQLParser.ExpressionContext e) {
            focus = u.call;
            Concept c = u.measure.getConcept();
            if (c == null && u.measure.getConceptRef() != null) {
                c = concepts.concept(u.measure.getConceptRef());
            }
            String family = c == null ? null : c.getTypeFamily();
            if (family == null
                    && e.function() != null
                    && "count".equals(e.function().func.getText())) {
                family = "INTEGER";
            }
            if (family == null) {
                throw new Refusal(
                        "measure '"
                                + layer.table.getName()
                                + "."
                                + u.measure.getName()
                                + "' has no concept: its result type is needed to pad the union");
            }
            return typedNull(family, "measure '" + u.measure.getName() + "'");
        }

        /** A NULL of the type of a column family, for padding a union. */
        private String typedNull(String family, String what) {
            return switch (family) {
                case "INTEGER" -> "to_integer(NULL)";
                case "DECIMAL" -> "to_decimal(NULL, 18, 4)";
                case "FLOAT" -> "to_float(NULL)";
                case "TEXT" -> "to_text(to_integer(NULL))";
                case "DATE" -> "to_date(NULL)";
                case "TIMESTAMP" -> "to_timestamp(NULL)";
                case "BOOLEAN" -> "to_boolean(NULL)";
                default ->
                        throw new Refusal(
                                what
                                        + " has the type family "
                                        + family
                                        + ", which the union cannot be padded for yet");
            };
        }

        /** The NULL that stands in for group key {@code i} on the rows of a rolled-up total. */
        private String keyNull(int i) {
            KQLParser.FieldContext f = keyFields.get(i);
            focus = f != null ? f : select.fetchClause();
            if (f == null) {
                throw new Refusal("ROLLUP needs plain columns as group keys, not expressions");
            }
            String entityName = entityOfAlias.get(f.alias.getText());
            Entity entity =
                    model.getEntity(entityName)
                            .orElseThrow(() -> new Refusal("unknown entity '" + entityName + "'"));
            String tableName = entity.getTable() != null ? entity.getTable() : entity.getName();
            Attribute a =
                    entity.getAttributes().stream()
                            .filter(x -> f.name.getText().equals(x.getName()))
                            .findFirst()
                            .orElseThrow(
                                    () ->
                                            new Refusal(
                                                    "unknown attribute '"
                                                            + text(f)
                                                            + "' as a key"));
            String column = a.getColumn() != null ? a.getColumn() : a.getName();
            Column c =
                    table(tableName).getColumns().stream()
                            .filter(x -> column.equals(x.getName()))
                            .findFirst()
                            .orElseThrow(() -> new Refusal("unknown column of key " + text(f)));
            String family = c.getConcept() == null ? null : c.getConcept().getTypeFamily();
            if (family == null) {
                throw new Refusal("group key " + text(f) + " has no type family for ROLLUP");
            }
            return typedNull(family, "group key " + text(f));
        }

        // ----- the output -----

        private String build(
                List<KQLParser.FetchItemContext> items,
                Map<KQLParser.FetchItemContext, Integer> keyOf,
                List<String> keys,
                List<String> rows,
                List<KQLParser.Logical_expressionContext> having) {
            StringBuilder sb = new StringBuilder("WITH\n");
            String graph = graph();
            String filter = rows.isEmpty() ? "" : "\n FILTER " + String.join(" AND ", rows);

            List<Layer> ls = new ArrayList<>(layers.values());
            List<String> blocks = new ArrayList<>();
            for (Layer l : ls) {
                StringBuilder d = new StringBuilder();
                d.append(prefix)
                        .append("aggd")
                        .append(l.n)
                        .append(" AS (\n ")
                        .append(graph)
                        .append(filter);
                d.append("\n FETCH DISTINCT ");
                List<String> cols = new ArrayList<>();
                for (int i = 0; i < l.identity.size(); i++) {
                    cols.add(l.alias + "." + l.identity.get(i) + " i" + (i + 1));
                }
                for (int i = 0; i < keys.size(); i++) {
                    cols.add(keys.get(i) + " k" + (i + 1));
                }
                l.args.forEach((attr, c) -> cols.add(l.alias + "." + attr + " " + c));
                d.append(String.join(", ", cols)).append(")");
                blocks.add(d.toString());

                // One aggregate block per grouping set: all the keys, then one fewer, ... none
                // (ROLLUP); without ROLLUP just the one set of all keys.
                for (int j = keys.size(); j >= (rollup ? 0 : keys.size()); j--) {
                    List<String> m = new ArrayList<>();
                    for (int i = 0; i < j; i++) {
                        m.add("x.k" + (i + 1) + " k" + (i + 1));
                    }
                    for (Use u : l.uses) {
                        m.add(anchor(u.call, u.expression + " " + u.column()));
                    }
                    blocks.add(
                            prefix
                                    + "aggm"
                                    + l.n
                                    + (rollup ? "s" + j : "")
                                    + " AS (FIND "
                                    + prefix
                                    + "aggd"
                                    + l.n
                                    + " x FETCH "
                                    + String.join(", ", m)
                                    + ")");
                }
            }

            String source;
            if (ls.size() == 1 && !rollup) {
                source = prefix + "aggm1";
            } else {
                List<String> branches = new ArrayList<>();
                for (int j = keys.size(); j >= (rollup ? 0 : keys.size()); j--) {
                    for (Layer l : ls) {
                        List<String> cols = new ArrayList<>();
                        if (rollup) {
                            // which grouping set: keeps a total apart from a group whose key is
                            // NULL
                            cols.add(j + " g");
                        }
                        for (int i = 0; i < keys.size(); i++) {
                            cols.add((i < j ? "a.k" + (i + 1) : keyNull(i)) + " k" + (i + 1));
                        }
                        for (Use u : uses.values()) {
                            cols.add(
                                    (u.layer == l ? "a." + u.column() : u.nullOf)
                                            + " "
                                            + u.column());
                        }
                        branches.add(
                                "FIND "
                                        + prefix
                                        + "aggm"
                                        + l.n
                                        + (rollup ? "s" + j : "")
                                        + " a FETCH "
                                        + String.join(", ", cols));
                    }
                }
                blocks.add(prefix + "aggu AS (" + String.join("\n UNIONALL\n ", branches) + ")");
                source = prefix + "aggu";
            }
            if (rollup) {
                // One row per grouping set and key; the set number is dropped in the query below.
                List<String> cols = new ArrayList<>();
                cols.add("t.g g");
                for (int i = 0; i < keys.size(); i++) {
                    cols.add("t.k" + (i + 1) + " k" + (i + 1));
                }
                for (Use u : uses.values()) {
                    cols.add("max(t." + u.column() + ") " + u.column());
                }
                blocks.add(
                        prefix
                                + "aggr AS (FIND "
                                + prefix
                                + "aggu t FETCH "
                                + String.join(", ", cols)
                                + ")");
                source = prefix + "aggr";
            }
            sb.append(String.join(",\n", blocks)).append("\n");
            this.blocks = blocks;
            int cut = sb.length();

            sb.append("FIND ").append(source).append(rollup ? " r" : " t");
            if (!having.isEmpty()) {
                List<String> h = new ArrayList<>();
                for (var part : having) {
                    String text = replaceAggs(part);
                    h.add(part.unary_logical_expression() != null ? text : "(" + text + ")");
                }
                sb.append("\n FILTER ").append(String.join(" AND ", h));
            }
            List<String> out = new ArrayList<>();
            for (var item : items) {
                out.add(outerItem(item, keyOf));
            }
            sb.append("\n FETCH ").append(String.join(", ", out));
            if (select.limitClause() != null) {
                sb.append("\n ").append(copy(select.limitClause()));
            }
            this.finalQuery = sb.substring(cut);
            return sb.toString();
        }

        private String graph() {
            ParserRuleContext last =
                    select.link().isEmpty()
                            ? select.source()
                            : select.link(select.link().size() - 1);
            return Mapped.mark(
                    'V',
                    select.start.getStartIndex(),
                    last.stop.getStopIndex(),
                    select.start
                            .getInputStream()
                            .getText(
                                    Interval.of(
                                            select.start.getStartIndex(),
                                            last.stop.getStopIndex())));
        }

        /** The expression as written, each agg call as {table.measure}. */
        private String shownText(
                KQLParser.ExpressionContext e, List<KQLParser.FunctionContext> calls) {
            int from = e.start.getStartIndex();
            StringBuilder sb =
                    new StringBuilder(
                            e.start
                                    .getInputStream()
                                    .getText(Interval.of(from, e.stop.getStopIndex())));
            for (int i = calls.size() - 1; i >= 0; i--) {
                var c = calls.get(i);
                sb.replace(
                        c.start.getStartIndex() - from,
                        c.stop.getStopIndex() + 1 - from,
                        "{" + tableOf(c) + "." + measureOf(c).getName() + "}");
            }
            return sb.toString();
        }

        private String outerItem(
                KQLParser.FetchItemContext item, Map<KQLParser.FetchItemContext, Integer> keyOf) {
            var e = item.expression();
            StringBuilder sb = new StringBuilder();
            String header = item.h == null ? null : item.h.getText();
            if (keyOf.containsKey(item)) {
                int k = keyOf.get(item) + 1;
                sb.append(rollup ? "r.k" : "t.k").append(k);
                if (header == null) {
                    header =
                            e.field() != null && e.getChildCount() == 1
                                    ? e.field().name.getText()
                                    : "k" + k;
                }
            } else {
                sb.append(replaceAggs(e));
                var calls = aggCalls(e);
                if (header == null
                        && calls.size() == 1
                        && e.function() == calls.get(0)
                        && e.getChildCount() == 1) {
                    header = measureOf(calls.get(0)).getName();
                }
                if (calls.size() == 1 && e.function() == calls.get(0) && e.getChildCount() == 1) {
                    provenance.put(
                            header,
                            tableOf(calls.get(0)) + "." + measureOf(calls.get(0)).getName());
                } else if (header != null && !calls.isEmpty()) {
                    // computed from measures: round(agg(..), 2), a ratio of two
                    derived.put(
                            header,
                            calls.stream()
                                    .map(c -> tableOf(c) + "." + measureOf(c).getName())
                                    .distinct()
                                    .toList());
                    shown.put(header, shownText(e, calls));
                }
            }
            if (header != null) {
                sb.append(' ').append(header);
            }
            if (item.label != null) {
                sb.append(' ').append(item.label.getText());
            }
            if (item.ASC() != null) {
                sb.append(" ASC");
            }
            if (item.DESC() != null) {
                sb.append(" DESC");
            }
            if (item.idx != null) {
                sb.append(' ').append(item.idx.getText());
            }
            return anchor(item, sb.toString());
        }

        /** A copy of the node's text from the input: it maps back character for character. */
        private String copy(ParserRuleContext ctx) {
            return Mapped.mark('V', ctx.start.getStartIndex(), ctx.stop.getStopIndex(), text(ctx));
        }

        /** Text from {@code offset} of the input, that has been cut up by the caller. */
        private String verbatim(int offset, String text) {
            return Mapped.mark('V', offset, offset + text.length() - 1, text);
        }

        /** Text the rewriter made: all of it maps back to the node that caused it. */
        private String anchor(ParserRuleContext ctx, String text) {
            return Mapped.mark('A', ctx.start.getStartIndex(), ctx.stop.getStopIndex(), text);
        }

        private Use useOf(KQLParser.FunctionContext call) {
            var f = call.argument(0).expression().field();
            return uses.get(f.alias.getText() + "." + f.name.getText());
        }

        private Measure measureOf(KQLParser.FunctionContext call) {
            return useOf(call).measure;
        }

        private String tableOf(KQLParser.FunctionContext call) {
            return useOf(call).layer.table.getName();
        }

        /** The text of a node with every agg(...) call replaced by the merged column. */
        private String replaceAggs(ParserRuleContext ctx) {
            List<KQLParser.FunctionContext> calls = aggCalls(ctx);
            calls.sort(Comparator.comparingInt(c -> c.start.getStartIndex()));
            String src = text(ctx);
            int base = ctx.start.getStartIndex();
            StringBuilder sb = new StringBuilder();
            int at = 0;
            for (var call : calls) {
                int from = call.start.getStartIndex() - base;
                int to = call.stop.getStopIndex() - base + 1;
                sb.append(verbatim(base + at, src.substring(at, from)))
                        .append(
                                anchor(
                                        call,
                                        rollup
                                                ? "r." + useOf(call).column()
                                                : "max(t." + useOf(call).column() + ")"));
                at = to;
            }
            return sb.append(verbatim(base + at, src.substring(at))).toString();
        }
    }

    // ---------------------------------------------------------------------------------------

    /**
     * Rewrites a whole query: every select that reads a measure directly, innermost first. The
     * blocks of a select are put in front of the block (or the main query) that holds it, and the
     * select itself is replaced by the query that reads them, so a sub-query, a WITH block of the
     * user and a branch of a set operation each stay where they were and read their measures.
     */
    private final class Driver {
        private final List<Mapped> parts = new ArrayList<>(); // the WITH blocks, as text
        private Mapped main;
        private final Map<String, String> provenance = new LinkedHashMap<>();
        private final Map<String, List<String>> derived = new LinkedHashMap<>();
        private final Map<String, String> shown = new LinkedHashMap<>();
        private final String original;

        Driver(String original) {
            this.original = original;
        }

        Rewritten run() {
            KQLParser.QueryContext q;
            try {
                q = parse(original);
            } catch (Refusal r) {
                // a syntax error of the query itself: the parser's position is the user's
                if (r.panicFrom > 0 && r.panicTo > 0 && !r.located()) {
                    int line = 1;
                    int i = 0;
                    while (line < r.panicFrom && i < original.length()) {
                        if (original.charAt(i++) == '\n') {
                            line++;
                        }
                    }
                    r.from = Math.min(i + r.panicTo - 1, original.length() - 1);
                    r.to = r.from;
                }
                throw r;
            }
            if (q.WITH() != null) {
                for (var block : q.block()) {
                    parts.add(Mapped.identity(text(block), block.start.getStartIndex()));
                }
            }
            main = Mapped.identity(text(q.set()), q.set().start.getStartIndex());
            int total = marked(q).size();
            int round = 0;
            while (true) {
                int[] starts = new int[parts.size() + 1];
                Mapped fullMapped = assemble(starts);
                String full = fullMapped.text;
                q = parse(full);
                List<KQLParser.SelectContext> marked = marked(q);
                if (marked.isEmpty()) {
                    return new Rewritten(
                            full,
                            provenance,
                            derived,
                            List.of(),
                            SourceMap.of(original, fullMapped),
                            List.of(),
                            shown);
                }
                KQLParser.SelectContext s = innermost(marked);
                Run run =
                        new Run(
                                fullMapped.slice(
                                        s.start.getStartIndex(), s.stop.getStopIndex() + 1),
                                total == 1 ? "" : "q" + (++round));
                Rewritten r = run.run();

                int at = s.start.getStartIndex();
                int part = parts.size(); // the main query, unless the select is in a block
                for (int i = 0; i < parts.size(); i++) {
                    int end = i + 1 < parts.size() ? starts[i + 1] : starts[parts.size()];
                    if (at >= starts[i] && at < end) {
                        part = i;
                        break;
                    }
                }
                int base = part < parts.size() ? starts[part] : starts[parts.size()];
                Mapped old = part < parts.size() ? parts.get(part) : main;
                Mapped replaced =
                        Mapped.concat(
                                old.slice(0, at - base),
                                run.decode(run.finalQuery),
                                old.slice(s.stop.getStopIndex() + 1 - base, old.length()));
                if (part < parts.size()) {
                    parts.set(part, replaced);
                } else {
                    main = replaced;
                    if (!hasSelectAbove(s)) {
                        r.measures().forEach(provenance::putIfAbsent);
                        r.derived().forEach(derived::putIfAbsent);
                        r.shown().forEach(shown::putIfAbsent);
                    }
                }
                parts.addAll(part, run.blocks.stream().map(run::decode).toList());
            }
        }

        /** The text of the query, and where each part starts in it. */
        private Mapped assemble(int[] starts) {
            if (parts.isEmpty()) {
                starts[0] = 0;
                return main;
            }
            int last = Math.max(0, original.length() - 1);
            List<Mapped> out = new ArrayList<>();
            out.add(Mapped.generated("WITH\n", 0, last));
            int length = out.get(0).length();
            for (int i = 0; i < parts.size(); i++) {
                starts[i] = length;
                out.add(parts.get(i));
                Mapped sep = Mapped.generated(i + 1 < parts.size() ? ",\n" : "\n", 0, last);
                out.add(sep);
                length += parts.get(i).length() + sep.length();
            }
            starts[parts.size()] = length;
            out.add(main);
            return Mapped.concat(out);
        }

        /** The selects that read a measure directly (not through a select inside them). */
        private List<KQLParser.SelectContext> marked(KQLParser.QueryContext q) {
            List<KQLParser.FunctionContext> fns = new ArrayList<>();
            collect(q, KQLParser.FunctionContext.class, fns);
            fns.removeIf(f -> !"agg".equals(f.func.getText()));
            java.util.Set<KQLParser.SelectContext> out = new java.util.LinkedHashSet<>();
            for (var f : fns) {
                for (ParseTree t = f.getParent(); t != null; t = t.getParent()) {
                    if (t instanceof KQLParser.SelectContext sel) {
                        out.add(sel);
                        break;
                    }
                }
            }
            return new ArrayList<>(out);
        }

        private KQLParser.SelectContext innermost(List<KQLParser.SelectContext> marked) {
            for (var s : marked) {
                boolean hasInner = false;
                for (var other : marked) {
                    if (other != s
                            && other.start.getStartIndex() >= s.start.getStartIndex()
                            && other.stop.getStopIndex() <= s.stop.getStopIndex()) {
                        hasInner = true;
                    }
                }
                if (!hasInner) {
                    return s;
                }
            }
            return marked.get(0);
        }

        private boolean hasSelectAbove(KQLParser.SelectContext s) {
            for (ParseTree t = s.getParent(); t != null; t = t.getParent()) {
                if (t instanceof KQLParser.SelectContext) {
                    return true;
                }
            }
            return false;
        }
    }

    // ---------------------------------------------------------------------------------------

    private static void conjuncts(
            KQLParser.Logical_expressionContext c, List<KQLParser.Logical_expressionContext> out) {
        if (c.AND() != null && c.left != null && c.right != null) {
            conjuncts(c.left, out);
            conjuncts(c.right, out);
        } else {
            out.add(c);
        }
    }

    private static <T extends ParseTree> void collect(ParseTree t, Class<T> type, List<T> out) {
        if (type.isInstance(t)) {
            out.add(type.cast(t));
        }
        for (int i = 0; i < t.getChildCount(); i++) {
            collect(t.getChild(i), type, out);
        }
    }

    private static String text(ParserRuleContext ctx) {
        return ctx.start
                .getInputStream()
                .getText(Interval.of(ctx.start.getStartIndex(), ctx.stop.getStopIndex()));
    }

    private static String firstLine(String s) {
        if (s == null) {
            return "";
        }
        int i = s.indexOf('\n');
        return i < 0 ? s : s.substring(0, i);
    }
}
