package com.devdesk.agent;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.stereotype.Component;

import com.devdesk.agent.ToolInvocationTracker.ToolCallInfo;

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
        String user = findUser(joinPoint.getArgs());
        Map<String, Object> arguments = allowedArguments(tool, joinPoint.getArgs());
        long started = System.nanoTime();
        invocationTracker.beginInvocation();
        try {
            Object result = joinPoint.proceed();
            long elapsedNanos = System.nanoTime() - started;
            boolean success = invocationTracker.currentInvocationSucceeded();
            String sanitizedResult = sanitizeResult(result);
            record(tool, arguments, sanitizedResult, success, elapsedNanos);
            audit.info("[AUDIT] user={} tool={} args={} result={} latencyMs={}",
                    user, tool, arguments, success ? "SUCCESS" : "FAILURE",
                    TimeUnit.NANOSECONDS.toMillis(elapsedNanos));
            return result;
        } catch (Throwable error) {
            long elapsedNanos = System.nanoTime() - started;
            record(tool, arguments, null, false, elapsedNanos);
            audit.error("[AUDIT] user={} tool={} args={} result=FAILURE latencyMs={}",
                    user, tool, arguments, TimeUnit.NANOSECONDS.toMillis(elapsedNanos));
            throw error;
        } finally {
            invocationTracker.endInvocation();
        }
    }

    private void record(String tool, Map<String, Object> arguments, String result,
                        boolean success, long elapsedNanos) {
        String outcome = success ? "success" : "failure";
        registry.counter("ai.tool.calls", "tool", tool, "result", outcome).increment();
        registry.timer("ai.tool.latency", "tool", tool, "result", outcome)
                .record(elapsedNanos, TimeUnit.NANOSECONDS);
        invocationTracker.record(new ToolCallInfo(
                tool, arguments, result, success, TimeUnit.NANOSECONDS.toMillis(elapsedNanos)));
    }

    private Map<String, Object> allowedArguments(String tool, Object[] args) {
        Map<String, Object> allowed = new LinkedHashMap<>();
        if ("getAccessStatus".equals(tool) && args.length > 0) {
            allowed.put("resource", sanitizeValue(args[0]));
        } else if ("createTicket".equals(tool) && args.length > 1) {
            allowed.put("type", sanitizeValue(args[0]));
            allowed.put("resource", sanitizeValue(args[1]));
        }
        return allowed;
    }

    private Object sanitizeValue(Object value) {
        return value == null ? null : mask(value.toString());
    }

    private String sanitizeResult(Object result) {
        return result == null ? null : mask(result.toString());
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
