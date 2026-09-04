package com.devdesk.agent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ModelFallbackExecutorTest {

    @Test
    void primary_실패시_fallback을_한번_사용한다() {
        var result = new ModelFallbackExecutor().execute(
                () -> { throw new IllegalStateException("primary unavailable"); },
                () -> "fallback response");

        assertThat(result.answer()).isEqualTo("fallback response");
        assertThat(result.fallbackUsed()).isTrue();
    }

    @Test
    void primary_성공시_fallback을_사용하지_않는다() {
        var result = new ModelFallbackExecutor().execute(
                () -> "primary response",
                () -> { throw new AssertionError("must not be called"); });

        assertThat(result.answer()).isEqualTo("primary response");
        assertThat(result.fallbackUsed()).isFalse();
    }
}
