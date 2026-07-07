-- permission-admin: data-sync 分组资源管理与任务创建元数据配置路由策略。
--
-- 设计说明：
-- 1. GET /groups/tree 是只读菜单能力，可开放给具备任务可见性的角色。
-- 2. POST /groups 会创建正式分组资源，普通用户可在自身数据范围内创建，项目负责人/运营/租户管理员可在授权范围内创建。
-- 3. DELETE /groups/{groupCode} 会归档分组并迁移任务到 DEFAULT，影响面大于普通查询，因此不开放给普通用户和审计员。
-- 4. metadata discovery / field mapping suggestion 只返回低敏结构信息，不返回样本数据、凭据、连接串或 SQL；但表结构仍属于企业资产信息，必须纳入权限策略。
SET search_path TO permission_admin, public;

INSERT INTO permission_route_policy
(tenant_id, policy_name, role_code, http_method, path_pattern, resource_type, action, effect, priority, enabled, description, create_time, update_time)
VALUES
(0, '普通用户查看自己的同步任务分组树', 'ORDINARY_USER', 'GET', '/api/sync/sync-tasks/groups/tree', 'SYNC_TASK_GROUP', 'LIST_GROUP_TREE', 'ALLOW', 128, TRUE, '普通用户可查看 SELF 范围内的分组树，用于任务列表和创建任务时选择分组。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '普通用户创建自己的同步任务分组', 'ORDINARY_USER', 'POST', '/api/sync/sync-tasks/groups', 'SYNC_TASK_GROUP', 'CREATE_GROUP', 'ALLOW', 128, TRUE, '普通用户可创建自身范围内的任务分组，服务层仍会按租户/项目/SELF 范围收口。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '普通用户禁止删除同步任务分组', 'ORDINARY_USER', 'DELETE', '/api/sync/sync-tasks/groups/*', 'SYNC_TASK_GROUP', 'DELETE_GROUP', 'DENY', 850, TRUE, '删除分组会迁移任务归属，普通用户不直接开放该高影响操作。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '普通用户发现同步配置低敏元数据', 'ORDINARY_USER', 'POST', '/api/sync/sync-tasks/metadata/objects/discover', 'SYNC_TASK_METADATA', 'DISCOVER_METADATA', 'ALLOW', 128, TRUE, '普通用户可在授权数据源范围内发现低敏 schema/table/field 摘要，用于创建同步任务。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '普通用户生成字段映射建议', 'ORDINARY_USER', 'POST', '/api/sync/sync-tasks/metadata/field-mappings/suggest', 'SYNC_TASK_METADATA', 'SUGGEST_FIELD_MAPPING', 'ALLOW', 128, TRUE, '普通用户可生成同名字段和类型兼容性建议，最终映射仍需用户确认。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),

(0, '项目负责人查看项目同步任务分组树', 'PROJECT_OWNER', 'GET', '/api/sync/sync-tasks/groups/tree', 'SYNC_TASK_GROUP', 'LIST_GROUP_TREE', 'ALLOW', 149, TRUE, '项目负责人可查看授权项目范围内的分组树。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '项目负责人创建项目同步任务分组', 'PROJECT_OWNER', 'POST', '/api/sync/sync-tasks/groups', 'SYNC_TASK_GROUP', 'CREATE_GROUP', 'ALLOW', 149, TRUE, '项目负责人可创建授权项目范围内的分组。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '项目负责人删除项目同步任务分组', 'PROJECT_OWNER', 'DELETE', '/api/sync/sync-tasks/groups/*', 'SYNC_TASK_GROUP', 'DELETE_GROUP', 'ALLOW', 149, TRUE, '项目负责人可删除授权项目范围内的普通分组，任务会迁回默认分组。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '项目负责人发现同步配置低敏元数据', 'PROJECT_OWNER', 'POST', '/api/sync/sync-tasks/metadata/objects/discover', 'SYNC_TASK_METADATA', 'DISCOVER_METADATA', 'ALLOW', 149, TRUE, '项目负责人可发现授权项目数据源的低敏元数据摘要。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '项目负责人生成字段映射建议', 'PROJECT_OWNER', 'POST', '/api/sync/sync-tasks/metadata/field-mappings/suggest', 'SYNC_TASK_METADATA', 'SUGGEST_FIELD_MAPPING', 'ALLOW', 149, TRUE, '项目负责人可生成授权项目范围内的字段映射建议。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),

(0, '运营人员查看同步任务分组树', 'OPERATOR', 'GET', '/api/sync/sync-tasks/groups/tree', 'SYNC_TASK_GROUP', 'LIST_GROUP_TREE', 'ALLOW', 785, TRUE, '运营人员可查看租户范围内分组树，用于容量治理和故障定位。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '运营人员创建同步任务分组', 'OPERATOR', 'POST', '/api/sync/sync-tasks/groups', 'SYNC_TASK_GROUP', 'CREATE_GROUP', 'ALLOW', 785, TRUE, '运营人员可创建租户范围内分组，用于迁移批次、故障隔离和运营治理。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '运营人员删除同步任务分组', 'OPERATOR', 'DELETE', '/api/sync/sync-tasks/groups/*', 'SYNC_TASK_GROUP', 'DELETE_GROUP', 'ALLOW', 785, TRUE, '运营人员可删除普通分组并把任务迁回默认分组。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '运营人员发现同步配置低敏元数据', 'OPERATOR', 'POST', '/api/sync/sync-tasks/metadata/objects/discover', 'SYNC_TASK_METADATA', 'DISCOVER_METADATA', 'ALLOW', 785, TRUE, '运营人员可发现租户范围内授权数据源的低敏元数据。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '运营人员生成字段映射建议', 'OPERATOR', 'POST', '/api/sync/sync-tasks/metadata/field-mappings/suggest', 'SYNC_TASK_METADATA', 'SUGGEST_FIELD_MAPPING', 'ALLOW', 785, TRUE, '运营人员可生成字段映射建议以辅助任务配置。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),

(0, '租户管理员查看同步任务分组树', 'TENANT_ADMINISTRATOR', 'GET', '/api/sync/sync-tasks/groups/tree', 'SYNC_TASK_GROUP', 'LIST_GROUP_TREE', 'ALLOW', 765, TRUE, '租户管理员可查看本租户同步任务分组树。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '租户管理员创建同步任务分组', 'TENANT_ADMINISTRATOR', 'POST', '/api/sync/sync-tasks/groups', 'SYNC_TASK_GROUP', 'CREATE_GROUP', 'ALLOW', 765, TRUE, '租户管理员可创建本租户同步任务分组。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '租户管理员删除同步任务分组', 'TENANT_ADMINISTRATOR', 'DELETE', '/api/sync/sync-tasks/groups/*', 'SYNC_TASK_GROUP', 'DELETE_GROUP', 'ALLOW', 765, TRUE, '租户管理员可删除本租户普通分组并触发任务迁回默认分组。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '租户管理员发现同步配置低敏元数据', 'TENANT_ADMINISTRATOR', 'POST', '/api/sync/sync-tasks/metadata/objects/discover', 'SYNC_TASK_METADATA', 'DISCOVER_METADATA', 'ALLOW', 765, TRUE, '租户管理员可发现本租户授权数据源的低敏元数据。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '租户管理员生成字段映射建议', 'TENANT_ADMINISTRATOR', 'POST', '/api/sync/sync-tasks/metadata/field-mappings/suggest', 'SYNC_TASK_METADATA', 'SUGGEST_FIELD_MAPPING', 'ALLOW', 765, TRUE, '租户管理员可生成字段映射建议。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),

(0, '审计员查看同步任务分组树', 'AUDITOR', 'GET', '/api/sync/sync-tasks/groups/tree', 'SYNC_TASK_GROUP', 'LIST_GROUP_TREE', 'ALLOW', 119, TRUE, '审计员可只读查看分组树，用于复核任务组织和批量操作范围。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '审计员禁止创建同步任务分组', 'AUDITOR', 'POST', '/api/sync/sync-tasks/groups', 'SYNC_TASK_GROUP', 'CREATE_GROUP', 'DENY', 900, TRUE, '审计员只复核证据，不创建分组资源。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '审计员禁止删除同步任务分组', 'AUDITOR', 'DELETE', '/api/sync/sync-tasks/groups/*', 'SYNC_TASK_GROUP', 'DELETE_GROUP', 'DENY', 900, TRUE, '审计员不能执行会迁移任务归属的删除分组动作。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '审计员发现同步配置低敏元数据', 'AUDITOR', 'POST', '/api/sync/sync-tasks/metadata/objects/discover', 'SYNC_TASK_METADATA', 'DISCOVER_METADATA', 'ALLOW', 119, TRUE, '审计员可查看低敏结构摘要以复核任务配置，不获取样本数据或凭据。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '审计员生成字段映射建议', 'AUDITOR', 'POST', '/api/sync/sync-tasks/metadata/field-mappings/suggest', 'SYNC_TASK_METADATA', 'SUGGEST_FIELD_MAPPING', 'ALLOW', 119, TRUE, '审计员可生成字段映射建议用于复核配置合理性。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),

(0, '服务账号禁止人工创建同步任务分组', 'SERVICE_ACCOUNT', 'POST', '/api/sync/sync-tasks/groups', 'SYNC_TASK_GROUP', 'CREATE_GROUP', 'DENY', 910, TRUE, '服务账号不应通过人工菜单入口创建分组，自动化治理应走受控内部协议。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '服务账号禁止人工删除同步任务分组', 'SERVICE_ACCOUNT', 'DELETE', '/api/sync/sync-tasks/groups/*', 'SYNC_TASK_GROUP', 'DELETE_GROUP', 'DENY', 910, TRUE, '服务账号不应通过人工菜单入口删除分组。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT DO NOTHING;

-- 兼容说明：
-- 旧版 GET /api/sync/sync-tasks/groups 原本按 SYNC_TASK + LIST_GROUPS 授权。
-- 本轮网关把“分组汇总/创建”统一升级为 SYNC_TASK_GROUP 资源语义，
-- 因此这里补充同一路径在新资源类型下的只读策略，避免前端分组菜单汇总接口被错误拒绝。
INSERT INTO permission_route_policy
(tenant_id, policy_name, role_code, http_method, path_pattern, resource_type, action, effect, priority, enabled, description, create_time, update_time)
VALUES
(0, '普通用户查看自己的同步任务分组汇总', 'ORDINARY_USER', 'GET', '/api/sync/sync-tasks/groups', 'SYNC_TASK_GROUP', 'LIST_GROUPS', 'ALLOW', 128, TRUE, '普通用户可查看 SELF 范围内的同步任务分组汇总，用于创建任务时选择默认分组或业务分组。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '项目负责人查看项目同步任务分组汇总', 'PROJECT_OWNER', 'GET', '/api/sync/sync-tasks/groups', 'SYNC_TASK_GROUP', 'LIST_GROUPS', 'ALLOW', 149, TRUE, '项目负责人可查看授权项目范围内的同步任务分组汇总。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '运营人员查看同步任务分组汇总', 'OPERATOR', 'GET', '/api/sync/sync-tasks/groups', 'SYNC_TASK_GROUP', 'LIST_GROUPS', 'ALLOW', 785, TRUE, '运营人员可查看租户范围内同步任务分组汇总，用于容量治理、批量运营和故障定位。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '租户管理员查看同步任务分组汇总', 'TENANT_ADMINISTRATOR', 'GET', '/api/sync/sync-tasks/groups', 'SYNC_TASK_GROUP', 'LIST_GROUPS', 'ALLOW', 765, TRUE, '租户管理员可查看本租户同步任务分组汇总。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '审计员查看同步任务分组汇总', 'AUDITOR', 'GET', '/api/sync/sync-tasks/groups', 'SYNC_TASK_GROUP', 'LIST_GROUPS', 'ALLOW', 119, TRUE, '审计员可只读查看同步任务分组汇总，用于复核任务组织和批量操作范围。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT DO NOTHING;
