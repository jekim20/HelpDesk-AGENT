package com.devdesk.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.ClassPathResource;

class RagRetrievalEvaluationTest {

    private static final int TOP_K = 5;
    private static final String GOLDEN_SET = "rag/golden-retrieval.json";
    private static final List<String> CORPUS = List.of(
            "docs/vpn-guide.md",
            "docs/database-access-policy.md",
            "docs/access-request-guide.md",
            "docs/incident-response-guide.md");

    @Test
    void golden_set으로_document_level_검색품질을_평가한다() throws IOException {
        List<GoldenCase> goldenCases = loadGoldenSet();
        DeterministicLexicalEmbeddingModel embeddingModel =
                DeterministicLexicalEmbeddingModel.fromClasspath(CORPUS);
        VectorStore vectorStore = SimpleVectorStore.builder(embeddingModel).build();
        IngestService ingestService = new IngestService(vectorStore);

        for (String documentPath : CORPUS) {
            IngestService.IngestResult result = ingestService.ingest(
                    new ClassPathResource(documentPath), "policy", "IT");
            assertThat(result.chunks()).isPositive();
        }

        Set<String> corpusSources = CORPUS.stream()
                .map(path -> path.substring(path.lastIndexOf('/') + 1))
                .collect(java.util.stream.Collectors.toSet());
        assertThat(goldenCases).allSatisfy(goldenCase -> {
            assertThat(goldenCase.id()).isNotBlank();
            assertThat(goldenCase.query()).isNotBlank();
            assertThat(goldenCase.category()).isNotBlank();
            assertThat(goldenCase.expectedSources()).isNotEmpty().allMatch(corpusSources::contains);
        });
        assertThat(goldenCases).extracting(GoldenCase::id).doesNotHaveDuplicates();

        List<QueryResult> queryResults = goldenCases.stream()
                .map(goldenCase -> retrieve(vectorStore, goldenCase))
                .toList();
        EvaluationResult evaluation = evaluate(queryResults);

        System.out.println(evaluation.report(TOP_K));

        assertThat(goldenCases).hasSize(16);
        assertThat(evaluation.totalQueries()).isEqualTo(goldenCases.size());
        assertThat(evaluation.hitAtK()).isGreaterThanOrEqualTo(0.90);
        assertThat(evaluation.recallAtK()).isGreaterThanOrEqualTo(0.90);
        assertThat(evaluation.mrr()).isGreaterThanOrEqualTo(0.85);
    }

    @Test
    void source를_순서대로_중복제거한_뒤_Hit_Recall_MRR을_계산한다() {
        List<QueryResult> results = List.of(
                new QueryResult(
                        new GoldenCase("RAG-A", "query-a", List.of("a.md"), "TEST"),
                        orderedUniqueSources(List.of("x.md", "a.md", "a.md"))),
                new QueryResult(
                        new GoldenCase("RAG-B", "query-b", List.of("b.md", "c.md"), "TEST"),
                        orderedUniqueSources(List.of("b.md", "b.md"))));

        EvaluationResult evaluation = evaluate(results);

        assertThat(results.getFirst().retrievedSources()).containsExactly("x.md", "a.md");
        assertThat(evaluation.totalQueries()).isEqualTo(2);
        assertThat(evaluation.hitAtK()).isEqualTo(1.0);
        assertThat(evaluation.recallAtK()).isEqualTo(0.75);
        assertThat(evaluation.mrr()).isEqualTo(0.75);
        assertThat(evaluation.failures()).singleElement()
                .satisfies(failure -> assertThat(failure.id()).isEqualTo("RAG-B"));
    }

    @Test
    void corpus와_무관한_질문은_similarity_threshold를_통과하지_않는다() throws IOException {
        VectorStore vectorStore = SimpleVectorStore.builder(
                DeterministicLexicalEmbeddingModel.fromClasspath(CORPUS)).build();
        IngestService ingestService = new IngestService(vectorStore);
        for (String documentPath : CORPUS) {
            ingestService.ingest(new ClassPathResource(documentPath), "policy", "IT");
        }

        List<Document> retrieved = vectorStore.similaritySearch(SearchRequest.builder()
                .query("반려동물 예방접종 일정과 날씨를 알려줘.")
                .topK(TOP_K)
                .similarityThreshold(AgentConfiguration.DEV_DESK_RAG_SIMILARITY_THRESHOLD)
                .build());

        assertThat(retrieved).isEmpty();
    }

