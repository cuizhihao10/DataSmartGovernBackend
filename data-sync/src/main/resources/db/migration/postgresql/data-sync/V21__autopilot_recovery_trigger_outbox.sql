-- Durable Kafka outbox for low-sensitive Autopilot recovery trigger events.
-- The payload is produced by a fixed Java record and never contains SQL, credentials,
-- source rows, raw logs, prompts, model output, broker addresses or internal endpoints.
CREATE TABLE data_sync_autopilot_recovery_trigger_outbox (
    id BIGSERIAL PRIMARY KEY,
    event_id VARCHAR(96) NOT NULL,
    tenant_id BIGINT NOT NULL,
    project_id BIGINT,
    sync_task_id BIGINT NOT NULL,
    root_execution_id BIGINT NOT NULL,
    current_execution_id BIGINT NOT NULL,
    cycle INTEGER NOT NULL,
    payload_json TEXT NOT NULL,
    outbox_state VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    max_attempt_count INTEGER NOT NULL,
    next_retry_at TIMESTAMP,
    last_attempt_at TIMESTAMP,
    delivered_at TIMESTAMP,
    dead_letter_at TIMESTAMP,
    last_error_code VARCHAR(96),
    last_error_summary VARCHAR(256),
    create_time TIMESTAMP NOT NULL DEFAULT LOCALTIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT LOCALTIMESTAMP,
    CONSTRAINT uk_data_sync_autopilot_trigger_event UNIQUE (event_id),
    CONSTRAINT ck_data_sync_autopilot_trigger_cycle CHECK (cycle > 0),
    CONSTRAINT ck_data_sync_autopilot_trigger_attempts CHECK (
        attempt_count >= 0 AND max_attempt_count > 0),
    CONSTRAINT ck_data_sync_autopilot_trigger_state CHECK (outbox_state IN (
        'PENDING', 'DISPATCHING', 'RETRY_WAIT', 'DELIVERED', 'DEAD_LETTER'))
);

CREATE INDEX IF NOT EXISTS ix_data_sync_autopilot_trigger_due
    ON data_sync_autopilot_recovery_trigger_outbox
        (outbox_state, next_retry_at, last_attempt_at, create_time);

CREATE INDEX IF NOT EXISTS ix_data_sync_autopilot_trigger_task
    ON data_sync_autopilot_recovery_trigger_outbox
        (tenant_id, sync_task_id, cycle, create_time);

COMMENT ON TABLE data_sync_autopilot_recovery_trigger_outbox IS
    'Durable low-sensitive Kafka outbox for user-authorized Autopilot recovery triggers';
