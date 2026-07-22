-- Agent failure recovery keeps dirty-row quarantine decisions durable and auditable.
-- A quarantined row is not deleted from the source. Its exact PRIMARY_KEY_EQ selector
-- is only forwarded over the internal data-sync -> datasource-management execution contract.
ALTER TABLE data_sync_error_sample
    ADD COLUMN IF NOT EXISTS resolution_status VARCHAR(32) NOT NULL DEFAULT 'OPEN',
    ADD COLUMN IF NOT EXISTS resolution_action VARCHAR(64),
    ADD COLUMN IF NOT EXISTS resolution_note_digest VARCHAR(64),
    ADD COLUMN IF NOT EXISTS resolved_by BIGINT,
    ADD COLUMN IF NOT EXISTS resolved_at TIMESTAMP WITHOUT TIME ZONE;

ALTER TABLE data_sync_error_sample
    DROP CONSTRAINT IF EXISTS ck_data_sync_error_resolution_status;

ALTER TABLE data_sync_error_sample
    ADD CONSTRAINT ck_data_sync_error_resolution_status
        CHECK (resolution_status IN ('OPEN', 'QUARANTINED', 'FIX_CONFIRMED', 'REPLAYED'));

CREATE INDEX IF NOT EXISTS idx_data_sync_error_active_quarantine
    ON data_sync_error_sample (sync_task_id, resolution_status, id);

COMMENT ON COLUMN data_sync_error_sample.resolution_status IS
    'Agent/运营修复状态：OPEN、QUARANTINED、FIX_CONFIRMED、REPLAYED；QUARANTINED 仅在重跑时跳过，不删除源数据';
COMMENT ON COLUMN data_sync_error_sample.resolution_note_digest IS
    '修复说明摘要的 SHA-256，仅用于审计绑定，不保存模型回复、SQL 或原始数据';
