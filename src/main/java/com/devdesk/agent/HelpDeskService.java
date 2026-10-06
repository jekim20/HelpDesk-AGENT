package com.devdesk.agent;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import com.devdesk.agent.ModelFallbackExecutor.ExecutionResult;
import com.devdesk.agent.TicketRepository.TicketType;
import com.devdesk.agent.ToolInvocationTracker.ToolCallInfo;

@Service
public class HelpDeskService {

    private static final Logger log = LoggerFactory.getLogger(HelpDeskService.class);
    private static final int MAX_INPUT_LENGTH = 4000;
    private final ChatClient primaryClient;
    private final ChatClient fallbackClient;
    private final ChatMemory memory;
    private final VectorStore vectorStore;
    private final AccessTools accessTools;
    private final TicketTools ticketTools;
    private final ModelFallbackExecutor fallbackExecutor;
    private final ToolInvocationTracker invocationTracker;
    private final UserInputSafetyAdvisor userInputSafety;
    private final PromptInjectionGuard promptInjectionGuard;
    private final Map<String, String> conversationResources = new ConcurrentHashMap<>();

    public HelpDeskService(@Qualifier("devDeskPrimaryClient") ChatClient primaryClient,
                           @Qualifier("devDeskFallbackClient") ChatClient fallbackClient,
                           ChatMemory memory,
                           VectorStore vectorStore,
                           AccessTools accessTools,
                           TicketTools ticketTools,
                           ModelFallbackExecutor fallbackExecutor,
                           ToolInvocationTracker invocationTracker,
                           UserInputSafetyAdvisor userInputSafety,
                           PromptInjectionGuard promptInjectionGuard) {
        this.primaryClient = primaryClient;
        this.fallbackClient = fallbackClient;
        this.memory = memory;
        this.vectorStore = vectorStore;
        this.accessTools = accessTools;
        this.ticketTools = ticketTools;
        this.fallbackExecutor = fallbackExecutor;
        this.invocationTracker = invocationTracker;
        this.userInputSafety = userInputSafety;
        this.promptInjectionGuard = promptInjectionGuard;
    }

    public ChatResult chat(String question, String sessionId, String userId, boolean simulatePrimaryFailure) {
        String conversationId = conversationId(userId, sessionId);
        String validation = validate(question, sessionId, userId);
        if (validation != null) {
            return new ChatResult(validation, sessionId, List.of(), false, List.of(), false);
        }

        String resource = explicitResource(question);
        if (resource != null) {
            conversationResources.put(conversationId, resource);
        } else if (isContextualReference(question)) {
            resource = conversationResources.get(conversationId);
        }

        if (isUnsupportedAccessLookup(question, resource)) {
            return new ChatResult(
                    "지원하지 않는 리소스입니다. VPN, DEV_DB, PROD_DB만 조회할 수 있습니다.",
                    sessionId, List.of(), false, List.of(), false);
        }
        if (isAccessLookup(question, resource)) {
            invocationTracker.begin();
            try {
                String answer = accessTools.getAccessStatus(resource, toolContext(userId));
                remember(conversationId, question, answer);
                return new ChatResult(answer, sessionId, List.of(), true,
                        invocationTracker.snapshot(), false);
            } finally {
                invocationTracker.clear();
            }
        }
        if (isTicketRequest(question)) {
            TicketType type = ticketType(question);
            String target = resource == null ? ticketTarget(question) : resource;
            invocationTracker.begin();
            try {
                String answer = ticketTools.createTicket(type, target, question, toolContext(userId));
                remember(conversationId, question, answer);
                return new ChatResult(answer, sessionId, List.of(), true,
                        invocationTracker.snapshot(), false);
            } finally {
                invocationTracker.clear();
            }
        }

        String contextualQuestion = contextualQuestion(question, resource);
        invocationTracker.begin();
        try {
            Supplier<String> primary = () -> {
                if (simulatePrimaryFailure) {
                    throw new IllegalStateException("simulated primary model failure");
                }
                return callModel(primaryClient, contextualQuestion, conversationId, userId);
            };
            Supplier<String> fallback = () -> callModel(fallbackClient, contextualQuestion, conversationId, userId);
            ExecutionResult result = fallbackExecutor.execute(primary, fallback);
            return new ChatResult(result.answer(), sessionId, findSources(contextualQuestion),
                    invocationTracker.wasInvoked(), invocationTracker.snapshot(), result.fallbackUsed());
        } finally {
            invocationTracker.clear();
        }
    }

