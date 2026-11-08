-- M8: auditable dispatch attempts. An attempt is NOT a financial ledger effect.
-- In-flight attempts may remain after a worker crash; the outbox lease enables safe retry.
CREATE TABLE settlement_attempts (
    id UUID PRIMARY KEY,
    outbox_event_id UUID NOT NULL REFERENCES outbox_events(id),
    transfer_id UUID NOT NULL REFERENCES transfers(id),
    settlement_request_id UUID NOT NULL,
    attempt_number INTEGER NOT NULL CHECK (attempt_number > 0),
    started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ,
    outcome VARCHAR(32) NOT NULL CHECK (outcome IN (
        'IN_FLIGHT', 'PENDING', 'SETTLED', 'REJECTED',
        'HTTP_ERROR', 'NETWORK_ERROR', 'INVALID_RESPONSE'
    )),
    http_status INTEGER,
    error_category VARCHAR(64),
    external_reference VARCHAR(128),
    last_error VARCHAR(500),
    latency_ms BIGINT CHECK (latency_ms IS NULL OR latency_ms >= 0),
    CONSTRAINT uq_settlement_attempt_sequence UNIQUE (outbox_event_id, attempt_number),
    CONSTRAINT ck_attempt_completion CHECK (
        (outcome = 'IN_FLIGHT' AND completed_at IS NULL)
        OR (outcome <> 'IN_FLIGHT' AND completed_at IS NOT NULL)
    )
);
CREATE INDEX idx_settlement_attempts_transfer ON settlement_attempts(transfer_id, started_at);
CREATE INDEX idx_settlement_attempts_outcome ON settlement_attempts(outcome, completed_at);
