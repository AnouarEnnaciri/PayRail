\set ON_ERROR_STOP on

-- Repeatable: verify every M16 storm transaction independently, including past runs.
-- No global COUNT=1 assumption that would fail if an isolated run is repeated.
DO $$
DECLARE
    storm_count bigint;
    violations bigint;
BEGIN
    SELECT count(*) INTO storm_count
    FROM transfer_idempotency i
    JOIN transfers t ON t.id = i.transfer_id
    WHERE t.transfer_type = 'INTERNAL' AND t.amount_minor = 137
      AND i.idempotency_key LIKE 'm16-%-storm';
    IF storm_count = 0 THEN
        RAISE EXCEPTION 'M16 storm: no storm idempotency mapping found';
    END IF;

    SELECT count(*) INTO violations
    FROM transfers t
    WHERE t.transfer_type = 'INTERNAL' AND t.amount_minor = 137
      AND (
          (SELECT count(*) FROM transfer_idempotency i
           WHERE i.transfer_id = t.id AND i.idempotency_key LIKE 'm16-%-storm') <> 1
          OR
          (SELECT count(*) FROM ledger_transactions lt
           WHERE lt.transfer_id = t.id AND lt.effect_type = 'INTERNAL_TRANSFER') <> 1
      );
    IF violations <> 0 THEN
        RAISE EXCEPTION 'M16 storm: % transactions have missing or duplicate effects/mappings', violations;
    END IF;
END
$$;

SELECT count(*) AS verified_storm_runs
FROM transfer_idempotency i
JOIN transfers t ON t.id = i.transfer_id
WHERE t.transfer_type = 'INTERNAL' AND t.amount_minor = 137
  AND i.idempotency_key LIKE 'm16-%-storm';
SELECT 'PASS: each M16 storm key has one persisted transfer and ledger effect' AS verification;
