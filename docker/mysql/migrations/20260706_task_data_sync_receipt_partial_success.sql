-- DataSmart Govern - task-management data-sync receipt 支持 PARTIALLY_SUCCEEDED
--
-- MySQL 兼容环境中，已有 task_data_sync_worker_execution_receipt 表的 CHECK 约束需要允许部分成功事件。
-- 如果当前 MySQL 版本或历史表不存在该 CHECK，本脚本也保持幂等。
SET @schema_name = DATABASE();

SET @drop_partial_receipt_check = (
    SELECT IF(
        EXISTS (
            SELECT 1
            FROM information_schema.check_constraints
            WHERE constraint_schema = @schema_name
              AND constraint_name = 'ck_task_datasync_exec_event_type'
        ),
        'ALTER TABLE task_data_sync_worker_execution_receipt DROP CHECK ck_task_datasync_exec_event_type',
        'SELECT 1'
    )
);
PREPARE stmt FROM @drop_partial_receipt_check;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

ALTER TABLE task_data_sync_worker_execution_receipt
    ADD CONSTRAINT ck_task_datasync_exec_event_type
        CHECK (event_type IN ('PROGRESS', 'CHECKPOINT', 'COMPLETE', 'PARTIALLY_SUCCEEDED', 'FAILED'));
