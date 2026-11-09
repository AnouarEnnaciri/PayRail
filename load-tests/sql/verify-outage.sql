\set ON_ERROR_STOP on

-- Run WHILE the simulator is stopped, just after 04-settlement-outage.js.
-- Scope to the most recent outage batch; older, already settled runs are valid.
DO $$
DECLARE
    latest_destination text;
    batch_count bigint;
    invalid bigint;
BEGIN
    SELECT destination_reference INTO latest_destination
    FROM transfers
    WHERE transfer_type = 'EXTERNAL' AND amount_minor = 19
      AND destination_reference LIKE 'LOAD-M16-%'
    ORDER BY created_at DESC, id DESC LIMIT 1;
    IF latest_destination IS NULL THEN
        RAISE EXCEPTION 'M16 outage: no outage transfers found';
    END IF;

    SELECT count(*) INTO batch_count
    FROM transfers
    WHERE transfer_type = 'EXTERNAL' AND amount_minor = 19
      AND destination_reference = latest_destination;

    SELECT count(*) INTO invalid
    FROM transfers t
    LEFT JOIN outbox_events e ON e.aggregate_id = t.id
        AND e.aggregate_type = 'TRANSFER' AND e.event_type = 'SETTLEMENT_REQUESTED'
    WHERE t.transfer_type = 'EXTERNAL' AND t.amount_minor = 19
      AND t.destination_reference = latest_destination
      AND (
          t.status NOT IN ('PENDING_SETTLEMENT', 'RECONCILIATION_REQUIRED')
          OR e.id IS NULL
          OR e.status NOT IN ('PENDING', 'PROCESSING', 'DEAD_LETTER')
      );
    IF invalid <> 0 THEN
        RAISE EXCEPTION 'M16 outage: % of % reservations are not durably pending', invalid, batch_count;
    END IF;
END
$$;

SELECT 'PASS: latest outage batch remains durably queued and unresolved' AS verification;
