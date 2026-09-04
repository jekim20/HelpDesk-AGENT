package com.devdesk.agent;

import java.util.Arrays;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.MeterRegistry;

@Aspect
@Component
public class ToolAuditAspect {

    private static final Logger audit = LoggerFactory.getLogger("AI_TOOL_AUDIT");
    private final MeterRegistry registry;
    private final ToolInvocationTracker invocationTracker;

    public ToolAuditAspect(MeterRegistry registry, ToolInvocationTracker invocationTracker) {
        this.registry = registry;
        this.invocationTracker = invocationTracker;
    }

    @Around("@annotation(org.springframework.ai.tool.annotation.Tool)")
    public Object audit(ProceedingJoinPoint joinPoint) throws Throwable {
        String tool = joinPoint.getSignature().getName();
        invocationTracker.markInvoked();
        String user = findUser(joinPoint.getArgs());
        String args = mask(Arrays.stream(joinPoint.getArgs())
                .filter(arg -> !(arg instanceof ToolContext))
                .toList().toString());
        try {
            Object result = joinPoint.proceed();
            registry.counter("ai.tool.calls", "tool", tool, "result", "success").increment();
            audit.info("[AUDIT] user={} tool={} args={} result=SUCCESS", user, tool, args);
            return result;
        } catch (Throwable error) {
            registry.counter("ai.tool.calls", "tool", tool, "result", "failure").increment();
            audit.error("[AUDIT] user={} tool={} args={} result=FAIL", user, tool, args);
            throw error;
        }
    }

    private String findUser(Object[] args) {
        return Arrays.stream(args)
                .filter(ToolContext.class::isInstance)
                .map(ToolContext.class::cast)
                .map(c -> c.getContext().get("userId"))
                .filter(java.util.Objects::nonNull)
                .map(Object::toString)
                .findFirst().orElse("system");
    }

    private String mask(String raw) {
        return raw.replaceAll("\\d{6}-\\d{7}", "******-*******")
                .replaceAll("\\d{4}-\\d{4}-\\d{4}-\\d{4}", "****-****-****-****")
                .replaceAll("[\\w.+-]+@[\\w-]+\\.[\\w.]+", "***@***")
                .replaceAll("sk-[A-Za-z0-9_-]+", "sk-***")
                .replaceAll("(?i)(api[-_ ]?key|password|비밀번호)\\s*[:=]?\\s*\\S+", "$1=***")
                .replaceAll("(?i)(otp)\\s*[:=]?\\s*\\d+", "$1=***");
    }
}
