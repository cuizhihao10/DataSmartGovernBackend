-- 普通同步任务已经收敛为“租户 / 项目下的用户自有资源”，列表、详情和导入导出不再把审批状态作为用户可见字段。
-- approval_state 暂时保留是为了兼容历史发布、恢复、高风险治理和旧数据迁移路径；后续若审批事实独立表闭环，再单独物理删除该列。
UPDATE data_sync_task
SET approval_state = 'NOT_REQUIRED'
WHERE approval_state IS DISTINCT FROM 'NOT_REQUIRED';

DROP INDEX IF EXISTS idx_data_sync_task_approval;

COMMENT ON COLUMN data_sync_task.approval_state IS
    '历史兼容字段：普通用户侧同步任务不再展示或筛选审批状态；高风险确认后续应迁移到独立治理事实表，而不是继续污染任务主列表';

-- 用户侧任务列表默认按 id 倒序展示。该索引匹配 tenant/project/state/group 过滤后的新任务优先查询，避免 update_time 改动导致旧任务压过新任务。
CREATE INDEX IF NOT EXISTS idx_data_sync_task_project_id_desc
    ON data_sync_task (tenant_id, project_id, id DESC)
    WHERE current_state NOT IN ('RECYCLED', 'DELETED');

CREATE INDEX IF NOT EXISTS idx_data_sync_task_group_id_desc
    ON data_sync_task (tenant_id, project_id, group_code, id DESC)
    WHERE current_state NOT IN ('RECYCLED', 'DELETED');
