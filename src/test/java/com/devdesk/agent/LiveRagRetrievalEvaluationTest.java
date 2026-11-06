package com.devdesk.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

@SpringBootTest
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
@EnabledIfEnvironmentVariable(named = "LIVE_RAG_DIAGNOSTIC", matches = "true")
class LiveRagRetrievalEvaluationTest {

    private static final int TOP_K = 5;

    private static final List<Double> THRESHOLDS = List.of(0.35, 0.30, 0.25, 0.20, 0.15);

    private static final double MIN_THRESHOLD = 0.15;

    private static final String GOLDEN_SET = "rag/golden-retrieval.json";

    private static final String NEGATIVE_SET = "rag/negative-queries.json";

    private static final List<String> CORPUS = List.of(
            "docs/vpn-guide.md",
            "docs/database-access-policy.md",
            "docs/access-request-guide.md",
            "docs/incident-response-guide.md");

    @Autowired
    EmbeddingModel embeddingModel;

    @Test
    void openai_embedding으로_threshold별_retrieval_tradeoff를_진단한다()
            throws IOException {

        List<GoldenCase> goldenCases = loadGoldenSet();
        List<NegativeCase> negativeCases = loadNegativeSet();

        /*
         * 중요:
         * production VectorStore bean을 직접 쓰지 않는다.
         *
         * 프로젝트에 설정된 실제 OpenAI EmbeddingModel만 가져와
         * 평가용 SimpleVectorStore를 새로 만든다.
         */
        VectorStore vectorStore = SimpleVectorStore.builder(embeddingModel).build();

        IngestService ingestService = new IngestService(vectorStore);

        for (String documentPath : CORPUS) {
            IngestService.IngestResult result = ingestService.ingest(
                    new ClassPathResource(documentPath),
                    "policy",
                    "IT");

            assertThat(result.chunks()).isPositive();
        }

        /*
         * 최저 threshold에서 query당 한 번만 검색하고 score cutoff를 다시 적용한다.
         * topK 순서는 그대로 유지하면서 threshold별 외부 API 재호출을 피한다.
         */
        List<GoldenCandidates> goldenCandidates = goldenCases.stream()
                .map(goldenCase -> new GoldenCandidates(
                        goldenCase,
                        retrieveCandidates(vectorStore, goldenCase.query())))
                .toList();
        List<NegativeCandidates> negativeCandidates = negativeCases.stream()
                .map(negativeCase -> new NegativeCandidates(
                        negativeCase,
                        retrieveCandidates(vectorStore, negativeCase.query())))
                .toList();

        System.out.println();
        System.out.println(
                "LIVE OpenAI RAG retrieval threshold diagnostic");
        System.out.println(
                "============================================");

        for (double threshold : THRESHOLDS) {
            List<QueryResult> results = goldenCandidates.stream()
                    .map(candidate -> new QueryResult(
                            candidate.goldenCase(),
                            sourcesAtThreshold(candidate.chunks(), threshold)))
                    .toList();
            EvaluationResult evaluation = evaluate(results);
            NegativeEvaluation negativeEvaluation = evaluateNegatives(
                    negativeCandidates, threshold);
            System.out.println(evaluation.report(threshold));
            System.out.println(negativeEvaluation.report());
        }

        /*
         * 이 Golden Set은 threshold 진단에 사용한 dev set이다.
         *
         * threshold 선택 후 최종 성능을 주장하려면 이 16건과 분리된
         * unseen retrieval query set으로 다시 평가해야 한다.
         */
        assertThat(goldenCases).hasSize(16);
        assertThat(negativeCases).hasSize(5);
    }

    private List<GoldenCase> loadGoldenSet()
            throws IOException {

        try (var input = new ClassPathResource(GOLDEN_SET)
                .getInputStream()) {

            return new ObjectMapper().readValue(
                    input,
                    new TypeReference<>() {
                    });
        }
    }

    private List<NegativeCase> loadNegativeSet()
            throws IOException {

        try (var input = new ClassPathResource(NEGATIVE_SET)
                .getInputStream()) {

            return new ObjectMapper().readValue(
                    input,
                    new TypeReference<>() {
                    });
        }
    }

