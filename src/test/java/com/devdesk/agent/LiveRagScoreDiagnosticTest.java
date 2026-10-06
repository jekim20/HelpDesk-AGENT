package com.devdesk.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.DoublePredicate;
import java.util.stream.Stream;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;

@SpringBootTest
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
@EnabledIfEnvironmentVariable(named = "LIVE_RAG_SCORE_DIAGNOSTIC", matches = "true")
class LiveRagScoreDiagnosticTest {

    private static final int TOP_K = 5;
    private static final double NON_FILTERING_THRESHOLD = 0.0;
    private static final List<Double> REFERENCE_THRESHOLDS = List.of(0.35, 0.30, 0.25);

    private static final String DEV_SET = "rag/golden-retrieval.json";
    private static final String UNSEEN_SET = "rag/unseen-retrieval.json";
    private static final String UNSEEN_NEGATIVE_SET = "rag/unseen-negative-queries.json";

    private static final List<String> DEV_POSITIVE_IDS = List.of(
            "RAG-002", "RAG-003", "RAG-014");
    private static final List<String> UNSEEN_POSITIVE_IDS = List.of(
            "UNSEEN-002", "UNSEEN-003", "UNSEEN-007", "UNSEEN-011", "UNSEEN-012");
    private static final List<String> NEGATIVE_IDS = List.of(
            "UNSEEN-NEG-002", "UNSEEN-NEG-005");

    private static final List<String> CORPUS = List.of(
            "docs/vpn-guide.md",
            "docs/database-access-policy.md",
            "docs/access-request-guide.md",
            "docs/incident-response-guide.md");

    @Autowired
    EmbeddingModel embeddingModel;

    @Test
    void 문제_query의_raw_retrieval_score를_진단한다() throws IOException {
        List<PositiveCase> positiveCases = loadPositiveCases();
        List<NegativeCase> negativeCases = loadNegativeCases();
        validateDiagnosticCases(positiveCases, negativeCases);

        VectorStore vectorStore = SimpleVectorStore.builder(embeddingModel).build();
        IngestService ingestService = new IngestService(vectorStore);
        for (String documentPath : CORPUS) {
            IngestService.IngestResult result = ingestService.ingest(
                    new ClassPathResource(documentPath), "policy", "IT");
            assertThat(result.chunks()).isPositive();
        }

        List<PositiveObservation> positiveObservations = positiveCases.stream()
                .map(positiveCase -> observePositive(
                        positiveCase,
                        retrieveAllSources(vectorStore, positiveCase.query())))
                .toList();
        List<NegativeObservation> negativeObservations = negativeCases.stream()
                .map(negativeCase -> new NegativeObservation(
                        negativeCase,
                        retrieveAllSources(vectorStore, negativeCase.query())))
                .toList();

        System.out.println();
        System.out.println("LIVE OpenAI RAG score diagnostic");
        System.out.println("================================");
        positiveObservations.forEach(observation -> System.out.println(observation.report()));
        negativeObservations.forEach(observation -> System.out.println(observation.report()));
        System.out.println(summary(positiveObservations, negativeObservations));
    }

    private List<PositiveCase> loadPositiveCases() throws IOException {
        List<PositiveCase> devCases = load(DEV_SET, new TypeReference<>() {});
        List<PositiveCase> unseenCases = load(UNSEEN_SET, new TypeReference<>() {});
        Set<String> selectedIds = new HashSet<>();
        selectedIds.addAll(DEV_POSITIVE_IDS);
        selectedIds.addAll(UNSEEN_POSITIVE_IDS);
        return Stream.concat(devCases.stream(), unseenCases.stream())
                .filter(positiveCase -> selectedIds.contains(positiveCase.id()))
                .toList();
    }

    private List<NegativeCase> loadNegativeCases() throws IOException {
        List<NegativeCase> negativeCases = load(UNSEEN_NEGATIVE_SET, new TypeReference<>() {});
        return negativeCases.stream()
                .filter(negativeCase -> NEGATIVE_IDS.contains(negativeCase.id()))
                .toList();
    }

    private <T> T load(String resourcePath, TypeReference<T> type) throws IOException {
        try (var input = new ClassPathResource(resourcePath).getInputStream()) {
            return new ObjectMapper().readValue(input, type);
        }
    }

    private void validateDiagnosticCases(
            List<PositiveCase> positiveCases,
            List<NegativeCase> negativeCases) {

        Set<String> corpusSources = CORPUS.stream()
                .map(path -> path.substring(path.lastIndexOf('/') + 1))
                .collect(java.util.stream.Collectors.toSet());
        List<String> expectedPositiveIds = Stream.concat(
                        DEV_POSITIVE_IDS.stream(), UNSEEN_POSITIVE_IDS.stream())
                .toList();

        assertThat(positiveCases).hasSize(8).allSatisfy(positiveCase -> {
            assertThat(positiveCase.expectedSources()).isNotEmpty().allMatch(corpusSources::contains);
            assertThat(positiveCase.query()).isNotBlank();
        });
        assertThat(positiveCases).extracting(PositiveCase::id)
                .doesNotHaveDuplicates()
                .containsExactlyInAnyOrderElementsOf(expectedPositiveIds);

        assertThat(negativeCases).hasSize(2).allSatisfy(negativeCase ->
                assertThat(negativeCase.query()).isNotBlank());
        assertThat(negativeCases).extracting(NegativeCase::id)
                .doesNotHaveDuplicates()
                .containsExactlyInAnyOrderElementsOf(NEGATIVE_IDS);
    }

