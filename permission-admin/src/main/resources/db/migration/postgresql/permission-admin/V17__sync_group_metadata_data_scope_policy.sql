-- permission-admin：补齐同步任务分组与创建向导元数据的数据范围策略。
--
-- 背景说明：
-- 1. gateway 已经把 /api/sync/sync-tasks/groups、/groups/tree、/metadata/objects/discover
--    识别为 SYNC_TASK_GROUP / SYNC_TASK_METADATA，而不再粗略落到 SYNC_TASK。
-- 2. permission_route_policy 中已经有对应路由策略，但 permission_data_scope_policy 之前缺少这两个
--    resource_type 的数据范围事实，导致 permission-admin 返回 allowed=true 但 dataScopeLevel=null。
-- 3. gateway 在接收到页面传入的 X-DataSmart-Project-Id 后，会要求 PROJECT 范围返回 authorizedProjectIds，
--    或者 TENANT/PLATFORM 范围明确表示更高层级授权。缺少数据范围时，gateway 会拒绝“未授权项目上下文”，
--    页面表现为分组树 403、任务列表联动空态，以及对象映射阶段元数据发现失败。
--
-- 产品语义：
-- - FlashSync 当前用户侧范围已经收敛为“租户 -> 项目 -> 数据源/同步任务”，工作空间不再参与页面筛选或创建。
-- - 分组树和元数据发现都属于“创建/配置同步任务的控制面辅助能力”，它们不写入目标库、不返回凭据、不返回样本数据，
--   但仍然会暴露企业数据资产结构，因此必须跟随项目或租户权限，不允许绕过 RBAC。
-- - 普通用户和项目负责人按 PROJECT 范围访问，保证只在自己加入的项目内配置任务；
--   运营、审计、租户管理员按 TENANT 范围读取低敏结构或治理任务；
--   平台管理员按 PLATFORM 范围保留跨租户排障能力。

SET search_path TO permission_admin, public;

