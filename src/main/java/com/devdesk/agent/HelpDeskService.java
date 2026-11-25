package com.devdesk.agent;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.devdesk.agent.ModelFallbackExecutor.ExecutionResult;
import com.devdesk.agent.TicketRepository.TicketType;
import com.devdesk.agent.ToolInvocationTracker.ToolCallInfo;

@Service
public class HelpDeskService {

    private static final Logger log = LoggerFactory.getLogger(HelpDeskService.class);
    private static final int MAX_INPUT_LENGTH = 4000;
    private static final Pattern EXPLICIT_ACCESS_REQUEST = Pattern.compile(
            "(?iu)(?:권한|접근)"
                    + "[^.!?。！？\\n]{0,24}?"
                    + "(?:신청|요청)(?:을|를)?\\s*"
                    + "(?:해\\s*(?:줘|주세요|주실래요|주시겠어요)|해줘|해주세요"
                    + "|어\\s*(?:줘|주세요)|어줘|어주세요|부탁해(?:요)?|부탁드립니다|해주십시오)");
    private static final Pattern ACCESS_DOMAIN = Pattern.compile("(?iu)(?:권한|접근)");
    private static final Pattern EXPLICIT_ACCESS_ACTION = Pattern.compile(
            "(?iu)(?:신청|요청)(?:을|를)?\\s*"
                    + "(?:해\\s*(?:줘|주세요|주실래요|주시겠어요)|해줘|해주세요"
                    + "|어\\s*(?:줘|주세요)|어줘|어주세요|부탁해(?:요)?|부탁드립니다|해주십시오)");
    private static final Pattern EXPLICIT_TICKET_OPERATION = Pattern.compile(
            "(?iu)(?:티켓|장애|지원\\s*(?:요청|티켓))"
                    + "[^.!?。！？\\n]{0,24}?"
                    + "(?:신청|요청|접수|생성|만들|등록)(?:을|를)?\\s*"
                    + "(?:해\\s*(?:줘|주세요|주실래요|주시겠어요)|해줘|해주세요"
                    + "|어\\s*(?:줘|주세요)|어줘|어주세요|부탁해(?:요)?|부탁드립니다|해주십시오)");
    private static final Pattern NON_EXECUTION_CONTEXT = Pattern.compile(
            "(?iu)(?:하지\\s*(?:마|말|않)|(?:신청|접수|생성|등록|만들)지\\s*마"
                    + "|말고|절차|방법|어떻게|무엇|뭐|뜻|의미|설명|알려|가이드|안내문"
                    + "|문서|정책|신청서|요청서|답변|메일|문구|예시|템플릿|양식|보고서|요약|초안|공지"
                    + "|가능"
                    + "|할\\s*수|해야|만약|가정|예를\\s*들|인용|번역|문장|표현|[?？])");
    private static final Pattern QUOTED_TEXT = Pattern.compile(
            "“[^”]*”|‘[^’]*’|「[^」]*」|『[^』]*』|\"[^\"]*\"|'[^']*'");
    private final ChatClient primaryClient;
    private final ChatClient fallbackClient;
    private final ChatMemory memory;
    private final AccessTools accessTools;
    private final TicketTools ticketTools;
    private final ModelFallbackExecutor fallbackExecutor;
    private final ToolInvocationTracker invocationTracker;
    private final UserInputSafetyAdvisor userInputSafety;
    private final PromptInjectionGuard promptInjectionGuard;
    private final RagIndexReadiness ragIndexReadiness;
    private final Map<String, String> conversationResources = new ConcurrentHashMap<>();

    public HelpDeskService(@Qualifier("devDeskPrimaryClient") ChatClient primaryClient,
            @Qualifier("devDeskFallbackClient") ChatClient fallbackClient,
            ChatMemory memory,
            AccessTools accessTools,
            TicketTools ticketTools,
            ModelFallbackExecutor fallbackExecutor,
            ToolInvocationTracker invocationTracker,
            UserInputSafetyAdvisor userInputSafety,
            PromptInjectionGuard promptInjectionGuard) {
        this(primaryClient, fallbackClient, memory, accessTools, ticketTools,
                fallbackExecutor, invocationTracker, userInputSafety, promptInjectionGuard,
                readyForUnitTests());
    }

