-- permission-admin：data-sync 控制面路由策略与数据范围补齐。
--
-- 背景说明：
-- 1. gateway 在强制授权模式下，不再只把 `/api/sync/**` 粗略标记为 SYNC_TASK；
--    它会根据具体端点把同步模板、同步任务、执行账本、内部 worker 协议等区分为
--    SYNC_TEMPLATE / SYNC_TASK / SYNC_EXECUTION 等资源类型，并传入 CREATE、VIEW、RUN、RECOVER 等动作。
-- 2. permission-admin 的授权匹配规则是“保守精确匹配”：
--    - 如果策略的 resource_type/action 为空，表示该字段通配；
--    - 如果策略显式写了 resource_type/action，则必须与 gateway 传入的语义完全一致。
--    这能避免一个宽泛的任务管理权限误放行执行器回调、调度器派发或高风险恢复动作。
-- 3. PostgreSQL 是当前权限中心目标数据库，不能只依赖旧 MySQL 初始化脚本中的兼容策略。
--    本迁移把 data-sync 控制面策略显式落到 PostgreSQL schema，保证本地 E2E 和后续生产部署都走同一套授权事实。
--
-- 商业化权限边界：
-- - 项目负责人可以管理授权项目范围内的同步模板、同步任务，并可以对失败对象做受控重试；
-- - 普通用户以只读或自有范围为主，不能触发执行器协议；
-- - 运营人员偏向查看、诊断和事故恢复，模板写入默认不开放；
-- - 租户管理员可管理本租户同步能力；
-- - 内部 worker-loop / scheduler 仍由 V4 中的 SERVICE_ACCOUNT 专用策略保护。

SET search_path TO permission_admin, public;

