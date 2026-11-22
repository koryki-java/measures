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

import ai.koryki.antlr.KorykiaiException;
import ai.koryki.catalog.SchemaModule;
import ai.koryki.catalog.schema.Schema;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads and writes a {@link MeasureCatalog}. A catalog is read against the schema it belongs to and
 * is refused where it does not fit it ({@link MeasureCheck}), here and not where a measure is first
 * used.
 *
 * <p>The mapper is core's way of reading a schema (the entries of a concept are a string or an
 * object) and leaves out the properties that are null, so that a file says only what it has to.
 */
public final class MeasureCatalogLoader {

    private MeasureCatalogLoader() {}

    private static final ObjectMapper JSON =
            JsonMapper.builder()
                    .addModule(new SchemaModule())
                    .changeDefaultPropertyInclusion(
                            inclusion -> inclusion.withValueInclusion(JsonInclude.Include.NON_NULL))
                    .build();

    public static MeasureCatalog read(String json, Schema schema) {
        MeasureCatalog catalog = JSON.readValue(json, MeasureCatalog.class);
        MeasureCheck.check(schema, catalog);
        return catalog;
    }

    public static MeasureCatalog read(InputStream in, Schema schema) {
        try (in) {
            return read(new String(in.readAllBytes(), StandardCharsets.UTF_8), schema);
        } catch (IOException e) {
            throw new KorykiaiException(e);
        }
    }

    /** Pretty-printed JSON, without the properties that are null. */
    public static String toJson(MeasureCatalog catalog) {
        return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(catalog);
    }

    public static void write(MeasureCatalog catalog, File out) {
        try {
            Files.writeString(out.toPath(), toJson(catalog), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new KorykiaiException(e);
        }
    }
}
