-- Demo-only feature: guarded by @Profile("demo") and ROLE_OPS.
-- Unique request ids serialize retries; each inserted request and ledger posting
-- live in the same PostgreSQL transaction.
CREATE TABLE demo_funding_requests (
    request_id UUID PRIMARY KEY,
    account_reference VARCHAR(64) NOT NULL,
    amount_minor BIGINT NOT NULL CHECK (amount_minor > 0 AND amount_minor <= 1000000),
    initiated_by VARCHAR(128) NOT NULL,
    ledger_transaction_id UUID REFERENCES ledger_transactions(id),
    balance_after_minor BIGINT CHECK (balance_after_minor >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_demo_funding_completed_together CHECK (
        (ledger_transaction_id IS NULL AND balance_after_minor IS NULL)
        OR (ledger_transaction_id IS NOT NULL AND balance_after_minor IS NOT NULL)
    )
);
