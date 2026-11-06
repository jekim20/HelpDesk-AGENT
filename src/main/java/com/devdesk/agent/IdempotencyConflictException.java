package com.devdesk.agent;

import java.util.List;

import com.devdesk.agent.ToolInvocationTracker.ToolCallInfo;

public class IdempotencyConflictException extends RuntimeException {

    private final List<ToolCallInfo> toolCalls;

    public IdempotencyConflictException() {
        this(List.of());
    }

    public IdempotencyConflictException(List<ToolCallInfo> toolCalls) {
        super("Idempotency key was already used for a different ticket request.");
        this.toolCalls = List.copyOf(toolCalls);
    }

    public List<ToolCallInfo> toolCalls() {
        return toolCalls;
    }
}
