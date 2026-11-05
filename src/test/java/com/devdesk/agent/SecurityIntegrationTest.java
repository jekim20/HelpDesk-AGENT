package com.devdesk.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class SecurityIntegrationTest {

    private static final String USER1_PASSWORD = UUID.randomUUID().toString();
    private static final String USER2_PASSWORD = UUID.randomUUID().toString();
    private static final String ADMIN_PASSWORD = UUID.randomUUID().toString();

    @DynamicPropertySource
    static void credentials(DynamicPropertyRegistry registry) {
        registry.add("app.security.user1-password", () -> USER1_PASSWORD);
        registry.add("app.security.user2-password", () -> USER2_PASSWORD);
        registry.add("app.security.admin-password", () -> ADMIN_PASSWORD);
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired TicketRepository ticketRepository;
    @MockitoBean IngestService ingestService;

    @Test
    void 미인증_API_요청은_로그인_HTML_대신_401을_반환한다() throws Exception {
        mvc.perform(get("/api/history").param("sessionId", "anonymous"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/me"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/tickets"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/ingest").with(csrf()))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/chat").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 익명_CSRF_초기화는_토큰과_브라우저_쿠키를_발급한다() throws Exception {
        mvc.perform(get("/api/csrf"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.headerName").isNotEmpty())
                .andExpect(cookie().exists("XSRF-TOKEN"));
    }

    @Test
    void 로그인_상태가_me로_복원되고_로그아웃하면_보호_API가_차단된다() throws Exception {
        MockHttpSession user = login("user1", USER1_PASSWORD);

        mvc.perform(get("/api/me").session(user))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("user1"))
                .andExpect(jsonPath("$.roles[0]").value("USER"));

        mvc.perform(post("/logout").session(user).with(csrf()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/me").session(user))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void SPA_로그인_토큰회전_채팅_CSRF_로그아웃_흐름이_동작한다() throws Exception {
        MvcResult csrfResult = mvc.perform(get("/api/csrf"))
                .andExpect(status().isOk()).andReturn();
        SecurityApiController.CsrfInfo loginCsrf = objectMapper.readValue(
                csrfResult.getResponse().getContentAsString(), SecurityApiController.CsrfInfo.class);
        Cookie loginCookie = csrfResult.getResponse().getCookie("XSRF-TOKEN");
        assertThat(loginCookie).isNotNull();

        MvcResult loginResult = mvc.perform(post("/login")
                        .cookie(loginCookie)
                        .header(loginCsrf.headerName(), loginCsrf.token())
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("username", "user1")
                        .param("password", USER1_PASSWORD))
                .andExpect(status().isNoContent()).andReturn();
        MockHttpSession session = (MockHttpSession) loginResult.getRequest().getSession(false);
        assertThat(session).isNotNull();

        MvcResult freshCsrfResult = mvc.perform(get("/api/csrf").session(session))
                .andExpect(status().isOk()).andReturn();
        SecurityApiController.CsrfInfo freshCsrf = objectMapper.readValue(
                freshCsrfResult.getResponse().getContentAsString(), SecurityApiController.CsrfInfo.class);
        Cookie freshCookie = freshCsrfResult.getResponse().getCookie("XSRF-TOKEN");
        assertThat(freshCookie).isNotNull();

        mvc.perform(post("/api/chat").session(session).cookie(freshCookie)
                        .header(freshCsrf.headerName(), freshCsrf.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ChatController.ChatRequest(
                                "내 VPN 권한 상태 알려줘.", "spa-csrf", null, false))))
                .andExpect(status().isOk());

        mvc.perform(post("/logout").session(session).cookie(freshCookie)
                        .header(freshCsrf.headerName(), freshCsrf.token()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/me").session(session))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 잘못된_비밀번호는_API_401로_반환한다() throws Exception {
        mvc.perform(formLogin().user("user1").password("incorrect-password"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void USER는_ADMIN_API와_문서_인제스트를_실행할_수_없다() throws Exception {
        MockHttpSession user = login("user1", USER1_PASSWORD);
        clearInvocations(ingestService);

        mvc.perform(get("/api/admin/tickets").session(user))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/ingest").session(user).with(csrf()))
                .andExpect(status().isForbidden());

        verifyNoInteractions(ingestService);
    }

    @Test
    void ADMIN은_전체_티켓과_문서_인제스트에_접근할_수_있다() throws Exception {
        MockHttpSession admin = login("admin", ADMIN_PASSWORD);
        var ticket = ticketRepository.create(
                TicketRepository.TicketType.INCIDENT, "VPN", "security integration fixture", "user1");
        when(ingestService.ingest(any(Resource.class), any(), any()))
                .thenAnswer(invocation -> new IngestService.IngestResult(
                        ((Resource) invocation.getArgument(0)).getFilename(), 1));

        String tickets = mvc.perform(get("/api/admin/tickets").session(admin))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(tickets).contains(ticket.id());
        mvc.perform(get("/api/me").session(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles[0]").value("ADMIN"));
        mvc.perform(post("/api/ingest").session(admin).with(csrf()))
                .andExpect(status().isOk());

        verify(ingestService, org.mockito.Mockito.times(4)).ingest(any(Resource.class), any(), any());
    }

    @Test
    void Health만_익명_공개하고_Actuator_세부정보는_노출하지_않는다() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.components").doesNotExist());
        mvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 같은_sessionId라도_사용자별로_대화_이력이_분리된다() throws Exception {
        MockHttpSession user1 = login("user1", USER1_PASSWORD);
        MockHttpSession user2 = login("user2", USER2_PASSWORD);
        String sharedSession = "shared-" + UUID.randomUUID();

        chat(user1, "내 VPN 권한 상태 알려줘.", sharedSession, "user1")
                .andExpect(status().isOk());
        chat(user2, "내 PROD_DB 권한 상태 알려줘.", sharedSession, "user2")
                .andExpect(status().isOk());

        String user1History = mvc.perform(get("/api/history").session(user1)
                        .param("sessionId", sharedSession))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String user2History = mvc.perform(get("/api/history").session(user2)
                        .param("sessionId", sharedSession))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(user1History).contains("VPN").doesNotContain("PROD_DB");
        assertThat(user2History).contains("PROD_DB").doesNotContain("VPN");
        mvc.perform(get("/api/history").session(user1)
                        .param("sessionId", sharedSession).param("userId", "user2"))
                .andExpect(status().isForbidden());
    }

    @Test
    void body_userId_위조는_권한_조회에_사용되지_않고_불일치면_거부한다() throws Exception {
        MockHttpSession user1 = login("user1", USER1_PASSWORD);
        String sessionId = "spoof-" + UUID.randomUUID();

        chat(user1, "내 PROD_DB 권한 상태 알려줘.", sessionId, "user2")
                .andExpect(status().isForbidden());
        String response = chat(user1, "내 PROD_DB 권한 상태 알려줘.", sessionId, "user1")
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(response).contains("NOT_REQUESTED").doesNotContain("APPROVED");
    }

    @Test
    void body_userId_위조로_다른_사용자_이름의_티켓을_만들_수_없다() throws Exception {
        MockHttpSession user1 = login("user1", USER1_PASSWORD);
        int initialCount = ticketRepository.findAll().size();
        String sessionId = "ticket-" + UUID.randomUUID();

        chat(user1, "DEV_DB 권한을 신청해줘. 프로젝트 작업에 필요해.", sessionId, "user2")
                .andExpect(status().isForbidden());
        assertThat(ticketRepository.findAll()).hasSize(initialCount);

        chat(user1, "DEV_DB 권한을 신청해줘. 프로젝트 작업에 필요해.", sessionId, "user1")
                .andExpect(status().isOk());
        assertThat(ticketRepository.findAll()).filteredOn(ticket -> ticket.requestedBy().equals("user1"))
                .isNotEmpty();
        assertThat(ticketRepository.findAll()).filteredOn(ticket -> ticket.requestedBy().equals("user2"))
                .isEmpty();
    }

    @Test
    void CSRF_토큰이_없는_인증된_변경_요청은_차단한다() throws Exception {
        MockHttpSession user = login("user1", USER1_PASSWORD);

        mvc.perform(post("/api/chat").session(user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ChatController.ChatRequest(
                                        "내 VPN 권한 상태 알려줘.", "csrf-" + UUID.randomUUID(), "user1", false))))
                .andExpect(status().isForbidden());
    }

    @Test
    void USER가_실패_시뮬레이션을_요청할_수_없다() throws Exception {
        MockHttpSession user = login("user1", USER1_PASSWORD);

        mvc.perform(post("/api/chat").session(user).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ChatController.ChatRequest(
                                        "VPN 정책을 알려줘.", "simulation-" + UUID.randomUUID(), "user1", true))))
                .andExpect(status().isForbidden());
    }

    private MockHttpSession login(String username, String password) throws Exception {
        MvcResult result = mvc.perform(formLogin().user(username).password(password))
                .andExpect(status().isNoContent())
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private org.springframework.test.web.servlet.ResultActions chat(
            MockHttpSession session, String question, String sessionId, String userId) throws Exception {
        return mvc.perform(post("/api/chat").session(session).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                        new ChatController.ChatRequest(question, sessionId, userId, false))));
    }
}
