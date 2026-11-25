package com.devdesk.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.ResourceAccessException;

class PolicyIndexStartupTest {

    @Test
    void process_restart는_정책을_자동_재인덱싱하고_검색결과를_유지한다() throws Exception {
        RagIndexReadiness firstReadiness = new RagIndexReadiness();
        VectorStore firstStore = SimpleVectorStore.builder(new StablePolicyEmbedding()).build();
        IngestService firstIngest = new IngestService(firstStore, firstReadiness);
        new PolicyIndexStartup(firstIngest, firstReadiness, "test-only-key", true)
                .run(new DefaultApplicationArguments(new String[0]));

        assertThat(firstReadiness.state()).isEqualTo(RagIndexReadiness.State.READY);
        List<String> firstSources = sources(firstStore, "VPN 승인 소요 시간");

        // A new VectorStore and readiness object represent a fresh application process.
        RagIndexReadiness restartedReadiness = new RagIndexReadiness();
        VectorStore restartedStore = SimpleVectorStore.builder(new StablePolicyEmbedding()).build();
        IngestService restartedIngest = new IngestService(restartedStore, restartedReadiness);
        new PolicyIndexStartup(restartedIngest, restartedReadiness, "test-only-key", true)
                .run(new DefaultApplicationArguments(new String[0]));

        assertThat(restartedReadiness.state()).isEqualTo(RagIndexReadiness.State.READY);
        assertThat(sources(restartedStore, "VPN 승인 소요 시간")).contains("vpn-guide.md");
        assertThat(sources(restartedStore, "VPN 승인 소요 시간")).containsExactlyElementsOf(firstSources);
    }

    @Test
    void 동일_프로세스에서_재인덱싱해도_청크가_중복되지_않는다() {
        RagIndexReadiness readiness = new RagIndexReadiness();
        readiness.markReady();
        VectorStore store = SimpleVectorStore.builder(new StablePolicyEmbedding()).build();
        IngestService ingest = new IngestService(store, readiness);

        List<IngestService.IngestResult> first = ingest.reindexPolicyCorpus();
        int firstCount = indexedCount(store);
        List<IngestService.IngestResult> second = ingest.reindexPolicyCorpus();

        assertThat(second).hasSameSizeAs(first);
        assertThat(indexedCount(store)).isEqualTo(firstCount);
        assertThat(readiness.state()).isEqualTo(RagIndexReadiness.State.READY);
    }

    @Test
    void 필수_문서가_누락되면_ready로_표시하지_않는다() {
        RagIndexReadiness readiness = new RagIndexReadiness();
        readiness.markReady();
        IngestService ingest = new IngestService(
                SimpleVectorStore.builder(new StablePolicyEmbedding()).build(), readiness);

        assertThatThrownBy(() -> ingest.reindexPolicyDocuments(new ClassPathResource[] {
                new ClassPathResource("docs/vpn-guide.md")
        })).isInstanceOf(AiIntegrationException.class);

        assertThat(readiness.state()).isEqualTo(RagIndexReadiness.State.UNAVAILABLE);
        assertThatThrownBy(readiness::requireReady)
                .isInstanceOf(AiIntegrationException.class)
                .extracting(error -> ((AiIntegrationException) error).kind())
                .isEqualTo(AiIntegrationException.Kind.POLICY_INDEX);
    }

    @Test
    void embedding_실패는_일부_색인만으로_ready가_되지_않는다() {
        RagIndexReadiness readiness = new RagIndexReadiness();
        VectorStore store = SimpleVectorStore.builder(new FailingEmbedding()).build();
        IngestService ingest = new IngestService(store, readiness);

        assertThatThrownBy(ingest::reindexPolicyCorpus).isInstanceOf(AiIntegrationException.class);
        assertThat(readiness.state()).isEqualTo(RagIndexReadiness.State.UNAVAILABLE);
        assertThatThrownBy(readiness::requireReady).isInstanceOf(AiIntegrationException.class);
    }

