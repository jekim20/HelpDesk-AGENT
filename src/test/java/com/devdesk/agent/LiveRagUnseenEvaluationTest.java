package com.devdesk.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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
@EnabledIfEnvironmentVariable(named = "LIVE_RAG_UNSEEN", matches = "true")
class LiveRagUnseenEvaluationTest {

    private static final int TOP_K = 5;
    private static final double HOLDOUT_THRESHOLD = 0.30;
    private static final String POSITIVE_SET = "rag/unseen-retrieval.json";
    private static final String NEGATIVE_SET = "rag/unseen-negative-queries.json";
    private static final List<String> CORPUS = List.of(
            "docs/vpn-guide.md",
            "docs/database-access-policy.md",
            "docs/access-request-guide.md",
            "docs/incident-response-guide.md");

    /*
     * Holdout freeze policy:
     * - threshold 0.30은 이 결과를 본 뒤 다시 조정하지 않는다.
     * - 실패에 맞춰 query, expected source, negative query를 수정하거나 삭제하지 않는다.
     * - 결과가 낮으면 0.30이 holdout에서 충분히 검증되지 않은 것으로 판단한다.
     */

    @Autowired
    EmbeddingModel embeddingModel;

    @Test
    void openai_embedding으로_frozen_unseen_set을_한번_평가한다() throws IOException {
        List<PositiveCase> positiveCases = load(POSITIVE_SET, new TypeReference<>() {});
        List<NegativeCase> negativeCases = load(NEGATIVE_SET, new TypeReference<>() {});
        validateDataset(positiveCases, negativeCases);

        VectorStore vectorStore = SimpleVectorStore.builder(embeddingModel).build();
        IngestService ingestService = new IngestService(vectorStore);
        for (String documentPath : CORPUS) {
            IngestService.IngestResult result = ingestService.ingest(
                    new ClassPathResource(documentPath), "policy", "IT");
            assertThat(result.chunks()).isPositive();
        }

        List<QueryResult> positiveResults = positiveCases.stream()
                .map(positiveCase -> new QueryResult(
                        positiveCase,
                        retrieveSources(vectorStore, positiveCase.query())))
                .toList();
        PositiveEvaluation positiveEvaluation = evaluatePositives(positiveResults);

        List<NegativeHit> falsePositives = negativeCases.stream()
                .map(negativeCase -> new NegativeHit(
                        negativeCase.id(),
                        negativeCase.query(),
                        retrieveSources(vectorStore, negativeCase.query())))
                .filter(hit -> !hit.retrievedSources().isEmpty())
                .toList();
        NegativeEvaluation negativeEvaluation = new NegativeEvaluation(
                negativeCases.size(), falsePositives);

        System.out.println();
        System.out.println("LIVE OpenAI RAG unseen evaluation");
        System.out.println("=================================");
        System.out.println();
        System.out.println("Threshold: " + format(HOLDOUT_THRESHOLD));
        System.out.println();
        System.out.println(positiveEvaluation.report());
        System.out.println(negativeEvaluation.report());
    }

    private <T> T load(String resourcePath, TypeReference<T> type) throws IOException {
        try (var input = new ClassPathResource(resourcePath).getInputStream()) {
            return new ObjectMapper().readValue(input, type);
        }
    }

    private void validateDataset(
            List<PositiveCase> positiveCases,
            List<NegativeCase> negativeCases) {

        Set<String> corpusSources = CORPUS.stream()
                .map(path -> path.substring(path.lastIndexOf('/') + 1))
                .collect(Collectors.toSet());

        assertThat(positiveCases).hasSize(12).allSatisfy(positiveCase -> {
            assertThat(positiveCase.id()).isNotBlank();
            assertThat(positiveCase.query()).isNotBlank();
            assertThat(positiveCase.category()).isNotBlank();
            assertThat(positiveCase.expectedSources())
                    .singleElement()
                    .satisfies(source -> assertThat(corpusSources).contains(source));
        });
        assertThat(positiveCases).extracting(PositiveCase::id).doesNotHaveDuplicates();

        Map<String, Long> sourceCounts = positiveCases.stream()
                .flatMap(positiveCase -> positiveCase.expectedSources().stream())
                .collect(Collectors.groupingBy(source -> source, Collectors.counting()));
        assertThat(sourceCounts).containsOnly(
                Map.entry("vpn-guide.md", 3L),
                Map.entry("database-access-policy.md", 3L),
                Map.entry("access-request-guide.md", 3L),
                Map.entry("incident-response-guide.md", 3L));

        assertThat(negativeCases).hasSize(8).allSatisfy(negativeCase -> {
            assertThat(negativeCase.id()).isNotBlank();
            assertThat(negativeCase.query()).isNotBlank();
        });
        assertThat(negativeCases).extracting(NegativeCase::id).doesNotHaveDuplicates();
    }

    private List<String> retrieveSources(VectorStore vectorStore, String query) {
        List<Document> chunks = vectorStore.similaritySearch(SearchRequest.builder()
                .query(query)
                .topK(TOP_K)
                .similarityThreshold(HOLDOUT_THRESHOLD)
                .build());
        return List.copyOf(new LinkedHashSet<>(chunks.stream()
                .map(chunk -> chunk.getMetadata().get("source").toString())
                .toList()));
    }