    @Autowired
    public HelpDeskService(@Qualifier("devDeskPrimaryClient") ChatClient primaryClient,
            @Qualifier("devDeskFallbackClient") ChatClient fallbackClient,
            ChatMemory memory,
            AccessTools accessTools,
            TicketTools ticketTools,
            ModelFallbackExecutor fallbackExecutor,
            ToolInvocationTracker invocationTracker,
            UserInputSafetyAdvisor userInputSafety,
            PromptInjectionGuard promptInjectionGuard,
            RagIndexReadiness ragIndexReadiness) {
        this.primaryClient = primaryClient;
        this.fallbackClient = fallbackClient;
        this.memory = memory;
        this.accessTools = accessTools;
        this.ticketTools = ticketTools;
        this.fallbackExecutor = fallbackExecutor;
        this.invocationTracker = invocationTracker;
        this.userInputSafety = userInputSafety;
        this.promptInjectionGuard = promptInjectionGuard;
        this.ragIndexReadiness = ragIndexReadiness;
    }

    public ChatResult chat(String question, String sessionId, String userId, boolean simulatePrimaryFailure) {
        return chat(question, sessionId, userId, simulatePrimaryFailure, null);
    }

    public ChatResult chat(String question, String sessionId, String userId, boolean simulatePrimaryFailure,
            String idempotencyKey) {
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
            if (!IdempotencyKey.isValid(idempotencyKey)) {
                return new ChatResult("티켓 생성 요청에는 유효한 Idempotency-Key가 필요합니다.",
                        sessionId, List.of(), false, List.of(), false);
            }
            TicketType type = ticketType(question);
            String target = resource == null ? ticketTarget(question) : resource;
            invocationTracker.begin();
            try {
                String answer = ticketTools.createTicket(type, target, question,
                        toolContext(userId, IdempotencyKey.canonical(idempotencyKey)));
                remember(conversationId, question, answer);
                ToolInvocationTracker.TicketOutcome outcome = invocationTracker.ticketOutcome();
                return new ChatResult(answer, sessionId, List.of(), true,
                        invocationTracker.snapshot(), false,
                        outcome == null ? null : outcome.ticketId(),
                        outcome == null ? null : outcome.status());
            } catch (IdempotencyConflictException conflict) {
                throw new IdempotencyConflictException(invocationTracker.snapshot());
            } finally {
                invocationTracker.clear();
            }
        }