    @Test
    void AI_키가_없으면_startup은_embedding을_호출하지_않고_서비스를_계속_기동한다() throws Exception {
        RagIndexReadiness readiness = new RagIndexReadiness();
        IngestService ingest = mock(IngestService.class);

        new PolicyIndexStartup(ingest, readiness, "not-set", true)
                .run(new DefaultApplicationArguments(new String[0]));

        assertThat(readiness.state()).isEqualTo(RagIndexReadiness.State.NOT_CONFIGURED);
        assertThat(new RagIndexHealthIndicator(readiness).health().getStatus())
                .isEqualTo(new Status("NOT_CONFIGURED"));
        verifyNoInteractions(ingest);
    }

    @Test
    void RAG_색인_중에는_health가_준비되지_않음으로_표시된다() {
        RagIndexReadiness readiness = new RagIndexReadiness();
        RagIndexHealthIndicator indicator = new RagIndexHealthIndicator(readiness);

        assertThat(indicator.health().getStatus()).isEqualTo(new Status("INDEXING"));
        readiness.markReady();
        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
        readiness.markUnavailable(AiIntegrationException.Kind.POLICY_INDEX);
        assertThat(indicator.health().getStatus()).isEqualTo(new Status("UNAVAILABLE"));
    }

    @Test
    void 비동기_인덱싱은_외부_호출중에도_애플리케이션_준비를_막지_않는다() throws Exception {
        RagIndexReadiness readiness = new RagIndexReadiness();
        IngestService ingest = new IngestService(
                SimpleVectorStore.builder(new StablePolicyEmbedding()).build(), readiness);
        java.util.concurrent.atomic.AtomicReference<Runnable> queued = new java.util.concurrent.atomic.AtomicReference<>();
        TaskExecutor executor = queued::set;

        new PolicyIndexStartup(ingest, readiness, "test-only-key", true, executor)
                .run(new DefaultApplicationArguments(new String[0]));

        assertThat(readiness.state()).isEqualTo(RagIndexReadiness.State.INDEXING);
        assertThat(new RagIndexHealthIndicator(readiness).health().getStatus())
                .isEqualTo(new Status("INDEXING"));
        queued.get().run();
        assertThat(readiness.state()).isEqualTo(RagIndexReadiness.State.READY);
    }

    @Test
    void disabled_auto_ingest는_테스트에서_외부모델_호출을_막는다() throws Exception {
        RagIndexReadiness readiness = new RagIndexReadiness();
        IngestService ingest = mock(IngestService.class);

        new PolicyIndexStartup(ingest, readiness, "test-only-key", false)
                .run(new DefaultApplicationArguments(new String[0]));

        assertThat(readiness.state()).isEqualTo(RagIndexReadiness.State.NOT_CONFIGURED);
        verifyNoInteractions(ingest);
    }

    @Test
    void 오류_분류는_미설정_인증_일시장애를_안전하게_구분한다() {
        assertThat(PolicyIndexStartup.isConfigured("not-set")).isFalse();
        assertThat(PolicyIndexStartup.isConfigured("  ")).isFalse();
        assertThat(PolicyIndexStartup.isConfigured("test-only-key")).isTrue();
        assertThat(AiFailureClassifier.classify(new NonTransientAiException("401 - secret-detail")))
                .isEqualTo(AiIntegrationException.Kind.AUTHENTICATION);
        assertThat(AiFailureClassifier.classify(new NonTransientAiException("HTTP 401 - {\"error\":\"hidden\"}")))
                .isEqualTo(AiIntegrationException.Kind.AUTHENTICATION);
        assertThat(AiFailureClassifier.classify(new NonTransientAiException("429 - quota-detail")))
                .isEqualTo(AiIntegrationException.Kind.TEMPORARY);
        assertThat(AiFailureClassifier.classify(new TransientAiException("503 - provider-detail")))
                .isEqualTo(AiIntegrationException.Kind.TEMPORARY);
        assertThat(AiFailureClassifier.classify(new ResourceAccessException("timeout detail")))
                .isEqualTo(AiIntegrationException.Kind.TEMPORARY);
        RagIndexReadiness readiness = new RagIndexReadiness();
        readiness.markUnavailable(AiIntegrationException.Kind.TEMPORARY);
        assertThatThrownBy(readiness::requireReady)
                .isInstanceOf(AiIntegrationException.class)
                .satisfies(error -> assertThat(((AiIntegrationException) error).kind())
                        .isEqualTo(AiIntegrationException.Kind.TEMPORARY));
    }

