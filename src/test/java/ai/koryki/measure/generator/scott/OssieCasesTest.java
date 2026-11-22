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
package ai.koryki.measure.generator.scott;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The rows the rewritten KQL of each translated case gave (the {@code csv} golden written by {@link
 * AggRewriterFixturesTest}) against the {@code expected.sql} of the same case in a checkout of
 * apache/ossie ({@code -Dossie.root}), evaluated on DuckDB. Rows are compared as a multiset: the
 * order of the result is in the SQL golden (ORDER BY), not in the CSV.
 *
 * <p>Writes {@code build/ossie-status.md}, one line per case of Ossie's suite.
 */
class OssieCasesTest {

    private static final Pattern FIELD = Pattern.compile("\"((?:[^\"]|\"\")*)\"");

    @Test
    void rowsEqualTheExpectedRowsOfOssie() throws Exception {
        String ossie = System.getProperty("ossie.root");
        assumeTrue(ossie != null, "no -Dossie.root: a checkout of apache/ossie");
        Path cases = Path.of(ossie, "compliance/query/cases");
        Path root = Path.of(System.getProperty("test.root"), System.getProperty("test.corpus"));
        Path csvs = root.resolve("expected/kql/measures/csv");

        List<String> status = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        List<Path> dirs;
        try (Stream<Path> s = Files.list(cases)) {
            dirs = s.filter(Files::isDirectory).sorted().toList();
        }
        try (Connection duck = DriverManager.getConnection("jdbc:duckdb:")) {
            for (Path dir : dirs) {
                String name = dir.getFileName().toString();
                Path csv = csvs.resolve(name + ".csv");
                if (!Files.isRegularFile(csv)) {
                    status.add("| " + name + " | not translated |");
                    continue;
                }
                List<String> expected =
                        expected(duck, Files.readString(dir.resolve("expected.sql")));
                List<String> actual = rows(Files.readAllLines(csv));
                if (expected.equals(actual)) {
                    status.add("| " + name + " | **equal** (" + actual.size() + " rows) |");
                } else {
                    status.add("| " + name + " | **DIFFERENT** |");
                    failures.add(name + "\n expected " + expected + "\n actual   " + actual);
                }
            }
        }
        Files.createDirectories(Path.of("build"));
        Files.writeString(
                Path.of("build/ossie-status.md"),
                "| case | koryki |\n|---|---|\n" + String.join("\n", status) + "\n");
        assertEquals(List.of(), failures, "cases that differ from Ossie's expected.sql");
    }

    private static List<String> expected(Connection duck, String sql) throws Exception {
        try (Statement s = duck.createStatement();
                ResultSet rs = s.executeQuery(sql)) {
            int n = rs.getMetaData().getColumnCount();
            List<String> rows = new ArrayList<>();
            while (rs.next()) {
                List<String> cells = new ArrayList<>();
                for (int i = 1; i <= n; i++) {
                    cells.add(normal(rs.getString(i)));
                }
                rows.add(String.join("|", cells));
            }
            Collections.sort(rows);
            return rows;
        }
    }

    private static List<String> rows(List<String> csvLines) {
        List<String> rows = new ArrayList<>();
        for (String line : csvLines.subList(1, csvLines.size())) {
            Matcher m = FIELD.matcher(line);
            List<String> cells = new ArrayList<>();
            while (m.find()) {
                cells.add(normal(m.group(1).replace("\"\"", "\"")));
            }
            rows.add(String.join("|", cells));
        }
        return rows.stream().sorted().collect(Collectors.toList());
    }

    /**
     * NULL and the empty cell are the same; numbers are compared to two decimals, the precision of
     * the CSV golden.
     */
    private static String normal(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        try {
            return new BigDecimal(value)
                    .setScale(2, RoundingMode.HALF_UP)
                    .stripTrailingZeros()
                    .toPlainString();
        } catch (NumberFormatException e) {
            return value;
        }
    }
}
