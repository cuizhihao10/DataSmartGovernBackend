-- data-sync: 同步任务分组资源表 MySQL 兼容迁移。
--
-- 与 PostgreSQL V9__data_sync_task_group_resource.sql 保持业务语义一致：
-- 1. data_sync_task_group 是正式分组资源表，用于多级菜单、新增/删除分组和默认分组兜底。
-- 2. MySQL 唯一索引对 NULL 的处理与 PostgreSQL 表达式索引不同，因此这里使用生成列把 NULL 规整为 -1。
-- 3. 迁移会把历史任务表上的 group_code/group_name 抽取成正式分组资源，保证升级后旧任务仍可在分组树中显示。

CREATE TABLE IF NOT EXISTS data_sync_task_group (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL DEFAULT 0 COMMENT '租户 ID；同一 group_code 在不同租户下代表不同业务分组',
    project_id BIGINT NULL COMMENT '项目 ID；为空表示租户级分组',
    workspace_id BIGINT NULL COMMENT '工作空间 ID；为空表示不限定工作空间',
    parent_group_code VARCHAR(64) NULL COMMENT '父分组稳定编码；用于前端多级分组树',
    group_code VARCHAR(64) NOT NULL COMMENT '分组稳定编码；任务创建、删除分组、导入导出和 Agent 调用都使用该字段',
    group_name VARCHAR(128) NOT NULL COMMENT '分组展示名称；允许中文',
    description VARCHAR(500) NULL COMMENT '分组低敏说明，不得保存 SQL、连接串、凭据、样本数据或完整字段映射',
    display_order INT NOT NULL DEFAULT 100 COMMENT '展示排序值；默认分组固定为 0',
    default_group TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否系统默认分组',
    archived TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否已归档；归档后不再被新任务选择',
    created_by BIGINT NULL COMMENT '创建人 ID',
    updated_by BIGINT NULL COMMENT '最近更新人 ID',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    project_scope_key BIGINT GENERATED ALWAYS AS (IFNULL(project_id, -1)) STORED,
    workspace_scope_key BIGINT GENERATED ALWAYS AS (IFNULL(workspace_id, -1)) STORED,
    UNIQUE KEY uk_data_sync_task_group_scope_code (tenant_id, project_scope_key, workspace_scope_key, group_code),
    KEY idx_data_sync_task_group_tree (tenant_id, project_id, workspace_id, parent_group_code, archived, display_order),
    KEY idx_data_sync_task_group_visible (tenant_id, project_id, workspace_id, archived, update_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='同步任务分组资源表：支撑多级分组树、默认分组、分组创建/删除、任务迁组和前端菜单渲染';

INSERT IGNORE INTO data_sync_task_group
(tenant_id, project_id, workspace_id, parent_group_code, group_code, group_name, description,
 display_order, default_group, archived, created_by, updated_by, create_time, update_time)
SELECT
    IFNULL(tenant_id, 0) AS tenant_id,
    project_id,
    workspace_id,
    NULL AS parent_group_code,
    IFNULL(NULLIF(group_code, ''), 'DEFAULT') AS group_code,
    IFNULL(MAX(NULLIF(group_name, '')), '默认分组') AS group_name,
    CASE
        WHEN IFNULL(NULLIF(group_code, ''), 'DEFAULT') = 'DEFAULT'
            THEN '系统默认分组：历史未分组任务和新建未指定分组任务都会归属到这里'
        ELSE '由历史 data_sync_task.group_code 自动迁移生成的正式分组资源'
    END AS description,
    CASE WHEN IFNULL(NULLIF(group_code, ''), 'DEFAULT') = 'DEFAULT' THEN 0 ELSE 100 END AS display_order,
    CASE WHEN IFNULL(NULLIF(group_code, ''), 'DEFAULT') = 'DEFAULT' THEN 1 ELSE 0 END AS default_group,
    0 AS archived,
    NULL AS created_by,
    NULL AS updated_by,
    CURRENT_TIMESTAMP AS create_time,
    CURRENT_TIMESTAMP AS update_time
FROM data_sync_task
WHERE current_state <> 'DELETED'
GROUP BY IFNULL(tenant_id, 0), project_id, workspace_id, IFNULL(NULLIF(group_code, ''), 'DEFAULT');
