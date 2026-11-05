package com.devdesk.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** Uses a fresh schema in a dedicated *_test database and drops only that generated schema. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("postgres")
@EnabledIfEnvironmentVariable(named = "TICKET_IT_DB_URL", matches = "jdbc:postgresql:.*")
class TicketRepositoryPostgresIntegrationTest {

    private static final String TEST_SCHEMA = "it_" + UUID.randomUUID().toString().replace("-", "");
    private static final String USER1_PASSWORD = UUID.randomUUID().toString();
    private static final String USER2_PASSWORD = UUID.randomUUID().toString();
    private static final String ADMIN_PASSWORD = UUID.randomUUID().toString();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String databaseUrl = requireDedicatedTestDatabase(System.getenv("TICKET_IT_DB_URL"));
        registry.add("spring.datasource.url", () -> databaseUrl +
                (databaseUrl.contains("?") ? "&" : "?") + "currentSchema=" + TEST_SCHEMA);
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("TICKET_IT_DB_USERNAME", "helpdesk"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("TICKET_IT_DB_PASSWORD", ""));
        registry.add("spring.flyway.schemas", () -> TEST_SCHEMA);
        registry.add("spring.flyway.default-schema", () -> TEST_SCHEMA);
        registry.add("app.security.user1-password", () -> USER1_PASSWORD);
        registry.add("app.security.user2-password", () -> USER2_PASSWORD);
        registry.add("app.security.admin-password", () -> ADMIN_PASSWORD);
    }

    @Autowired TicketRepository ticketRepository;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;

    @AfterAll
    static void dropRunSchema() throws SQLException {
        String databaseUrl = System.getenv("TICKET_IT_DB_URL");
        if (databaseUrl == null || !databaseUrl.startsWith("jdbc:postgresql:")) return;
        databaseUrl = requireDedicatedTestDatabase(databaseUrl);
        String username = System.getenv().getOrDefault("TICKET_IT_DB_USERNAME", "helpdesk");
        String password = System.getenv().getOrDefault("TICKET_IT_DB_PASSWORD", "");
        try (var connection = DriverManager.getConnection(databaseUrl, username, password);
             var statement = connection.createStatement()) {
            statement.execute("drop schema if exists " + TEST_SCHEMA + " cascade");
        }
    }

    @Test
    void 최초_생성과_같은_사용자_KEY_재전송은_기존_티켓을_반환한다() {
        String key = key();
        String reason = "same payload " + UUID.randomUUID();

        var first = ticketRepository.create(TicketRepository.TicketType.ACCESS_REQUEST,
                "DEV_DB", reason, "user1", key);
        var retry = ticketRepository.create(TicketRepository.TicketType.ACCESS_REQUEST,
                "DEV_DB", reason, "user1", key);

        assertThat(first.replayed()).isFalse();
        assertThat(retry.replayed()).isTrue();
        assertThat(retry.ticket().id()).isEqualTo(first.ticket().id());
        assertThat(countByReason(reason)).isEqualTo(1);
        String fingerprint = jdbcTemplate.queryForObject(
                "select request_fingerprint from tickets where requested_by = ? and idempotency_key = ?",
                String.class, "user1", key);
        assertThat(fingerprint).matches("[0-9a-f]{64}").doesNotContain(reason);
    }

    @Test
    void 같은_KEY에_다른_내용이면_충돌하고_티켓을_추가하지_않는다() {
        String key = key();
        String original = "original " + UUID.randomUUID();
        ticketRepository.create(TicketRepository.TicketType.ACCESS_REQUEST,
                "DEV_DB", original, "user1", key);

        assertThatThrownBy(() -> ticketRepository.create(TicketRepository.TicketType.INCIDENT,
                "VPN", "different request", "user1", key))
                .isInstanceOf(IdempotencyConflictException.class);
        assertThat(countByReason(original)).isEqualTo(1);
        assertThat(countByReason("different request")).isZero();
    }

    @Test
    void 새_KEY와_다른_사용자는_각각_별도_요청으로_처리한다() {
        String reason = "independent requests " + UUID.randomUUID();
        var first = ticketRepository.create(TicketRepository.TicketType.ACCESS_REQUEST,
                "DEV_DB", reason, "user1", key());
        var second = ticketRepository.create(TicketRepository.TicketType.ACCESS_REQUEST,
                "DEV_DB", reason, "user1", key());
        String sharedKey = key();
        var user1 = ticketRepository.create(TicketRepository.TicketType.INCIDENT,
                "VPN", "user scoped " + reason, "user1", sharedKey);
        var user2 = ticketRepository.create(TicketRepository.TicketType.INCIDENT,
                "VPN", "user scoped " + reason, "user2", sharedKey);

        assertThat(first.ticket().id()).isNotEqualTo(second.ticket().id());
        assertThat(user1.ticket().id()).isNotEqualTo(user2.ticket().id());
        assertThat(countByReason(reason)).isEqualTo(2);
    }

    @Test
    void 같은_KEY_동시_요청은_DB에서_한_건만_생성한다() throws Exception {
        String key = key();
        String reason = "concurrent retry " + UUID.randomUUID();
        int requestCount = 24;
        try (var executor = Executors.newFixedThreadPool(8)) {
            List<Callable<TicketRepository.TicketCreation>> calls = java.util.stream.IntStream.range(0, requestCount)
                    .<Callable<TicketRepository.TicketCreation>>mapToObj(i -> () -> ticketRepository.create(
                            TicketRepository.TicketType.INCIDENT, "VPN", reason, "user1", key))
                    .toList();
            var results = executor.invokeAll(calls).stream().map(future -> {
                try { return future.get(); }
                catch (Exception error) { throw new RuntimeException(error); }
            }).toList();
            assertThat(results).extracting(result -> result.ticket().id())
                    .containsOnly(results.getFirst().ticket().id());
            assertThat(results).filteredOn(TicketRepository.TicketCreation::replayed).hasSize(requestCount - 1);
        }
        assertThat(countByReason(reason)).isEqualTo(1);
    }

    @Test
    void 새_Repository_인스턴스에서_재시도해도_기존_Key와_Ticket이_유지된다() {
        String key = key();
        String reason = "repository restart " + UUID.randomUUID();
        var created = ticketRepository.create(TicketRepository.TicketType.ACCESS_REQUEST,
                "DEV_DB", reason, "user1", key);

        TicketRepository restartedRepository = new JdbcTicketRepository(jdbcTemplate);
        var replay = restartedRepository.create(TicketRepository.TicketType.ACCESS_REQUEST,
                "DEV_DB", reason, "user1", key);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.ticket().id()).isEqualTo(created.ticket().id());
        assertThat(countByReason(reason)).isEqualTo(1);
    }

    @Test
    void Spring_애플리케이션_재시작_후에도_같은_Key가_기존_Ticket을_반환한다() {
        String key = key();
        String reason = "application restart " + UUID.randomUUID();
        TicketRepository.Ticket created;
        try (ConfigurableApplicationContext first = testApplication()) {
            created = first.getBean(TicketRepository.class).create(
                    TicketRepository.TicketType.ACCESS_REQUEST, "DEV_DB", reason, "user1", key).ticket();
        }

        try (ConfigurableApplicationContext restarted = testApplication()) {
            var replay = restarted.getBean(TicketRepository.class).create(
                    TicketRepository.TicketType.ACCESS_REQUEST, "DEV_DB", reason, "user1", key);
            assertThat(replay.replayed()).isTrue();
            assertThat(replay.ticket().id()).isEqualTo(created.id());
        }
        assertThat(countByReason(reason)).isEqualTo(1);
    }

    @Test
    void 제약조건_위반과_DB_저장실패는_접수성공이_아니다() {
        String key = key();
        String reason = "constraint fixture " + UUID.randomUUID();
        assertThatThrownBy(() -> ticketRepository.create(null, "DEV_DB", reason, "user1", key))
                .isInstanceOf(DataIntegrityViolationException.class);

        String result = new TicketTools(ticketRepository, new ToolInvocationTracker()).createTicket(
                null, "DEV_DB", reason, context("user1", key));
        assertThat(result).contains("접수하지 못했습니다").doesNotContain("접수했습니다");
        assertThat(countByReason(reason)).isZero();
    }

    @Test
    void principal로_저장하고_USER의_관리자조회는_계속_거부한다() throws Exception {
        MockHttpSession user = login("user1", USER1_PASSWORD);
        String sessionId = "pg-it-" + UUID.randomUUID();
        String key = key();
        String reason = "PostgreSQL auth fixture " + UUID.randomUUID();
        mvc.perform(post("/api/chat").session(user).with(csrf())
                        .header("Idempotency-Key", key)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(new ChatController.ChatRequest(
                                "DEV_DB 권한을 신청해줘. " + reason, sessionId, null, false))))
                .andExpect(status().isOk());

        var created = ticketRepository.findAll().stream()
                .filter(ticket -> ticket.reason().contains(reason)).findFirst().orElseThrow();
        assertThat(created.requestedBy()).isEqualTo("user1");
        mvc.perform(get("/api/admin/tickets").session(user)).andExpect(status().isForbidden());

        MockHttpSession admin = login("admin", ADMIN_PASSWORD);
        mvc.perform(get("/api/admin/tickets").session(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '%s')]".formatted(created.id())).exists());
    }

    @Test
    void Key_없는_명시적_쓰기만_400으로_거부하고_저장하지_않는다() throws Exception {
        MockHttpSession user = login("user1", USER1_PASSWORD);
        String reason = "missing key fixture " + UUID.randomUUID();
        mvc.perform(post("/api/chat").session(user).with(csrf())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(new ChatController.ChatRequest(
                                "DEV_DB 권한을 신청해줘. " + reason, "missing-key", null, false))))
                .andExpect(status().isBadRequest());
        assertThat(countByReason(reason)).isZero();
    }

    private int countByReason(String reason) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from tickets where reason = ?", Integer.class, reason);
        return count == null ? 0 : count;
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    private ConfigurableApplicationContext testApplication() {
        String url = System.getenv("TICKET_IT_DB_URL") +
                (System.getenv("TICKET_IT_DB_URL").contains("?") ? "&" : "?") + "currentSchema=" + TEST_SCHEMA;
        String username = System.getenv().getOrDefault("TICKET_IT_DB_USERNAME", "helpdesk");
        String password = System.getenv().getOrDefault("TICKET_IT_DB_PASSWORD", "");
        return new SpringApplicationBuilder(DevDeskApplication.class)
                .profiles("postgres")
                .properties(Map.ofEntries(
                        Map.entry("spring.datasource.url", url),
                        Map.entry("spring.datasource.username", username),
                        Map.entry("spring.datasource.password", password),
                        Map.entry("spring.flyway.schemas", TEST_SCHEMA),
                        Map.entry("spring.flyway.default-schema", TEST_SCHEMA),
                        Map.entry("server.port", "0"),
                        Map.entry("spring.main.banner-mode", "off"),
                        Map.entry("spring.ai.openai.api-key", "test-only-not-used"),
                        Map.entry("app.security.user1-password", USER1_PASSWORD),
                        Map.entry("app.security.user2-password", USER2_PASSWORD),
                        Map.entry("app.security.admin-password", ADMIN_PASSWORD)))
                .run("--spring.datasource.url=" + url,
                        "--spring.datasource.username=" + username,
                        "--spring.datasource.password=" + password,
                        "--spring.flyway.schemas=" + TEST_SCHEMA,
                        "--spring.flyway.default-schema=" + TEST_SCHEMA);
    }

    private org.springframework.ai.chat.model.ToolContext context(String userId, String key) {
        return new org.springframework.ai.chat.model.ToolContext(
                Map.of("userId", userId, "idempotencyKey", key));
    }

    private MockHttpSession login(String username, String password) throws Exception {
        var result = mvc.perform(formLogin().user(username).password(password))
                .andExpect(status().isNoContent()).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private static String requireDedicatedTestDatabase(String url) {
        if (url == null || !url.matches("(?is)^jdbc:postgresql:.*_test(?:[?].*)?$")) {
            throw new IllegalArgumentException("TICKET_IT_DB_URL must target a dedicated database ending in _test.");
        }
        return url;
    }
}
