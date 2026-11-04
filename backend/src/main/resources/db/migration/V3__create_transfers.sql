
CREATE TABLE transfers (
                           id UUID PRIMARY KEY,
                           sender_account_id UUID NOT NULL,
                           receiver_account_id UUID NOT NULL,
                           amount_minor BIGINT NOT NULL,
                           status VARCHAR(16) NOT NULL,
                           created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

                           CONSTRAINT fk_transfers_sender
                               FOREIGN KEY (sender_account_id)
                                   REFERENCES accounts(id),

                           CONSTRAINT fk_transfers_receiver
                               FOREIGN KEY (receiver_account_id)
                                   REFERENCES accounts(id),

                           CONSTRAINT ck_transfers_positive_amount
                               CHECK (amount_minor > 0),

                           CONSTRAINT ck_transfers_different_accounts
                               CHECK (sender_account_id <> receiver_account_id),

                           CONSTRAINT ck_transfers_status
                               CHECK (status IN ('COMPLETED', 'FAILED'))
);

ALTER TABLE ledger_transactions
    ADD CONSTRAINT fk_ledger_transactions_transfer
        FOREIGN KEY (transfer_id)
            REFERENCES transfers(id);
