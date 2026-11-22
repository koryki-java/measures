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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.koryki.catalog.CatalogLoader;
import ai.koryki.catalog.schema.Schema;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Measures are read beside a schema, checked against it, copied and written back. */
class MeasureCatalogTest {

    private static final Schema SCHEMA =
            CatalogLoader.readSchemaJson(
                    "{ \"name\": \"d\", \"relations\": [], \"concepts\": { \"money\": {"
                            + " \"typeFamily\": \"DECIMAL\", \"unit\": \"EUR\" } }, \"tables\": ["
                            + " { \"name\": \"orders\", \"columns\": [ { \"name\": \"id\","
                            + " \"pkPos\": 1, \"concept\": { \"typeFamily\": \"INTEGER\" } },"
                            + " { \"name\": \"amount\", \"conceptRef\": \"money\" } ] },"
                            + " { \"name\": \"notes\", \"columns\": [ { \"name\": \"text\","
                            + " \"concept\": { \"typeFamily\": \"TEXT\" } } ] } ] }");

    private static String measures(String list) {
        return "{ \"tables\": { \"orders\": [ " + list + " ] } }";
    }

    private static MeasureCatalog read(String list) {
        return MeasureCatalogLoader.read(measures(list), SCHEMA);
    }

    private static String refused(String list) {
        return assertThrows(IllegalArgumentException.class, () -> read(list)).getMessage();
    }

    @Test
    void aMeasureLoadsWithEverythingItSays() {
        MeasureCatalog catalog =
                read(
                        "{ \"name\": \"revenue\", \"label\": \"Revenue\", \"comment\": \"c\","
                                + " \"description\": \"d\", \"expression\": \"sum(amount)\","
                                + " \"conceptRef\": \"money\" }");

        Measure m = catalog.find("orders", "revenue").orElseThrow();
        assertEquals("sum(amount)", m.getExpression());
        assertEquals("Revenue", m.getLabel());
        assertEquals("c", m.getComment());
        assertEquals("d", m.getDescription());
        assertEquals("money", m.getConceptRef());
    }

    @Test
    void aTableWithoutMeasuresHasNone() {
        MeasureCatalog catalog = read("{ \"name\": \"n\", \"expression\": \"count(*)\" }");

        assertTrue(catalog.measuresOf("notes").isEmpty());
        assertTrue(new MeasureCatalog().isEmpty());
        assertTrue(catalog.find("orders", "nothing").isEmpty());
    }

    @Test
    void aCatalogReadsFromAStream() {
        byte[] json =
                measures("{ \"name\": \"n\", \"expression\": \"count(*)\" }")
                        .getBytes(StandardCharsets.UTF_8);

        MeasureCatalog catalog = MeasureCatalogLoader.read(new ByteArrayInputStream(json), SCHEMA);

        assertEquals("count(*)", catalog.find("orders", "n").orElseThrow().getExpression());
    }

    @Test
    void aMeasureSurvivesWritingAndReading(@TempDir Path dir) throws IOException {
        MeasureCatalog catalog =
                read(
                        "{ \"name\": \"orders_count\", \"expression\": \"count(*)\","
                                + " \"concept\": { \"typeFamily\": \"INTEGER\" } }");
        File file = dir.resolve("measures.json").toFile();

        MeasureCatalogLoader.write(catalog, file);
        MeasureCatalog again = MeasureCatalogLoader.read(Files.readString(file.toPath()), SCHEMA);

        Measure m = again.find("orders", "orders_count").orElseThrow();
        assertEquals("count(*)", m.getExpression());
        assertEquals("INTEGER", m.getConcept().getTypeFamily());
    }

    @Test
    void whatIsNotSaidIsNotWritten() {
        String json =
                MeasureCatalogLoader.toJson(
                        new MeasureCatalog().add("orders", new Measure("n", "count(*)")));

        assertTrue(json.contains("\"expression\""));
        assertTrue(!json.contains("label") && !json.contains("concept"));
    }

