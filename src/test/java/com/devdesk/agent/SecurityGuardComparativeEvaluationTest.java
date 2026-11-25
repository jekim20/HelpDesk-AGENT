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

/** Guard-only OFF/ON comparison. No ChatModel or external service is invoked. */
class SecurityGuardComparativeEvaluationTest {

    private static final List<String> PRIVACY_CORPORA = List.of(
            "privacy-cases", "privacy-holdout", "privacy-postfreeze",
            "privacy-independent-final", "privacy-independent-v2");
    private final ObjectMapper mapper = new ObjectMapper();
    private final PromptInjectionGuard injectionGuard = new PromptInjectionGuard();
    private final UserInputSafetyAdvisor privacyGuard = new UserInputSafetyAdvisor();

    @Test
    void recordsGuardOffOnResultsWithoutCallingAModel() throws Exception {
        List<Map<String, Object>> attackRows = evaluatePromptInjectionCases();
        Map<String, Object> attackMetrics = metrics(attackRows);
        List<Map<String, Object>> privacyRows = evaluatePrivacyCorpora();
        Map<String, Object> privacyMetrics = metrics(privacyRows);

        assertThat(attackRows).hasSize(18);
        assertThat(privacyRows).hasSize(82);

        String output = System.getenv("SECURITY_GUARD_EVAL_OUTPUT");
        if (output != null && !output.isBlank()) {
            writeNew(Path.of(output), attackRows, attackMetrics, privacyRows, privacyMetrics);
        }
    }

    private List<Map<String, Object>> evaluatePromptInjectionCases() throws Exception {
        try (InputStream stream = getClass().getResourceAsStream("/security/prompt_injection_cases.json")) {
            assertThat(stream).isNotNull();
            JsonNode cases = mapper.readTree(stream);
            List<Map<String, Object>> rows = new ArrayList<>();
            for (JsonNode item : cases) {
                String id = item.required("id").asText();
                String prompt = item.required("prompt").asText();
                boolean expectedBlocked = "BLOCK".equals(item.required("expected").asText());
                rows.add(evaluate("prompt-injection-18", id, prompt, expectedBlocked));
            }
            return rows;
        }
    }

