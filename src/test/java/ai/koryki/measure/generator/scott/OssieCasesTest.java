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

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The rows the rewritten KQL of each translated query gave (the {@code csv} golden written by
 * {@link AggRewriterFixturesTest}) against the expected result of the same query in a checkout of
 * apache/ossie ({@code -Dossie.root}, the checkout itself or its {@code compliance/query}
 * directory). Rows are compared as a multiset: the order of a result is in the query, not in the
 * CSV.
 *
 * <p>A case of Ossie's suite is a script, {@code compliance/query/<case>.ost}, with one or more
 * {@code executeExpand(...)} commands. After each comes the SQL the view expander wrote, up to the
 * closing mark of the expansion, and then the expected result as a table:
 *
 * <pre>
 * +--------+---------+
 * | deptno | sum_sal |
 * +--------+---------+
 * |     10 | 7450.00 |
 * +--------+---------+
 * </pre>
 *
 * <p>The corpus names the first query of a case {@code <case>}, the second {@code <case>_a}, the
 * third {@code <case>_b}, as Ossie labels them (B8, B8a, B8b). A query of a script with no
 * translation, and a translation with no query in Ossie's script, are failures, not skips: the
 * suite is checked for completeness as well as for rows.
 *
 * <p>Writes {@code build/ossie-status.md}, one line per query of Ossie's suite.
 */
class OssieCasesTest {

    /** What ends the expansion in a script: the SQL the view expander wrote is above it. */
    private static final String RESULT_MARK = "|_}";

    @Test
    void rowsEqualTheExpectedRowsOfOssie() throws Exception {
        String ossie = System.getProperty("ossie.root");
        assumeTrue(ossie != null, "no -Dossie.root: a checkout of apache/ossie");
        Path cases = cases(Path.of(ossie));
        Path root = Path.of(System.getProperty("test.root"), System.getProperty("test.corpus"));
        Path csvs = root.resolve("expected/kql/measures/csv");

        List<String> status = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        List<Path> scripts;
        try (Stream<Path> s = Files.list(cases)) {
            scripts = s.filter(p -> p.toString().endsWith(".ost")).sorted().toList();
        }
        assumeTrue(!scripts.isEmpty(), "no .ost script in " + cases);

        List<String> inSuite = new ArrayList<>();
        for (Path script : scripts) {
            String name = script.getFileName().toString().replaceAll("\\.ost$", "");
            List<List<List<String>>> results = expectedResults(Files.readString(script));
            for (int i = 0; i < results.size(); i++) {
                String query = i == 0 ? name : name + "_" + (char) ('a' + i - 1);
                inSuite.add(query);
                Path csv = csvs.resolve(query + ".csv");
                if (!Files.isRegularFile(csv)) {
                    status.add("| " + query + " | not translated |");
                    failures.add(query + ": a query of Ossie's suite with no translation");
                    continue;
                }
                List<String> expected = multiset(results.get(i));
                List<String> actual = multiset(rows(Files.readAllLines(csv)));
                if (expected.equals(actual)) {
                    status.add("| " + query + " | **equal** (" + actual.size() + " rows) |");
                } else {
                    status.add("| " + query + " | **DIFFERENT** |");
                    failures.add(query + "\n expected " + expected + "\n actual   " + actual);
                }
            }
        }
        // The other way: a translation named after a case of the suite, for a query its script
        // does not have. A query with no case at all (range_join_reverse) is the corpus's own and
        // not checked here.
        List<String> caseNames =
                scripts.stream()
                        .map(p -> p.getFileName().toString().replaceAll("\\.ost$", ""))
                        .toList();
        try (Stream<Path> s = Files.list(csvs)) {
            s.map(p -> p.getFileName().toString())
                    .filter(f -> f.endsWith(".csv"))
                    .map(f -> f.replaceAll("\\.csv$", ""))
                    .filter(q -> caseNames.stream().anyMatch(c -> q.startsWith(c + "_")))
                    .filter(q -> !inSuite.contains(q))
                    .sorted()
                    .forEach(
                            q ->
                                    failures.add(
                                            q + ": a translation of a query Ossie's script lacks"));
        }
        Files.createDirectories(Path.of("build"));
        Files.writeString(
                Path.of("build/ossie-status.md"),
                "| query | koryki |\n|---|---|\n" + String.join("\n", status) + "\n");
        assertEquals(List.of(), failures, "queries that differ from Ossie's expected results");
    }

    /**
     * The directory with the {@code .ost} scripts: {@code compliance/query}, or the path itself.
     */
    private static Path cases(Path ossie) {
        Path query = ossie.resolve("compliance/query");
        return Files.isDirectory(query) ? query : ossie;
    }

    /**
     * The expected result of every {@code executeExpand} of a script, in order: the table that
     * follows each expansion. A command with no table gives an empty result, which no translation
     * of a query can equal by accident.
     */
    static List<List<List<String>>> expectedResults(String script) {
        String[] parts = script.split(Pattern.quote(RESULT_MARK));
        List<List<List<String>>> results = new ArrayList<>();
        for (int i = 1; i < parts.length; i++) {
            results.add(table(parts[i]));
        }
        return results;
    }

    /** The rows of the first ASCII table of {@code text}, the header row left out. */
    private static List<List<String>> table(String text) {
        // the first line is the rest of the mark's own line
        List<String> lines = text.lines().skip(1).toList();
        List<Integer> borders = new ArrayList<>();
        for (int i = 0; i < lines.size() && borders.size() < 3; i++) {
            if (lines.get(i).startsWith("+-")) {
                borders.add(i);
            }
        }
        List<List<String>> rows = new ArrayList<>();
        if (borders.size() < 3) {
            return rows;
        }
        for (int i = borders.get(1) + 1; i < borders.get(2); i++) {
            String line = lines.get(i).trim();
            List<String> cells = new ArrayList<>();
            // the outer bars are the frame; no cell of these tables holds a bar
            for (String cell : line.substring(1, line.length() - 1).split("\\|", -1)) {
                cells.add(normal(cell.trim()));
            }
            rows.add(cells);
        }
        return rows;
    }

    private static final Pattern FIELD = Pattern.compile("\"((?:[^\"]|\"\")*)\"");

    private static List<List<String>> rows(List<String> csvLines) throws IOException {
        List<List<String>> rows = new ArrayList<>();
        for (String line : csvLines.subList(1, csvLines.size())) {
            Matcher m = FIELD.matcher(line);
            List<String> cells = new ArrayList<>();
            while (m.find()) {
                cells.add(normal(m.group(1).replace("\"\"", "\"")));
            }
            rows.add(cells);
        }
        return rows;
    }

    private static List<String> multiset(List<List<String>> rows) {
        List<String> lines =
                rows.stream().map(r -> String.join("|", r)).collect(Collectors.toList());
        Collections.sort(lines);
        return lines;
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
