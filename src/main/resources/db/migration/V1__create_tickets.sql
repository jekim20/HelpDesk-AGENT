create sequence helpdesk_ticket_number_seq start with 1 increment by 1;

create table tickets (
    ticket_number bigint not null unique,
    id varchar(16) primary key,
    type varchar(32) not null check (type in ('ACCESS_REQUEST', 'INCIDENT', 'ACCOUNT_SUPPORT')),
    resource varchar(255) not null,
    reason text not null,
    requested_by varchar(128) not null,
    requested_at timestamptz not null,
    status varchar(16) not null check (status = 'PENDING')
);

create index idx_tickets_requested_by_requested_at
    on tickets (requested_by, requested_at desc);
