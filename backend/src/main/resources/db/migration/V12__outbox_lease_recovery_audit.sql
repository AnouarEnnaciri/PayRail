-- M14: audit expired worker leases without inferring any external financial outcome.
-- M10/V8 renamed the constraint to ck_settlement_attempts_outcome and added NOT_FOUND.
-- Preserve ALL prior legal outcomes; LEASE_EXPIRED is a technical attempt outcome only.
ALTER TABLE settlement_attempts
    DROP CONSTRAINT ck_settlement_attempts_outcome;

ALTER TABLE settlement_attempts
    ADD CONSTRAINT ck_settlement_attempts_outcome CHECK (outcome IN (
        'IN_FLIGHT', 'PENDING', 'SETTLED', 'REJECTED',
        'HTTP_ERROR', 'NETWORK_ERROR', 'INVALID_RESPONSE', 'NOT_FOUND',
        'LEASE_EXPIRED'
    ));