INSERT INTO permission_data_scope_policy
(tenant_id, role_code, resource_type, scope_level, scope_expression, approval_required, enabled, description, create_time, update_time)
VALUES
(0, 'ORDINARY_USER', 'SYNC_TASK_GROUP', 'PROJECT', 'project_id IN ${actorProjectIds}', FALSE, TRUE,
 '普通用户只能查看和创建自己已加入项目内的同步任务分组；工作空间字段不再参与页面侧分组范围。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, 'PROJECT_OWNER', 'SYNC_TASK_GROUP', 'PROJECT', 'project_id IN ${actorProjectIds}', FALSE, TRUE,
 '项目负责人可以管理自己负责或被授权项目内的同步任务分组。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, 'OPERATOR', 'SYNC_TASK_GROUP', 'TENANT', 'tenant_id = ${tenantId}', FALSE, TRUE,
 '运营人员可以查看和治理本租户内同步任务分组，用于容量治理、故障定位和批量运维。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, 'AUDITOR', 'SYNC_TASK_GROUP', 'TENANT', 'tenant_id = ${tenantId}', FALSE, TRUE,
 '审计员可以只读查看本租户同步任务分组结构，用于复核任务组织和批量操作范围。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, 'TENANT_ADMINISTRATOR', 'SYNC_TASK_GROUP', 'TENANT', 'tenant_id = ${tenantId}', FALSE, TRUE,
 '租户管理员可以管理本租户内同步任务分组。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, 'PLATFORM_ADMINISTRATOR', 'SYNC_TASK_GROUP', 'PLATFORM', '1 = 1', FALSE, TRUE,
 '平台管理员可以跨租户查看和治理同步任务分组，用于平台级排障和实施交付。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),

(0, 'ORDINARY_USER', 'SYNC_TASK_METADATA', 'PROJECT', 'project_id IN ${actorProjectIds}', FALSE, TRUE,
 '普通用户只能发现自己已加入项目内数据源的低敏 schema/table/field 元数据，用于创建同步任务对象映射。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, 'PROJECT_OWNER', 'SYNC_TASK_METADATA', 'PROJECT', 'project_id IN ${actorProjectIds}', FALSE, TRUE,
 '项目负责人可以发现授权项目内数据源的低敏元数据，用于配置同步对象和字段映射。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, 'OPERATOR', 'SYNC_TASK_METADATA', 'TENANT', 'tenant_id = ${tenantId}', FALSE, TRUE,
 '运营人员可以发现本租户授权数据源的低敏元数据，用于辅助任务配置排障和迁移评估。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, 'AUDITOR', 'SYNC_TASK_METADATA', 'TENANT', 'tenant_id = ${tenantId}', FALSE, TRUE,
 '审计员可以只读发现本租户低敏结构摘要，用于复核同步配置，不返回样本数据、凭据或连接串。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, 'TENANT_ADMINISTRATOR', 'SYNC_TASK_METADATA', 'TENANT', 'tenant_id = ${tenantId}', FALSE, TRUE,
 '租户管理员可以发现本租户授权数据源的低敏元数据，用于租户级同步配置治理。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, 'PLATFORM_ADMINISTRATOR', 'SYNC_TASK_METADATA', 'PLATFORM', '1 = 1', FALSE, TRUE,
 '平台管理员可以跨租户发现低敏元数据摘要，用于实施交付和平台级故障排查。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (tenant_id, role_code, resource_type) DO UPDATE
SET scope_level = EXCLUDED.scope_level,
    scope_expression = EXCLUDED.scope_expression,
    approval_required = EXCLUDED.approval_required,
    enabled = EXCLUDED.enabled,
    description = EXCLUDED.description,
    update_time = CURRENT_TIMESTAMP;

-- 创建向导别名要与正式 metadata 路径保持同一权限语义，避免“别名因为落入 /api/sync/** 兜底而能访问，
-- 正式路径因为 SYNC_TASK_METADATA 数据范围缺失而被拒绝”的产品体验分叉。
INSERT INTO permission_route_policy
(tenant_id, policy_name, role_code, http_method, path_pattern, resource_type, action, effect, priority, enabled, description, create_time, update_time)
VALUES
(0, '普通用户创建向导发现同步配置低敏元数据', 'ORDINARY_USER', 'POST', '/api/sync/sync-tasks/create-wizard/metadata/objects/discover',
 'SYNC_TASK_METADATA', 'DISCOVER_METADATA', 'ALLOW', 128, TRUE,
 '普通用户可在授权项目范围内通过创建向导别名发现低敏 schema/table/field 摘要。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '项目负责人创建向导发现同步配置低敏元数据', 'PROJECT_OWNER', 'POST', '/api/sync/sync-tasks/create-wizard/metadata/objects/discover',
 'SYNC_TASK_METADATA', 'DISCOVER_METADATA', 'ALLOW', 149, TRUE,
 '项目负责人可通过创建向导别名发现授权项目数据源的低敏元数据摘要。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '运营人员创建向导发现同步配置低敏元数据', 'OPERATOR', 'POST', '/api/sync/sync-tasks/create-wizard/metadata/objects/discover',
 'SYNC_TASK_METADATA', 'DISCOVER_METADATA', 'ALLOW', 785, TRUE,
 '运营人员可通过创建向导别名发现租户范围内授权数据源的低敏元数据。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '租户管理员创建向导发现同步配置低敏元数据', 'TENANT_ADMINISTRATOR', 'POST', '/api/sync/sync-tasks/create-wizard/metadata/objects/discover',
 'SYNC_TASK_METADATA', 'DISCOVER_METADATA', 'ALLOW', 765, TRUE,
 '租户管理员可通过创建向导别名发现本租户授权数据源的低敏元数据。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '审计员创建向导发现同步配置低敏元数据', 'AUDITOR', 'POST', '/api/sync/sync-tasks/create-wizard/metadata/objects/discover',
 'SYNC_TASK_METADATA', 'DISCOVER_METADATA', 'ALLOW', 119, TRUE,
 '审计员可通过创建向导别名查看低敏结构摘要以复核任务配置。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT DO NOTHING;
