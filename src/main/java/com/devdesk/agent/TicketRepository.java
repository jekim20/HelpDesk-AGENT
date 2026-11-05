package com.devdesk.agent;

import java.time.Instant;
import java.util.List;

/** Ticket 저장 경계. 운영 프로필은 PostgreSQL, 단위 테스트 프로필은 메모리 구현을 사용한다. */
public interface TicketRepository {

    TicketCreation create(TicketType type, String resource, String reason, String requestedBy,
                          String idempotencyKey);

    List<Ticket> findAll();

    public enum TicketType {
        ACCESS_REQUEST,
        INCIDENT,
        ACCOUNT_SUPPORT
    }

    public record Ticket(String id, TicketType type, String resource, String reason,
                         String requestedBy, Instant requestedAt, String status) {}

    public record TicketCreation(Ticket ticket, boolean replayed) {}
}