    public List<String> history(String sessionId, String userId) {
        return memory.get(conversationId(userId, sessionId)).stream().map(Message::getText).toList();
    }

    private String callModel(ChatClient client, String question, String conversationId, String userId) {
        return client.prompt()
                .user(question)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                .tools(accessTools, ticketTools)
                .toolContext(Map.of("userId", userId))
                .call()
                .content();
    }

    String validate(String question, String sessionId, String userId) {
        if (question == null || question.isBlank()) return "질문을 입력해 주세요.";
        if (question.length() > MAX_INPUT_LENGTH) return "입력이 너무 깁니다. 4,000자 이내로 줄여 주세요.";
        if (sessionId == null || sessionId.isBlank() || userId == null || userId.isBlank()) {
            return "sessionId와 userId는 필수입니다.";
        }
        if (promptInjectionGuard.isSuspicious(question)) return PromptInjectionGuard.BLOCK_RESPONSE;
        return userInputSafety.rejectionFor(question);
    }

    private String explicitResource(String question) {
        String normalized = question.toUpperCase(java.util.Locale.ROOT);
        if (normalized.contains("PROD_DB") || question.contains("운영 DB")) return "PROD_DB";
        if (normalized.contains("DEV_DB") || question.contains("개발 DB")) return "DEV_DB";
        if (normalized.contains("VPN")) return "VPN";
        return null;
    }

    private boolean isContextualReference(String question) {
        return question.contains("그 권한") || question.contains("해당 권한") || question.contains("그 리소스");
    }

    private boolean isAccessLookup(String question, String resource) {
        return resource != null && question.contains("권한") && !question.contains("정책")
                && (question.contains("상태") || question.contains("조회") || question.contains("확인")
                || question.contains("알려")) && !isTicketRequest(question);
    }

    private boolean isUnsupportedAccessLookup(String question, String resource) {
        return resource == null && question.contains("권한")
                && (question.contains("상태") || question.contains("조회") || question.contains("확인"));
    }

    private boolean isTicketRequest(String question) {
        return question.contains("신청해줘") || question.contains("신청해 줘")
                || question.contains("접수해줘") || question.contains("접수해 줘")
                || question.contains("티켓 생성해줘") || question.contains("티켓 만들어줘");
    }

    private TicketType ticketType(String question) {
        boolean accessApplication = ((question.contains("권한") || question.contains("접근"))
                && question.contains("신청"))
                || question.contains("권한 요청") || question.contains("접근 요청");
        if (accessApplication) {
            return TicketType.ACCESS_REQUEST;
        }
        if (question.contains("장애") || question.contains("실패") || question.contains("접속")) {
            return TicketType.INCIDENT;
        }
        if (question.contains("계정") || question.contains("로그인")) {
            return TicketType.ACCOUNT_SUPPORT;
        }
        return TicketType.ACCESS_REQUEST;
    }

    private String ticketTarget(String question) {
        if (question.toUpperCase(java.util.Locale.ROOT).contains("VPN")) return "VPN";
        return ticketType(question).name();
    }

    private String contextualQuestion(String question, String resource) {
        if (resource != null && explicitResource(question) == null) {
            return "대화에서 지칭한 리소스는 %s입니다. 사용자 질문: %s".formatted(resource, question);
        }
        return question;
    }

    private List<String> findSources(String question) {
        try {
            Set<String> sources = new LinkedHashSet<>();
            vectorStore.similaritySearch(SearchRequest.builder()
                            .query(question).topK(5)
                            .similarityThreshold(AgentConfiguration.DEV_DESK_RAG_SIMILARITY_THRESHOLD)
                            .build())
                    .forEach(document -> {
                        Object source = document.getMetadata().get("source");
                        if (source != null) sources.add(source.toString());
                    });
            return List.copyOf(sources);
        } catch (RuntimeException error) {
            log.warn("RAG source 조회 실패; source 목록을 비워 반환합니다.");
            return List.of();
        }
    }

    private void remember(String conversationId, String question, String answer) {
        memory.add(conversationId, List.of(new UserMessage(question), new AssistantMessage(answer)));
    }

    private ToolContext toolContext(String userId) {
        return new ToolContext(Map.of("userId", userId));
    }

    private String conversationId(String userId, String sessionId) {
        return userId + ":" + sessionId;
    }

    public record ChatResult(String answer, String sessionId, List<String> sources,
                             boolean toolUsed, List<ToolCallInfo> toolCalls, boolean fallbackUsed) {}
}
