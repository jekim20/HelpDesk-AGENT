package com.devdesk.agent;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

/** 격리된 테스트에서만 사용하는 메모리 저장소. */
@Repository
@Profile("test-memory")
public class InMemoryTicketRepository implements TicketRepository {

    private final Map<String, Ticket> tickets = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();

    @Override
    public Ticket create(TicketType type, String resource, String reason, String requestedBy) {
        String id = "HD-%04d".formatted(sequence.incrementAndGet());
        Ticket ticket = new Ticket(id, type, resource, reason, requestedBy, Instant.now(), "PENDING");
        tickets.put(id, ticket);
        return ticket;
    }

    @Override
    public List<Ticket> findAll() {
        return tickets.values().stream().sorted(Comparator.comparing(Ticket::id)).toList();
    }
}
