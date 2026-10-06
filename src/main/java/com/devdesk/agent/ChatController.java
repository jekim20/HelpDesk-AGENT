package com.devdesk.agent;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.http.ResponseEntity;
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
    public ResponseEntity<ChatResult> chat(@RequestBody ChatRequest request) {
        if (request == null || request.question() == null || request.question().isBlank()) {
            String sessionId = request == null ? null : request.sessionId();
            return ResponseEntity.badRequest().body(
                    new ChatResult("질문을 입력해 주세요.", sessionId, List.of(), false, List.of(), false));
        }
        if (request.sessionId() == null || request.sessionId().isBlank()
                || request.userId() == null || request.userId().isBlank()) {
            return ResponseEntity.badRequest().body(new ChatResult(
                    "sessionId와 userId는 필수입니다.", request.sessionId(),
                    List.of(), false, List.of(), false));
        }
        return ResponseEntity.ok(helpDeskService.chat(
                request.question(), request.sessionId(), request.userId(),
                Boolean.TRUE.equals(request.simulatePrimaryFailure())));
    }

    @PostMapping("/ingest")
    public List<IngestService.IngestResult> ingest() throws IOException {
        Resource[] docs = new PathMatchingResourcePatternResolver().getResources("classpath:/docs/*.md");
        return Arrays.stream(docs).map(doc -> ingestService.ingest(doc, "policy", "IT")).toList();
    }

    @GetMapping("/history")
    public List<String> history(@RequestParam String sessionId, @RequestParam String userId) {
        return helpDeskService.history(sessionId, userId);
    }

    @GetMapping("/admin/tickets")
    public List<Ticket> tickets() {
        return ticketRepository.findAll();
    }

    public record ChatRequest(String question, String sessionId, String userId,
                              Boolean simulatePrimaryFailure) {}
}