    private static PositiveEvaluation evaluatePositives(List<QueryResult> results) {
        double hitsAtOne = 0.0;
        double hitsAtThree = 0.0;
        double recallAtThree = 0.0;
        double reciprocalRanks = 0.0;
        int emptyRetrievalCount = 0;
        int rankOneMissCount = 0;
        List<PositiveFailure> failures = new ArrayList<>();

        for (QueryResult result : results) {
            Set<String> expected = new HashSet<>(result.positiveCase().expectedSources());
            List<String> topThree = result.retrievedSources().stream().limit(3).toList();
            long relevantAtThree = topThree.stream().filter(expected::contains).count();
            int firstRelevantRank = firstRelevantRank(result.retrievedSources(), expected);

            if (firstRelevantRank == 1) hitsAtOne += 1.0;
            if (firstRelevantRank > 0 && firstRelevantRank <= 3) hitsAtThree += 1.0;
            if (firstRelevantRank > 0) reciprocalRanks += 1.0 / firstRelevantRank;
            recallAtThree += (double) relevantAtThree / expected.size();
            if (result.retrievedSources().isEmpty()) emptyRetrievalCount++;
            if (firstRelevantRank != 1) rankOneMissCount++;

            if (firstRelevantRank != 1 || relevantAtThree < expected.size()) {
                failures.add(new PositiveFailure(
                        result.positiveCase().id(),
                        result.positiveCase().query(),
                        result.positiveCase().expectedSources(),
                        result.retrievedSources(),
                        firstRelevantRank));
            }
        }

        int total = results.size();
        return new PositiveEvaluation(
                total,
                hitsAtOne / total,
                hitsAtThree / total,
                recallAtThree / total,
                reciprocalRanks / total,
                emptyRetrievalCount,
                rankOneMissCount,
                List.copyOf(failures));
    }

    private static int firstRelevantRank(List<String> retrievedSources, Set<String> expected) {
        for (int index = 0; index < retrievedSources.size(); index++) {
            if (expected.contains(retrievedSources.get(index))) return index + 1;
        }
        return 0;
    }

    private record PositiveCase(
            String id,
            String query,
            List<String> expectedSources,
            String category) {}

    private record NegativeCase(String id, String query) {}

    private record QueryResult(PositiveCase positiveCase, List<String> retrievedSources) {}

    private record PositiveFailure(
            String id,
            String query,
            List<String> expectedSources,
            List<String> retrievedSources,
            int firstRelevantRank) {}

    private record NegativeHit(String id, String query, List<String> retrievedSources) {}

    private record PositiveEvaluation(
            int totalQueries,
            double hitAtOne,
            double hitAtThree,
            double recallAtThree,
            double mrr,
            int emptyRetrievalCount,
            int rankOneMissCount,
            List<PositiveFailure> failures) {

        String report() {
            StringBuilder report = new StringBuilder()
                    .append("Positive queries: ").append(totalQueries).append('\n')
                    .append("Hit@1: ").append(format(hitAtOne)).append('\n')
                    .append("Hit@3: ").append(format(hitAtThree)).append('\n')
                    .append("Recall@3: ").append(format(recallAtThree)).append('\n')
                    .append("MRR: ").append(format(mrr)).append('\n')
                    .append("Empty retrieval count: ").append(emptyRetrievalCount).append('\n')
                    .append("Rank1 miss count: ").append(rankOneMissCount).append('\n')
                    .append('\n')
                    .append("Positive failures: ").append(failures.size()).append('\n');
            for (PositiveFailure failure : failures) {
                report.append(failure.id()).append('\n')
                        .append("query: ").append(failure.query()).append('\n')
                        .append("expected: ").append(failure.expectedSources()).append('\n')
                        .append("retrieved: ").append(failure.retrievedSources()).append('\n')
                        .append("firstRelevantRank: ")
                        .append(failure.firstRelevantRank() == 0
                                ? "not-found"
                                : Integer.toString(failure.firstRelevantRank()))
                        .append('\n');
            }
            return report.toString();
        }
    }

    private record NegativeEvaluation(int totalQueries, List<NegativeHit> falsePositives) {

        String report() {
            StringBuilder report = new StringBuilder()
                    .append("Negative queries: ").append(totalQueries).append('\n')
                    .append("False positive retrievals: ")
                    .append(falsePositives.size()).append('\n')
                    .append("False Positive Rate: ")
                    .append(falsePositives.size()).append('/').append(totalQueries)
                    .append(" (")
                    .append(format((double) falsePositives.size() / totalQueries))
                    .append(")\n")
                    .append('\n')
                    .append("Negative failures: ").append(falsePositives.size()).append('\n');
            for (NegativeHit falsePositive : falsePositives) {
                report.append(falsePositive.id()).append('\n')
                        .append("query: ").append(falsePositive.query()).append('\n')
                        .append("retrieved: ").append(falsePositive.retrievedSources()).append('\n');
            }
            return report.toString();
        }
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }
}
