/*
 * Copyright 2025-2026 Johannes Zemlin
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package ai.koryki.measure;

import ai.koryki.catalog.schema.Column;
import ai.koryki.catalog.schema.Schema;
import ai.koryki.catalog.schema.Table;
import ai.koryki.catalog.types.ConceptResolver;
import ai.koryki.catalog.types.TypeDescriptorParser;
import ai.koryki.measure.catalog.Measure;
import ai.koryki.measure.catalog.MeasureCatalog;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Whether the measures fit the schema they are kept beside. What can be said about a measure
 * without parsing its expression: that it belongs to a table the schema has, has a name that is its
 * own, an expression, and at most one concept, which the schema can resolve.
 *
 * <p>The expression itself is not parsed here. The measures do not know the query language at load;
 * whoever evaluates a measure reads it, and reports what is wrong with it where it is used.
 *
 * <p>Not checked, on purpose: whether the table has a primary key. A measure counts each row of its
 * table once, and the key is the identity of the row, but a model may state it another way (a
 * unique key, for one). Whoever evaluates the measure needs an identity and refuses the one that
 * has none, where it can say so in the words of the query.
 */
public final class MeasureCheck {

    private MeasureCheck() {}

    /**
     * @throws IllegalArgumentException for the first mistake, naming the table and the measure
     */
    public static void check(Schema schema, MeasureCatalog catalog) {
        for (var entry : catalog.getTables().entrySet()) {
            Optional<Table> table =
                    schema.getTables() == null
                            ? Optional.empty()
                            : schema.getTables().stream()
                                    .filter(t -> entry.getKey().equals(t.getName()))
                                    .findFirst();
            if (table.isEmpty()) {
                throw new IllegalArgumentException(
                        "measures of table '" + entry.getKey() + "': the schema has no such table");
            }
            check(schema, table.get(), entry.getValue());
        }
    }

    private static void check(Schema schema, Table table, List<Measure> measures) {
        Set<String> columns = new HashSet<>();
        for (Column column : table.getColumns()) {
            columns.add(lower(column.getName()));
        }
        Set<String> seen = new HashSet<>();
        for (Measure measure : measures) {
            if (measure.getName() == null || measure.getName().isBlank()) {
                throw new IllegalArgumentException(
                        "table '" + table.getName() + "': a measure has no name");
            }
            String where = "measure '" + table.getName() + "." + measure.getName() + "'";
            if (!seen.add(lower(measure.getName()))) {
                throw new IllegalArgumentException(where + ": the name is used twice");
            }
            if (columns.contains(lower(measure.getName()))) {
                throw new IllegalArgumentException(
                        where + ": the name is the name of a column of the same table");
            }
            if (measure.getExpression() == null || measure.getExpression().isBlank()) {
                throw new IllegalArgumentException(where + ": it has no expression");
            }
            concept(schema, table, measure, where);
        }
    }

    private static void concept(Schema schema, Table table, Measure measure, String where) {
        if (measure.getConcept() != null && measure.getConceptRef() != null) {
            throw new IllegalArgumentException(
                    where
                            + ": it has a concept of its own and refers to '"
                            + measure.getConceptRef()
                            + "'; a measure has one or the other");
        }
        if (measure.getConceptRef() != null
                && (schema.getConcepts() == null
                        || !schema.getConcepts().containsKey(measure.getConceptRef()))) {
            throw new IllegalArgumentException(
                    where
                            + ": it refers to the concept '"
                            + measure.getConceptRef()
                            + "', which the schema does not have");
        }
        if (measure.getConcept() != null) {
            // core checks the concepts of columns; a measure's own is put in a column of a schema
            // of
            // its own, with the concepts of this one, and checked the same way
            Column column = new Column(measure.getName());
            column.setConcept(measure.getConcept());
            Table probe = new Table(table.getName());
            probe.addColumn(column);
            Schema alone = new Schema();
            alone.setName(schema.getName());
            alone.setConcepts(schema.getConcepts());
            alone.setTables(List.of(probe));
            try {
                new ConceptResolver(alone).check(new TypeDescriptorParser() {});
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(where + ": " + e.getMessage(), e);
            }
        }
    }

    private static String lower(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
