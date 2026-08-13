-- Durable low-sensitive Autopilot recovery control-plane facts.
-- Policy text, prompts, SQL, credentials, logs, row samples and model output stay out of these tables.
CREATE TABLE data_sync_autopilot_recovery_case (
    case_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    project_id BIGINT,
    sync_task_id BIGINT NOT NULL,
    root_execution_id BIGINT NOT NULL,
    current_execution_id BIGINT,
    execution_mode VARCHAR(32) NOT NULL,
    authorization_digest CHAR(64) NOT NULL,
    policy_digest CHAR(64) NOT NULL,
    case_state VARCHAR(32) NOT NULL,
    cycle INTEGER NOT NULL DEFAULT 1,
    max_cycles INTEGER NOT NULL,
    deadline_at TIMESTAMP NOT NULL,
    last_error_fingerprint CHAR(64) NOT NULL,
    repeated_error_count INTEGER NOT NULL DEFAULT 0,
    recovery_action VARCHAR(64) NOT NULL,
    risk_level VARCHAR(16) NOT NULL,
    repair_fingerprint CHAR(64) NOT NULL,
    attention_reason VARCHAR(96),
    version BIGINT NOT NULL DEFAULT 0,
    create_time TIMESTAMP NOT NULL DEFAULT LOCALTIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT LOCALTIMESTAMP,
    CONSTRAINT uk_data_sync_autopilot_case_identity UNIQUE
        (tenant_id, sync_task_id, root_execution_id, authorization_digest, repair_fingerprint),
    CONSTRAINT ck_data_sync_autopilot_case_mode CHECK (execution_mode = 'AUTOPILOT'),
    CONSTRAINT ck_data_sync_autopilot_case_state CHECK (case_state IN (
        'AUTO_APPROVED', 'WAITING_APPROVAL', 'MANUALLY_APPROVED', 'RECOVERY_STARTED',
        'RECOVERED', 'REJECTED', 'ATTENTION_REQUIRED', 'CANCELLED')),
    CONSTRAINT ck_data_sync_autopilot_case_cycle CHECK (cycle > 0 AND max_cycles > 0),
    CONSTRAINT ck_data_sync_autopilot_case_digest CHECK (
        authorization_digest ~ '^[0-9a-fA-F]{64}$'
        AND policy_digest ~ '^[0-9a-fA-F]{64}$'
        AND last_error_fingerprint ~ '^[0-9a-fA-F]{64}$'
        AND repair_fingerprint ~ '^[0-9a-fA-F]{64}$'),
    CONSTRAINT ck_data_sync_autopilot_case_risk CHECK (risk_level IN ('LOW', 'HIGH', 'CRITICAL'))
);

CREATE INDEX IF NOT EXISTS ix_data_sync_autopilot_case_task_state
    ON data_sync_autopilot_recovery_case (tenant_id, sync_task_id, case_state, update_time);

CREATE TABLE data_sync_autopilot_recovery_receipt (
    id BIGSERIAL PRIMARY KEY,
    receipt_id VARCHAR(128) NOT NULL,
    case_id BIGINT NOT NULL,
    receipt_digest CHAR(64) NOT NULL,
    receipt_type VARCHAR(32) NOT NULL,
    receipt_state VARCHAR(16) NOT NULL DEFAULT 'PROCESSING',
    resulting_case_state VARCHAR(32),
    resulting_version BIGINT,
    create_time TIMESTAMP NOT NULL DEFAULT LOCALTIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT LOCALTIMESTAMP,
    CONSTRAINT uk_data_sync_autopilot_receipt_id UNIQUE (receipt_id),
    CONSTRAINT ck_data_sync_autopilot_receipt_state CHECK (receipt_state IN ('PROCESSING', 'COMPLETED')),
    CONSTRAINT ck_data_sync_autopilot_receipt_digest CHECK (receipt_digest ~ '^[0-9a-fA-F]{64}$')
);

CREATE INDEX IF NOT EXISTS ix_data_sync_autopilot_receipt_case
    ON data_sync_autopilot_recovery_receipt (case_id, update_time);

ALTER TABLE data_sync_task_definition
    ADD COLUMN IF NOT EXISTS autopilot_policy TEXT;

COMMENT ON COLUMN data_sync_task_definition.autopilot_policy IS
    'Low-sensitive user-confirmed Autopilot authorization snapshot; no prompts, credentials, SQL, logs or model output';
