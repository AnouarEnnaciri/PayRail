\set ON_ERROR_STOP on

-- Read-only verification of the most recent M16 outage batch.
-- LEFT JOIN is intentional: a transfer with no outbox row MUST fail verification.
DO $$
DECLARE
    latest_destination text;
    total bigint;
    unresolved bigint;
BEGIN
    SELECT destination_reference INTO latest_destination
    FROM transfers
    WHERE transfer_type = 'EXTERNAL' AND amount_minor = 19
      AND destination_reference LIKE 'LOAD-M16-%'
    ORDER BY created_at DESC, id DESC LIMIT 1;
    IF latest_destination IS NULL THEN
        RAISE EXCEPTION 'M16 recovery: no outage transfers found';
    END IF;

    SELECT count(*) INTO total
    FROM transfers
    WHERE transfer_type = 'EXTERNAL' AND amount_minor = 19
      AND destination_reference = latest_destination;

    SELECT count(*) INTO unresolved
    FROM transfers t
    LEFT JOIN outbox_events e ON e.aggregate_id = t.id
        AND e.aggregate_type = 'TRANSFER' AND e.event_type = 'SETTLEMENT_REQUESTED'
    WHERE t.transfer_type = 'EXTERNAL' AND t.amount_minor = 19
      AND t.destination_reference = latest_destination
      AND (
          t.status <> 'SETTLED'
          OR e.id IS NULL
          OR e.status <> 'PROCESSED'
          OR e.processed_at IS NULL
          OR (SELECT count(*) FROM ledger_transactions lt
              WHERE lt.transfer_id = t.id
                AND lt.effect_type = 'EXTERNAL_SETTLEMENT_FINALIZATION') <> 1
      );
    IF unresolved <> 0 THEN
        RAISE EXCEPTION 'M16 recovery: % of % transfers were not fully finalized', unresolved, total;
    END IF;
END
$$;

WITH latest_batch AS (
    SELECT destination_reference FROM transfers
    WHERE transfer_type = 'EXTERNAL' AND amount_minor = 19
      AND destination_reference LIKE 'LOAD-M16-%'
    ORDER BY created_at DESC, id DESC LIMIT 1
)
SELECT count(*) AS recovered_transfers,
       round(avg(extract(epoch FROM (e.processed_at - e.created_at)))::numeric, 2)
           AS mean_end_to_end_seconds_including_outage
FROM transfers t
JOIN outbox_events e ON e.aggregate_id = t.id
JOIN latest_batch b ON b.destination_reference = t.destination_reference
WHERE t.transfer_type = 'EXTERNAL' AND t.amount_minor = 19;

SELECT 'PASS: latest outage backlog drained and ledger finalization verified' AS verification;