    @Test
    void HTTP_오류는_사용자에게_비밀이나_내부_메시지를_반환하지_않는다() {
        AiIntegrationExceptionHandler handler = new AiIntegrationExceptionHandler();
        var response = handler.handleAiIntegration(new AiIntegrationException(
                AiIntegrationException.Kind.AUTHENTICATION, new IllegalStateException("secret-detail")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(response.getBody().code()).isEqualTo("AI_AUTHENTICATION_FAILED");
        assertThat(response.getBody().message()).doesNotContain("secret-detail");
        assertThat(response.getBody().toString()).doesNotContain("secret-detail", "IllegalStateException");
        assertThat(response.getBody().retryable()).isFalse();
    }

    @Test
    void 모델_인증실패는_예외본문을_감춘_인증오류로_변환한다() {
        ChatClient primary = mock(ChatClient.class);
        ChatClient fallback = mock(ChatClient.class);
        when(primary.prompt()).thenThrow(new NonTransientAiException("401 - api-key-secret-detail"));
        when(fallback.prompt()).thenThrow(new NonTransientAiException("401 - api-key-secret-detail"));
        RagIndexReadiness readiness = new RagIndexReadiness();
        readiness.markReady();
        ToolInvocationTracker tracker = new ToolInvocationTracker();
        HelpDeskService service = new HelpDeskService(primary, fallback,
                MessageWindowChatMemory.builder().chatMemoryRepository(new InMemoryChatMemoryRepository())
                        .maxMessages(20).build(),
                new AccessTools(new AccessRepository(), tracker),
                new TicketTools(new InMemoryTicketRepository(), tracker), new ModelFallbackExecutor(), tracker,
                new UserInputSafetyAdvisor(), new PromptInjectionGuard(), readiness);

        assertThatThrownBy(() -> service.chat("VPN 정책을 알려줘.", "safe-error", "user1", false))
                .isInstanceOf(AiIntegrationException.class)
                .satisfies(error -> {
                    var aiError = (AiIntegrationException) error;
                    assertThat(aiError.kind()).isEqualTo(AiIntegrationException.Kind.AUTHENTICATION);
                    var response = new AiIntegrationExceptionHandler().handleAiIntegration(aiError);
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
                    assertThat(response.getBody().toString())
                            .doesNotContain("api-key-secret-detail", "NonTransientAiException");
                });
    }

    private static int indexedCount(VectorStore store) {
        return store.similaritySearch(SearchRequest.builder()
                .query("VPN 정책 문서 검색")
                .topK(1000)
                .similarityThreshold(0.0)
                .build()).size();
    }

    private static List<String> sources(VectorStore store, String query) {
        return store.similaritySearch(SearchRequest.builder()
                        .query(query).topK(100).similarityThreshold(0.0).build())
                .stream().map(document -> document.getMetadata().get("source").toString())
                .distinct().sorted().toList();
    }

    private static class StablePolicyEmbedding implements EmbeddingModel {
        @Override
        public float[] embed(String text) {
            String normalized = text.toLowerCase();
            if (normalized.contains("vpn")) return new float[] {1, 0, 0, 0};
            if (normalized.contains("database") || normalized.contains("데이터베이스")) {
                return new float[] {0, 1, 0, 0};
            }
            if (normalized.contains("access request") || normalized.contains("권한 신청")) {
                return new float[] {0, 0, 1, 0};
            }
            if (normalized.contains("incident") || normalized.contains("장애")) {
                return new float[] {0, 0, 0, 1};
            }
            return new float[] {0.5f, 0.5f, 0.5f, 0.5f};
        }

        @Override
        public float[] embed(Document document) { return embed(document.getText()); }

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            throw new UnsupportedOperationException("deterministic test embedding only");
        }

        @Override
        public int dimensions() { return 4; }
    }

    private static final class FailingEmbedding extends StablePolicyEmbedding {
        @Override
        public float[] embed(String text) { throw new IllegalStateException("simulated local embedding failure"); }
    }
}
