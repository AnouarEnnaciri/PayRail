-- M12: signed callback evidence. Callback records are audit/idempotency metadata,
-- never a second reservation, debit, compensation or terminal ledger effect.
CREATE TABLE settlement_callbacks (
    event_id UUID PRIMARY KEY,
    transfer_id UUID NOT NULL REFERENCES transfers(id),
    settlement_request_id UUID NOT NULL,
    payload_fingerprint VARCHAR(64) NOT NULL,
    external_status VARCHAR(16) NOT NULL CHECK (external_status IN ('SETTLED', 'REJECTED')),
    disposition VARCHAR(32) NOT NULL CHECK (disposition IN ('FINALIZED', 'ALREADY_FINALIZED')),
    received_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_settlement_callbacks_transfer_id
    ON settlement_callbacks(transfer_id, received_at);
