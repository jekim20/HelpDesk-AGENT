package com.devdesk.agent;

import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class ModelFallbackExecutor {

    private static final Logger log = LoggerFactory.getLogger(ModelFallbackExecutor.class);

    public ExecutionResult execute(Supplier<String> primary, Supplier<String> fallback) {
        try {
            return new ExecutionResult(primary.get(), false);
        } catch (RuntimeException primaryFailure) {
            log.warn("Primary model 호출 실패; fallback model로 전환합니다.");
            return new ExecutionResult(fallback.get(), true);
        }
    }

    public record ExecutionResult(String answer, boolean fallbackUsed) {}
}
