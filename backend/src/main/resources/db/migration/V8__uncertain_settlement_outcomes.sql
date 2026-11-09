-- M10: a status lookup may observe NOT_FOUND without proving a financial rejection.
-- Leave existing attempt history and all ledger entries untouched.
ALTER TABLE settlement_attempts
    DROP CONSTRAINT settlement_attempts_outcome_check;

ALTER TABLE settlement_attempts
    ADD CONSTRAINT ck_settlement_attempts_outcome CHECK (outcome IN (
        'IN_FLIGHT', 'PENDING', 'SETTLED', 'REJECTED',
        'HTTP_ERROR', 'NETWORK_ERROR', 'INVALID_RESPONSE', 'NOT_FOUND'
    ));
