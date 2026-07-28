-- Remove the final persisted terminology from the former standalone sync-template model.
UPDATE data_sync_task
SET run_mode = 'MANUAL',
    update_time = CURRENT_TIMESTAMP
WHERE UPPER(run_mode) = 'TEMPLATE';

ALTER TABLE data_sync_task
    ALTER COLUMN run_mode SET DEFAULT 'MANUAL';

UPDATE data_sync_audit_record
SET action_type = 'SAVE_TASK_DEFINITION'
WHERE action_type = 'CREATE_TEMPLATE';

UPDATE data_sync_audit_record
SET action_type = 'PRECHECK_TASK'
WHERE action_type = 'VALIDATE_TEMPLATE';

COMMENT ON TABLE data_sync_audit_record IS
    '数据同步审计记录表，用于追踪任务定义、执行、checkpoint、恢复和人工操作';
COMMENT ON COLUMN data_sync_audit_record.action_type IS
    '审计动作类型，例如 SAVE_TASK_DEFINITION、CREATE_TASK、PRECHECK_TASK、RUN_TASK、UPDATE_CHECKPOINT';
