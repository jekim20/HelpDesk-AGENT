package com.devdesk.agent;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.devdesk.agent.HelpDeskService.ChatResult;
import com.devdesk.agent.TicketRepository.Ticket;

@RestController
@RequestMapping("/api")
public class ChatController {

    private final HelpDeskService helpDeskService;
    private final IngestService ingestService;
    private final TicketRepository ticketRepository;

    public ChatController(HelpDeskService helpDeskService, IngestService ingestService,
                          TicketRepository ticketRepository) {
        this.helpDeskService = helpDeskService;
        this.ingestService = ingestService;
        this.ticketRepository = ticketRepository;
    }

    @PostMapping("/chat")
    public ResponseEntity<ChatResult> chat(@RequestBody ChatRequest request, Authentication authentication) {
        if (request == null || request.question() == null || request.question().isBlank()) {
            String sessionId = request == null ? null : request.sessionId();
            return ResponseEntity.badRequest().body(
                    new ChatResult("질문을 입력해 주세요.", sessionId, List.of(), false, List.of(), false));
        }
        if (request.sessionId() == null || request.sessionId().isBlank()) {
            return ResponseEntity.badRequest().body(new ChatResult(
                    "sessionId는 필수입니다.", request.sessionId(),
                    List.of(), false, List.of(), false));
        }
        String authenticatedUserId = authentication.getName();
        if (request.userId() != null && !request.userId().isBlank()
                && !request.userId().equals(authenticatedUserId)) {
            return ResponseEntity.status(403).body(new ChatResult(
                    "요청 userId가 인증 사용자와 일치하지 않습니다.", request.sessionId(),
                    List.of(), false, List.of(), false));
        }
        boolean simulateFailure = Boolean.TRUE.equals(request.simulatePrimaryFailure());
        if (simulateFailure && authentication.getAuthorities().stream()
                .noneMatch(authority -> authority.getAuthority().equals("ROLE_ADMIN"))) {
            return ResponseEntity.status(403).body(new ChatResult(
                    "모델 장애 시뮬레이션은 관리자만 사용할 수 있습니다.", request.sessionId(),
                    List.of(), false, List.of(), false));
        }
        return ResponseEntity.ok(helpDeskService.chat(
                request.question(), request.sessionId(), authenticatedUserId, simulateFailure));
    }

    @PostMapping("/ingest")
    public List<IngestService.IngestResult> ingest() throws IOException {
        Resource[] docs = new PathMatchingResourcePatternResolver().getResources("classpath:/docs/*.md");
        return Arrays.stream(docs).map(doc -> ingestService.ingest(doc, "policy", "IT")).toList();
    }

    @GetMapping("/history")
    public ResponseEntity<List<String>> history(@RequestParam String sessionId,
                                                @RequestParam(required = false) String userId,
                                                Authentication authentication) {
        if (userId != null && !userId.isBlank() && !userId.equals(authentication.getName())) {
            return ResponseEntity.status(403).build();
        }
        return ResponseEntity.ok(helpDeskService.history(sessionId, authentication.getName()));
    }

    @GetMapping("/admin/tickets")
    public List<Ticket> tickets() {
        return ticketRepository.findAll();
    }

    public record ChatRequest(String question, String sessionId, String userId,
                              Boolean simulatePrimaryFailure) {}
}