    private List<GoldenCase> loadGoldenSet() throws IOException {
        try (var input = new ClassPathResource(GOLDEN_SET).getInputStream()) {
            return new ObjectMapper().readValue(input, new TypeReference<>() {});
        }
    }

    private QueryResult retrieve(VectorStore vectorStore, GoldenCase goldenCase) {
        List<Document> chunks = vectorStore.similaritySearch(SearchRequest.builder()
                .query(goldenCase.query())
                .topK(TOP_K)
                .similarityThreshold(AgentConfiguration.DEV_DESK_RAG_SIMILARITY_THRESHOLD)
                .build());

        assertThat(chunks).allSatisfy(chunk -> {
            assertThat(chunk.getMetadata()).containsEntry("docType", "policy");
            assertThat(chunk.getMetadata()).containsEntry("dept", "IT");
            assertThat(chunk.getMetadata()).containsKeys("source", "version");
        });

        List<String> sources = orderedUniqueSources(chunks.stream()
                .map(chunk -> chunk.getMetadata().get("source").toString())
                .toList());
        return new QueryResult(goldenCase, sources);
    }

    private static List<String> orderedUniqueSources(List<String> sources) {
        return List.copyOf(new LinkedHashSet<>(sources));
    }

    private static EvaluationResult evaluate(List<QueryResult> results) {
        double hits = 0.0;
        double recall = 0.0;
        double reciprocalRanks = 0.0;
        List<Failure> failures = new ArrayList<>();

        for (QueryResult result : results) {
            Set<String> expected = new HashSet<>(result.goldenCase().expectedSources());
            long relevantCount = result.retrievedSources().stream().filter(expected::contains).count();
            int firstRelevantRank = 0;
            for (int index = 0; index < result.retrievedSources().size(); index++) {
                if (expected.contains(result.retrievedSources().get(index))) {
                    firstRelevantRank = index + 1;
                    break;
                }
            }

            if (firstRelevantRank > 0) {
                hits += 1.0;
                reciprocalRanks += 1.0 / firstRelevantRank;
            }
            recall += (double) relevantCount / expected.size();
            if (relevantCount < expected.size()) {
                failures.add(new Failure(
                        result.goldenCase().id(),
                        result.goldenCase().query(),
                        result.goldenCase().expectedSources(),
                        result.retrievedSources()));
            }
        }

        int total = results.size();
        return new EvaluationResult(
                total,
                hits / total,
                recall / total,
                reciprocalRanks / total,
                List.copyOf(failures));
    }

    private record GoldenCase(
            String id,
            String query,
            List<String> expectedSources,
            String category) {}

    private record QueryResult(GoldenCase goldenCase, List<String> retrievedSources) {}

    private record Failure(
            String id,
            String query,
            List<String> expectedSources,
            List<String> retrievedSources) {}

    private record EvaluationResult(
            int totalQueries,
            double hitAtK,
            double recallAtK,
            double mrr,
            List<Failure> failures) {

        String report(int topK) {
            StringBuilder report = new StringBuilder()
                    .append("RAG retrieval evaluation\n")
                    .append("------------------------\n")
                    .append("Queries: ").append(totalQueries).append('\n')
                    .append("Hit@").append(topK).append(": ").append(format(hitAtK)).append('\n')
                    .append("Recall@").append(topK).append(": ").append(format(recallAtK)).append('\n')
                    .append("MRR: ").append(format(mrr)).append('\n')
                    .append("Failures: ").append(failures.size()).append('\n');
            for (Failure failure : failures) {
                report.append(failure.id()).append('\n')
                        .append("query: ").append(failure.query()).append('\n')
                        .append("expected: ").append(failure.expectedSources()).append('\n')
                        .append("retrieved: ").append(failure.retrievedSources()).append('\n');
            }
            return report.toString();
        }

        private static String format(double value) {
            return String.format(Locale.ROOT, "%.4f", value);
        }
    }

