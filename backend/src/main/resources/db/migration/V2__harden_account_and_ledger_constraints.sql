ALTER TABLE accounts
    ADD CONSTRAINT ck_accounts_owner_by_type
        CHECK (
            (account_type = 'CUSTOMER' AND owner_id IS NOT NULL)
                OR
            (account_type = 'SYSTEM' AND owner_id IS NULL)
            );

ALTER TABLE ledger_entries
    ADD CONSTRAINT ck_ledger_entries_non_zero_amount
        CHECK (signed_amount_minor <> 0);

ALTER TABLE ledger_transactions
    ADD COLUMN funding_reference VARCHAR(128);

CREATE UNIQUE INDEX uq_ledger_transactions_funding_reference
    ON ledger_transactions(funding_reference)
    WHERE funding_reference IS NOT NULL;