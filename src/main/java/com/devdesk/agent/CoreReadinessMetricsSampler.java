package com.devdesk.agent;

import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.Status;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/** Samples core readiness, including the database, on a bounded interval in the optional profile. */
@Component
@Profile("monitoring")
public class CoreReadinessMetricsSampler {

    private final HealthEndpoint healthEndpoint;
    private final AtomicInteger coreReady = new AtomicInteger();

    public CoreReadinessMetricsSampler(HealthEndpoint healthEndpoint, MeterRegistry registry) {
        this.healthEndpoint = healthEndpoint;
        Gauge.builder("helpdesk.core.readiness", coreReady, AtomicInteger::get)
                .description("1 when the core readiness group, including PostgreSQL, is UP")
                .register(registry);
        sample();
    }

    @Scheduled(fixedDelayString = "${app.monitoring.readiness-sample-interval-ms:5000}")
    public void sample() {
        try {
            coreReady.set(Status.UP.equals(healthEndpoint.healthForPath("readiness").getStatus()) ? 1 : 0);
        } catch (RuntimeException failure) {
            coreReady.set(0);
        }
    }
}
