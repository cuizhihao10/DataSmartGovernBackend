-- datasource-management：项目级资源隔离下的 workspace 兼容清理。
--
-- 业务背景：
-- 1. 数据源是 FlashSync 同步任务、质量检查和 Agent 工具调用的基础资源，必须明确属于某个租户和项目。
-- 2. workspace 已经从普通用户可见层级中退出；用户不应在新建数据源、编辑数据源、授权数据源或创建同步任务时选择 workspace。
-- 3. 本迁移不删除列，只清理用户侧主对象和授权账本中的 workspace 值，并用注释明确保留字段的兼容用途。
--    这样既能让当前项目模型收敛，又能避免旧审计、旧脚本、旧执行事实在升级时直接失败。

SET search_path TO datasource_management, public;

UPDATE datasource_config
SET workspace_id = NULL,
    update_time = CURRENT_TIMESTAMP
WHERE workspace_id IS NOT NULL;

UPDATE datasource_authorization
SET workspace_id = NULL,
    update_time = CURRENT_TIMESTAMP
WHERE workspace_id IS NOT NULL;

-- datasource-management 早期基线中混放过 sync_template / sync_task。
-- 当前正式数据同步控制面已经迁入 data-sync 微服务，这里只做兼容清理和注释，不再扩展这些旧表能力。
UPDATE sync_template
SET workspace_id = NULL,
    update_time = CURRENT_TIMESTAMP
WHERE workspace_id IS NOT NULL;

UPDATE sync_task
SET workspace_id = NULL,
    update_time = CURRENT_TIMESTAMP
WHERE workspace_id IS NOT NULL;

COMMENT ON COLUMN datasource_config.workspace_id IS
    '历史兼容字段：数据源用户侧归属已经收敛为 tenant_id + project_id；新建、编辑、授权、连接测试和元数据发现不再按 workspace_id 判断范围。';

COMMENT ON COLUMN datasource_authorization.workspace_id IS
    '历史兼容字段：数据源实例授权按 datasource_id、tenant_id、project_id 和授权主体生效，不再按 workspace_id 缩小普通用户业务范围。';

COMMENT ON COLUMN datasource_readonly_sql_execution_audit.datasource_workspace_id IS
    '历史只读 SQL 审计快照字段：用于追溯旧版本数据源空间来源，不再作为普通用户数据源权限或列表过滤层级。';

COMMENT ON COLUMN sync_template.workspace_id IS
    '历史兼容字段：该表属于 datasource-management 早期混放的同步模板，正式同步控制面已迁入 data-sync；保留字段仅用于旧数据卷兼容。';

COMMENT ON COLUMN sync_task.workspace_id IS
    '历史兼容字段：该表属于 datasource-management 早期混放的同步任务，正式任务生命周期已迁入 data-sync；保留字段仅用于旧数据卷兼容。';

COMMENT ON COLUMN sync_agent_command_receipt.workspace_id IS
    '历史 Agent 命令 receipt 快照字段：Agent 内部工作区可在专用合同中表达，数据源用户侧资源范围不再依赖该字段。';

COMMENT ON INDEX idx_datasource_workspace IS
    '历史兼容索引：workspace_id 已退出数据源普通业务范围，后续确认无旧查询依赖后可在单独迁移中删除。';

COMMENT ON INDEX idx_datasource_authorization_subject IS
    '项目级授权查询索引：当前数据源实例授权以 tenant_id + project_id + subject 为主边界，workspace_id 不再参与普通业务授权。';

COMMENT ON INDEX idx_sync_template_workspace IS
    '历史兼容索引：datasource-management 内旧 sync_template 不再作为正式同步控制面扩展。';

COMMENT ON INDEX idx_sync_task_workspace_state IS
    '历史兼容索引：datasource-management 内旧 sync_task 不再作为正式同步控制面扩展。';
