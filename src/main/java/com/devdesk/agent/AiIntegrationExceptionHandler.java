package com.devdesk.agent;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class AiIntegrationExceptionHandler {

    @ExceptionHandler(AiIntegrationException.class)
    ResponseEntity<ApiError> handleAiIntegration(AiIntegrationException exception) {
        AiIntegrationException.Kind kind = exception.kind();
        return ResponseEntity.status(kind.status())
                .body(new ApiError(kind.code(), kind.publicMessage(), kind.retryable()));
    }

    public record ApiError(String code, String message, boolean retryable) {}
}
