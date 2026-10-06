package com.devdesk.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

/** 한 API 요청 안에서 실행된 Tool 호출 정보를 순서대로 추적한다. */
@Component
public class ToolInvocationTracker {

    private final ThreadLocal<List<ToolCallInfo>> calls = ThreadLocal.withInitial(ArrayList::new);
    private final ThreadLocal<Boolean> currentInvocationSuccess = new ThreadLocal<>();

    public void begin() {
        calls.set(new ArrayList<>());
        currentInvocationSuccess.remove();
    }

    void beginInvocation() {
        currentInvocationSuccess.set(true);
    }

    /** Tool이 예외를 사용자용 실패 응답으로 변환한 경우 업무 실패임을 표시한다. */
    public void markFailed() {
        if (currentInvocationSuccess.get() != null) {
            currentInvocationSuccess.set(false);
        }
    }

    boolean currentInvocationSucceeded() {
        return !Boolean.FALSE.equals(currentInvocationSuccess.get());
    }

    void record(ToolCallInfo call) {
        calls.get().add(call);
    }

    void endInvocation() {
        currentInvocationSuccess.remove();
    }

    public boolean wasInvoked() {
        return !calls.get().isEmpty();
    }

    public List<ToolCallInfo> snapshot() {
        return List.copyOf(calls.get());
    }

    public void clear() {
        calls.remove();
        currentInvocationSuccess.remove();
    }

    public record ToolCallInfo(String toolName, Map<String, Object> toolArguments,
                               String toolResult, boolean success, long latencyMs) {

        public ToolCallInfo {
            toolArguments = Collections.unmodifiableMap(new LinkedHashMap<>(toolArguments));
        }
    }
}