        String contextualQuestion = contextualQuestion(question, resource);
        ragIndexReadiness.requireReady();
        AtomicReference<List<String>> retrievedSources = new AtomicReference<>(List.of());
        invocationTracker.begin();
        try {
            Supplier<String> primary = () -> {
                if (simulatePrimaryFailure) {
                    throw new IllegalStateException("simulated primary model failure");
                }
                return callModel(primaryClient, contextualQuestion, conversationId, userId, retrievedSources);
            };
            Supplier<String> fallback = () -> callModel(
                    fallbackClient, contextualQuestion, conversationId, userId, retrievedSources);
            ExecutionResult result;
            try {
                result = fallbackExecutor.execute(primary, fallback);
            } catch (RuntimeException failure) {
                throw AiFailureClassifier.safeException(failure);
            }
            ToolInvocationTracker.TicketOutcome outcome = invocationTracker.ticketOutcome();
            return new ChatResult(result.answer(), sessionId, retrievedSources.get(),
                    invocationTracker.wasInvoked(), invocationTracker.snapshot(), result.fallbackUsed(),
                    outcome == null ? null : outcome.ticketId(),
                    outcome == null ? null : outcome.status());
        } finally {
            invocationTracker.clear();
        }
    }

    private static RagIndexReadiness readyForUnitTests() {
        RagIndexReadiness readiness = new RagIndexReadiness();
        readiness.markReady();
        return readiness;
    }

    public boolean requiresIdempotencyKey(String question) {
        return isTicketRequest(question);
    }

    public List<String> history(String sessionId, String userId) {
        return memory.get(conversationId(userId, sessionId)).stream().map(Message::getText).toList();
    }

    private String callModel(ChatClient client, String question, String conversationId, String userId,
            AtomicReference<List<String>> retrievedSources) {
        // A fallback invocation owns its result; never retain source metadata from a failed primary attempt.
        retrievedSources.set(List.of());
        ChatClientResponse response = client.prompt()
                .user(question)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                // The model path is read-only. Ticket writes are routed only by the server-side
                // intent gate.
                .tools(accessTools)
                .toolContext(Map.of("userId", userId))
                .call()
                .chatClientResponse();
        if (response == null || response.chatResponse() == null
                || response.chatResponse().getResult() == null
                || response.chatResponse().getResult().getOutput() == null) {
            throw new IllegalStateException("AI 모델 응답이 비어 있습니다.");
        }

        long sourceExtractionStarted = System.nanoTime();
        Object retrieved = response.context() == null
                ? null
                : response.context().get(QuestionAnswerAdvisor.RETRIEVED_DOCUMENTS);
        if (retrieved instanceof List<?> documents) {
            Set<String> sources = new LinkedHashSet<>();
            documents.stream()
                    .filter(Document.class::isInstance)
                    .map(Document.class::cast)
                    .map(document -> document.getMetadata().get("source"))
                    .filter(Objects::nonNull)
                    .map(Object::toString)
                    .forEach(sources::add);
            retrievedSources.set(List.copyOf(sources));
        }
        log.info("[PERF] rag.source.extract latencyMs={} sourceCount={}",
                (System.nanoTime() - sourceExtractionStarted) / 1_000_000.0, retrievedSources.get().size());
        return response.chatResponse().getResult().getOutput().getText();
    }

    String validate(String question, String sessionId, String userId) {
        if (question == null || question.isBlank())
            return "질문을 입력해 주세요.";
        if (question.length() > MAX_INPUT_LENGTH)
            return "입력이 너무 깁니다. 4,000자 이내로 줄여 주세요.";
        if (sessionId == null || sessionId.isBlank() || userId == null || userId.isBlank()) {
            return "sessionId와 userId는 필수입니다.";
        }
        if (promptInjectionGuard.isSuspicious(question))
            return PromptInjectionGuard.BLOCK_RESPONSE;
        return userInputSafety.rejectionFor(question);
    }

    private String explicitResource(String question) {
        String normalized = question.toUpperCase(java.util.Locale.ROOT);
        if (normalized.contains("PROD_DB") || question.contains("운영 DB"))
            return "PROD_DB";
        if (normalized.contains("DEV_DB") || question.contains("개발 DB"))
            return "DEV_DB";
        if (normalized.contains("VPN"))
            return "VPN";
        return null;
    }

    private boolean isContextualReference(String question) {
        return question.contains("그 권한") || question.contains("해당 권한") || question.contains("그 리소스");
    }

    private boolean isAccessLookup(String question, String resource) {
        return resource != null && question.contains("권한") && !question.contains("정책")
                && (question.contains("상태") || question.contains("조회") || question.contains("확인")
                        || question.contains("알려"))
                && !isTicketRequest(question);
    }

    private boolean isUnsupportedAccessLookup(String question, String resource) {
        return resource == null && question.contains("권한")
                && (question.contains("상태") || question.contains("조회") || question.contains("확인"));
    }

    private boolean isTicketRequest(String question) {
        if (question == null || question.isBlank() || NON_EXECUTION_CONTEXT.matcher(question).find()) {
            return false;
        }
        String unquoted = QUOTED_TEXT.matcher(question).replaceAll(" ");
        return EXPLICIT_ACCESS_REQUEST.matcher(unquoted).find()
                || EXPLICIT_TICKET_OPERATION.matcher(unquoted).find()
                || (ACCESS_DOMAIN.matcher(unquoted).find()
                        && EXPLICIT_ACCESS_ACTION.matcher(unquoted).find());
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
        if (question.toUpperCase(java.util.Locale.ROOT).contains("VPN"))
            return "VPN";
        return ticketType(question).name();
    }

    private String contextualQuestion(String question, String resource) {
        if (resource != null && explicitResource(question) == null) {
            return "대화에서 지칭한 리소스는 %s입니다. 사용자 질문: %s".formatted(resource, question);
        }
        return question;
    }

    private void remember(String conversationId, String question, String answer) {
        memory.add(conversationId, List.of(new UserMessage(question), new AssistantMessage(answer)));
    }

    private ToolContext toolContext(String userId) {
        return new ToolContext(Map.of("userId", userId));
    }

    private ToolContext toolContext(String userId, String idempotencyKey) {
        return new ToolContext(Map.of("userId", userId, "idempotencyKey", idempotencyKey));
    }

    private String conversationId(String userId, String sessionId) {
        return userId + ":" + sessionId;
    }

    public record ChatResult(String answer, String sessionId, List<String> sources,
            boolean toolUsed, List<ToolCallInfo> toolCalls, boolean fallbackUsed,
            String ticketId, String ticketStatus) {
        public ChatResult(String answer, String sessionId, List<String> sources,
                boolean toolUsed, List<ToolCallInfo> toolCalls, boolean fallbackUsed) {
            this(answer, sessionId, sources, toolUsed, toolCalls, fallbackUsed, null, null);
        }
    }
}
