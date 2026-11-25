package com.devdesk.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.vectorstore.VectorStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/** Opt-in local component measurements. No HTTP, PostgreSQL or external model calls. */
@EnabledIfEnvironmentVariable(named = "HELPDESK_QUANT_OUTPUT", matches = ".+")
class QuantitativeEvidenceTest {
    private final ObjectMapper json = new ObjectMapper();

    private void save(String filename, Object data) throws Exception {
        Path out = Path.of(System.getenv("HELPDESK_QUANT_OUTPUT"));
        Files.createDirectories(out);
        Path target = out.resolve(filename);
        // Never replace evidence from a previous run.
        Files.writeString(target, json.writerWithDefaultPrettyPrinter().writeValueAsString(data) + "\n",
                java.nio.file.StandardOpenOption.CREATE_NEW);
    }

    private <T> T audited(T target, ToolInvocationTracker tracker) {
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAspect(new ToolAuditAspect(new SimpleMeterRegistry(), tracker));
        return factory.getProxy();
    }

    private HelpDeskService service(TicketRepository tickets) {
        ToolInvocationTracker tracker = new ToolInvocationTracker();
        // Fixed model is only a path placeholder, never used to score RAG/LLM quality.
        ChatClient client = ChatClient.create(prompt -> new ChatResponse(List.of(
                new Generation(new AssistantMessage("결정론적 테스트 대역 응답")))));
        return new HelpDeskService(client, client, MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository()).maxMessages(20).build(),
                audited(new AccessTools(new AccessRepository(), tracker), tracker),
                audited(new TicketTools(tickets, tracker), tracker), new ModelFallbackExecutor(), tracker,
                new UserInputSafetyAdvisor(), new PromptInjectionGuard());
    }

    @Test
    void measureRoutesAndToolOutcomes() throws Exception {
        var scenarios = json.readTree(Path.of("evaluation/agent_scenarios.json").toFile());
        List<Map<String, Object>> samples = new ArrayList<>();
        // 10 warm-up passes, 100 measured passes. Preserve ordering inside each conversation.
        for (int iteration = -10; iteration < 100; iteration++) {
            var tickets = new InMemoryTicketRepository();
            var service = service(tickets);
            for (var scenario : scenarios) {
                int turnIndex = 0;
                for (var turn : scenario.path("turns")) {
                    turnIndex++;
                    var expected = turn.path("expected");
                    if (expected.path("expectedToolCalls").isEmpty()) continue;
                    long started = System.nanoTime();
                    var result = service.chat(turn.path("question").asText(), scenario.path("sessionId").asText(),
                            scenario.path("userId").asText(), false, UUID.randomUUID().toString());
                    double elapsed = (System.nanoTime() - started) / 1_000_000.0;
                    var wanted = expected.path("expectedToolCalls").get(0);
                    boolean selection = result.toolCalls().size() == 1
                            && result.toolCalls().getFirst().toolName().equals(wanted.path("toolName").asText());
                    boolean arguments = selection;
                    if (selection) {
                        for (var it = wanted.path("arguments").fields(); it.hasNext();) {
                            var entry = it.next();
                            arguments &= entry.getValue().asText().equals(
                                    String.valueOf(result.toolCalls().getFirst().toolArguments().get(entry.getKey())));
                        }
                    }
                    boolean execution = selection && result.toolCalls().getFirst().success();
                    for (var word : expected.path("answerContainsAll")) execution &= result.answer().contains(word.asText());
                    for (var word : expected.path("answerNotContains")) execution &= !result.answer().contains(word.asText());
                    if (wanted.path("toolName").asText().equals("createTicket")) {
                        execution &= tickets.findAll().stream().anyMatch(t -> result.answer().contains(t.id())
                                && t.status().equals("PENDING") && t.requestedBy().equals("user1"));
                    } else {
                        execution &= result.answer().contains(wanted.path("arguments").path("resource").asText());
                        execution &= result.answer().contains(result.answer().contains("DEV_DB") ? "APPROVED" : "NOT_REQUESTED");
                    }
                    if (iteration >= 0) samples.add(Map.of("iteration", iteration, "scenario", scenario.path("id").asText(),
                            "turn", turnIndex, "path", wanted.path("toolName").asText(), "latencyMs", elapsed,
                            "selectionCorrect", selection, "argumentsCorrect", arguments, "executionCorrect", execution));
                }
            }
        }
        save("tool-routes.json", samples);
        assertThat(samples).hasSize(600).allSatisfy(s -> {
            assertThat(s.get("selectionCorrect")).isEqualTo(true);
            assertThat(s.get("argumentsCorrect")).isEqualTo(true);
            assertThat(s.get("executionCorrect")).isEqualTo(true);
        });
    }

    @Test
    void measurePrivacyCorpusIncludingBlindSpots() throws Exception {
        var cases = json.readTree(Path.of("src/test/resources/quantitative/privacy-cases.json").toFile());
        var guard = new UserInputSafetyAdvisor();
        List<Map<String, Object>> samples = new ArrayList<>();
        for (int repeat = -100; repeat < 100; repeat++) {
            for (var c : cases) {
                long started = System.nanoTime();
                boolean blocked = guard.rejectionFor(c.path("input").asText()) != null;
                double elapsed = (System.nanoTime() - started) / 1_000_000.0;
                if (repeat >= 0) samples.add(Map.of("iteration", repeat, "id", c.path("id").asText(),
                        "category", c.path("category").asText(), "expectedBlocked", c.path("expectedBlocked").asBoolean(),
                        "blocked", blocked, "latencyMs", elapsed));
            }
        }
        save("privacy.json", samples);
        // Corpus characterization: retain false positives/negatives rather than assert them away.
        assertThat(samples).hasSize(2400);
    }

    @Test
    void compareIdempotencyAgainstHistoricalCreateAlgorithm() throws Exception {
        List<Map<String, Object>> samples = new ArrayList<>();
        for (int trial = 0; trial < 30; trial++) {
            for (String mode : List.of("sequential", "concurrent-24")) {
                var current = new InMemoryTicketRepository();
                var old = new HistoricalTicketRepository();
                String key = UUID.randomUUID().toString();
                try (var pool = Executors.newFixedThreadPool(24)) {
                    for (String variant : (trial % 2 == 0 ? List.of("historical", "current") : List.of("current", "historical"))) {
                        List<Callable<String>> requests = new ArrayList<>();
                        for (int i = 0; i < 24; i++) requests.add(() -> variant.equals("current")
                                ? current.create(TicketRepository.TicketType.ACCESS_REQUEST, "DEV_DB", "dummy reason", "dummy-user", key).ticket().id()
                                : old.create(TicketRepository.TicketType.ACCESS_REQUEST, "DEV_DB", "dummy reason", "dummy-user").id());
                        long started = System.nanoTime();
                        List<String> ids = new ArrayList<>();
                        if (mode.equals("sequential")) for (var request : requests) ids.add(request.call());
                        else for (var future : pool.invokeAll(requests)) ids.add(future.get());
                        double elapsed = (System.nanoTime() - started) / 1_000_000.0;
                        int stored = variant.equals("current") ? current.findAll().size() : old.findAll().size();
                        samples.add(Map.of("trial", trial, "mode", mode, "variant", variant,
                                "requests", 24, "storedTickets", stored, "uniqueReturnedIds", new HashSet<>(ids).size(),
                                "duplicates", stored - 1, "batchLatencyMs", elapsed));
                        assertThat(stored).isEqualTo(variant.equals("current") ? 1 : 24);
                        if (variant.equals("current")) assertThat(new HashSet<>(ids)).hasSize(1);
                    }
                }
            }
        }
        save("idempotency.json", samples);
        assertThat(samples).hasSize(120);
    }

    /** Source: git show 75ac1dc:src/main/java/com/devdesk/agent/InMemoryTicketRepository.java.
     * Same create algorithm; class name/interface adapted for current TicketRepository signature.
     * This is an isolated reconstruction, not execution of an old complete server.
     */
    private static class HistoricalTicketRepository {
        private final Map<String, TicketRepository.Ticket> tickets = new ConcurrentHashMap<>();
        private final java.util.concurrent.atomic.AtomicLong sequence = new java.util.concurrent.atomic.AtomicLong();
        TicketRepository.Ticket create(TicketRepository.TicketType type, String resource, String reason, String requestedBy) {
            String id = "HD-%04d".formatted(sequence.incrementAndGet());
            var ticket = new TicketRepository.Ticket(id, type, resource, reason, requestedBy, Instant.now(), "PENDING");
            tickets.put(id, ticket);
            return ticket;
        }
        List<TicketRepository.Ticket> findAll() {
            return tickets.values().stream().sorted(Comparator.comparing(TicketRepository.Ticket::id)).toList();
        }
    }
}
