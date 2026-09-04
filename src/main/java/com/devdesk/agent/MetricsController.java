package com.devdesk.agent;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.search.Search;

@RestController
@RequestMapping("/api/metrics")
public class MetricsController {

    private final MeterRegistry registry;

    public MetricsController(MeterRegistry registry) {
        this.registry = registry;
    }

    @GetMapping
    public Map<String, Object> metrics() {
        var latency = Search.in(registry).name("ai.latency").timer();
        return Map.of(
                "promptTokens", counter("ai.tokens", "type", "prompt"),
                "completionTokens", counter("ai.tokens", "type", "completion"),
                "calls", latency == null ? 0L : latency.count());
    }

    private double counter(String name, String tagKey, String tagValue) {
        var counter = Search.in(registry).name(name).tag(tagKey, tagValue).counter();
        return counter == null ? 0d : counter.count();
    }
}
