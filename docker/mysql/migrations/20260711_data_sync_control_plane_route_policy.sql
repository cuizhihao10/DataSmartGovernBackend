-- permission-admin 兼容策略：data-sync 控制面路由策略与数据范围补齐。
--
-- 说明：
-- 1. PostgreSQL 是当前 permission-admin 的目标数据库，主迁移见
--    `permission-admin/.../V5__data_sync_control_plane_route_policy.sql`。
-- 2. 本脚本只服务仍处在 MySQL 兼容期的本地/历史环境，使旧权限库也能理解
--    gateway 传入的 SYNC_TEMPLATE、SYNC_EXECUTION、VIEW、RECOVER 等细粒度语义。
-- 3. 所有写入均使用 INSERT IGNORE，确保重复执行不会破坏已有人工调整。

INSERT IGNORE INTO permission_route_policy
(tenant_id, policy_name, role_code, http_method, path_pattern, resource_type, action, effect, priority, enabled, description, create_time, update_time)
VALUES
(0, '普通用户查看同步模板', 'ORDINARY_USER', 'GET', '/api/sync/sync-templates/**', 'SYNC_TEMPLATE', 'VIEW', 'ALLOW', 112, 1, '普通用户只能查看自有或被授权范围内的同步模板；模板创建、预检和执行类动作不在普通用户默认权限内。', NOW(), NOW()),
(0, '项目负责人管理同步模板', 'PROJECT_OWNER', 'ANY', '/api/sync/sync-templates/**', 'SYNC_TEMPLATE', NULL, 'ALLOW', 126, 1, '项目负责人可以在授权项目范围内创建、查看、预检和维护同步模板；数据范围由 SYNC_TEMPLATE 的 PROJECT 策略物化。', NOW(), NOW()),
(0, '运营人员查看同步模板', 'OPERATOR', 'GET', '/api/sync/sync-templates/**', 'SYNC_TEMPLATE', 'VIEW', 'ALLOW', 131, 1, '运营人员可查看低敏模板配置，用于判断失败是否来自字段映射、过滤条件、批量策略或写入模式。', NOW(), NOW()),
(0, '审计员查看同步模板', 'AUDITOR', 'GET', '/api/sync/sync-templates/**', 'SYNC_TEMPLATE', 'VIEW', 'ALLOW', 116, 1, '审计员可只读查看同步模板摘要和配置证据，用于复核高风险同步是否进入审批与审计边界。', NOW(), NOW()),
(0, '租户管理员管理同步模板', 'TENANT_ADMINISTRATOR', 'ANY', '/api/sync/sync-templates/**', 'SYNC_TEMPLATE', NULL, 'ALLOW', 151, 1, '租户管理员可管理本租户同步模板，用于租户级数据同步能力配置、容量治理和风险控制。', NOW(), NOW()),
(0, '普通用户查看同步模板离线作业计划', 'ORDINARY_USER', 'POST', '/api/sync/sync-templates/*/offline-job-plan', 'SYNC_TEMPLATE', 'VIEW', 'ALLOW', 113, 1, '普通用户可在自有范围内查看低敏离线作业计划；服务层仍会校验模板租户、项目和 SELF 范围。', NOW(), NOW()),
(0, '项目负责人查看同步模板离线作业计划', 'PROJECT_OWNER', 'POST', '/api/sync/sync-templates/*/offline-job-plan', 'SYNC_TEMPLATE', 'VIEW', 'ALLOW', 127, 1, '项目负责人可查看项目范围模板的 DataX-style 离线作业计划，用于任务创建、审批和容量评估。', NOW(), NOW()),
(0, '运营人员查看同步模板离线作业计划', 'OPERATOR', 'POST', '/api/sync/sync-templates/*/offline-job-plan', 'SYNC_TEMPLATE', 'VIEW', 'ALLOW', 132, 1, '运营人员可查看低敏离线作业计划，用于判断任务是否需要专用 runner、checkpoint handoff、调度窗口或审批。', NOW(), NOW()),
(0, '审计员查看同步模板离线作业计划', 'AUDITOR', 'POST', '/api/sync/sync-templates/*/offline-job-plan', 'SYNC_TEMPLATE', 'VIEW', 'ALLOW', 117, 1, '审计员可查看离线作业计划摘要，用于复核 SQL 自定义传输、整库迁移、覆盖写入等高风险配置是否进入审批边界。', NOW(), NOW()),
(0, '租户管理员查看同步模板离线作业计划', 'TENANT_ADMINISTRATOR', 'POST', '/api/sync/sync-templates/*/offline-job-plan', 'SYNC_TEMPLATE', 'VIEW', 'ALLOW', 152, 1, '租户管理员可查看本租户同步模板的低敏离线作业计划，用于租户级容量、调度和风险治理。', NOW(), NOW()),
(0, '项目负责人查看同步对象执行账本', 'PROJECT_OWNER', 'GET', '/api/sync/sync-tasks/*/executions/*/objects', 'SYNC_EXECUTION', 'VIEW', 'ALLOW', 146, 1, '项目负责人可查看授权项目范围内 execution 的对象级账本，用于确认哪些表、分片或对象已成功、失败或等待重试。', NOW(), NOW()),
(0, '项目负责人重试失败同步对象', 'PROJECT_OWNER', 'POST', '/api/sync/sync-tasks/*/executions/*/objects/retry', 'SYNC_EXECUTION', 'RECOVER', 'ALLOW', 147, 1, '项目负责人可对授权项目范围内的失败对象执行选择性重试；服务层会校验 execution、对象状态、尝试次数和幂等边界。', NOW(), NOW()),
(0, '运营人员查看同步对象执行账本', 'OPERATOR', 'GET', '/api/sync/sync-tasks/*/executions/*/objects', 'SYNC_EXECUTION', 'VIEW', 'ALLOW', 782, 1, '运营人员可查看租户内对象级执行账本，用于故障诊断、容量评估和事故复盘。', NOW(), NOW()),
(0, '运营人员重试失败同步对象', 'OPERATOR', 'POST', '/api/sync/sync-tasks/*/executions/*/objects/retry', 'SYNC_EXECUTION', 'RECOVER', 'ALLOW', 783, 1, '运营人员可在事故恢复场景下选择性重试失败对象；该动作仍会进入 data-sync 执行审计。', NOW(), NOW()),
(0, '租户管理员查看同步对象执行账本', 'TENANT_ADMINISTRATOR', 'GET', '/api/sync/sync-tasks/*/executions/*/objects', 'SYNC_EXECUTION', 'VIEW', 'ALLOW', 762, 1, '租户管理员可查看本租户对象级执行账本，用于租户级同步运行治理。', NOW(), NOW()),
(0, '租户管理员重试失败同步对象', 'TENANT_ADMINISTRATOR', 'POST', '/api/sync/sync-tasks/*/executions/*/objects/retry', 'SYNC_EXECUTION', 'RECOVER', 'ALLOW', 763, 1, '租户管理员可在本租户范围内触发失败对象选择性重试，避免整任务重跑造成资源浪费。', NOW(), NOW()),
(0, '审计员查看同步对象执行账本', 'AUDITOR', 'GET', '/api/sync/sync-tasks/*/executions/*/objects', 'SYNC_EXECUTION', 'VIEW', 'ALLOW', 118, 1, '审计员可只读查看对象级执行证据，但不能触发重试、回放或执行器协议。', NOW(), NOW());

