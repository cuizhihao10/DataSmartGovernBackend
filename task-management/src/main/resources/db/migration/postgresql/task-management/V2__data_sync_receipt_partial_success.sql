-- DataSmart Govern - task-management data-sync receipt 部分成功事件
--
-- PARTIALLY_SUCCEEDED 用于 OBJECT_LIST、多对象、多分片或未来 DataX-style TaskGroup 场景：
-- 一部分对象已经成功落地，另一部分对象失败且需要选择性重试。它不应被伪装成 COMPLETE 或 FAILED。
SET search_path TO task_management, public;

ALTER TABLE task_data_sync_worker_execution_receipt
    DROP CONSTRAINT IF EXISTS ck_task_datasync_exec_event_type;

ALTER TABLE task_data_sync_worker_execution_receipt
    ADD CONSTRAINT ck_task_datasync_exec_event_type CHECK (
        event_type IN ('PROGRESS', 'CHECKPOINT', 'COMPLETE', 'PARTIALLY_SUCCEEDED', 'FAILED')
    );

COMMENT ON COLUMN task_data_sync_worker_execution_receipt.event_type
    IS '执行事件类型：PROGRESS、CHECKPOINT、COMPLETE、PARTIALLY_SUCCEEDED、FAILED；部分成功表示已有对象完成但仍有失败对象需要选择性重试';
