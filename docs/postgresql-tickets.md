# PostgreSQL ticket storage

`TicketRepository` uses PostgreSQL in the application and an isolated in-memory implementation in the ordinary unit and security test profile. `JdbcTicketRepository` persists tickets; Flyway applies versioned schema changes at startup. Chat Memory, VectorStore, and access-status data remain in memory.

Ticket writes require an `Idempotency-Key` HTTP header containing a UUID. The React client makes a fresh key for each submitted message and reuses it for automatic or user-triggered retries of that message. The evaluator makes an independent key for each turn and reuses it for a transport retry. A separate user submission receives a fresh key. Read-only questions do not require a key. Legacy clients that submit an explicit write without a valid key receive HTTP 400 without creating a ticket.

The key is scoped by the authenticated principal. A repeat with the same principal, key, and canonical ticket fields returns the existing ticket and a replay-specific Tool result. Reusing the same principal/key for different ticket fields returns HTTP 409 and a failed Tool trace. Canonical fields are type, trimmed/uppercased resource, and NFC-normalized reason with collapsed whitespace. Only their SHA-256 fingerprint is stored for comparison; the request key and fingerprint do not replace the ticket's existing reason field.

## Local configuration

Create two empty databases owned by a local PostgreSQL login. Keep the development and test database names separate. Do not point the integration test at an existing or production database.

Set connection properties outside the repository:

```bash
export DB_URL='jdbc:postgresql://localhost:5432/helpdesk'
export DB_USERNAME='your_local_postgres_role'
export DB_PASSWORD='your_local_password'
./gradlew bootRun
```

Flyway creates the ticket sequence, table, constraints, and index on first startup. Existing databases are not dropped or reset. The migration does not import tickets from the old in-memory repository; those records existed only in the process that held them.

## PostgreSQL integration test

The normal test suite uses the `test-memory` profile and does not connect to a database. To run the PostgreSQL tests, point them at a dedicated test database whose name ends in `_test`. Each test run gets a random schema, applies Flyway migrations there, then drops only that run-created schema. Existing schemas and rows in the test database are not touched.

```bash
export TICKET_IT_DB_URL='jdbc:postgresql://localhost:5432/helpdesk_test'
export TICKET_IT_DB_USERNAME='your_local_postgres_role'
export TICKET_IT_DB_PASSWORD='your_local_password'
./gradlew test --tests '*TicketRepositoryPostgresIntegrationTest'
```

The integration suite checks same-key replay, same-key payload conflicts, different keys, cross-user key scope, concurrent retries, server-instance persistence, database constraint failures, authenticated ticket creation, principal-derived `requestedBy`, missing-key rejection, and USER/ADMIN read access. A full process restart can also be checked by stopping and starting the application against the same `DB_URL`; Flyway validates the existing version and leaves ticket rows intact.

## Stored fields and constraints

`tickets` stores the HD identifier, monotonically allocated ticket number, type, resource, reason, authenticated requester, creation time, `PENDING` status, idempotency key, and a request fingerprint. V2 adds a unique `(requested_by, idempotency_key)` constraint; old rows keep null key/fingerprint values and remain readable. The identifier is a primary key; the sequence-backed ticket number is unique. Type and status checks reject invalid values. An index supports requester and newest-first lookups.

Idempotency protects retries that reuse the same key. A separate submission with a fresh key is treated as a new request, even if its text matches an earlier ticket.
