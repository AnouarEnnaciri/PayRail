-- M7: an external reservation is a transfer with no local recipient account.
ALTER TABLE transfers
    ADD COLUMN transfer_type VARCHAR(16) NOT NULL DEFAULT 'INTERNAL',
    ADD COLUMN destination_reference VARCHAR(128),
    ADD COLUMN settlement_request_id UUID;

ALTER TABLE transfers ALTER COLUMN receiver_account_id DROP NOT NULL;
ALTER TABLE transfers ALTER COLUMN status TYPE VARCHAR(32);
ALTER TABLE transfers DROP CONSTRAINT ck_transfers_different_accounts;
ALTER TABLE transfers DROP CONSTRAINT ck_transfers_status;

ALTER TABLE transfers ADD CONSTRAINT ck_transfers_type_and_state CHECK (
    (transfer_type = 'INTERNAL'
        AND receiver_account_id IS NOT NULL
        AND sender_account_id <> receiver_account_id
        AND destination_reference IS NULL
        AND settlement_request_id IS NULL
        AND status IN ('COMPLETED', 'FAILED'))
    OR
    (transfer_type = 'EXTERNAL'
        AND receiver_account_id IS NULL
        AND destination_reference IS NOT NULL
        AND length(trim(destination_reference)) > 0
        AND settlement_request_id IS NOT NULL
        AND status IN ('PENDING_SETTLEMENT', 'SETTLED', 'FAILED', 'RECONCILIATION_REQUIRED'))
);

CREATE UNIQUE INDEX uq_transfers_settlement_request_id
    ON transfers(settlement_request_id) WHERE settlement_request_id IS NOT NULL;

-- A reservation can create only one corresponding financial ledger effect.
CREATE UNIQUE INDEX uq_external_reservation_ledger
    ON ledger_transactions(transfer_id) WHERE effect_type = 'EXTERNAL_RESERVATION';

-- System balances remain ledger-derived. This row is not updated by reservations.
INSERT INTO accounts (id, reference, owner_id, account_type, currency, balance_minor, status)
VALUES (gen_random_uuid(), 'SETTLEMENT_CLEARING', NULL, 'SYSTEM', 'MAD', NULL, 'ACTIVE')
ON CONFLICT (reference) DO NOTHING;

CREATE TABLE outbox_events (
    id UUID PRIMARY KEY,
    aggregate_type VARCHAR(32) NOT NULL CHECK (aggregate_type = 'TRANSFER'),
    aggregate_id UUID NOT NULL REFERENCES transfers(id),
    event_type VARCHAR(64) NOT NULL CHECK (event_type = 'SETTLEMENT_REQUESTED'),
    payload_json JSONB NOT NULL CHECK (jsonb_typeof(payload_json) = 'object'),
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'PROCESSING', 'PROCESSED', 'DEAD_LETTER')),
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    available_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    locked_until TIMESTAMPTZ,
    locked_by VARCHAR(128),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    processed_at TIMESTAMPTZ,
    last_error TEXT,
    CONSTRAINT uq_outbox_aggregate_event UNIQUE (aggregate_type, aggregate_id, event_type)
);

CREATE INDEX idx_outbox_claim ON outbox_events(status, available_at, created_at);
