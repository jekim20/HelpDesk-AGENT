package com.devdesk.agent;

import java.sql.Timestamp;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Repository;

import com.devdesk.agent.TicketRepository.Ticket;
import com.devdesk.agent.TicketRepository.TicketCreation;
import com.devdesk.agent.TicketRepository.TicketType;

/** PostgreSQL-backed ticket repository. */
@Repository
@Profile("!test-memory")
public class JdbcTicketRepository implements TicketRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcTicketRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public TicketCreation create(TicketType type, String resource, String reason, String requestedBy,
                                 String idempotencyKey) {
        idempotencyKey = IdempotencyKey.canonical(idempotencyKey);
        String fingerprint = TicketRequestFingerprint.of(type, resource, reason);
        TicketCreation prior = existing(requestedBy, idempotencyKey, fingerprint);
        if (prior != null) return prior;

        long ticketNumber = jdbcTemplate.queryForObject(
                "select nextval('helpdesk_ticket_number_seq')", Long.class);
        String id = "HD-%04d".formatted(ticketNumber);
        Instant createdAt = Instant.now();
        List<Ticket> inserted = jdbcTemplate.query("""
                insert into tickets (ticket_number, id, type, resource, reason, requested_by, requested_at, status,
                                     idempotency_key, request_fingerprint)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (requested_by, idempotency_key) do nothing
                returning id, type, resource, reason, requested_by, requested_at, status
                """, ticketRowMapper(), ticketNumber, id, type == null ? null : type.name(), resource, reason, requestedBy,
                Timestamp.from(createdAt), "PENDING", idempotencyKey, fingerprint);
        if (!inserted.isEmpty()) return new TicketCreation(inserted.getFirst(), false);

        TicketCreation concurrent = existing(requestedBy, idempotencyKey, fingerprint);
        if (concurrent != null) return concurrent;
        throw new IllegalStateException("Idempotency conflict row was not found.");
    }

    private TicketCreation existing(String requestedBy, String idempotencyKey, String fingerprint) {
        List<StoredTicket> existing = jdbcTemplate.query("""
                select id, type, resource, reason, requested_by, requested_at, status, request_fingerprint
                from tickets where requested_by = ? and idempotency_key = ?
                """, (rs, rowNum) -> new StoredTicket(new Ticket(
                rs.getString("id"), TicketType.valueOf(rs.getString("type")),
                rs.getString("resource"), rs.getString("reason"), rs.getString("requested_by"),
                rs.getTimestamp("requested_at").toInstant(), rs.getString("status")),
                rs.getString("request_fingerprint")), requestedBy, idempotencyKey);
        if (existing.isEmpty()) return null;
        StoredTicket stored = existing.getFirst();
        if (!MessageDigest.isEqual(stored.fingerprint().getBytes(StandardCharsets.US_ASCII),
                fingerprint.getBytes(StandardCharsets.US_ASCII))) {
            throw new IdempotencyConflictException();
        }
        return new TicketCreation(stored.ticket(), true);
    }

    @Override
    public List<Ticket> findAll() {
        return jdbcTemplate.query("""
                select id, type, resource, reason, requested_by, requested_at, status
                from tickets
                order by ticket_number
                """, ticketRowMapper());
    }

    private RowMapper<Ticket> ticketRowMapper() {
        return (rs, rowNum) -> new Ticket(
                rs.getString("id"), TicketType.valueOf(rs.getString("type")),
                rs.getString("resource"), rs.getString("reason"), rs.getString("requested_by"),
                rs.getTimestamp("requested_at").toInstant(), rs.getString("status"));
    }

    private record StoredTicket(Ticket ticket, String fingerprint) {}
}
