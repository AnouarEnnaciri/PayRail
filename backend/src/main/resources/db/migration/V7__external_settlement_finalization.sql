-- M9: mutually exclusive terminal financial effects, enforced at database level.
-- A single transfer cannot ever have both a settlement finalization and compensation.
CREATE UNIQUE INDEX uq_external_terminal_effect_per_transfer
    ON ledger_transactions(transfer_id)
    WHERE effect_type IN ('EXTERNAL_SETTLEMENT_FINALIZATION', 'EXTERNAL_COMPENSATION');

-- System account balances are ledger-derived and never updated in accounts.balance_minor.
INSERT INTO accounts (id, reference, owner_id, account_type, currency, balance_minor, status)
VALUES (gen_random_uuid(), 'EXTERNAL_SETTLED', NULL, 'SYSTEM', 'MAD', NULL, 'ACTIVE')
ON CONFLICT (reference) DO NOTHING;
