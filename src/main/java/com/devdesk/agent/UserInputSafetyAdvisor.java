package com.devdesk.agent;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Flux;

/** 현재 턴의 사용자 입력만 검사하는 Safety Advisor. */
@Component
public class UserInputSafetyAdvisor implements CallAdvisor, StreamAdvisor {

    static final String SENSITIVE_RESPONSE = "죄송합니다. 민감정보가 포함된 요청은 처리할 수 없습니다.";
    static final String SECURITY_RESPONSE = "보안 정책상 해당 요청은 처리할 수 없습니다.";
    static final String OTHER_USER_RESPONSE = "보안 정책상 본인 외 다른 사용자의 권한 정보는 조회할 수 없습니다.";

    private static final Pattern PERSONAL_SECRET = Pattern.compile(
            "(?iu)(?:(?:내|제|나의|my)\\s*(?:비밀번호|패스워드|password|otp|일회용\\s*비밀번호)"
                    + "\\s*(?:은|는|이|가|is|[:=])"
                    + "|(?:비밀번호|패스워드|password|otp|일회용\\s*비밀번호)\\s*[:=])"
                    + "\\s*\\S+");
    private static final Pattern API_KEY_VALUE = Pattern.compile(
            "(?iu)(?:\\bsk-(?:proj-)?[a-z0-9_-]{12,}\\b"
                    + "|(?:api[-_ ]?key|apikey)\\s*(?:은|는|이|가|[:=])\\s*\\S{8,})");
    private static final List<String> INJECTION = List.of(
            "이전 지시를 모두 무시", "이전 지시 무시", "시스템 프롬프트", "system prompt");
    private static final List<String> OTHER_USER = List.of(
            "다른 사용자", "타 사용자", "다른 사람의", "타인의");

    /** null이면 허용, 문자열이면 해당 안전 응답으로 차단한다. */
    public String rejectionFor(String userText) {
        if (userText == null || userText.isBlank()) {
            return null;
        }
        if (PERSONAL_SECRET.matcher(userText).find() || API_KEY_VALUE.matcher(userText).find()) {
            return SENSITIVE_RESPONSE;
        }
        String normalized = userText.toLowerCase(Locale.ROOT);
        if (INJECTION.stream().anyMatch(normalized::contains)) {
            return SECURITY_RESPONSE;
        }
        if (OTHER_USER.stream().anyMatch(normalized::contains)) {
            return OTHER_USER_RESPONSE;
        }
        return null;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        String rejection = rejectionFor(request.prompt().getUserMessage().getText());
        return rejection == null ? chain.nextCall(request) : failureResponse(request, rejection);
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        String rejection = rejectionFor(request.prompt().getUserMessage().getText());
        return rejection == null ? chain.nextStream(request) : Flux.just(failureResponse(request, rejection));
    }

    private ChatClientResponse failureResponse(ChatClientRequest request, String response) {
        return ChatClientResponse.builder()
                .chatResponse(ChatResponse.builder()
                        .generations(List.of(new Generation(new AssistantMessage(response))))
                        .build())
                .context(Map.copyOf(request.context()))
                .build();
    }

    @Override
    public String getName() {
        return "userInputSafety";
    }

    @Override
    public int getOrder() {
        return 100;
    }
}
