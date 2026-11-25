package com.devdesk.agent;

import org.springframework.http.HttpStatus;

/** Safe application-level description of an AI provider or policy-index failure. */
public final class AiIntegrationException extends RuntimeException {

    public enum Kind {
        NOT_CONFIGURED(HttpStatus.SERVICE_UNAVAILABLE, "AI_NOT_CONFIGURED",
                "AI 기능이 설정되지 않았습니다. 관리자에게 문의해 주세요."),
        AUTHENTICATION(HttpStatus.BAD_GATEWAY, "AI_AUTHENTICATION_FAILED",
                "AI 서비스 인증에 실패했습니다. 관리자에게 문의해 주세요."),
        TEMPORARY(HttpStatus.SERVICE_UNAVAILABLE, "AI_TEMPORARILY_UNAVAILABLE",
                "AI 서비스가 일시적으로 응답하지 않습니다. 잠시 후 다시 시도해 주세요.", true),
        POLICY_INDEX(HttpStatus.SERVICE_UNAVAILABLE, "POLICY_INDEX_UNAVAILABLE",
                "정책 검색을 준비하지 못했습니다. 잠시 후 다시 시도해 주세요."),
        PROVIDER(HttpStatus.BAD_GATEWAY, "AI_REQUEST_FAILED",
                "AI 요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.");

        private final HttpStatus status;
        private final String code;
        private final String publicMessage;
        private final boolean retryable;

        Kind(HttpStatus status, String code, String publicMessage) {
            this(status, code, publicMessage, false);
        }

        Kind(HttpStatus status, String code, String publicMessage, boolean retryable) {
            this.status = status;
            this.code = code;
            this.publicMessage = publicMessage;
            this.retryable = retryable;
        }

        public HttpStatus status() { return status; }
        public String code() { return code; }
        public String publicMessage() { return publicMessage; }
        public boolean retryable() { return retryable; }
    }

    private final Kind kind;

    public AiIntegrationException(Kind kind) {
        super(kind.name());
        this.kind = kind;
    }

    public AiIntegrationException(Kind kind, Throwable cause) {
        // Keep the original provider message out of the exception's own message and API response.
        super(kind.name(), cause);
        this.kind = kind;
    }

    public Kind kind() { return kind; }
}
