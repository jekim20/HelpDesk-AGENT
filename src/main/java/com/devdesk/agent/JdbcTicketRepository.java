package com.devdesk.agent;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** PostgreSQL-backed ticket repository. */
@Repository
@Profile("!test-memory")
public class JdbcTicketRepository implements TicketRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcTicketRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Ticket create(TicketType type, String resource, String reason, String requestedBy) {
        long ticketNumber = jdbcTemplate.queryForObject(
                "select nextval('helpdesk_ticket_number_seq')", Long.class);
        String id = "HD-%04d".formatted(ticketNumber);
        Instant createdAt = Instant.now();
        jdbcTemplate.update("""
                insert into tickets (ticket_number, id, type, resource, reason, requested_by, requested_at, status)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                """, ticketNumber, id, type == null ? null : type.name(), resource, reason, requestedBy,
                Timestamp.from(createdAt), "PENDING");
        return new Ticket(id, type, resource, reason, requestedBy, createdAt, "PENDING");
    }

    @Override
    public List<Ticket> findAll() {
        return jdbcTemplate.query("""
                select id, type, resource, reason, requested_by, requested_at, status
                from tickets
                order by ticket_number
                """, (rs, rowNum) -> new Ticket(
                rs.getString("id"), TicketType.valueOf(rs.getString("type")),
                rs.getString("resource"), rs.getString("reason"), rs.getString("requested_by"),
                rs.getTimestamp("requested_at").toInstant(), rs.getString("status")));
    }
}
