-- Durable, low-sensitive consumer-result facts for the existing Autopilot trigger outbox.
-- data-sync computes consumer_result_digest from event ID, execution ID, status, reason code, and optional case ID.
-- No Python answer, model text, exception body, prompt, evidence, SQL, credentials, or Kafka payload is stored.
ALTER TABLE data_sync_autopilot_recovery_trigger_outbox
    ADD COLUMN consumer_result_digest VARCHAR(64),
    ADD COLUMN consumer_result_status VARCHAR(32),
    ADD COLUMN consumer_result_reason_code VARCHAR(96),
    ADD COLUMN consumer_result_case_id BIGINT,
    ADD COLUMN consumed_at TIMESTAMP,
    ADD CONSTRAINT ck_data_sync_autopilot_trigger_consumer_result CHECK (
        (
            consumer_result_digest IS NULL
            AND consumer_result_status IS NULL
            AND consumer_result_reason_code IS NULL
            AND consumer_result_case_id IS NULL
            AND consumed_at IS NULL
        )
        OR
        (
            consumer_result_digest IS NOT NULL
            AND consumer_result_digest ~ '^[0-9a-f]{64}$'
            AND consumer_result_status IS NOT NULL
            AND consumer_result_status IN (
                'AUTO_APPROVED',
                'WAITING_APPROVAL',
                'MANUALLY_APPROVED',
                'RECOVERY_STARTED',
                'RECOVERED',
                'REJECTED',
                'ATTENTION_REQUIRED',
                'CANCELLED',
                'FAILED'
            )
            AND consumer_result_reason_code IS NOT NULL
            AND consumer_result_reason_code ~ '^[A-Z][A-Z0-9_]{0,95}$'
            AND (consumer_result_case_id IS NULL OR consumer_result_case_id > 0)
            AND consumed_at IS NOT NULL
        )
    );

COMMENT ON COLUMN data_sync_autopilot_recovery_trigger_outbox.consumer_result_digest IS
    'Server-computed SHA-256 digest of compact Autopilot trigger consumer-result facts';