INSERT IGNORE INTO permission_data_scope_policy
(tenant_id, role_code, resource_type, scope_level, scope_expression, approval_required, enabled, description, create_time, update_time)
VALUES
(0, 'ORDINARY_USER', 'SYNC_TEMPLATE', 'SELF', 'created_by = ${actorId} OR owner_id = ${actorId}', 0, 1, '普通用户只能查看自己创建或被授权的同步模板。', NOW(), NOW()),
(0, 'PROJECT_OWNER', 'SYNC_TEMPLATE', 'PROJECT', 'project_id IN ${actorProjectIds}', 0, 1, '项目负责人可访问授权项目范围内的同步模板。', NOW(), NOW()),
(0, 'OPERATOR', 'SYNC_TEMPLATE', 'TENANT', 'tenant_id = ${tenantId}', 0, 1, '运营人员可查看租户内同步模板摘要，用于故障诊断和容量治理。', NOW(), NOW()),
(0, 'AUDITOR', 'SYNC_TEMPLATE', 'TENANT', 'tenant_id = ${tenantId}', 0, 1, '审计员可查看租户内同步模板证据，用于合规复核。', NOW(), NOW()),
(0, 'TENANT_ADMINISTRATOR', 'SYNC_TEMPLATE', 'TENANT', 'tenant_id = ${tenantId}', 0, 1, '租户管理员可访问本租户同步模板。', NOW(), NOW()),
(0, 'PLATFORM_ADMINISTRATOR', 'SYNC_TEMPLATE', 'PLATFORM', '1 = 1', 0, 1, '平台管理员可跨租户管理同步模板。', NOW(), NOW()),
(0, 'PROJECT_OWNER', 'SYNC_EXECUTION', 'PROJECT', 'project_id IN ${actorProjectIds}', 0, 1, '项目负责人可查看并恢复授权项目范围内的同步执行记录和对象级账本。', NOW(), NOW()),
(0, 'TENANT_ADMINISTRATOR', 'SYNC_EXECUTION', 'TENANT', 'tenant_id = ${tenantId}', 0, 1, '租户管理员可查看和恢复本租户同步执行状态。', NOW(), NOW()),
(0, 'PLATFORM_ADMINISTRATOR', 'SYNC_EXECUTION', 'PLATFORM', '1 = 1', 0, 1, '平台管理员可跨租户查看和恢复同步执行记录。', NOW(), NOW());
