-- Data-sync task definitions are owned one-to-one by sync_task_id.
-- Template identifiers are no longer valid command inputs.
UPDATE task_data_sync_worker_command_outbox
SET status = 'DEAD_LETTER',
    last_error = '历史命令缺少 syncTaskId，无法在任务定义模型下安全投递',
    next_retry_at = NULL,
    update_time = CURRENT_TIMESTAMP
WHERE sync_task_id IS NULL
  AND status IN ('PENDING', 'DISPATCHING', 'DEFERRED', 'FAILED');

ALTER TABLE task_data_sync_worker_command_outbox
    DROP COLUMN IF EXISTS template_id;

ALTER TABLE task_data_sync_worker_command_outbox
    DROP COLUMN IF EXISTS sync_template_id;

COMMENT ON COLUMN task_data_sync_worker_command_outbox.sync_task_id IS
    'Required data-sync task identifier for new commands; also retained on receipts for execution correlation';