    /**
     * 실제 corpus에서 word/character n-gram TF-IDF를 만들고, 각 문서와의 lexical similarity
     * profile로 투영한다. source 이름이나 golden query를 알지 못하는 테스트 전용 embedding이다.
     */
    private static final class DeterministicLexicalEmbeddingModel implements EmbeddingModel {

        private final Map<String, Integer> vocabulary;
        private final double[] inverseDocumentFrequency;
        private final List<float[]> corpusVectors;

        private DeterministicLexicalEmbeddingModel(List<String> corpusTexts) {
            Map<String, Integer> documentFrequency = new HashMap<>();
            for (String text : corpusTexts) {
                for (String feature : features(text).keySet()) {
                    documentFrequency.merge(feature, 1, Integer::sum);
                }
            }
            this.vocabulary = new LinkedHashMap<>();
            documentFrequency.keySet().stream().sorted()
                    .forEach(feature -> vocabulary.put(feature, vocabulary.size()));
            this.inverseDocumentFrequency = new double[vocabulary.size()];
            vocabulary.forEach((feature, index) -> inverseDocumentFrequency[index] =
                    Math.log((corpusTexts.size() + 1.0) / (documentFrequency.get(feature) + 1.0)) + 1.0);
            this.corpusVectors = corpusTexts.stream().map(this::termVector).toList();
        }

        static DeterministicLexicalEmbeddingModel fromClasspath(List<String> paths) throws IOException {
            List<String> corpusTexts = new ArrayList<>();
            for (String path : paths) {
                corpusTexts.add(new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8));
            }
            return new DeterministicLexicalEmbeddingModel(corpusTexts);
        }

        @Override
        public float[] embed(String text) {
            float[] terms = termVector(text);
            float[] vector = new float[dimensions()];
            for (int index = 0; index < corpusVectors.size(); index++) {
                vector[index] = dot(terms, corpusVectors.get(index));
            }
            if (isZero(vector)) {
                vector[vector.length - 1] = 1.0f;
            } else {
                normalize(vector);
            }
            return vector;
        }

        private float[] termVector(String text) {
            float[] vector = new float[vocabulary.size()];
            features(text).forEach((feature, weight) -> {
                Integer index = vocabulary.get(feature);
                if (index != null) {
                    vector[index] += (float) (weight * inverseDocumentFrequency[index]);
                }
            });
            normalize(vector);
            return vector;
        }

        @Override
        public float[] embed(Document document) {
            return embed(document.getText());
        }

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            throw new UnsupportedOperationException("이 테스트에서는 직접 embed만 사용한다.");
        }

        @Override
        public int dimensions() {
            return corpusVectors.size() + 1;
        }

        private static Map<String, Double> features(String text) {
            Map<String, Double> features = new HashMap<>();
            String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC)
                    .toLowerCase(Locale.ROOT);
            Arrays.stream(normalized.split("[^\\p{L}\\p{N}_]+"))
                    .filter(token -> !token.isBlank())
                    .forEach(token -> {
                        features.merge("word:" + token, 2.0, Double::sum);
                        addCharacterNgrams(features, token, 2, 1.0);
                        addCharacterNgrams(features, token, 3, 1.5);
                    });
            return features;
        }

        private static void addCharacterNgrams(
                Map<String, Double> features, String token, int size, double weight) {
            if (token.length() < size) return;
            for (int index = 0; index <= token.length() - size; index++) {
                features.merge("char" + size + ":" + token.substring(index, index + size),
                        weight, Double::sum);
            }
        }

        private static void normalize(float[] vector) {
            double magnitude = 0.0;
            for (float value : vector) magnitude += value * value;
            if (magnitude == 0.0) return;
            float divisor = (float) Math.sqrt(magnitude);
            for (int index = 0; index < vector.length; index++) vector[index] /= divisor;
        }

        private static float dot(float[] left, float[] right) {
            float result = 0.0f;
            for (int index = 0; index < left.length; index++) result += left[index] * right[index];
            return result;
        }

        private static boolean isZero(float[] vector) {
            for (float value : vector) {
                if (value != 0.0f) return false;
            }
            return true;
        }
    }
}
