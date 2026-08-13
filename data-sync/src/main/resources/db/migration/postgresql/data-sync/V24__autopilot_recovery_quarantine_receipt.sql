-- Durable, low-sensitive receipt for preauthorized autonomous dirty-record quarantine.
-- Selected row IDs, selectors, source values, prompts, model output, and credentials are intentionally absent.
CREATE TABLE data_sync_autopilot_recovery_quarantine_receipt (
    id BIGSERIAL PRIMARY KEY,
    receipt_id VARCHAR(128) NOT NULL,
    case_id BIGINT NOT NULL,
    request_digest CHAR(64) NOT NULL,
    preview_digest CHAR(64) NOT NULL,
    action_fingerprint CHAR(64) NOT NULL,
    sync_task_id BIGINT NOT NULL,
    execution_id BIGINT NOT NULL,
    represented_actor_id VARCHAR(128) NOT NULL,
    agent_id VARCHAR(128) NOT NULL,
    delegation_id VARCHAR(128) NOT NULL,
    selected_count INTEGER NOT NULL,
    affected_count INTEGER NOT NULL DEFAULT 0,
    operation_state VARCHAR(16) NOT NULL DEFAULT 'PROCESSING',
    receipt_state VARCHAR(16) NOT NULL DEFAULT 'PROCESSING',
    create_time TIMESTAMP NOT NULL DEFAULT LOCALTIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT LOCALTIMESTAMP,
    CONSTRAINT uk_data_sync_autopilot_quarantine_receipt UNIQUE (receipt_id),
    CONSTRAINT ck_data_sync_autopilot_quarantine_digest CHECK (
        request_digest ~ '^[0-9a-f]{64}$'
        AND preview_digest ~ '^[0-9a-f]{64}$'
        AND action_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_data_sync_autopilot_quarantine_count CHECK (
        selected_count BETWEEN 1 AND 500
        AND affected_count BETWEEN 0 AND selected_count),
    CONSTRAINT ck_data_sync_autopilot_quarantine_operation CHECK (
        operation_state IN ('PROCESSING', 'APPLIED')),
    CONSTRAINT ck_data_sync_autopilot_quarantine_receipt_state CHECK (
        receipt_state IN ('PROCESSING', 'COMPLETED'))
);

CREATE INDEX ix_data_sync_autopilot_quarantine_case
    ON data_sync_autopilot_recovery_quarantine_receipt (case_id, update_time);
