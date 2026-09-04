package com.devdesk.agent;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.stereotype.Repository;

/** 실제 ITSM 대신 PENDING 요청만 보관하는 인메모리 저장소. */
@Repository
public class TicketRepository {

    private final Map<String, Ticket> tickets = new ConcurrentHashMap<>();
    private final AtomicInteger sequence = new AtomicInteger();

    public Ticket create(TicketType type, String resource, String reason, String requestedBy) {
        String id = "HD-%04d".formatted(sequence.incrementAndGet());
        Ticket ticket = new Ticket(id, type, resource, reason, requestedBy, Instant.now(), "PENDING");
        tickets.put(id, ticket);
        return ticket;
    }

    public List<Ticket> findAll() {
        return tickets.values().stream().sorted(Comparator.comparing(Ticket::id)).toList();
    }

    public enum TicketType {
        ACCESS_REQUEST,
        INCIDENT,
        ACCOUNT_SUPPORT
    }

    public record Ticket(String id, TicketType type, String resource, String reason,
                         String requestedBy, Instant requestedAt, String status) {}
}
