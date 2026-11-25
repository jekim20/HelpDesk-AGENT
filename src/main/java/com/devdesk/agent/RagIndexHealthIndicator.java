package com.devdesk.agent;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/** Exposes only the optional RAG dependency state; service health uses the core readiness group. */
@Component("ragIndex")
public class RagIndexHealthIndicator implements HealthIndicator {

    private final RagIndexReadiness readiness;

    public RagIndexHealthIndicator(RagIndexReadiness readiness) {
        this.readiness = readiness;
    }

    @Override
    public Health health() {
        return switch (readiness.state()) {
            case READY -> Health.up().build();
            case NOT_CONFIGURED -> Health.status("NOT_CONFIGURED").build();
            case INDEXING, NOT_INITIALIZED -> Health.status("INDEXING").build();
            case UNAVAILABLE -> Health.status("UNAVAILABLE").build();
        };
    }
}
