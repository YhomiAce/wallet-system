-- User-facing record of a transfer/deposit: receipt breakdown + idempotency.
CREATE TABLE transactions (
    id                      uuid         PRIMARY KEY,
    type                    varchar(16)  NOT NULL CHECK (type IN ('TRANSFER','DEPOSIT')),
    idempotency_key         varchar(64)  NOT NULL,
    request_hash            varchar(64)  NOT NULL,           -- detects key reuse with a different payload
    source_account_id       uuid         NOT NULL REFERENCES accounts (id),
    destination_account_id  uuid         NOT NULL REFERENCES accounts (id),
    amount                  bigint       NOT NULL CHECK (amount > 0),
    stamp_duty              bigint       NOT NULL DEFAULT 0 CHECK (stamp_duty >= 0),
    fee                     bigint       NOT NULL DEFAULT 0 CHECK (fee >= 0),
    vat                     bigint       NOT NULL DEFAULT 0 CHECK (vat >= 0),
    total_debit             bigint       NOT NULL,
    status                  varchar(10)  NOT NULL CHECK (status IN ('PENDING','COMPLETED','REVERSED')),
    journal_id              uuid         UNIQUE REFERENCES journals (id),
    reference               varchar(40)  NOT NULL UNIQUE,
    narration               varchar(140),
    created_at              timestamptz  NOT NULL DEFAULT now(),
    updated_at              timestamptz  NOT NULL DEFAULT now(),

    -- Keys are scoped to the debited account so one customer cannot collide with another.
    CONSTRAINT uq_tx_idempotency UNIQUE (source_account_id, idempotency_key),
    CONSTRAINT chk_total CHECK (total_debit = amount + stamp_duty + fee + vat)
);

CREATE INDEX idx_tx_source ON transactions (source_account_id, created_at DESC);
CREATE INDEX idx_tx_destination ON transactions (destination_account_id, created_at DESC);

-- Transactional outbox: events are written in the SAME transaction as the money movement,
-- then published to Redis by a poller. No "committed but never announced" gap.
CREATE TABLE outbox_events (
    id            bigint       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    aggregate_id  uuid         NOT NULL,
    event_type    varchar(64)  NOT NULL,
    payload       jsonb        NOT NULL,
    created_at    timestamptz  NOT NULL DEFAULT now(),
    published_at  timestamptz
);

CREATE INDEX idx_outbox_unpublished ON outbox_events (id) WHERE published_at IS NULL;
