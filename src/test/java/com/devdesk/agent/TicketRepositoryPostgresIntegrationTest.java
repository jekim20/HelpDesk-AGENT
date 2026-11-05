package com.devdesk.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/** Run only against a dedicated PostgreSQL test database configured with TICKET_IT_DB_URL. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("postgres")
@EnabledIfEnvironmentVariable(named = "TICKET_IT_DB_URL", matches = "jdbc:postgresql:.*")
class TicketRepositoryPostgresIntegrationTest {

    private static final String USER1_PASSWORD = UUID.randomUUID().toString();
    private static final String USER2_PASSWORD = UUID.randomUUID().toString();
    private static final String ADMIN_PASSWORD = UUID.randomUUID().toString();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("TICKET_IT_DB_URL"));
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("TICKET_IT_DB_USERNAME", "helpdesk"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("TICKET_IT_DB_PASSWORD", ""));
        registry.add("app.security.user1-password", () -> USER1_PASSWORD);
        registry.add("app.security.user2-password", () -> USER2_PASSWORD);
        registry.add("app.security.admin-password", () -> ADMIN_PASSWORD);
    }

    @Autowired TicketRepository ticketRepository;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;

    @Test
    void PostgreSQL_저장과_새_Repository_인스턴스에서_재조회가_된다() {
        String reason = "postgres persistence fixture " + UUID.randomUUID();
        TicketRepository.Ticket created = ticketRepository.create(
                TicketRepository.TicketType.ACCESS_REQUEST, "DEV_DB", reason, "user1");

        TicketRepository restartedRepository = new JdbcTicketRepository(jdbcTemplate);
        assertThat(restartedRepository.findAll())
                .anySatisfy(ticket -> {
                    assertThat(ticket.id()).isEqualTo(created.id());
                    assertThat(ticket.requestedBy()).isEqualTo("user1");
                    assertThat(ticket.reason()).isEqualTo(reason);
                    assertThat(ticket.status()).isEqualTo("PENDING");
                });
    }

    @Test
    void 동시_생성에서_DB_시퀀스가_고유_ID를_발급한다() throws Exception {
        String marker = "postgres concurrent fixture " + UUID.randomUUID();
        int requestCount = 24;
        try (var executor = Executors.newFixedThreadPool(8)) {
            List<Callable<TicketRepository.Ticket>> calls = java.util.stream.IntStream.range(0, requestCount)
                    .<Callable<TicketRepository.Ticket>>mapToObj(i -> () -> ticketRepository.create(
                            TicketRepository.TicketType.INCIDENT, "VPN", marker + " " + i, "user1"))
                    .toList();
            var created = executor.invokeAll(calls).stream().map(future -> {
                try { return future.get(); }
                catch (Exception error) { throw new RuntimeException(error); }
            }).toList();
            assertThat(created).extracting(TicketRepository.Ticket::id).doesNotHaveDuplicates();
        }
        assertThat(ticketRepository.findAll()).filteredOn(ticket -> ticket.reason().startsWith(marker))
                .hasSize(requestCount);
    }

    @Test
    void DB_제약조건_위반은_예외이며_Tool은_접수성공으로_응답하지_않는다() {
        String reason = "postgres constraint fixture " + UUID.randomUUID();
        assertThatThrownBy(() -> ticketRepository.create(null, "DEV_DB", reason, "user1"))
                .isInstanceOf(DataIntegrityViolationException.class);

        String result = new TicketTools(ticketRepository, new ToolInvocationTracker()).createTicket(
                null, "DEV_DB", reason, new org.springframework.ai.chat.model.ToolContext(Map.of("userId", "user1")));
        assertThat(result).contains("접수하지 못했습니다").doesNotContain("접수했습니다");
        assertThat(ticketRepository.findAll()).noneMatch(ticket -> ticket.reason().equals(reason));
    }

    @Test
    void 인증된_USER의_Ticket이_PG에_저장되고_ADMIN만_전체조회한다() throws Exception {
        MockHttpSession user = login("user1", USER1_PASSWORD);
        String sessionId = "pg-it-" + UUID.randomUUID();
        String reason = "PostgreSQL auth fixture " + UUID.randomUUID();
        mvc.perform(post("/api/chat").session(user).with(csrf())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(new ChatController.ChatRequest(
                                "DEV_DB 권한을 신청해줘. " + reason, sessionId, null, false))))
                .andExpect(status().isOk());

        TicketRepository.Ticket created = ticketRepository.findAll().stream()
                .filter(ticket -> ticket.reason().contains(reason))
                .findFirst().orElseThrow();
        assertThat(created.requestedBy()).isEqualTo("user1");
        mvc.perform(get("/api/admin/tickets").session(user)).andExpect(status().isForbidden());

        MockHttpSession admin = login("admin", ADMIN_PASSWORD);
        mvc.perform(get("/api/admin/tickets").session(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '%s')]".formatted(created.id())).exists());
    }

    private MockHttpSession login(String username, String password) throws Exception {
        var result = mvc.perform(formLogin().user(username).password(password))
                .andExpect(status().isNoContent()).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }
}
