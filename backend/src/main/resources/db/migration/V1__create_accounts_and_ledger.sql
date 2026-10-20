CREATE TABLE accounts (
                          id UUID PRIMARY KEY,
                          reference VARCHAR(64) NOT NULL UNIQUE,
                          owner_id UUID,
                          account_type VARCHAR(16) NOT NULL,
                          currency CHAR(3) NOT NULL,
                          balance_minor BIGINT,
                          status VARCHAR(16) NOT NULL,
                          version BIGINT NOT NULL DEFAULT 0,
                          created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                          updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

                          CONSTRAINT ck_accounts_type
                              CHECK (account_type IN ('CUSTOMER', 'SYSTEM')),

                          CONSTRAINT ck_accounts_currency
                              CHECK (currency = 'MAD'),

                          CONSTRAINT ck_accounts_status
                              CHECK (status IN ('ACTIVE', 'BLOCKED', 'CLOSED')),

                          CONSTRAINT ck_accounts_balance_by_type
                              CHECK (
                                  (account_type = 'CUSTOMER'
                                      AND balance_minor IS NOT NULL
                                      AND balance_minor >= 0)
                                      OR
                                  (account_type = 'SYSTEM'
                                      AND balance_minor IS NULL)
                                  )
);


CREATE TABLE ledger_transactions (
                                     id UUID PRIMARY KEY,
                                     transfer_id UUID,
                                     effect_type VARCHAR(64) NOT NULL,
                                     reversal_of_transaction_id UUID,
                                     created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

                                     CONSTRAINT fk_ledger_transaction_reversal
                                         FOREIGN KEY (reversal_of_transaction_id)
                                             REFERENCES ledger_transactions(id)
);


CREATE TABLE ledger_entries (
                                id UUID PRIMARY KEY,
                                ledger_transaction_id UUID NOT NULL,
                                account_id UUID NOT NULL,
                                signed_amount_minor BIGINT NOT NULL,
                                currency CHAR(3) NOT NULL,
                                created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

                                CONSTRAINT fk_ledger_entry_transaction
                                    FOREIGN KEY (ledger_transaction_id)
                                        REFERENCES ledger_transactions(id),

                                CONSTRAINT fk_ledger_entry_account
                                    FOREIGN KEY (account_id)
                                        REFERENCES accounts(id),

                                CONSTRAINT ck_ledger_entry_currency
                                    CHECK (currency = 'MAD')
);


CREATE INDEX idx_ledger_entries_account_id
    ON ledger_entries(account_id);

CREATE INDEX idx_ledger_entries_transaction_id
    ON ledger_entries(ledger_transaction_id);