    private List<Map<String, Object>> evaluatePrivacyCorpora() throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String corpus : PRIVACY_CORPORA) {
            try (InputStream stream = getClass().getResourceAsStream("/quantitative/" + corpus + ".json")) {
                assertThat(stream).as(corpus).isNotNull();
                for (JsonNode item : mapper.readTree(stream)) {
                    rows.add(evaluate(corpus, item.required("id").asText(),
                            item.required("input").asText(), item.required("expectedBlocked").asBoolean()));
                }
            }
        }
        return rows;
    }

    private Map<String, Object> evaluate(String dataset, String id, String input, boolean expectedBlocked) {
        boolean injectionBlocked = injectionGuard.isSuspicious(input);
        boolean privacyBlocked = privacyGuard.rejectionFor(input) != null;
        String blockingGuard = injectionBlocked ? "PROMPT_INJECTION"
                : privacyBlocked ? "PRIVACY" : "NONE";
        boolean guardOnBlocked = !"NONE".equals(blockingGuard);
        boolean attack = expectedBlocked;

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("dataset", dataset);
        row.put("id", id);
        row.put("expectedBlocked", expectedBlocked);
        row.put("guardOffBlocked", false);
        row.put("guardOnPromptInjectionBlocked", injectionBlocked);
        row.put("guardOnPrivacyBlocked", privacyBlocked);
        row.put("guardOnCombinedBlocked", guardOnBlocked);
        row.put("blockingGuard", blockingGuard);
        // This flag describes reaching a deliberately vulnerable test sink, not a model response.
        row.put("mockAttackEffectOff", attack);
        row.put("mockAttackEffectOn", attack && !guardOnBlocked);
        row.put("actualModelAsrMeasured", false);
        return row;
    }

    private static Map<String, Object> metrics(List<Map<String, Object>> rows) {
        Map<String, Object> off = confusion(rows, "guardOffBlocked");
        Map<String, Object> on = confusion(rows, "guardOnCombinedBlocked");
        long totalAttacks = rows.stream().filter(row -> Boolean.TRUE.equals(row.get("expectedBlocked"))).count();
        long preblocked = rows.stream().filter(row -> Boolean.TRUE.equals(row.get("expectedBlocked")))
                .filter(row -> Boolean.TRUE.equals(row.get("guardOnCombinedBlocked"))).count();
        long mockEffectsOff = rows.stream().filter(row -> Boolean.TRUE.equals(row.get("mockAttackEffectOff"))).count();
        long mockEffectsOn = rows.stream().filter(row -> Boolean.TRUE.equals(row.get("mockAttackEffectOn"))).count();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("cases", rows.size());
        result.put("guardOff", off);
        result.put("guardOn", on);
        result.put("attackInputs", totalAttacks);
        result.put("guardOnPreBlockRatePercent", percent(preblocked, totalAttacks));
        result.put("mockAttackEffectsOff", mockEffectsOff);
        result.put("mockAttackEffectsOn", mockEffectsOn);
        result.put("mockAttackEffectReductionPercent", percent(mockEffectsOff - mockEffectsOn, mockEffectsOff));
        result.put("actualModelAsr", null);
        return result;
    }

    private static Map<String, Object> confusion(List<Map<String, Object>> rows, String decisionKey) {
        long tp = rows.stream().filter(row -> Boolean.TRUE.equals(row.get("expectedBlocked")))
                .filter(row -> Boolean.TRUE.equals(row.get(decisionKey))).count();
        long fp = rows.stream().filter(row -> !Boolean.TRUE.equals(row.get("expectedBlocked")))
                .filter(row -> Boolean.TRUE.equals(row.get(decisionKey))).count();
        long fn = rows.stream().filter(row -> Boolean.TRUE.equals(row.get("expectedBlocked")))
                .filter(row -> !Boolean.TRUE.equals(row.get(decisionKey))).count();
        long tn = rows.stream().filter(row -> !Boolean.TRUE.equals(row.get("expectedBlocked")))
                .filter(row -> !Boolean.TRUE.equals(row.get(decisionKey))).count();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tp", tp);
        result.put("fp", fp);
        result.put("fn", fn);
        result.put("tn", tn);
        result.put("detectionRatePercent", percent(tp, tp + fn));
        result.put("falsePositiveRatePercent", percent(fp, fp + tn));
        return result;
    }

    private static Double percent(long numerator, long denominator) {
        return denominator == 0 ? null : Math.round(numerator * 10000.0 / denominator) / 100.0;
    }

    private void writeNew(Path output, List<Map<String, Object>> attacks, Map<String, Object> attackMetrics,
            List<Map<String, Object>> privacy, Map<String, Object> privacyMetrics) throws Exception {
        Files.createDirectories(output);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("evaluatedAt", Instant.now().toString());
        report.put("environment", Map.of("javaVersion", System.getProperty("java.version"),
                "osArch", System.getProperty("os.arch"), "mode", "local-deterministic-guard-only"));
        report.put("conditions", Map.of("guardOff", "bypass both guards in isolated evaluation only",
                "guardOn", "PromptInjectionGuard then UserInputSafetyAdvisor, matching production order",
                "downstream", "no model call; deterministic vulnerable sink marks labeled attack as a mock effect",
                "actualModelAsr", "not measured; no LLM response is observed"));
        report.put("promptInjection", Map.of("metrics", attackMetrics, "results", attacks));
        report.put("privacyCorpora", Map.of("metrics", privacyMetrics, "results", privacy));
        Files.writeString(output.resolve("results.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report)
                + "\n", StandardOpenOption.CREATE_NEW);
        writeCsv(output.resolve("cases.csv"), attacks, privacy);
    }

    private static void writeCsv(Path output, List<Map<String, Object>> attacks,
            List<Map<String, Object>> privacy) throws Exception {
        StringBuilder csv = new StringBuilder("dataset,id,expected_blocked,guard_off_blocked,guard_on_prompt_blocked,guard_on_privacy_blocked,guard_on_combined_blocked,blocking_guard,mock_attack_effect_off,mock_attack_effect_on,actual_model_asr_measured\n");
        for (Map<String, Object> row : concat(attacks, privacy)) {
            csv.append(row.get("dataset")).append(',').append(row.get("id")).append(',')
                    .append(row.get("expectedBlocked")).append(',').append(row.get("guardOffBlocked")).append(',')
                    .append(row.get("guardOnPromptInjectionBlocked")).append(',')
                    .append(row.get("guardOnPrivacyBlocked")).append(',')
                    .append(row.get("guardOnCombinedBlocked")).append(',').append(row.get("blockingGuard")).append(',')
                    .append(row.get("mockAttackEffectOff")).append(',').append(row.get("mockAttackEffectOn")).append(',')
                    .append(row.get("actualModelAsrMeasured")).append('\n');
        }
        Files.writeString(output, csv, StandardOpenOption.CREATE_NEW);
    }

    private static List<Map<String, Object>> concat(List<Map<String, Object>> first, List<Map<String, Object>> second) {
        List<Map<String, Object>> rows = new ArrayList<>(first.size() + second.size());
        rows.addAll(first);
        rows.addAll(second);
        return rows;
    }
}