    /**
     * production threshold와 무관한 score 관찰 전용 검색이다.
     * corpus가 4개 문서이므로 topK 5와 threshold 0.0으로 모든 source의 raw score를 요청한다.
     */
    private List<ScoredSource> retrieveAllSources(VectorStore vectorStore, String query) {
        List<Document> documents = vectorStore.similaritySearch(SearchRequest.builder()
                .query(query)
                .topK(TOP_K)
                .similarityThreshold(NON_FILTERING_THRESHOLD)
                .build());

        LinkedHashMap<String, Double> sourceScores = new LinkedHashMap<>();
        for (Document document : documents) {
            Object source = document.getMetadata().get("source");
            Double score = document.getScore();
            assertThat(source).isInstanceOf(String.class);
            assertThat(score).isNotNull();
            assertThat(Double.isFinite(score)).isTrue();
            sourceScores.putIfAbsent(source.toString(), score);
        }

        return sourceScores.entrySet().stream()
                .map(entry -> new ScoredSource(entry.getKey(), entry.getValue()))
                .toList();
    }

    private PositiveObservation observePositive(
            PositiveCase positiveCase,
            List<ScoredSource> rankedSources) {

        Set<String> expected = new HashSet<>(positiveCase.expectedSources());
        for (int index = 0; index < rankedSources.size(); index++) {
            ScoredSource source = rankedSources.get(index);
            if (expected.contains(source.source())) {
                return new PositiveObservation(positiveCase, rankedSources, index + 1, source.score());
            }
        }
        return new PositiveObservation(positiveCase, rankedSources, 0, null);
    }

    private static String summary(
            List<PositiveObservation> positives,
            List<NegativeObservation> negatives) {

        List<Double> relevantScores = positives.stream()
                .map(PositiveObservation::relevantScore)
                .filter(java.util.Objects::nonNull)
                .sorted()
                .toList();
        List<Double> negativeTopOneScores = negatives.stream()
                .map(NegativeObservation::topOneScore)
                .filter(java.util.Objects::nonNull)
                .sorted()
                .toList();

        Double lowestRelevant = relevantScores.isEmpty() ? null : relevantScores.getFirst();
        Double highestNegative = negativeTopOneScores.isEmpty() ? null : negativeTopOneScores.getLast();

        StringBuilder report = new StringBuilder()
                .append("================================================\n")
                .append("Diagnostic summary\n")
                .append("================================================\n")
                .append("Positive diagnostic queries: ").append(positives.size()).append('\n')
                .append('\n')
                .append("Relevant source scores:\n")
                .append("min: ").append(formatNullable(lowestRelevant)).append('\n')
                .append("max: ").append(formatNullable(max(relevantScores))).append('\n')
                .append("mean: ").append(formatNullable(mean(relevantScores))).append('\n')
                .append("median: ").append(formatNullable(median(relevantScores))).append('\n')
                .append('\n')
                .append("Relevant source ranks:\n")
                .append("rank1: ").append(countRank(positives, 1)).append('\n')
                .append("rank2: ").append(countRank(positives, 2)).append('\n')
                .append("rank3: ").append(countRank(positives, 3)).append('\n')
                .append("rank4+: ").append(positives.stream()
                        .filter(observation -> observation.relevantRank() >= 4).count()).append('\n')
                .append("not-found: ").append(countRank(positives, 0)).append('\n')
                .append('\n')
                .append("Negative diagnostic queries: ").append(negatives.size()).append('\n')
                .append('\n')
                .append("Negative Top1 scores:\n")
                .append("min: ").append(formatNullable(min(negativeTopOneScores))).append('\n')
                .append("max: ").append(formatNullable(highestNegative)).append('\n')
                .append("mean: ").append(formatNullable(mean(negativeTopOneScores))).append('\n')
                .append('\n')
                .append("Lowest relevant score: ").append(formatNullable(lowestRelevant)).append('\n')
                .append("Highest negative Top1 score: ").append(formatNullable(highestNegative)).append('\n')
                .append("Score overlap: ")
                .append(lowestRelevant == null || highestNegative == null
                        ? "N/A"
                        : highestNegative >= lowestRelevant ? "YES" : "NO")
                .append('\n');

        for (double threshold : REFERENCE_THRESHOLDS) {
            report.append("Relevant score below ").append(format(threshold)).append(": ")
                    .append(countScores(relevantScores, score -> score < threshold)).append('\n');
        }
        for (double threshold : REFERENCE_THRESHOLDS) {
            report.append("Negative Top1 score >= ").append(format(threshold)).append(": ")
                    .append(countScores(negativeTopOneScores, score -> score >= threshold)).append('\n');
        }
        return report.toString();
    }

