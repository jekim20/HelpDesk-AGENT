package com.devdesk.agent;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import com.devdesk.agent.TicketRepository.Ticket;
import com.devdesk.agent.TicketRepository.TicketCreation;
import com.devdesk.agent.TicketRepository.TicketType;

/** 격리된 테스트에서만 사용하는 메모리 저장소. */
@Repository
@Profile("test-memory")
public class InMemoryTicketRepository implements TicketRepository {

    private final Map<RequestKey, StoredTicket> tickets = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();

    @Override
    public TicketCreation create(TicketType type, String resource, String reason, String requestedBy,
                                 String idempotencyKey) {
        String canonicalKey = IdempotencyKey.canonical(idempotencyKey);
        String fingerprint = TicketRequestFingerprint.of(type, resource, reason);
        RequestKey key = new RequestKey(requestedBy, canonicalKey);
        AtomicReference<TicketCreation> result = new AtomicReference<>();
        tickets.compute(key, (ignored, existing) -> {
            if (existing != null) {
                if (!existing.fingerprint().equals(fingerprint)) throw new IdempotencyConflictException();
                result.set(new TicketCreation(existing.ticket(), true));
                return existing;
            }
            String id = "HD-%04d".formatted(sequence.incrementAndGet());
            Ticket ticket = new Ticket(id, type, resource, reason, requestedBy, Instant.now(), "PENDING");
            StoredTicket stored = new StoredTicket(fingerprint, ticket);
            result.set(new TicketCreation(ticket, false));
            return stored;
        });
        return result.get();
    }

    @Override
    public List<Ticket> findAll() {
        return tickets.values().stream().map(StoredTicket::ticket)
                .sorted(Comparator.comparing(Ticket::id)).toList();
    }

    private record RequestKey(String requestedBy, String idempotencyKey) {}
    private record StoredTicket(String fingerprint, Ticket ticket) {}
}
