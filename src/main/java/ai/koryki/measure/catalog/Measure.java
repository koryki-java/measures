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
package ai.koryki.measure.catalog;

import ai.koryki.catalog.schema.Concept;

/**
 * A measure of a table: an aggregate calculation that belongs to the table, such as the total
 * revenue of {@code orders}, kept under a name so that it is defined once and means the same in
 * every query.
 *
 * <p><b>What it is not.</b> It is not a column: it has no value per row, and the database does not
 * know it. It is an <em>unevaluated</em> aggregate. A query that reads it asks for its value in the
 * context of that query, and the rows it aggregates are the rows of its table, each counted once,
 * however the query joins or filters. How it is evaluated is not said here; this class says only
 * what the measure is.
 *
 * <p><b>Names.</b> A measure and a column of one table share one namespace: the name of a measure
 * is never the name of a column of the same table, whatever the case, and names are unique among
 * the measures of a table. {@link ai.koryki.measure.MeasureCheck} sees to it.
 *
 * <p><b>The expression</b> is one aggregate expression over the columns of its table, written in
 * KQL: {@code sum(unit_price * quantity)}, {@code count(*)}. It is text from a file and is not
 * trusted: whoever puts it into a query parses it as an expression of its own first.
 *
 * <p><b>A derived measure</b> is an expression over other measures, which may belong to other
 * tables: {@code to_double(agg(emp.sum_sal)) / agg(dept.dept_count)}. Each measure it reads is
 * written {@code agg(entity.measure)}, by the name of the entity, not of an alias; the query that
 * reads the derived measure must have that entity once, and the measure is read through its alias.
 * A derived measure reads no column and no aggregate of its own, and does not read another derived
 * measure (so there are no cycles). It counts the rows of each table it reads once, as those
 * measures do. A measure that needs the rows of another table in any other way (one aggregate over
 * a join) is not expressible here yet.
 *
 * <p>Plain data. A copy is {@link ai.koryki.measure.MeasureCopy#deepCopy(Measure)}.
 */
public class Measure {

    private String name;
    private String label;
    private String comment;
    private String description;

    /**
     * The aggregate expression, in KQL, over the columns of the table this measure belongs to. Not
     * blank.
     */
    private String expression;

    /**
     * The name of a concept in the schema's concepts: what the value of the measure is, where the
     * concept says it (type, unit, presentation). A measure has this or a {@link #concept}, or
     * neither when its type is left to be derived from the expression, but not both.
     */
    private String conceptRef;

    /**
     * The concept of this measure alone, not shared with any other. A measure has this or a {@link
     * #conceptRef}, not both.
     */
    private Concept concept;

    /**
     * The tables whose columns the expression reads besides its own, each reached by a link to one
     * row; null or empty if it reads only its own table.
     */
    private java.util.List<MeasureJoin> joins;

    public Measure() {}

    public Measure(String name, String expression) {
        this.name = name;
        this.expression = expression;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getExpression() {
        return expression;
    }

    public void setExpression(String expression) {
        this.expression = expression;
    }

    public String getConceptRef() {
        return conceptRef;
    }

    public void setConceptRef(String conceptRef) {
        this.conceptRef = conceptRef;
    }

    public Concept getConcept() {
        return concept;
    }

    public void setConcept(Concept concept) {
        this.concept = concept;
    }

    public java.util.List<MeasureJoin> getJoins() {
        return joins;
    }

    public void setJoins(java.util.List<MeasureJoin> joins) {
        this.joins = joins;
    }
}
