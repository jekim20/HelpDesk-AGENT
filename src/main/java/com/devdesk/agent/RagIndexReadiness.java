package com.devdesk.agent;

import java.util.concurrent.atomic.AtomicReference;

import org.springframework.stereotype.Component;

/** Per-process readiness for the in-memory policy vector index. */
@Component
public class RagIndexReadiness {

    public enum State { NOT_INITIALIZED, NOT_CONFIGURED, INDEXING, READY, UNAVAILABLE }

    private final AtomicReference<State> state = new AtomicReference<>(State.NOT_INITIALIZED);
    private final AtomicReference<AiIntegrationException.Kind> failure = new AtomicReference<>();

    public State state() { return state.get(); }

    public void markNotConfigured() {
        failure.set(AiIntegrationException.Kind.NOT_CONFIGURED);
        state.set(State.NOT_CONFIGURED);
    }

    public void beginIndexing() {
        failure.set(null);
        state.set(State.INDEXING);
    }

    public void markReady() {
        failure.set(null);
        state.set(State.READY);
    }

    public void markUnavailable(AiIntegrationException.Kind kind) {
        failure.set(kind);
        state.set(State.UNAVAILABLE);
    }

    public void requireConfigured() {
        if (state.get() == State.NOT_CONFIGURED) {
            throw new AiIntegrationException(AiIntegrationException.Kind.NOT_CONFIGURED);
        }
    }

    public void requireReady() {
        State current = state.get();
        if (current == State.READY) return;
        if (current == State.NOT_CONFIGURED || current == State.NOT_INITIALIZED) {
            throw new AiIntegrationException(AiIntegrationException.Kind.NOT_CONFIGURED);
        }
        AiIntegrationException.Kind currentFailure = failure.get();
        if (currentFailure != null) {
            if (currentFailure == AiIntegrationException.Kind.AUTHENTICATION
                    || currentFailure == AiIntegrationException.Kind.TEMPORARY) {
                throw new AiIntegrationException(currentFailure);
            }
            throw new AiIntegrationException(AiIntegrationException.Kind.POLICY_INDEX);
        }
        throw new AiIntegrationException(AiIntegrationException.Kind.POLICY_INDEX);
    }
}
