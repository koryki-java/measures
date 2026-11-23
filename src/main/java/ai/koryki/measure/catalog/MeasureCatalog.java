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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The measures of a catalog, by the name of the table they belong to. Kept beside the catalog
 * ({@code db.json}), not in it, so that they are not tied to the classes of core ({@code Table} is
 * not extended), survive a catalog that is written again, and change without a release of core.
 *
 * <p>In JSON:
 *
 * <pre>
 * { "tables": { "orders": [ { "name": "revenue", "expression": "sum(amount)" } ] } }
 * </pre>
 *
 * <p>Plain data, like the classes of {@code ai.koryki.catalog.schema}: no logic. Reading, writing,
 * copying, checking against a schema and looking measures up are in {@code ai.koryki.measure}.
 */
public class MeasureCatalog {

    private Map<String, List<Measure>> tables = new LinkedHashMap<>();

    public Map<String, List<Measure>> getTables() {
        return tables;
    }

    public void setTables(Map<String, List<Measure>> tables) {
        this.tables = tables;
    }
}
