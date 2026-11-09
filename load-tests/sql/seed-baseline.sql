\set ON_ERROR_STOP on

-- Test-only setup for the isolated payrail_m16 PostgreSQL database.
-- Never run against the public demo or another environment.
-- Each funded sender has one ledger transaction with balanced entries.
DO $$
DECLARE
    i integer;
    suffix text;
    sender_ref text;
    receiver_ref text;
    sender_id uuid;
    receiver_id uuid;
    funding_id uuid;
    funding_tx_id uuid;
    alpha_owner constant uuid := 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa';
    initial_amount constant bigint := 200000;
BEGIN
    IF current_database() <> 'payrail_m16' THEN
        RAISE EXCEPTION 'M16 fixtures can only be seeded in payrail_m16';
    END IF;
    SELECT id INTO STRICT funding_id FROM accounts WHERE reference = 'DEMO-SYSTEM-FUNDING'
        AND account_type = 'SYSTEM' AND status = 'ACTIVE';

    FOR i IN 1..20 LOOP
        suffix := lpad(i::text, 2, '0');
        sender_ref := 'M16-BASE-S-' || suffix;
        receiver_ref := 'M16-BASE-R-' || suffix;

        SELECT id INTO sender_id FROM accounts WHERE reference = sender_ref;
        IF sender_id IS NULL THEN
            sender_id := gen_random_uuid();
            funding_tx_id := gen_random_uuid();

            INSERT INTO accounts (id, reference, owner_id, account_type, currency, balance_minor, status)
            VALUES (sender_id, sender_ref, alpha_owner, 'CUSTOMER', 'MAD', initial_amount, 'ACTIVE');
            INSERT INTO ledger_transactions (id, effect_type, funding_reference)
            VALUES (funding_tx_id, 'INITIAL_FUNDING', 'INITIAL-FUNDING:' || sender_id::text);
            INSERT INTO ledger_entries (id, ledger_transaction_id, account_id, signed_amount_minor, currency)
            VALUES
              (gen_random_uuid(), funding_tx_id, funding_id, -initial_amount, 'MAD'),
              (gen_random_uuid(), funding_tx_id, sender_id, initial_amount, 'MAD');
        END IF;

        SELECT id INTO receiver_id FROM accounts WHERE reference = receiver_ref;
        IF receiver_id IS NULL THEN
            receiver_id := gen_random_uuid();
            INSERT INTO accounts (id, reference, owner_id, account_type, currency, balance_minor, status)
            VALUES (receiver_id, receiver_ref, alpha_owner, 'CUSTOMER', 'MAD', 0, 'ACTIVE');
        END IF;

        IF NOT EXISTS (
            SELECT 1 FROM accounts WHERE id IN (sender_id, receiver_id)
            GROUP BY owner_id, account_type, currency, status
            HAVING count(*) = 2 AND owner_id = alpha_owner
                AND account_type = 'CUSTOMER' AND currency = 'MAD' AND status = 'ACTIVE'
        ) THEN
            RAISE EXCEPTION 'Baseline fixture % has invalid ownership, currency or status', suffix;
        END IF;
    END LOOP;
END
$$;

SELECT count(*) AS baseline_accounts
FROM accounts WHERE reference LIKE 'M16-BASE-%';
