-- 用户侧治理任务列表默认按 id 倒序展示最新任务。
-- 队列认领、租约恢复和延迟重排仍继续使用 queued_time / lease_expire_time 等专用索引，不能和用户列表排序混用。
CREATE INDEX IF NOT EXISTS idx_task_project_id_desc
    ON task (tenant_id, project_id, id DESC);

CREATE INDEX IF NOT EXISTS idx_task_project_status_id_desc
    ON task (tenant_id, project_id, status, id DESC);

CREATE INDEX IF NOT EXISTS idx_task_draft_project_id_desc
    ON task_draft (tenant_id, project_id, id DESC);
