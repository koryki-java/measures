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

import ai.koryki.measure.catalog.Measure;
import ai.koryki.measure.catalog.MeasureCatalog;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Lookups in and additions to a {@link MeasureCatalog}, kept off the data class as core keeps its
 * own.
 */
public final class Measures {

    private Measures() {}

    /** The measures of the table, in the order they were written; none for a table without. */
    public static List<Measure> of(MeasureCatalog catalog, String table) {
        List<Measure> found = catalog.getTables().get(table);
        return found == null ? List.of() : List.copyOf(found);
    }

    public static Optional<Measure> find(MeasureCatalog catalog, String table, String name) {
        return of(catalog, table).stream().filter(m -> name.equals(m.getName())).findFirst();
    }

    /** Adds the measure to the table, which is created if it is new; returns the catalog. */
    public static MeasureCatalog add(MeasureCatalog catalog, String table, Measure measure) {
        catalog.getTables().computeIfAbsent(table, k -> new ArrayList<>()).add(measure);
        return catalog;
    }

    /** True if no table has a measure. */
    public static boolean isEmpty(MeasureCatalog catalog) {
        return catalog.getTables().values().stream().allMatch(List::isEmpty);
    }
}
