
CREATE TABLE transfer_idempotency (
                                      actor_id UUID NOT NULL,
                                      idempotency_key VARCHAR(128) NOT NULL,
                                      request_hash VARCHAR(64) NOT NULL,
                                      transfer_id UUID,
                                      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

                                      CONSTRAINT pk_transfer_idempotency
                                          PRIMARY KEY (actor_id, idempotency_key),

                                      CONSTRAINT fk_transfer_idempotency_transfer
                                          FOREIGN KEY (transfer_id)
                                              REFERENCES transfers(id),

                                      CONSTRAINT uq_transfer_idempotency_transfer
                                          UNIQUE (transfer_id)
);