    private static long countRank(List<PositiveObservation> observations, int rank) {
        return observations.stream().filter(observation -> observation.relevantRank() == rank).count();
    }

    private static long countScores(List<Double> scores, DoublePredicate predicate) {
        return scores.stream().mapToDouble(Double::doubleValue).filter(predicate).count();
    }

    private static Double min(List<Double> values) {
        return values.stream().min(Comparator.naturalOrder()).orElse(null);
    }

    private static Double max(List<Double> values) {
        return values.stream().max(Comparator.naturalOrder()).orElse(null);
    }

    private static Double mean(List<Double> values) {
        return values.isEmpty()
                ? null
                : values.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
    }

    private static Double median(List<Double> sortedValues) {
        if (sortedValues.isEmpty()) return null;
        int middle = sortedValues.size() / 2;
        if (sortedValues.size() % 2 == 1) return sortedValues.get(middle);
        return (sortedValues.get(middle - 1) + sortedValues.get(middle)) / 2.0;
    }

    private record PositiveCase(
            String id,
            String query,
            List<String> expectedSources,
            String category) {}

    private record NegativeCase(String id, String query) {}

    private record ScoredSource(String source, double score) {}

    private record PositiveObservation(
            PositiveCase positiveCase,
            List<ScoredSource> rankedSources,
            int relevantRank,
            Double relevantScore) {

        String report() {
            StringBuilder report = header(
                    positiveCase.id(), "POSITIVE", positiveCase.query())
                    .append("Expected: ").append(positiveCase.expectedSources()).append("\n\n")
                    .append("Rank  Source                        Score    Relevant\n");
            Set<String> expected = new HashSet<>(positiveCase.expectedSources());
            appendRankedSources(report, rankedSources, expected);
            report.append('\n')
                    .append("Relevant source:\n")
                    .append("rank: ").append(relevantRank == 0
                            ? "not-found"
                            : Integer.toString(relevantRank)).append('\n')
                    .append("score: ").append(formatNullable(relevantScore)).append('\n')
                    .append('\n')
                    .append("Top1:\n")
                    .append("source: ").append(topOneSource()).append('\n')
                    .append("score: ").append(formatNullable(topOneScore())).append('\n')
                    .append('\n')
                    .append("Top1 - Relevant score gap:\n")
                    .append(formatNullable(scoreGap())).append('\n')
                    .append("================================================\n");
            return report.toString();
        }

        private String topOneSource() {
            return rankedSources.isEmpty() ? "not-found" : rankedSources.getFirst().source();
        }

        private Double topOneScore() {
            return rankedSources.isEmpty() ? null : rankedSources.getFirst().score();
        }

        private Double scoreGap() {
            return topOneScore() == null || relevantScore == null
                    ? null
                    : topOneScore() - relevantScore;
        }
    }

    private record NegativeObservation(
            NegativeCase negativeCase,
            List<ScoredSource> rankedSources) {

        String report() {
            StringBuilder report = header(
                    negativeCase.id(), "NEGATIVE", negativeCase.query())
                    .append("\nRank  Source                        Score\n");
            appendRankedSources(report, rankedSources);
            report.append('\n')
                    .append("Top1 score: ").append(formatNullable(topOneScore())).append('\n')
                    .append('\n')
                    .append("Would pass:\n");
            for (double threshold : REFERENCE_THRESHOLDS) {
                report.append("threshold ").append(format(threshold)).append(": ")
                        .append(topOneScore() != null && topOneScore() >= threshold)
                        .append('\n');
            }
            report.append("================================================\n");
            return report.toString();
        }

        private Double topOneScore() {
            return rankedSources.isEmpty() ? null : rankedSources.getFirst().score();
        }
    }

    private static StringBuilder header(String id, String type, String query) {
        return new StringBuilder()
                .append("================================================\n")
                .append("ID: ").append(id).append('\n')
                .append("Type: ").append(type).append('\n')
                .append("Query: ").append(query).append('\n');
    }

    private static void appendRankedSources(
            StringBuilder report,
            List<ScoredSource> rankedSources,
            Set<String> expected) {

        for (int index = 0; index < rankedSources.size(); index++) {
            ScoredSource source = rankedSources.get(index);
            report.append(String.format(
                    Locale.ROOT,
                    "%-5d %-29s %.4f   %s%n",
                    index + 1,
                    source.source(),
                    source.score(),
                    expected.contains(source.source())));
        }
    }

    private static void appendRankedSources(
            StringBuilder report,
            List<ScoredSource> rankedSources) {

        for (int index = 0; index < rankedSources.size(); index++) {
            ScoredSource source = rankedSources.get(index);
            report.append(String.format(
                    Locale.ROOT,
                    "%-5d %-29s %.4f%n",
                    index + 1,
                    source.source(),
                    source.score()));
        }
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }

    private static String formatNullable(Double value) {
        return value == null ? "not-found" : format(value);
    }
}
