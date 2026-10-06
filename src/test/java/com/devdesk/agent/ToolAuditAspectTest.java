package com.devdesk.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

import com.devdesk.agent.TicketRepository.TicketType;
import com.devdesk.agent.ToolInvocationTracker.ToolCallInfo;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class ToolAuditAspectTest {

    private ToolInvocationTracker tracker;
    private SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() {
        tracker = new ToolInvocationTracker();
        registry = new SimpleMeterRegistry();
        tracker.begin();
    }

    @AfterEach
    void tearDown() {
        tracker.clear();
        registry.close();
    }

    @Test
    void Access_Tool_성공정보를_기록한다() {
        AccessTools tools = proxy(new AccessTools(new AccessRepository(), tracker));

        String result = tools.getAccessStatus("DEV_DB", context("user1"));

        assertThat(tracker.snapshot()).singleElement().satisfies(call -> {
            assertThat(call.toolName()).isEqualTo("getAccessStatus");
            assertThat(call.toolArguments()).containsExactly(Map.entry("resource", "DEV_DB"));
            assertThat(call.toolResult()).isEqualTo(result);
            assertThat(call.success()).isTrue();
            assertThat(call.latencyMs()).isGreaterThanOrEqualTo(0);
        });
        assertThat(registry.get("ai.tool.calls")
                .tags("tool", "getAccessStatus", "result", "success").counter().count())
                .isEqualTo(1);
        assertThat(registry.get("ai.tool.latency")
                .tags("tool", "getAccessStatus", "result", "success").timer().count())
                .isEqualTo(1);
    }

    @Test
    void Ticket_Tool은_type과_resource만_기록한다() {
        TicketTools tools = proxy(new TicketTools(new TicketRepository(), tracker));

        tools.createTicket(TicketType.ACCESS_REQUEST, "DEV_DB",
                "person@example.com의 신규 프로젝트", context("user1"));

        assertThat(tracker.snapshot()).singleElement().satisfies(call -> {
            assertThat(call.toolName()).isEqualTo("createTicket");
            assertThat(call.toolArguments()).containsExactly(
                    Map.entry("type", "ACCESS_REQUEST"), Map.entry("resource", "DEV_DB"));
            assertThat(call.toolArguments()).doesNotContainKeys("reason", "userId", "context");
            assertThat(call.toolArguments().toString())
                    .doesNotContain("person@example.com", "user1");
            assertThat(call.toolResult()).contains("PENDING");
            assertThat(call.success()).isTrue();
        });
    }

    @Test
    void Access_Repository_실패를_업무실패로_기록한다() {
        AccessRepository repository = mock(AccessRepository.class);
        when(repository.findStatus("user1", "DEV_DB"))
                .thenThrow(new RuntimeException("password=repository-secret"));
        AccessTools tools = proxy(new AccessTools(repository, tracker));

        tools.getAccessStatus("DEV_DB", context("user1"));

        assertThat(tracker.snapshot()).singleElement().satisfies(call -> {
            assertThat(call.success()).isFalse();
            assertThat(call.toolResult()).contains("조회할 수 없습니다")
                    .doesNotContain("repository-secret");
        });
    }

    @Test
    void Ticket_Repository_실패를_업무실패로_기록한다() {
        TicketRepository repository = mock(TicketRepository.class);
        when(repository.create(any(), any(), any(), any()))
                .thenThrow(new RuntimeException("api-key=ticket-secret"));
        TicketTools tools = proxy(new TicketTools(repository, tracker));

        tools.createTicket(TicketType.INCIDENT, "VPN", "접속 실패", context("user1"));

        assertThat(tracker.snapshot()).singleElement().satisfies(call -> {
            assertThat(call.success()).isFalse();
            assertThat(call.toolResult()).contains("접수하지 못했습니다")
                    .doesNotContain("ticket-secret");
        });
    }

    @Test
    void 지원하지_않는_resource를_업무실패로_기록한다() {
        AccessTools tools = proxy(new AccessTools(new AccessRepository(), tracker));

        tools.getAccessStatus("PRINTER", context("user1"));

        assertThat(tracker.snapshot()).singleElement().satisfies(call -> {
            assertThat(call.toolArguments()).containsEntry("resource", "PRINTER");
            assertThat(call.success()).isFalse();
        });
    }

    @Test
    void 여러_Tool_호출순서를_보존한다() {
        AccessTools accessTools = proxy(new AccessTools(new AccessRepository(), tracker));
        TicketTools ticketTools = proxy(new TicketTools(new TicketRepository(), tracker));

        accessTools.getAccessStatus("DEV_DB", context("user1"));
        ticketTools.createTicket(TicketType.ACCESS_REQUEST, "DEV_DB", "신규 프로젝트", context("user1"));

        assertThat(tracker.snapshot()).extracting(ToolCallInfo::toolName)
                .containsExactly("getAccessStatus", "createTicket");
    }

    @Test
    void clear_후_다음_요청으로_호출정보가_누수되지_않는다() {
        AccessTools tools = proxy(new AccessTools(new AccessRepository(), tracker));
        tools.getAccessStatus("DEV_DB", context("user1"));
        assertThat(tracker.wasInvoked()).isTrue();

        tracker.clear();
        tracker.begin();

        assertThat(tracker.wasInvoked()).isFalse();
        assertThat(tracker.snapshot()).isEmpty();
    }

    @Test
    void argument와_result의_민감정보를_마스킹한다() {
        AccessTools accessTools = proxy(new AccessTools(new AccessRepository(), tracker));
        accessTools.getAccessStatus("VPN password=argument-secret", context("user1"));

        SensitiveResultTool resultTool = proxy(new SensitiveResultTool());
        resultTool.exposeSensitiveResult();

        assertThat(tracker.snapshot()).hasSize(2);
        assertThat(tracker.snapshot().get(0).toolArguments().toString())
                .contains("password=***")
                .doesNotContain("argument-secret");
        assertThat(tracker.snapshot().get(1).toolResult())
                .contains("password=***", "***@***", "sk-***")
                .doesNotContain("result-secret", "person@example.com", "sk-sensitive-key");
    }

    private ToolContext context(String userId) {
        return new ToolContext(Map.of("userId", userId));
    }

    @SuppressWarnings("unchecked")
    private <T> T proxy(T target) {
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAspect(new ToolAuditAspect(registry, tracker));
        return (T) factory.getProxy();
    }

    static class SensitiveResultTool {

        @Tool(description = "마스킹 테스트용 Tool")
        public String exposeSensitiveResult() {
            return "password=result-secret person@example.com sk-sensitive-key";
        }
    }
}