-- ---------------------------------------------------------------------------
-- 1. 同步模板控制面策略
-- ---------------------------------------------------------------------------
-- `/api/sync/sync-templates/**` 覆盖模板创建、详情、预检、校验、离线作业计划等入口。
-- 对 PROJECT_OWNER / TENANT_ADMINISTRATOR 使用 action 为空的策略，是为了允许同一资源下的
-- CREATE / VIEW / UPDATE / VALIDATE / PRECHECK 等模板级动作，同时仍受 SYNC_TEMPLATE 数据范围约束。
INSERT INTO permission_route_policy
(tenant_id, policy_name, role_code, http_method, path_pattern, resource_type, action, effect, priority, enabled, description, create_time, update_time)
VALUES
(0, '普通用户查看同步模板', 'ORDINARY_USER', 'GET', '/api/sync/sync-templates/**', 'SYNC_TEMPLATE', 'VIEW', 'ALLOW', 112, TRUE, '普通用户只能查看自有或被授权范围内的同步模板；模板创建、预检和执行类动作不在普通用户默认权限内。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '项目负责人管理同步模板', 'PROJECT_OWNER', 'ANY', '/api/sync/sync-templates/**', 'SYNC_TEMPLATE', NULL, 'ALLOW', 126, TRUE, '项目负责人可以在授权项目范围内创建、查看、预检和维护同步模板；数据范围由 SYNC_TEMPLATE 的 PROJECT 策略物化。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '运营人员查看同步模板', 'OPERATOR', 'GET', '/api/sync/sync-templates/**', 'SYNC_TEMPLATE', 'VIEW', 'ALLOW', 131, TRUE, '运营人员可查看低敏模板配置，用于判断失败是否来自字段映射、过滤条件、批量策略或写入模式。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '审计员查看同步模板', 'AUDITOR', 'GET', '/api/sync/sync-templates/**', 'SYNC_TEMPLATE', 'VIEW', 'ALLOW', 116, TRUE, '审计员可只读查看同步模板摘要和配置证据，用于复核高风险同步是否进入审批与审计边界。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '租户管理员管理同步模板', 'TENANT_ADMINISTRATOR', 'ANY', '/api/sync/sync-templates/**', 'SYNC_TEMPLATE', NULL, 'ALLOW', 151, TRUE, '租户管理员可管理本租户同步模板，用于租户级数据同步能力配置、容量治理和风险控制。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT DO NOTHING;

-- offline-job-plan 是“POST 承载只读规划”的特殊接口：它不创建任务、不执行 SQL、不写入目标端，
-- 只是返回低敏 Reader/Writer、checkpoint、审批和容量摘要，所以动作语义应为 VIEW，而不是 HTTP POST 默认的 CREATE。
INSERT INTO permission_route_policy
(tenant_id, policy_name, role_code, http_method, path_pattern, resource_type, action, effect, priority, enabled, description, create_time, update_time)
VALUES
(0, '普通用户查看同步模板离线作业计划', 'ORDINARY_USER', 'POST', '/api/sync/sync-templates/*/offline-job-plan', 'SYNC_TEMPLATE', 'VIEW', 'ALLOW', 113, TRUE, '普通用户可在自有范围内查看低敏离线作业计划；服务层仍会校验模板租户、项目和 SELF 范围。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '项目负责人查看同步模板离线作业计划', 'PROJECT_OWNER', 'POST', '/api/sync/sync-templates/*/offline-job-plan', 'SYNC_TEMPLATE', 'VIEW', 'ALLOW', 127, TRUE, '项目负责人可查看项目范围模板的 DataX-style 离线作业计划，用于任务创建、审批和容量评估。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '运营人员查看同步模板离线作业计划', 'OPERATOR', 'POST', '/api/sync/sync-templates/*/offline-job-plan', 'SYNC_TEMPLATE', 'VIEW', 'ALLOW', 132, TRUE, '运营人员可查看低敏离线作业计划，用于判断任务是否需要专用 runner、checkpoint handoff、调度窗口或审批。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '审计员查看同步模板离线作业计划', 'AUDITOR', 'POST', '/api/sync/sync-templates/*/offline-job-plan', 'SYNC_TEMPLATE', 'VIEW', 'ALLOW', 117, TRUE, '审计员可查看离线作业计划摘要，用于复核 SQL 自定义传输、整库迁移、覆盖写入等高风险配置是否进入审批边界。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '租户管理员查看同步模板离线作业计划', 'TENANT_ADMINISTRATOR', 'POST', '/api/sync/sync-templates/*/offline-job-plan', 'SYNC_TEMPLATE', 'VIEW', 'ALLOW', 152, TRUE, '租户管理员可查看本租户同步模板的低敏离线作业计划，用于租户级容量、调度和风险治理。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------------------
-- 2. 同步执行对象账本和失败对象重试策略
-- ---------------------------------------------------------------------------
-- 对象账本用于 OBJECT_LIST / SCHEMA_FULL / DATABASE_FULL 等“大任务拆小任务”场景。
-- 查询账本是低风险只读动作，失败对象重试是受控恢复动作：它只重置失败对象，不重跑已成功对象。
INSERT INTO permission_route_policy
(tenant_id, policy_name, role_code, http_method, path_pattern, resource_type, action, effect, priority, enabled, description, create_time, update_time)
VALUES
(0, '项目负责人查看同步对象执行账本', 'PROJECT_OWNER', 'GET', '/api/sync/sync-tasks/*/executions/*/objects', 'SYNC_EXECUTION', 'VIEW', 'ALLOW', 146, TRUE, '项目负责人可查看授权项目范围内 execution 的对象级账本，用于确认哪些表、分片或对象已成功、失败或等待重试。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '项目负责人重试失败同步对象', 'PROJECT_OWNER', 'POST', '/api/sync/sync-tasks/*/executions/*/objects/retry', 'SYNC_EXECUTION', 'RECOVER', 'ALLOW', 147, TRUE, '项目负责人可对授权项目范围内的失败对象执行选择性重试；服务层会校验 execution、对象状态、尝试次数和幂等边界。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '运营人员查看同步对象执行账本', 'OPERATOR', 'GET', '/api/sync/sync-tasks/*/executions/*/objects', 'SYNC_EXECUTION', 'VIEW', 'ALLOW', 782, TRUE, '运营人员可查看租户内对象级执行账本，用于故障诊断、容量评估和事故复盘。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '运营人员重试失败同步对象', 'OPERATOR', 'POST', '/api/sync/sync-tasks/*/executions/*/objects/retry', 'SYNC_EXECUTION', 'RECOVER', 'ALLOW', 783, TRUE, '运营人员可在事故恢复场景下选择性重试失败对象；该动作仍会进入 data-sync 执行审计。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '租户管理员查看同步对象执行账本', 'TENANT_ADMINISTRATOR', 'GET', '/api/sync/sync-tasks/*/executions/*/objects', 'SYNC_EXECUTION', 'VIEW', 'ALLOW', 762, TRUE, '租户管理员可查看本租户对象级执行账本，用于租户级同步运行治理。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '租户管理员重试失败同步对象', 'TENANT_ADMINISTRATOR', 'POST', '/api/sync/sync-tasks/*/executions/*/objects/retry', 'SYNC_EXECUTION', 'RECOVER', 'ALLOW', 763, TRUE, '租户管理员可在本租户范围内触发失败对象选择性重试，避免整任务重跑造成资源浪费。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '审计员查看同步对象执行账本', 'AUDITOR', 'GET', '/api/sync/sync-tasks/*/executions/*/objects', 'SYNC_EXECUTION', 'VIEW', 'ALLOW', 118, TRUE, '审计员可只读查看对象级执行证据，但不能触发重试、回放或执行器协议。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------------------
-- 3. 数据范围策略
-- ---------------------------------------------------------------------------
-- 数据范围是下游服务真正做“项目/租户可见性过滤”的依据。
-- 这里补齐 SYNC_TEMPLATE 和 PROJECT_OWNER/TENANT_ADMINISTRATOR 的 SYNC_EXECUTION 范围，
-- 让 gateway 的授权响应能够携带 authorizedProjectIds，避免业务服务再反查权限中心内部表。
INSERT INTO permission_data_scope_policy
(tenant_id, role_code, resource_type, scope_level, scope_expression, approval_required, enabled, description, create_time, update_time)
VALUES
(0, 'ORDINARY_USER', 'SYNC_TEMPLATE', 'SELF', 'created_by = ${actorId} OR owner_id = ${actorId}', FALSE, TRUE, '普通用户只能查看自己创建或被授权的同步模板。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, 'PROJECT_OWNER', 'SYNC_TEMPLATE', 'PROJECT', 'project_id IN ${actorProjectIds}', FALSE, TRUE, '项目负责人可访问授权项目范围内的同步模板。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, 'OPERATOR', 'SYNC_TEMPLATE', 'TENANT', 'tenant_id = ${tenantId}', FALSE, TRUE, '运营人员可查看租户内同步模板摘要，用于故障诊断和容量治理。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, 'AUDITOR', 'SYNC_TEMPLATE', 'TENANT', 'tenant_id = ${tenantId}', FALSE, TRUE, '审计员可查看租户内同步模板证据，用于合规复核。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, 'TENANT_ADMINISTRATOR', 'SYNC_TEMPLATE', 'TENANT', 'tenant_id = ${tenantId}', FALSE, TRUE, '租户管理员可访问本租户同步模板。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, 'PLATFORM_ADMINISTRATOR', 'SYNC_TEMPLATE', 'PLATFORM', '1 = 1', FALSE, TRUE, '平台管理员可跨租户管理同步模板。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, 'PROJECT_OWNER', 'SYNC_EXECUTION', 'PROJECT', 'project_id IN ${actorProjectIds}', FALSE, TRUE, '项目负责人可查看并恢复授权项目范围内的同步执行记录和对象级账本。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, 'TENANT_ADMINISTRATOR', 'SYNC_EXECUTION', 'TENANT', 'tenant_id = ${tenantId}', FALSE, TRUE, '租户管理员可查看和恢复本租户同步执行状态。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, 'PLATFORM_ADMINISTRATOR', 'SYNC_EXECUTION', 'PLATFORM', '1 = 1', FALSE, TRUE, '平台管理员可跨租户查看和恢复同步执行记录。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (tenant_id, role_code, resource_type) DO NOTHING;
