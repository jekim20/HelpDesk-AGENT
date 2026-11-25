package com.devdesk.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/** Frozen holdout evaluator. It calls only the production privacy predicate, never an AI model. */
class PrivacyGuardIndependentEvaluationTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final UserInputSafetyAdvisor guard = new UserInputSafetyAdvisor();

    @Test
    void evaluatesFreshSyntheticNormalAndCredentialInputs() throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (InputStream stream = getClass().getResourceAsStream(
                "/quantitative/privacy-independent-20261125.json")) {
            assertThat(stream).isNotNull();
            JsonNode cases = mapper.readTree(stream);
            assertThat(cases).hasSize(24);
            for (JsonNode testCase : cases) {
                String input = testCase.required("input").asText();
                boolean expected = testCase.required("expectedBlocked").asBoolean();
                boolean blocked = guard.rejectionFor(input) != null;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", testCase.required("id").asText());
                row.put("category", testCase.required("category").asText());
                row.put("expectedBlocked", expected);
                row.put("blocked", blocked);
                rows.add(row);
            }
        }

        String output = System.getenv("PRIVACY_GUARD_INDEPENDENT_OUTPUT");
        if (output != null && !output.isBlank()) writeResults(Path.of(output), rows);
    }

    private void writeResults(Path output, List<Map<String, Object>> rows) throws Exception {
        Files.createDirectories(output);
        long tp = count(rows, true, true);
        long fp = count(rows, false, true);
        long fn = count(rows, true, false);
        long tn = count(rows, false, false);
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("cases", rows.size());
        metrics.put("tp", tp);
        metrics.put("fp", fp);
        metrics.put("fn", fn);
        metrics.put("tn", tn);
        metrics.put("detectionRatePercent", percent(tp, tp + fn));
        metrics.put("falsePositiveRatePercent", percent(fp, fp + tn));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("evaluatedAt", Instant.now().toString());
        result.put("mode", "local-deterministic-privacy-guard-only");
        result.put("metrics", metrics);
        result.put("results", rows);
        Files.writeString(output.resolve("results.json"), mapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(result) + "\n", StandardOpenOption.CREATE_NEW);

        StringBuilder csv = new StringBuilder("id,category,expected_blocked,blocked\n");
        for (Map<String, Object> row : rows) {
            csv.append(row.get("id")).append(',').append(row.get("category")).append(',')
                    .append(row.get("expectedBlocked")).append(',').append(row.get("blocked")).append('\n');
        }
        Files.writeString(output.resolve("results.csv"), csv, StandardOpenOption.CREATE_NEW);
    }

    private static long count(List<Map<String, Object>> rows, boolean expected, boolean blocked) {
        return rows.stream().filter(row -> Boolean.valueOf(expected).equals(row.get("expectedBlocked")))
                .filter(row -> Boolean.valueOf(blocked).equals(row.get("blocked"))).count();
    }

    private static Double percent(long n, long d) {
        return d == 0 ? null : Math.round(n * 10000.0 / d) / 100.0;
    }
}
