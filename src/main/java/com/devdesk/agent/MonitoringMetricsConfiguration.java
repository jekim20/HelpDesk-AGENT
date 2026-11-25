package com.devdesk.agent;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

import io.micrometer.core.instrument.MeterRegistry;

/** Optional-profile readiness gauges. Labels are bounded state names only. */
@Configuration
@Profile("monitoring")
@EnableScheduling
public class MonitoringMetricsConfiguration {

    MonitoringMetricsConfiguration(MeterRegistry registry, RagIndexReadiness ragIndexReadiness) {
        for (RagIndexReadiness.State state : RagIndexReadiness.State.values()) {
            io.micrometer.core.instrument.Gauge.builder("helpdesk.rag.readiness", ragIndexReadiness,
                            readiness -> readiness.state() == state ? 1 : 0)
                    .tag("state", state.name().toLowerCase(java.util.Locale.ROOT))
                    .description("One-hot AI/RAG readiness state")
                    .register(registry);
        }
    }
}
