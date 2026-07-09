-- MySQL 兼容迁移：项目级资源隔离下的 workspace 兼容清理。
--
-- 说明：
-- 1. PostgreSQL 是当前目标事实库，data-sync 主迁移见
--    data-sync/V14__project_scope_workspace_compatibility_cleanup.sql，
--    datasource-management 主迁移见
--    datasource-management/V7__project_scope_workspace_compatibility_cleanup.sql。
-- 2. 本脚本只用于仍在 MySQL 兼容路径上的本地环境或历史数据卷，避免旧数据继续把 workspace
--    误表达成普通用户可见层级。
-- 3. 本脚本不删除列、不删除历史执行、日志、审计事实，只清理用户侧主对象和授权主对象。

USE datasmart_govern;

-- 处理 data_sync_task_group：同一项目下多个 workspace DEFAULT 直接清空会触发唯一键冲突。
-- 先保留排序最靠前的一条项目级分组，其他同名分组归档并改成历史编码。
UPDATE data_sync_task_group target
JOIN (
    SELECT
        id,
        ROW_NUMBER() OVER (
            PARTITION BY tenant_id, IFNULL(project_id, -1), group_code
            ORDER BY
                IF(workspace_id IS NULL, 0, 1),
                IF(default_group = 1, 0, 1),
                display_order ASC,
                id ASC
        ) AS keep_rank
    FROM data_sync_task_group
    WHERE workspace_id IS NOT NULL
       OR EXISTS (
           SELECT 1
           FROM data_sync_task_group duplicated
           WHERE duplicated.id <> data_sync_task_group.id
             AND duplicated.tenant_id = data_sync_task_group.tenant_id
             AND IFNULL(duplicated.project_id, -1) = IFNULL(data_sync_task_group.project_id, -1)
             AND duplicated.group_code = data_sync_task_group.group_code
       )
) ranked ON target.id = ranked.id
SET
    target.group_code = CASE
        WHEN ranked.keep_rank = 1 THEN target.group_code
        ELSE CONCAT(LEFT(target.group_code, 30), '_LEGACY_', target.id)
    END,
    target.group_name = CASE
        WHEN ranked.keep_rank = 1 THEN target.group_name
        ELSE LEFT(CONCAT(target.group_name, '（历史工作空间分组）'), 128)
    END,
    target.description = CASE
        WHEN ranked.keep_rank = 1 THEN target.description
        ELSE LEFT(CONCAT(IFNULL(target.description, ''), '；已在项目级隔离收敛迁移中归档，原 workspaceId=', IFNULL(CAST(target.workspace_id AS CHAR), 'NULL')), 500)
    END,
    target.archived = CASE WHEN ranked.keep_rank = 1 THEN target.archived ELSE 1 END,
    target.default_group = CASE WHEN ranked.keep_rank = 1 THEN target.default_group ELSE 0 END,
    target.workspace_id = NULL,
    target.update_time = NOW();

UPDATE data_sync_template
SET workspace_id = NULL,
    update_time = NOW()
WHERE workspace_id IS NOT NULL;

UPDATE data_sync_task
SET workspace_id = NULL,
    update_time = NOW()
WHERE workspace_id IS NOT NULL;

UPDATE datasource_config
SET workspace_id = NULL,
    update_time = NOW()
WHERE workspace_id IS NOT NULL;

UPDATE datasource_authorization
SET workspace_id = NULL,
    update_time = NOW()
WHERE workspace_id IS NOT NULL;

UPDATE sync_template
SET workspace_id = NULL,
    update_time = NOW()
WHERE workspace_id IS NOT NULL;

UPDATE sync_task
SET workspace_id = NULL,
    update_time = NOW()
WHERE workspace_id IS NOT NULL;

ALTER TABLE data_sync_template
    MODIFY workspace_id BIGINT NULL COMMENT '历史兼容字段：FlashSync 用户侧同步模板不再按 workspace 归属，正式隔离边界为 tenant_id + project_id。';

ALTER TABLE data_sync_task
    MODIFY workspace_id BIGINT NULL COMMENT '历史兼容字段：同步任务用户侧生命周期、分组、列表、编辑、运行和权限校验均不再使用 workspace_id。';

ALTER TABLE data_sync_task_group
    MODIFY workspace_id BIGINT NULL COMMENT '历史兼容字段：任务分组树已收敛到项目级，同一项目下只保留一个 DEFAULT 默认分组。';

ALTER TABLE datasource_config
    MODIFY workspace_id BIGINT NULL COMMENT '历史兼容字段：数据源用户侧归属已经收敛为 tenant_id + project_id，新建、编辑、授权和元数据发现不再按 workspace_id 判断范围。';

ALTER TABLE datasource_authorization
    MODIFY workspace_id BIGINT NULL COMMENT '历史兼容字段：数据源实例授权按 datasource_id、tenant_id、project_id 和授权主体生效，不再按 workspace_id 缩小普通用户业务范围。';

ALTER TABLE datasource_readonly_sql_execution_audit
    MODIFY datasource_workspace_id BIGINT NULL COMMENT '历史只读 SQL 审计快照字段：用于追溯旧版本数据源空间来源，不再作为普通用户数据源权限或列表过滤层级。';

ALTER TABLE sync_template
    MODIFY workspace_id BIGINT NULL COMMENT '历史兼容字段：该表属于 datasource-management 早期混放的同步模板，正式同步控制面已迁入 data-sync。';

ALTER TABLE sync_task
    MODIFY workspace_id BIGINT NULL COMMENT '历史兼容字段：该表属于 datasource-management 早期混放的同步任务，正式任务生命周期已迁入 data-sync。';

ALTER TABLE sync_agent_command_receipt
    MODIFY workspace_id BIGINT NULL COMMENT '历史 Agent 命令 receipt 快照字段：Agent 内部工作区可在专用合同中表达，数据源用户侧资源范围不再依赖该字段。';