    private List<Document> retrieveCandidates(
            VectorStore vectorStore,
            String query) {

        return vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(query)
                        .topK(TOP_K)
                        .similarityThreshold(MIN_THRESHOLD)
                        .build());
    }

    private static List<String> sourcesAtThreshold(
            List<Document> chunks,
            double threshold) {

        return orderedUniqueSources(
                chunks.stream()
                        .filter(chunk -> chunk.getScore() != null
                                && chunk.getScore() >= threshold)
                        .map(chunk -> chunk.getMetadata()
                                .get("source")
                                .toString())
                        .toList());
    }

    private static List<String> orderedUniqueSources(
            List<String> sources) {

        return List.copyOf(
                new LinkedHashSet<>(sources));
    }

    private static EvaluationResult evaluate(
            List<QueryResult> results) {

        double hitsAtOne = 0.0;
        double hitsAtThree = 0.0;
        double recallAtThree = 0.0;
        double reciprocalRanks = 0.0;
        int emptyRetrievalCount = 0;
        int rankOneMissCount = 0;

        List<Failure> failures = new ArrayList<>();

        for (QueryResult result : results) {

            Set<String> expected = new HashSet<>(
                    result.goldenCase()
                            .expectedSources());

            List<String> topThree = result.retrievedSources()
                    .stream()
                    .limit(3)
                    .toList();

            long relevantAtThree = topThree.stream()
                    .filter(expected::contains)
                    .count();

            int firstRelevantRank = 0;

            for (int index = 0; index < result.retrievedSources().size(); index++) {

                if (expected.contains(
                        result.retrievedSources()
                                .get(index))) {

                    firstRelevantRank = index + 1;
                    break;
                }
            }

            if (firstRelevantRank > 0) {
                reciprocalRanks += 1.0 / firstRelevantRank;
            }

            if (result.retrievedSources().isEmpty()) {
                emptyRetrievalCount++;
            }

            if (firstRelevantRank != 1) {
                rankOneMissCount++;
            }

            if (firstRelevantRank == 1) {
                hitsAtOne += 1.0;
            }

            if (firstRelevantRank > 0
                    && firstRelevantRank <= 3) {
                hitsAtThree += 1.0;
            }

            recallAtThree += (double) relevantAtThree
                    / expected.size();

            /*
             * live 평가에서는 rank1 miss도 보고 싶으므로
             * deterministic evaluator보다 조금 더 자세히 남긴다.
             */
            if (firstRelevantRank != 1
                    || relevantAtThree < expected.size()) {

                failures.add(
                        new Failure(
                                result.goldenCase().id(),
                                result.goldenCase().query(),
                                result.goldenCase()
                                        .expectedSources(),
                                result.retrievedSources(),
                                firstRelevantRank));
            }
        }

        int total = results.size();

        return new EvaluationResult(
                total,
                hitsAtOne / total,
                hitsAtThree / total,
                recallAtThree / total,
                reciprocalRanks / total,
                emptyRetrievalCount,
                rankOneMissCount,
                List.copyOf(failures));
    }

    private static NegativeEvaluation evaluateNegatives(
            List<NegativeCandidates> candidates,
            double threshold) {

        List<NegativeHit> falsePositives = candidates.stream()
                .map(candidate -> new NegativeHit(
                        candidate.negativeCase().id(),
                        candidate.negativeCase().query(),
                        sourcesAtThreshold(candidate.chunks(), threshold)))
                .filter(hit -> !hit.retrievedSources().isEmpty())
                .toList();

        return new NegativeEvaluation(candidates.size(), falsePositives);
    }

    private record GoldenCase(
            String id,
            String query,
            List<String> expectedSources,
            String category) {
    }

    private record QueryResult(
            GoldenCase goldenCase,
            List<String> retrievedSources) {
    }

    private record GoldenCandidates(
            GoldenCase goldenCase,
            List<Document> chunks) {
    }

    private record NegativeCase(
            String id,
            String query) {
    }

    private record NegativeCandidates(
            NegativeCase negativeCase,
            List<Document> chunks) {
    }

    private record NegativeHit(
            String id,
            String query,
            List<String> retrievedSources) {
    }

    private record Failure(
            String id,
            String query,
            List<String> expectedSources,
            List<String> retrievedSources,
            int firstRelevantRank) {
    }

    private record EvaluationResult(
            int totalQueries,
            double hitAtOne,
            double hitAtThree,
            double recallAtThree,
            double mrr,
            int emptyRetrievalCount,
            int rankOneMissCount,
            List<Failure> failures) {

        String report(double threshold) {

            StringBuilder report = new StringBuilder()
                    .append("Threshold ")
                    .append(format(threshold))
                    .append('\n')
                    .append("Queries: ")
                    .append(totalQueries)
                    .append('\n')
                    .append("Hit@1: ")
                    .append(format(hitAtOne))
                    .append('\n')
                    .append("Hit@3: ")
                    .append(format(hitAtThree))
                    .append('\n')
                    .append("Recall@3: ")
                    .append(format(recallAtThree))
                    .append('\n')
                    .append("MRR: ")
                    .append(format(mrr))
                    .append('\n')
                    .append("Empty retrieval count: ")
                    .append(emptyRetrievalCount)
                    .append('\n')
                    .append("Rank1 miss count: ")
                    .append(rankOneMissCount)
                    .append('\n')
                    .append("Rank1 / Recall failures: ")
                    .append(failures.size())
                    .append('\n');

            for (Failure failure : failures) {

                report.append('\n')
                        .append(failure.id())
                        .append('\n')
                        .append("query: ")
                        .append(failure.query())
                        .append('\n')
                        .append("expected: ")
                        .append(
                                failure.expectedSources())
                        .append('\n')
                        .append("retrieved: ")
                        .append(
                                failure.retrievedSources())
                        .append('\n')
                        .append("firstRelevantRank: ")
                        .append(failure.firstRelevantRank() == 0
                                ? "not-found"
                                : Integer.toString(failure.firstRelevantRank()))
                        .append('\n');
            }

            return report.toString();
        }

        private static String format(
                double value) {

            return String.format(
                    Locale.ROOT,
                    "%.4f",
                    value);
        }
    }

    private record NegativeEvaluation(
            int totalQueries,
            List<NegativeHit> falsePositives) {

        String report() {
            StringBuilder report = new StringBuilder()
                    .append("Negative queries: ")
                    .append(totalQueries)
                    .append('\n')
                    .append("False positive retrievals: ")
                    .append(falsePositives.size())
                    .append('\n')
                    .append("False Positive Rate: ")
                    .append(falsePositives.size())
                    .append('/')
                    .append(totalQueries)
                    .append(" (")
                    .append(EvaluationResult.format(
                            (double) falsePositives.size() / totalQueries))
                    .append(")\n");

            for (NegativeHit falsePositive : falsePositives) {
                report.append(falsePositive.id())
                        .append('\n')
                        .append("query: ")
                        .append(falsePositive.query())
                        .append('\n')
                        .append("retrieved: ")
                        .append(falsePositive.retrievedSources())
                        .append('\n');
            }

            return report.toString();
        }
    }
}
