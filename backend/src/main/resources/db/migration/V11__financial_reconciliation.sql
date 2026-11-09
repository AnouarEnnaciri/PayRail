-- M13: durable, inspectable reconciliation runs. Detection never changes money.
CREATE TABLE reconciliation_runs (
    id UUID PRIMARY KEY,
    started_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('COMPLETED', 'PARTIAL')),
    triggered_by VARCHAR(128) NOT NULL,
    checks_performed INTEGER NOT NULL CHECK (checks_performed >= 0),
    external_checked INTEGER NOT NULL CHECK (external_checked >= 0),
    issues_found INTEGER NOT NULL CHECK (issues_found >= 0)
);

CREATE TABLE reconciliation_issues (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES reconciliation_runs(id),
    issue_type VARCHAR(64) NOT NULL,
    resource_type VARCHAR(32) NOT NULL,
    resource_id UUID NOT NULL,
    expected_state VARCHAR(256) NOT NULL,
    observed_state VARCHAR(256) NOT NULL,
    detail VARCHAR(1024) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'RESOLVED')),
    detected_at TIMESTAMPTZ NOT NULL,
    resolved_at TIMESTAMPTZ,
    resolution_action VARCHAR(64),
    resolution_actor VARCHAR(128),
    evidence_reference VARCHAR(256),
    CONSTRAINT ck_reconciliation_issue_resolution CHECK (
        (status = 'OPEN' AND resolved_at IS NULL AND resolution_action IS NULL
            AND resolution_actor IS NULL)
        OR
        (status = 'RESOLVED' AND resolved_at IS NOT NULL AND resolution_action IS NOT NULL
            AND resolution_actor IS NOT NULL)
    ),
    CONSTRAINT uq_reconciliation_issue_per_run UNIQUE (run_id, issue_type, resource_type, resource_id)
);

CREATE INDEX idx_reconciliation_issues_run ON reconciliation_issues(run_id, issue_type);
CREATE INDEX idx_reconciliation_issues_open ON reconciliation_issues(status, detected_at);
