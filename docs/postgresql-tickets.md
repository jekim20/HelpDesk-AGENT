# PostgreSQL ticket storage

`TicketRepository` uses PostgreSQL in the application and an isolated in-memory implementation in the ordinary unit and security test profile. `JdbcTicketRepository` persists tickets; Flyway applies versioned schema changes at startup. Chat Memory, VectorStore, and access-status data remain in memory.

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

The normal test suite uses the `test-memory` profile and does not connect to a database. To run the PostgreSQL tests, point them at a dedicated, disposable test database. Tests add uniquely marked rows and intentionally do not delete existing rows.

```bash
export TICKET_IT_DB_URL='jdbc:postgresql://localhost:5432/helpdesk_test'
export TICKET_IT_DB_USERNAME='your_local_postgres_role'
export TICKET_IT_DB_PASSWORD='your_local_password'
./gradlew test --tests '*TicketRepositoryPostgresIntegrationTest'
```

The integration suite checks persisted reads through a fresh repository instance, concurrent ID allocation, database constraint failures, authenticated ticket creation, principal-derived `requestedBy`, and USER/ADMIN read access. A full process restart can also be checked by stopping and starting the application against the same `DB_URL`; Flyway validates the existing version and leaves ticket rows intact.

## Stored fields and constraints

`tickets` stores the HD identifier, monotonically allocated ticket number, type, resource, reason, authenticated requester, creation time, and `PENDING` status. The identifier is a primary key; the sequence-backed ticket number is unique. Type and status checks reject invalid values. An index supports requester and newest-first lookups.

Repeated identical requests can still create multiple tickets. A future idempotency design should accept a client request key, enforce a unique `(requested_by, idempotency_key)` constraint, and return the original ticket for retries. No such key is stored in this phase.
