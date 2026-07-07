-- data-sync: 同步任务分组资源表。
--
-- 设计说明：
-- 1. V8 只在 data_sync_task 上增加了 group_code / group_name，适合快速完成列表筛选和导入导出。
-- 2. 当前产品已经需要“新增分组、删除分组、多级分组树、默认分组兜底、删除后任务回默认分组”等能力，
--    因此必须把分组升级为独立资源表，而不是继续依赖任务表里的两个展示字段。
-- 3. 分组表不保存源端/目标端连接信息、SQL、字段映射正文或样本数据，只保存运营菜单所需的低敏结构信息。
-- 4. 删除分组采用 archived 逻辑归档，任务本身不会被删除；业务层会把受影响任务迁回 DEFAULT/默认分组。
SET search_path TO data_sync, public;

CREATE TABLE IF NOT EXISTS data_sync_task_group
(
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL DEFAULT 0,
    project_id BIGINT,
    workspace_id BIGINT,
    parent_group_code VARCHAR(64),
    group_code VARCHAR(64) NOT NULL,
    group_name VARCHAR(128) NOT NULL,
    description VARCHAR(500),
    display_order INTEGER NOT NULL DEFAULT 100,
    default_group BOOLEAN NOT NULL DEFAULT FALSE,
    archived BOOLEAN NOT NULL DEFAULT FALSE,
    created_by BIGINT,
    updated_by BIGINT,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE data_sync_task_group IS
    '同步任务分组资源表：支撑多级分组树、默认分组、分组创建/删除、任务迁组和前端菜单渲染';
COMMENT ON COLUMN data_sync_task_group.tenant_id IS '租户 ID；同一 group_code 在不同租户下代表完全不同的业务分组';
COMMENT ON COLUMN data_sync_task_group.project_id IS '项目 ID；为空表示租户级分组，PROJECT 范围写入时通常要求明确项目';
COMMENT ON COLUMN data_sync_task_group.workspace_id IS '工作空间 ID；用于进一步隔离同一项目下不同团队或环境的任务菜单';
COMMENT ON COLUMN data_sync_task_group.parent_group_code IS '父分组稳定编码；使用业务编码而不是 parent_id，便于跨环境导入导出和 Agent 调用';
COMMENT ON COLUMN data_sync_task_group.group_code IS '分组稳定编码；创建任务、删除分组、导入导出和 Agent 工具调用都使用该字段';
COMMENT ON COLUMN data_sync_task_group.group_name IS '分组展示名称；允许中文，用于左侧导航栏和中间分组菜单栏展示';
COMMENT ON COLUMN data_sync_task_group.description IS '分组低敏说明；不得保存 SQL、连接串、凭据、样本数据或完整字段映射';
COMMENT ON COLUMN data_sync_task_group.display_order IS '展示排序值；默认分组固定为 0，业务分组通常从 100 开始';
COMMENT ON COLUMN data_sync_task_group.default_group IS '是否系统默认分组；默认分组不可通过普通删除入口归档';
COMMENT ON COLUMN data_sync_task_group.archived IS '是否已归档；归档分组不再出现在默认选择树中，也不能被新任务选择';

CREATE UNIQUE INDEX IF NOT EXISTS uk_data_sync_task_group_scope_code
    ON data_sync_task_group (
        tenant_id,
        COALESCE(project_id, -1),
        COALESCE(workspace_id, -1),
        group_code
    );

CREATE INDEX IF NOT EXISTS idx_data_sync_task_group_tree
    ON data_sync_task_group (tenant_id, project_id, workspace_id, parent_group_code, archived, display_order);

CREATE INDEX IF NOT EXISTS idx_data_sync_task_group_visible
    ON data_sync_task_group (tenant_id, project_id, workspace_id, archived, update_time);

-- 把历史任务上的分组快照抽取为正式分组资源。
-- 注意：这里不会修改任务表，只是让旧 group_code 能在新的树形菜单里被看到和继续选择。
INSERT INTO data_sync_task_group
(tenant_id, project_id, workspace_id, parent_group_code, group_code, group_name, description,
 display_order, default_group, archived, created_by, updated_by, create_time, update_time)
SELECT
    COALESCE(tenant_id, 0) AS tenant_id,
    project_id,
    workspace_id,
    NULL AS parent_group_code,
    COALESCE(NULLIF(group_code, ''), 'DEFAULT') AS group_code,
    COALESCE(MAX(NULLIF(group_name, '')), '默认分组') AS group_name,
    CASE
        WHEN COALESCE(NULLIF(group_code, ''), 'DEFAULT') = 'DEFAULT'
            THEN '系统默认分组：历史未分组任务和新建未指定分组任务都会归属到这里'
        ELSE '由历史 data_sync_task.group_code 自动迁移生成的正式分组资源'
    END AS description,
    CASE WHEN COALESCE(NULLIF(group_code, ''), 'DEFAULT') = 'DEFAULT' THEN 0 ELSE 100 END AS display_order,
    COALESCE(NULLIF(group_code, ''), 'DEFAULT') = 'DEFAULT' AS default_group,
    FALSE AS archived,
    NULL AS created_by,
    NULL AS updated_by,
    CURRENT_TIMESTAMP AS create_time,
    CURRENT_TIMESTAMP AS update_time
FROM data_sync_task
WHERE current_state <> 'DELETED'
GROUP BY COALESCE(tenant_id, 0), project_id, workspace_id, COALESCE(NULLIF(group_code, ''), 'DEFAULT')
ON CONFLICT DO NOTHING;