    @Test
    void aCopyHasTheMeasuresAsNewObjects() {
        MeasureCatalog catalog =
                read(
                        "{ \"name\": \"orders_count\", \"expression\": \"count(*)\","
                                + " \"concept\": { \"typeFamily\": \"INTEGER\" } }");

        MeasureCatalog copy = MeasureCopy.deepCopy(catalog);

        Measure original = catalog.measuresOf("orders").get(0);
        Measure copied = copy.measuresOf("orders").get(0);
        assertNotSame(original, copied);
        assertNotSame(original.getConcept(), copied.getConcept());
        assertEquals("count(*)", copied.getExpression());
        assertEquals("INTEGER", copied.getConcept().getTypeFamily());
    }

    @Test
    void aMeasureWithoutAnExpressionIsRefused() {
        assertTrue(
                refused("{ \"name\": \"m\" }")
                        .contains("measure 'orders.m': it has no expression"));
    }

    @Test
    void aMeasureWithoutANameIsRefused() {
        assertTrue(refused("{ \"expression\": \"count(*)\" }").contains("a measure has no name"));
    }

    @Test
    void aMeasureNamedLikeAColumnIsRefused() {
        assertTrue(
                refused("{ \"name\": \"amount\", \"expression\": \"sum(amount)\" }")
                        .contains("the name is the name of a column of the same table"));
    }

    @Test
    void theCaseOfAColumnNameDoesNotMatter() {
        assertTrue(
                refused("{ \"name\": \"AMOUNT\", \"expression\": \"sum(amount)\" }")
                        .contains("the name is the name of a column of the same table"));
    }

    @Test
    void aNameUsedTwiceIsRefusedWhateverTheCase() {
        assertTrue(
                refused(
                                "{ \"name\": \"m\", \"expression\": \"count(*)\" }, { \"name\":"
                                        + " \"M\", \"expression\": \"sum(amount)\" }")
                        .contains("the name is used twice"));
    }

    @Test
    void aMeasureWithTwoConceptsIsRefused() {
        assertTrue(
                refused(
                                "{ \"name\": \"m\", \"expression\": \"count(*)\", \"conceptRef\":"
                                        + " \"money\", \"concept\": { \"typeFamily\": \"INTEGER\""
                                        + " } }")
                        .contains("a measure has one or the other"));
    }

    @Test
    void aDanglingConceptRefIsRefused() {
        assertTrue(
                refused(
                                "{ \"name\": \"m\", \"expression\": \"count(*)\", \"conceptRef\":"
                                        + " \"nowhere\" }")
                        .contains("which the schema does not have"));
    }

    @Test
    void anInlineConceptIsCheckedLikeThatOfAColumn() {
        // a STRUCT with an entry that refers to nothing: refused where the measures are read
        assertTrue(
                refused(
                                "{ \"name\": \"m\", \"expression\": \"count(*)\", \"concept\": {"
                                        + " \"typeFamily\": \"STRUCT\", \"entries\": { \"x\":"
                                        + " \"nowhere\" } } }")
                        .contains("neither a concept nor a type spelling"));
    }

    @Test
    void aTableTheSchemaDoesNotHaveIsRefused() {
        String message =
                assertThrows(
                                IllegalArgumentException.class,
                                () ->
                                        MeasureCatalogLoader.read(
                                                "{ \"tables\": { \"orderz\": [ { \"name\": \"m\","
                                                        + " \"expression\": \"count(*)\" } ] } }",
                                                SCHEMA))
                        .getMessage();

        assertTrue(message.contains("the schema has no such table"));
    }

    @Test
    void aTableWithoutAPrimaryKeyIsNotRefusedHere() {
        // the identity of the rows is for whoever evaluates the measure to ask for
        MeasureCatalog catalog =
                MeasureCatalogLoader.read(
                        "{ \"tables\": { \"notes\": [ { \"name\": \"n\", \"expression\":"
                                + " \"count(*)\" } ] } }",
                        SCHEMA);

        assertEquals(1, catalog.measuresOf("notes").size());
    }
}
