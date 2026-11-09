\set ON_ERROR_STOP on

-- Read-only checks. Run against the isolated payrail_m16 database after each scenario.
DO $$
DECLARE
    violations bigint;
BEGIN
    SELECT count(*) INTO violations
    FROM accounts
    WHERE account_type = 'CUSTOMER' AND (balance_minor IS NULL OR balance_minor < 0);
    IF violations <> 0 THEN
        RAISE EXCEPTION 'M16: % customer accounts have invalid balances', violations;
    END IF;

    SELECT count(*) INTO violations
    FROM accounts a
    LEFT JOIN (
        SELECT account_id, sum(signed_amount_minor) AS derived_balance
        FROM ledger_entries GROUP BY account_id
    ) totals ON totals.account_id = a.id
    WHERE a.account_type = 'CUSTOMER'
      AND a.balance_minor IS DISTINCT FROM coalesce(totals.derived_balance, 0);
    IF violations <> 0 THEN
        RAISE EXCEPTION 'M16: % customer balances differ from the ledger', violations;
    END IF;

    SELECT count(*) INTO violations FROM (
        SELECT t.id
        FROM ledger_transactions t
        LEFT JOIN ledger_entries e ON e.ledger_transaction_id = t.id
        GROUP BY t.id
        HAVING count(e.id) < 2 OR coalesce(sum(e.signed_amount_minor), 0) <> 0
    ) bad_transactions;
    IF violations <> 0 THEN
        RAISE EXCEPTION 'M16: % unbalanced or incomplete ledger transactions', violations;
    END IF;

    SELECT count(*) INTO violations FROM (
        SELECT transfer_id
        FROM ledger_transactions
        WHERE effect_type IN ('EXTERNAL_SETTLEMENT_FINALIZATION', 'EXTERNAL_COMPENSATION')
        GROUP BY transfer_id HAVING count(*) > 1
    ) duplicate_terminal_effects;
    IF violations <> 0 THEN
        RAISE EXCEPTION 'M16: % external transfers have duplicate terminal effects', violations;
    END IF;

    SELECT count(*) INTO violations
    FROM transfers t
    WHERE t.transfer_type = 'INTERNAL' AND (
        t.status <> 'COMPLETED'
        OR (SELECT count(*) FROM ledger_transactions lt
            WHERE lt.transfer_id = t.id AND lt.effect_type = 'INTERNAL_TRANSFER') <> 1
    );
    IF violations <> 0 THEN
        RAISE EXCEPTION 'M16: % internal transfers lack their unique financial effect', violations;
    END IF;

    SELECT count(*) INTO violations
    FROM transfers t
    WHERE t.transfer_type = 'EXTERNAL' AND (
        (SELECT count(*) FROM ledger_transactions lt
            WHERE lt.transfer_id = t.id AND lt.effect_type = 'EXTERNAL_RESERVATION') <> 1
        OR (t.status = 'SETTLED' AND
            (SELECT count(*) FROM ledger_transactions lt
             WHERE lt.transfer_id = t.id AND lt.effect_type = 'EXTERNAL_SETTLEMENT_FINALIZATION') <> 1)
        OR (t.status = 'FAILED' AND
            (SELECT count(*) FROM ledger_transactions lt
             WHERE lt.transfer_id = t.id AND lt.effect_type = 'EXTERNAL_COMPENSATION') <> 1)
        OR (t.status IN ('PENDING_SETTLEMENT', 'RECONCILIATION_REQUIRED') AND
            (SELECT count(*) FROM ledger_transactions lt
             WHERE lt.transfer_id = t.id
               AND lt.effect_type IN ('EXTERNAL_SETTLEMENT_FINALIZATION', 'EXTERNAL_COMPENSATION')) <> 0)
    );
    IF violations <> 0 THEN
        RAISE EXCEPTION 'M16: % external transfers have inconsistent financial effects', violations;
    END IF;

    -- Every committed external reservation must have its durable outbox event.
    SELECT count(*) INTO violations
    FROM transfers t
    LEFT JOIN outbox_events e ON e.aggregate_id = t.id
        AND e.aggregate_type = 'TRANSFER' AND e.event_type = 'SETTLEMENT_REQUESTED'
    WHERE t.transfer_type = 'EXTERNAL' AND e.id IS NULL;
    IF violations <> 0 THEN
        RAISE EXCEPTION 'M16: % external transfers have no durable outbox event', violations;
    END IF;
END
$$;

SELECT 'PASS: financial conservation and ledger invariants' AS verification;
