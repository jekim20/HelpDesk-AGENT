package com.devdesk.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import com.devdesk.agent.TicketRepository.TicketType;

class DevDeskToolsTest {

    @Test
    void 본인_권한만_ToolContext로_조회한다() {
        AccessTools tools = new AccessTools(new AccessRepository(), new ToolInvocationTracker());

        assertThat(tools.getAccessStatus("DEV_DB", context("user1")))
                .contains("DEV_DB", "APPROVED")
                .doesNotContain("user2");
        assertThat(tools.getAccessStatus("PROD_DB", context("user1")))
                .contains("PROD_DB", "NOT_REQUESTED")
                .doesNotContain("APPROVED", "user2");
    }

    @Test
    void 권한_신청은_실제_권한을_바꾸지_않고_본인에게_신청상태만_안내한다() {
        TicketRepository tickets = new InMemoryTicketRepository();
        tickets.create(TicketType.ACCESS_REQUEST, "PROD_DB", "테스트 요청", "user1", UUID.randomUUID().toString());
        AccessTools tools = new AccessTools(new AccessRepository(), new ToolInvocationTracker(), tickets);

        String own = tools.getAccessStatus("PROD_DB", context("user1"));
        String other = tools.getAccessStatus("PROD_DB", context("user2"));

        assertThat(own).contains("실제 접근 권한 상태는 NOT_REQUESTED", "승인 대기", "PENDING", "HD-");
        assertThat(own).doesNotContain("APPROVED");
        assertThat(other).contains("실제 접근 권한 상태는 APPROVED")
                .doesNotContain("승인 대기", "PENDING", "HD-");
    }

    @Test
    void 지원하지_않는_권한을_임의_리소스로_바꾸지_않는다() {
        AccessTools tools = new AccessTools(new AccessRepository(), new ToolInvocationTracker());

        assertThat(tools.getAccessStatus("PRINTER", context("user1")))
                .contains("지원하지 않는")
                .doesNotContain("APPROVED", "권한 상태는", "VPN은");
    }

    @Test
    void 권한_저장소_장애시_성공상태를_추측하지_않는다() {
        AccessRepository repository = mock(AccessRepository.class);
        when(repository.findStatus("user1", "DEV_DB"))
                .thenThrow(new RuntimeException("db-host=internal.example secret-detail"));

        String result = new AccessTools(repository, new ToolInvocationTracker())
                .getAccessStatus("DEV_DB", context("user1"));

        assertThat(result)
                .contains("조회할 수 없습니다")
                .doesNotContain("APPROVED", "NOT_REQUESTED", "secret-detail", "RuntimeException");
    }

    @Test
    void 쓰기_Tool은_PENDING_티켓만_만든다() {
        TicketRepository repository = new InMemoryTicketRepository();
        TicketTools tools = new TicketTools(repository, new ToolInvocationTracker());

        String result = tools.createTicket(
                TicketType.ACCESS_REQUEST, "DEV_DB", "신규 프로젝트", context("user1"));

        assertThat(result).contains("접수했습니다", "PENDING").doesNotContain("APPROVED");
        assertThat(repository.findAll()).singleElement().satisfies(ticket -> {
            assertThat(ticket.requestedBy()).isEqualTo("user1");
            assertThat(ticket.status()).isEqualTo("PENDING");
            assertThat(ticket.resource()).isEqualTo("DEV_DB");
        });
    }

    @Test
    void 티켓_저장소_장애시_접수성공으로_응답하지_않는다() {
        TicketRepository repository = mock(TicketRepository.class);
        when(repository.create(any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("itsm-token=secret-detail"));

        String result = new TicketTools(repository, new ToolInvocationTracker()).createTicket(
                TicketType.INCIDENT, "VPN", "접속 실패", context("user1"));

        assertThat(result)
                .contains("접수하지 못했습니다")
                .doesNotContain("접수했습니다", "완료", "secret-detail", "RuntimeException");
    }

    private ToolContext context(String userId) {
        return new ToolContext(Map.of("userId", userId, "idempotencyKey", UUID.randomUUID().toString()));
    }
}
