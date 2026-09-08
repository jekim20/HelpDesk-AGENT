package com.devdesk.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.ai.document.Document;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.ClassPathResource;

class RagRetrievalTest {

    private static final double PREVIOUS_THRESHOLD = 0.62;

    @ParameterizedTest(name = "{0} -> {2}")
    @MethodSource("retrievalCases")
    void ingest한_VectorStore를_직접_검색하면_기대_source가_반환된다(
            String question, String documentPath, String expectedSource) {
        VectorStore vectorStore = SimpleVectorStore.builder(new RetrievalTestEmbeddingModel()).build();
        IngestService ingestService = new IngestService(vectorStore);

        IngestService.IngestResult ingestResult = ingestService.ingest(
                new ClassPathResource(documentPath), "policy", "IT");

        assertThat(ingestResult.source()).isEqualTo(expectedSource);
        assertThat(ingestResult.chunks()).isPositive();

        List<Document> rejectedAtPreviousThreshold = vectorStore.similaritySearch(request(question, PREVIOUS_THRESHOLD));
        List<Document> retrieved = vectorStore.similaritySearch(
                request(question, AgentConfiguration.DEV_DESK_RAG_SIMILARITY_THRESHOLD));

        assertThat(rejectedAtPreviousThreshold).isEmpty();
        assertThat(retrieved).isNotEmpty();
        assertThat(retrieved.getFirst().getMetadata().get("source")).isEqualTo(expectedSource);
        assertThat(retrieved.getFirst().getScore())
                .isGreaterThanOrEqualTo(AgentConfiguration.DEV_DESK_RAG_SIMILARITY_THRESHOLD)
                .isLessThan(PREVIOUS_THRESHOLD);

        QuestionAnswerAdvisor advisor = QuestionAnswerAdvisor.builder(vectorStore)
                .searchRequest(request(question, AgentConfiguration.DEV_DESK_RAG_SIMILARITY_THRESHOLD))
                .build();
        ChatClientRequest advisedRequest = advisor.before(
                new ChatClientRequest(new Prompt(new UserMessage(question)), java.util.Map.of()),
                mock(AdvisorChain.class));

        @SuppressWarnings("unchecked")
        List<Document> advisorDocuments = (List<Document>) advisedRequest.context()
                .get(QuestionAnswerAdvisor.RETRIEVED_DOCUMENTS);
        assertThat(advisorDocuments).isNotEmpty();
        assertThat(advisorDocuments.getFirst().getMetadata().get("source")).isEqualTo(expectedSource);

        ChatClient testClient = ChatClient.create(prompt -> new ChatResponse(
                List.of(new Generation(new AssistantMessage("정책 문서 기반 테스트 응답")))));
        TicketRepository ticketRepository = new TicketRepository();
        ToolInvocationTracker invocationTracker = new ToolInvocationTracker();
        HelpDeskService service = new HelpDeskService(
                testClient,
                testClient,
                MessageWindowChatMemory.builder()
                        .chatMemoryRepository(new InMemoryChatMemoryRepository())
                        .maxMessages(20)
                        .build(),
                vectorStore,
                new AccessTools(new AccessRepository(), invocationTracker),
                new TicketTools(ticketRepository, invocationTracker),
                new ModelFallbackExecutor(),
                invocationTracker,
                new UserInputSafetyAdvisor());
        ChatController controller = new ChatController(
                service, mock(IngestService.class), ticketRepository);

        var apiResponse = controller.chat(new ChatController.ChatRequest(
                question, "rag-regression", "user1", false));

        assertThat(apiResponse.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(apiResponse.getBody()).isNotNull();
        assertThat(apiResponse.getBody().sources()).contains(expectedSource);
    }

    private SearchRequest request(String question, double threshold) {
        return SearchRequest.builder()
                .query(question)
                .topK(5)
                .similarityThreshold(threshold)
                .build();
    }

    private static Stream<Arguments> retrievalCases() {
        return Stream.of(
                Arguments.of("VPN 승인에는 얼마나 걸려?", "docs/vpn-guide.md", "vpn-guide.md"),
                Arguments.of("운영 DB 접근 정책을 알려줘.",
                        "docs/database-access-policy.md", "database-access-policy.md"));
    }

    /**
     * 외부 API 없이 threshold 경계를 재현하는 test embedding이다.
     * 관련 문서와 질문의 cosine score는 0.55, 무관 문서는 0이 되도록 고정한다.
     */
    private static final class RetrievalTestEmbeddingModel implements EmbeddingModel {

        @Override
        public float[] embed(String text) {
            if (text.contains("VPN")) return new float[] {0.55f, 0f, 0f, 0.83516467f};
            if (text.contains("운영 DB")) return new float[] {0f, 0.55f, 0f, 0.83516467f};
            return new float[] {0f, 0f, 1f, 0f};
        }

        @Override
        public float[] embed(Document document) {
            String text = document.getText();
            if (text.contains("# VPN 사용 및 승인 가이드")) return new float[] {1f, 0f, 0f, 0f};
            if (text.contains("# 데이터베이스 접근 정책")) return new float[] {0f, 1f, 0f, 0f};
            return new float[] {0f, 0f, 1f, 0f};
        }

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            throw new UnsupportedOperationException("이 테스트에서는 직접 embed만 사용한다.");
        }

        @Override
        public int dimensions() {
            return 4;
        }
    }
}
