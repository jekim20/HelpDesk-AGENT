alter table tickets
    add column idempotency_key varchar(36),
    add column request_fingerprint char(64),
    add constraint ck_tickets_idempotency_pair
        check ((idempotency_key is null and request_fingerprint is null)
            or (idempotency_key is not null and request_fingerprint is not null)),
    add constraint uq_tickets_requester_idempotency
        unique (requested_by, idempotency_key);
