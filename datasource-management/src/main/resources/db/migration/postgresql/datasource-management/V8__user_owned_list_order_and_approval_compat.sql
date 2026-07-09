-- 数据源是项目内用户创建和授权使用的资源，普通列表默认按 id 倒序展示最新数据源。
-- 该索引服务 datasource_config 的 tenant/project/status 过滤与 id DESC 排序，避免数据源数量增长后列表排序退化。
CREATE INDEX IF NOT EXISTS idx_datasource_project_id_desc
    ON datasource_config (tenant_id, project_id, id DESC)
    WHERE status <> 'DELETED';

-- datasource-management 早期基线内置过 sync_task；正式同步任务已经由 data-sync 微服务承载。
-- 这里不直接 DROP approval_state，是为了避免旧表、旧审计和旧恢复链路在本地历史数据卷中启动失败。
UPDATE sync_task
SET approval_state = 'NOT_REQUIRED'
WHERE approval_state IS DISTINCT FROM 'NOT_REQUIRED';

DROP INDEX IF EXISTS idx_sync_task_approval;

COMMENT ON COLUMN sync_task.approval_state IS
    '历史兼容字段：普通用户侧作业/同步任务不再展示或筛选审批状态；正式审批能力应进入权限治理或独立审批事实表';

CREATE INDEX IF NOT EXISTS idx_sync_task_project_id_desc
    ON sync_task (tenant_id, project_id, id DESC)
    WHERE current_state NOT IN ('RECYCLED', 'DELETED');
