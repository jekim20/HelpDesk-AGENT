package com.devdesk.agent;

import java.text.Normalizer;
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

    private static final String PERSONAL_CREDENTIAL = "비밀번호|패스워드|일회용\\s*비밀번호|password|otp";
    private static final String OTHER_CREDENTIAL =
            "암호|인증\\s*코드|(?:액세스|접근|갱신|리프레시|인증)?\\s*토큰"
                    + "|\\b(?:access[ _-]+|refresh[ _-]+|auth[ _-]+)?token\\b";
    private static final Pattern EXPLICIT_CREDENTIAL_VALUE = Pattern.compile(
            "(?iu)(?:" + PERSONAL_CREDENTIAL + "|" + OTHER_CREDENTIAL + ")[\"']?\\s*[:=]\\s*\\S"
                    + "|\\bauthorization[\"']?\\s*[:=]\\s*[\"']?bearer\\s+\\S");
    private static final Pattern CREDENTIAL_STATEMENT = Pattern.compile(
            "(?iu)(?:(?:" + PERSONAL_CREDENTIAL + "|" + OTHER_CREDENTIAL + ")\\s*[은는이가]"
                    + "|(?:내|제|나의|my)\\s*(?:" + PERSONAL_CREDENTIAL + ")\\s*is"
                    + "|(?:" + OTHER_CREDENTIAL + ")\\s*\\bis\\b)"
                    + "\\s*(?=\\S)");
    // Also catch value-before-particle forms such as "인증 코드 123456은 ...".
    private static final Pattern CREDENTIAL_VALUE_BEFORE_PARTICLE = Pattern.compile(
            "(?iu)(?:" + PERSONAL_CREDENTIAL + "|" + OTHER_CREDENTIAL + ")\\s+"
                    + "(?:\\d{4,}|[A-Za-z][A-Za-z0-9_-]{5,})\\s*[은는이가](?=\\s|[,.;!?]|$)");
    // Read the clause after a topic marker before treating it as a credential value.
    private static final Pattern PROCEDURE_CLAUSE = Pattern.compile(
            "(?:(?:어떻게|어디(?:서|에서)?)\\h+)?(?:재\\h*발급|발급|재설정|초기화|변경|갱신|교체|바꾸|풀|다시\\h*(?=받))"
                    + "\\h*(?:(?:(?:받|하|되)?(?:나요|는지|려면)|(?:받을|할)\\h*수\\h*있나요"
                    + "|(?:받아야|해야)\\h*하나요)"
                    + "|(?:(?:받는|하는)\\h*)?(?:방법|절차|정책|기준)(?:을|를|이|가|은|는)?"
                    + "(?:\\h+(?:있나요|뭔가요|무엇인가요|뭐예요"
                    + "|궁금(?:합니다|해요)|안내(?:해)?\\h*(?:줘|주세요)"
                    + "|알려\\h*(?:줘|주세요)|설명해\\h*(?:줘|주세요)))?)\\h*(?:[.?!]|$)");
    // A credential can be the grammatical subject of an expiry, delivery, lock, or error question.
    // Allow complete status/procedure questions, while still treating arbitrary following text as a value.
    private static final Pattern CREDENTIAL_STATUS_INQUIRY = Pattern.compile(
            "(?iu)(?:"
                    + "(?:만료|유효\\s*기간|기한|잠금|잠겨|차단|분실|오류|실패|전송|전송되지|도착|도착하지|오지|수신되지|받지\\s*못|등록)"
                    + "\\s*(?:되면|되었을\\s*때|됐을\\s*때|될\\s*때|되지\\s*않으면|않으면|하지\\s*않으면|이\\s*지나면|지나면)"
                    + "[^.!?\\r\\n]{0,60}(?:무엇|뭐|어떤|어디|언제|어떻게|확인|조치|재발급|갱신|신청|변경|요청|절차|방법)"
                    + "|(?:언제|어디(?:서|에서)?|어떻게|어떤\\s*경우(?:에)?)"
                    + "[^.!?\\r\\n]{0,50}(?:만료|유효\\s*기간|기한|잠금|차단|분실|오류|실패|전송|도착|발급|재발급|변경|갱신)"
                    + ")[^.!?\\r\\n]{0,40}(?:나요|까요|습니까|해요|해주세요|할까요|되나요|해야\\s*하나요)[.?!]?");
    // Only a complete educational question qualifies. Extra clauses retain the ordinary block.
    private static final Pattern EDUCATIONAL_QUESTION = Pattern.compile(
            "(?:시스템\\s*프롬프트\\s*(?:보안|보호)\\s*(?:정책|지침)"
                    + "|(?:다른 사용자|타 사용자)(?:라는|란)\\s*(?:표현|문구|용어)의\\s*(?:의미|뜻))"
                    + "(?:을|를)?\\s*(?:설명|알려)\\s*(?:해)?\\s*(?:줘|주세요|주십시오)[.?!]?");
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
        String credentialText = Normalizer.normalize(userText, Normalizer.Form.NFKC);
        if (EXPLICIT_CREDENTIAL_VALUE.matcher(credentialText).find()
                || CREDENTIAL_VALUE_BEFORE_PARTICLE.matcher(credentialText).find()
                || API_KEY_VALUE.matcher(credentialText).find()
                || containsCredentialStatement(credentialText)) {
            return SENSITIVE_RESPONSE;
        }
        String normalized = userText.toLowerCase(Locale.ROOT);
        if (EDUCATIONAL_QUESTION.matcher(normalized.strip()).matches()) {
            return null;
        }
        if (INJECTION.stream().anyMatch(normalized::contains)) {
            return SECURITY_RESPONSE;
        }
        if (OTHER_USER.stream().anyMatch(normalized::contains)) {
            return OTHER_USER_RESPONSE;
        }
        return null;
    }

    private boolean containsCredentialStatement(String text) {
        var statements = CREDENTIAL_STATEMENT.matcher(text);
        var procedure = PROCEDURE_CLAUSE.matcher(text);
        while (statements.find()) {
            if (!procedure.region(statements.end(), text.length()).lookingAt()
                    && !CREDENTIAL_STATUS_INQUIRY.matcher(text)
                            .region(statements.end(), text.length()).matches()) {
                return true;
            }
        }
        return false;
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
