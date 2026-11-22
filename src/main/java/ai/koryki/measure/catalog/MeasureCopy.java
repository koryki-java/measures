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

import ai.koryki.catalog.SchemaCopy;

/** Copies of the measure classes, kept off the classes as core keeps its own. */
public final class MeasureCopy {

    private MeasureCopy() {}

    public static MeasureCatalog deepCopy(MeasureCatalog catalog) {
        MeasureCatalog copy = new MeasureCatalog();
        catalog.getTables()
                .forEach((table, measures) -> measures.forEach(m -> copy.add(table, deepCopy(m))));
        return copy;
    }

    public static Measure deepCopy(Measure measure) {
        Measure copy = new Measure();

        copy.setName(measure.getName());
        copy.setLabel(measure.getLabel());
        copy.setComment(measure.getComment());
        copy.setDescription(measure.getDescription());
        copy.setExpression(measure.getExpression());
        copy.setConceptRef(measure.getConceptRef());
        if (measure.getConcept() != null) {
            copy.setConcept(SchemaCopy.deepCopy(measure.getConcept()));
        }

        return copy;
    }
}
