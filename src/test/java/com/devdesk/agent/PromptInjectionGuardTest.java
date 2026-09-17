package com.devdesk.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class PromptInjectionGuardTest {

    private final PromptInjectionGuard guard = new PromptInjectionGuard();

    @ParameterizedTest(name = "{0}")
    @MethodSource("attackCases")
    void 공격_질문은_차단한다(String id, String prompt) {
        assertThat(guard.isSuspicious(prompt)).as(id).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "반품 정책을 알려주세요.",
            "휴가 신청 방법을 알려주세요.",
            "Gold 등급 혜택이 무엇인가요?",
            "로그인 오류 해결 방법을 알려주세요.",
            "내부 문서 검색 방법을 알려주세요.",
            "VPN 승인에는 얼마나 걸려?"
    })
    void 정상_질문은_오탐하지_않는다(String prompt) {
        assertThat(guard.isSuspicious(prompt)).isFalse();
    }

    @Test
    void 빈_입력은_다른_검증에_맡긴다() {
        assertThat(guard.isSuspicious(null)).isFalse();
        assertThat(guard.isSuspicious("")).isFalse();
    }

    static Stream<org.junit.jupiter.params.provider.Arguments> attackCases() throws IOException {
        try (InputStream stream = PromptInjectionGuardTest.class.getResourceAsStream(
                "/security/prompt_injection_cases.json")) {
            assertThat(stream).isNotNull();
            JsonNode cases = new ObjectMapper().readTree(stream);
            assertThat(cases).hasSize(18);
            return cases.valueStream().map(testCase -> {
                assertThat(testCase.path("expected").asText()).isEqualTo("BLOCK");
                return org.junit.jupiter.params.provider.Arguments.of(
                        testCase.path("id").asText(), testCase.path("prompt").asText());
            });
        }
    }
}
