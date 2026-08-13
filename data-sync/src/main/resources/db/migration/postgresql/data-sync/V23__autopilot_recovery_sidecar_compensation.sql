-- Durable, low-sensitive replay journal for Autopilot sidecar transactions that failed before the normal
-- trigger outbox could prove completion. This journal records a local invocation only; it never stores a
-- transport body, raw exception text, SQL, credentials, source rows, URLs, prompts, or model output.
CREATE TABLE data_sync_autopilot_recovery_sidecar_compensation (
    id BIGSERIAL PRIMARY KEY,
    compensation_key VARCHAR(96) NOT NULL,
    operation VARCHAR(32) NOT NULL,
    sync_task_id BIGINT NOT NULL,
    sync_execution_id BIGINT NOT NULL,
    error_code VARCHAR(96),
    issue_codes_json VARCHAR(2048) NOT NULL DEFAULT '[]',
    compensation_state VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    claim_token VARCHAR(64),
    attempt_count INTEGER NOT NULL DEFAULT 0,
    max_attempt_count INTEGER NOT NULL,
    next_retry_at TIMESTAMP,
    last_attempt_at TIMESTAMP,
    resolved_at TIMESTAMP,
    dead_letter_at TIMESTAMP,
    last_error_code VARCHAR(96),
    last_error_summary VARCHAR(256),
    create_time TIMESTAMP NOT NULL DEFAULT LOCALTIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT LOCALTIMESTAMP,
    CONSTRAINT uk_data_sync_autopilot_sidecar_compensation UNIQUE (compensation_key),
    CONSTRAINT ck_data_sync_autopilot_sidecar_operation CHECK (
        operation IN ('TRIGGER_FAILURE', 'SUCCESS_FINALIZATION')),
    CONSTRAINT ck_data_sync_autopilot_sidecar_state CHECK (
        compensation_state IN ('PENDING', 'DISPATCHING', 'RETRY_WAIT', 'RESOLVED', 'DEAD_LETTER')),
    CONSTRAINT ck_data_sync_autopilot_sidecar_attempts CHECK (
        attempt_count >= 0 AND max_attempt_count > 0),
    CONSTRAINT ck_data_sync_autopilot_sidecar_error_code CHECK (
        error_code IS NULL OR error_code ~ '^[A-Z][A-Z0-9_:.\\-]{0,95}$')
);

CREATE INDEX IF NOT EXISTS ix_data_sync_autopilot_sidecar_due
    ON data_sync_autopilot_recovery_sidecar_compensation
        (compensation_state, next_retry_at, last_attempt_at, create_time);

COMMENT ON TABLE data_sync_autopilot_recovery_sidecar_compensation IS
    'Bounded local replay journal for failed Autopilot trigger/finalization sidecars';
