CREATE TABLE settlement_requests (
    settlement_request_id UUID PRIMARY KEY,
    request_hash CHAR(64) NOT NULL,
    amount_minor BIGINT NOT NULL CHECK (amount_minor > 0),
    destination_reference VARCHAR(128) NOT NULL CHECK (length(trim(destination_reference)) > 0),
    mode VARCHAR(40) NOT NULL CHECK (mode IN (
        'SUCCESS', 'DELAYED_SUCCESS', 'REJECTED',
        'TIMEOUT_BEFORE_PROCESSING', 'TIMEOUT_AFTER_PROCESSING'
    )),
    status VARCHAR(16) NOT NULL CHECK (status IN ('PENDING', 'SETTLED', 'REJECTED')),
    external_reference VARCHAR(80),
    settle_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT settled_requires_reference CHECK (status <> 'SETTLED' OR external_reference IS NOT NULL),
    CONSTRAINT pending_requires_deadline CHECK (status <> 'PENDING' OR settle_at IS NOT NULL)
);
CREATE UNIQUE INDEX settlement_requests_external_reference_uq
    ON settlement_requests (external_reference)
    WHERE external_reference IS NOT NULL;
