-- M11: a database safety net for legal state changes, including manual SQL updates.
-- This trigger enforces the transition graph, NOT the existence of external evidence:
-- receipt verification and atomic financial effects remain the backend's responsibility.
CREATE FUNCTION payrail_guard_transfer_status_transition()
RETURNS trigger AS $$
BEGIN
    IF NEW.status IS NOT DISTINCT FROM OLD.status THEN
        RETURN NEW;
    END IF;

    IF OLD.transfer_type = 'EXTERNAL' AND NEW.transfer_type = 'EXTERNAL' AND (
        (OLD.status = 'PENDING_SETTLEMENT' AND
            NEW.status IN ('SETTLED', 'FAILED', 'RECONCILIATION_REQUIRED'))
        OR (OLD.status = 'RECONCILIATION_REQUIRED' AND
            NEW.status IN ('SETTLED', 'FAILED'))
    ) THEN
        RETURN NEW;
    END IF;

    RAISE EXCEPTION 'Illegal transfer state transition: % -> % (type %)',
        OLD.status, NEW.status, OLD.transfer_type
        USING ERRCODE = '23514';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_payrail_transfer_status_transition
BEFORE UPDATE OF status ON transfers
FOR EACH ROW
EXECUTE FUNCTION payrail_guard_transfer_status_transition();
