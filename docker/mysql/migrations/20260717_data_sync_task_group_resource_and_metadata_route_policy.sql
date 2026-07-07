-- permission-admin MySQL 兼容策略：data-sync 任务分组资源与创建任务元数据发现。
--
-- 设计说明：
-- 1. 任务分组已经从 data_sync_task 上的展示字段升级为正式资源，因此网关会以 SYNC_TASK_GROUP
--    作为 resource_type 调用 permission-admin。这里必须补齐 MySQL 兼容环境的同名策略。
-- 2. GET /groups 是旧版分组汇总入口，GET /groups/tree 是新版多级分组树入口；两者都只返回低敏
--    运营信息，不返回源端/目标端数据、SQL、凭据或字段映射正文。
-- 3. POST /groups 和 DELETE /groups/* 会改变分组资源或任务归属，属于写操作；审计员和服务账号
--    不能通过人工菜单入口执行。
-- 4. 元数据发现与字段映射建议只允许读取 datasource-management 返回的低敏结构摘要，但表结构本身
--    仍属于企业数据资产信息，所以必须纳入路由授权。

INSERT IGNORE INTO permission_route_policy
(tenant_id, policy_name, role_code, http_method, path_pattern, resource_type, action, effect, priority, enabled, description, create_time, update_time)
VALUES
(0, '普通用户查看自己的同步任务分组汇总', 'ORDINARY_USER', 'GET', '/api/sync/sync-tasks/groups', 'SYNC_TASK_GROUP', 'LIST_GROUPS', 'ALLOW', 128, 1, '普通用户可查看 SELF 范围内的同步任务分组汇总，用于创建任务时选择默认分组或业务分组。', NOW(), NOW()),
(0, '普通用户查看自己的同步任务分组树', 'ORDINARY_USER', 'GET', '/api/sync/sync-tasks/groups/tree', 'SYNC_TASK_GROUP', 'LIST_GROUP_TREE', 'ALLOW', 128, 1, '普通用户可查看 SELF 范围内的分组树，用于任务列表和创建任务时选择分组。', NOW(), NOW()),
(0, '普通用户创建自己的同步任务分组', 'ORDINARY_USER', 'POST', '/api/sync/sync-tasks/groups', 'SYNC_TASK_GROUP', 'CREATE_GROUP', 'ALLOW', 128, 1, '普通用户可创建自身范围内的任务分组，服务层仍会按租户、项目和 SELF 范围收口。', NOW(), NOW()),
(0, '普通用户禁止删除同步任务分组', 'ORDINARY_USER', 'DELETE', '/api/sync/sync-tasks/groups/*', 'SYNC_TASK_GROUP', 'DELETE_GROUP', 'DENY', 850, 1, '删除分组会迁移任务归属，普通用户不直接开放该高影响操作。', NOW(), NOW()),
(0, '普通用户发现同步配置低敏元数据', 'ORDINARY_USER', 'POST', '/api/sync/sync-tasks/metadata/objects/discover', 'SYNC_TASK_METADATA', 'DISCOVER_METADATA', 'ALLOW', 128, 1, '普通用户可在授权数据源范围内发现低敏 schema、table、field 摘要，用于创建同步任务。', NOW(), NOW()),
(0, '普通用户生成字段映射建议', 'ORDINARY_USER', 'POST', '/api/sync/sync-tasks/metadata/field-mappings/suggest', 'SYNC_TASK_METADATA', 'SUGGEST_FIELD_MAPPING', 'ALLOW', 128, 1, '普通用户可生成同名字段和类型兼容性建议，最终映射仍需用户确认。', NOW(), NOW()),

(0, '项目负责人查看项目同步任务分组汇总', 'PROJECT_OWNER', 'GET', '/api/sync/sync-tasks/groups', 'SYNC_TASK_GROUP', 'LIST_GROUPS', 'ALLOW', 149, 1, '项目负责人可查看授权项目范围内的同步任务分组汇总。', NOW(), NOW()),
(0, '项目负责人查看项目同步任务分组树', 'PROJECT_OWNER', 'GET', '/api/sync/sync-tasks/groups/tree', 'SYNC_TASK_GROUP', 'LIST_GROUP_TREE', 'ALLOW', 149, 1, '项目负责人可查看授权项目范围内的分组树。', NOW(), NOW()),
(0, '项目负责人创建项目同步任务分组', 'PROJECT_OWNER', 'POST', '/api/sync/sync-tasks/groups', 'SYNC_TASK_GROUP', 'CREATE_GROUP', 'ALLOW', 149, 1, '项目负责人可创建授权项目范围内的分组。', NOW(), NOW()),
(0, '项目负责人删除项目同步任务分组', 'PROJECT_OWNER', 'DELETE', '/api/sync/sync-tasks/groups/*', 'SYNC_TASK_GROUP', 'DELETE_GROUP', 'ALLOW', 149, 1, '项目负责人可删除授权项目范围内的普通分组，任务会迁回默认分组。', NOW(), NOW()),
(0, '项目负责人发现同步配置低敏元数据', 'PROJECT_OWNER', 'POST', '/api/sync/sync-tasks/metadata/objects/discover', 'SYNC_TASK_METADATA', 'DISCOVER_METADATA', 'ALLOW', 149, 1, '项目负责人可发现授权项目数据源的低敏元数据摘要。', NOW(), NOW()),
(0, '项目负责人生成字段映射建议', 'PROJECT_OWNER', 'POST', '/api/sync/sync-tasks/metadata/field-mappings/suggest', 'SYNC_TASK_METADATA', 'SUGGEST_FIELD_MAPPING', 'ALLOW', 149, 1, '项目负责人可生成授权项目范围内的字段映射建议。', NOW(), NOW()),

(0, '运营人员查看同步任务分组汇总', 'OPERATOR', 'GET', '/api/sync/sync-tasks/groups', 'SYNC_TASK_GROUP', 'LIST_GROUPS', 'ALLOW', 785, 1, '运营人员可查看租户范围内同步任务分组汇总，用于容量治理、批量运营和故障定位。', NOW(), NOW()),
(0, '运营人员查看同步任务分组树', 'OPERATOR', 'GET', '/api/sync/sync-tasks/groups/tree', 'SYNC_TASK_GROUP', 'LIST_GROUP_TREE', 'ALLOW', 785, 1, '运营人员可查看租户范围内分组树，用于容量治理和故障定位。', NOW(), NOW()),
(0, '运营人员创建同步任务分组', 'OPERATOR', 'POST', '/api/sync/sync-tasks/groups', 'SYNC_TASK_GROUP', 'CREATE_GROUP', 'ALLOW', 785, 1, '运营人员可创建租户范围内分组，用于迁移批次、故障隔离和运营治理。', NOW(), NOW()),
(0, '运营人员删除同步任务分组', 'OPERATOR', 'DELETE', '/api/sync/sync-tasks/groups/*', 'SYNC_TASK_GROUP', 'DELETE_GROUP', 'ALLOW', 785, 1, '运营人员可删除普通分组并把任务迁回默认分组。', NOW(), NOW()),
(0, '运营人员发现同步配置低敏元数据', 'OPERATOR', 'POST', '/api/sync/sync-tasks/metadata/objects/discover', 'SYNC_TASK_METADATA', 'DISCOVER_METADATA', 'ALLOW', 785, 1, '运营人员可发现租户范围内授权数据源的低敏元数据。', NOW(), NOW()),
(0, '运营人员生成字段映射建议', 'OPERATOR', 'POST', '/api/sync/sync-tasks/metadata/field-mappings/suggest', 'SYNC_TASK_METADATA', 'SUGGEST_FIELD_MAPPING', 'ALLOW', 785, 1, '运营人员可生成字段映射建议以辅助任务配置。', NOW(), NOW()),

(0, '租户管理员查看同步任务分组汇总', 'TENANT_ADMINISTRATOR', 'GET', '/api/sync/sync-tasks/groups', 'SYNC_TASK_GROUP', 'LIST_GROUPS', 'ALLOW', 765, 1, '租户管理员可查看本租户同步任务分组汇总。', NOW(), NOW()),
(0, '租户管理员查看同步任务分组树', 'TENANT_ADMINISTRATOR', 'GET', '/api/sync/sync-tasks/groups/tree', 'SYNC_TASK_GROUP', 'LIST_GROUP_TREE', 'ALLOW', 765, 1, '租户管理员可查看本租户同步任务分组树。', NOW(), NOW()),
(0, '租户管理员创建同步任务分组', 'TENANT_ADMINISTRATOR', 'POST', '/api/sync/sync-tasks/groups', 'SYNC_TASK_GROUP', 'CREATE_GROUP', 'ALLOW', 765, 1, '租户管理员可创建本租户同步任务分组。', NOW(), NOW()),
(0, '租户管理员删除同步任务分组', 'TENANT_ADMINISTRATOR', 'DELETE', '/api/sync/sync-tasks/groups/*', 'SYNC_TASK_GROUP', 'DELETE_GROUP', 'ALLOW', 765, 1, '租户管理员可删除本租户普通分组并触发任务迁回默认分组。', NOW(), NOW()),
(0, '租户管理员发现同步配置低敏元数据', 'TENANT_ADMINISTRATOR', 'POST', '/api/sync/sync-tasks/metadata/objects/discover', 'SYNC_TASK_METADATA', 'DISCOVER_METADATA', 'ALLOW', 765, 1, '租户管理员可发现本租户授权数据源的低敏元数据。', NOW(), NOW()),
(0, '租户管理员生成字段映射建议', 'TENANT_ADMINISTRATOR', 'POST', '/api/sync/sync-tasks/metadata/field-mappings/suggest', 'SYNC_TASK_METADATA', 'SUGGEST_FIELD_MAPPING', 'ALLOW', 765, 1, '租户管理员可生成字段映射建议。', NOW(), NOW()),

(0, '审计员查看同步任务分组汇总', 'AUDITOR', 'GET', '/api/sync/sync-tasks/groups', 'SYNC_TASK_GROUP', 'LIST_GROUPS', 'ALLOW', 119, 1, '审计员可只读查看同步任务分组汇总，用于复核任务组织和批量操作范围。', NOW(), NOW()),
(0, '审计员查看同步任务分组树', 'AUDITOR', 'GET', '/api/sync/sync-tasks/groups/tree', 'SYNC_TASK_GROUP', 'LIST_GROUP_TREE', 'ALLOW', 119, 1, '审计员可只读查看分组树，用于复核任务组织和批量操作范围。', NOW(), NOW()),
(0, '审计员禁止创建同步任务分组', 'AUDITOR', 'POST', '/api/sync/sync-tasks/groups', 'SYNC_TASK_GROUP', 'CREATE_GROUP', 'DENY', 900, 1, '审计员只复核证据，不创建分组资源。', NOW(), NOW()),
(0, '审计员禁止删除同步任务分组', 'AUDITOR', 'DELETE', '/api/sync/sync-tasks/groups/*', 'SYNC_TASK_GROUP', 'DELETE_GROUP', 'DENY', 900, 1, '审计员不能执行会迁移任务归属的删除分组动作。', NOW(), NOW()),
(0, '审计员发现同步配置低敏元数据', 'AUDITOR', 'POST', '/api/sync/sync-tasks/metadata/objects/discover', 'SYNC_TASK_METADATA', 'DISCOVER_METADATA', 'ALLOW', 119, 1, '审计员可查看低敏结构摘要以复核任务配置，不获取样本数据或凭据。', NOW(), NOW()),
(0, '审计员生成字段映射建议', 'AUDITOR', 'POST', '/api/sync/sync-tasks/metadata/field-mappings/suggest', 'SYNC_TASK_METADATA', 'SUGGEST_FIELD_MAPPING', 'ALLOW', 119, 1, '审计员可生成字段映射建议用于复核配置合理性。', NOW(), NOW()),

(0, '服务账号禁止人工创建同步任务分组', 'SERVICE_ACCOUNT', 'POST', '/api/sync/sync-tasks/groups', 'SYNC_TASK_GROUP', 'CREATE_GROUP', 'DENY', 910, 1, '服务账号不应通过人工菜单入口创建分组，自动化治理应走受控内部协议。', NOW(), NOW()),
(0, '服务账号禁止人工删除同步任务分组', 'SERVICE_ACCOUNT', 'DELETE', '/api/sync/sync-tasks/groups/*', 'SYNC_TASK_GROUP', 'DELETE_GROUP', 'DENY', 910, 1, '服务账号不应通过人工菜单入口删除分组。', NOW(), NOW());
