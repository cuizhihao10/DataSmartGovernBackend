-- DataSmart Govern - data-sync 运行日志查询路由策略。
--
-- 背景说明：
-- 1. data-sync 已经把任务入队、执行器认领、计划生成、通道创建、批次回执、最终完成等阶段写入
--    data_sync_execution_log 表，并提供 GET /sync-tasks/{taskId}/executions/{executionId}/logs 查询接口。
-- 2. 前端通过 gateway 访问的路径是 /api/sync/sync-tasks/*/executions/*/logs。该路径之前没有在
--    permission_route_policy 中显式登记，gateway 授权判定会因为“没有命中任何启用的路由策略”拒绝访问。
-- 3. 运行日志属于低敏运行证据：允许展示阶段、读写计数、失败数、进度、速度、traceId 和低敏摘要；
--    不允许展示 SQL 正文、where 原文、连接串、密码、token、样本行或真实分片边界。
--
-- 产品权限语义：
-- - 普通用户可以查看自己可见任务的运行日志，用于确认任务是否已经真正执行、卡在哪个阶段、是否有失败行。
-- - 项目负责人可以查看授权项目内任务的运行日志，用于项目交付、故障定位和执行复盘。
-- - 运营人员、租户管理员可以在租户范围内查看运行日志，用于客户支持、容量治理和事故处理。
-- - 审计员可以只读查看运行日志证据，但不能触发重试、回放、执行器回调或任务变更。
-- - 平台管理员作为平台级兜底角色，可跨租户查看运行日志，用于实施交付和平台级排障。
--
-- 注意：
-- 这里仅登记 GET + VIEW。POST 回调、对象重试、脏数据回放等有副作用动作仍由独立策略控制，
-- 不能因为“日志查询也是 execution 资源”而误放开执行类接口。

SET search_path TO permission_admin, public;

INSERT INTO permission_route_policy
(tenant_id, policy_name, role_code, http_method, path_pattern, resource_type, action, effect, priority, enabled, description, create_time, update_time)
VALUES
(0, '普通用户查看同步运行日志', 'ORDINARY_USER', 'GET', '/api/sync/sync-tasks/*/executions/*/logs',
 'SYNC_EXECUTION', 'VIEW', 'ALLOW', 128, TRUE,
 '普通用户可查看自己可见同步任务的低敏运行日志，用于确认任务是否入队、被认领、执行批次和完成状态；日志不包含 SQL、凭据、样本行或连接串。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '项目负责人查看同步运行日志', 'PROJECT_OWNER', 'GET', '/api/sync/sync-tasks/*/executions/*/logs',
 'SYNC_EXECUTION', 'VIEW', 'ALLOW', 148, TRUE,
 '项目负责人可查看授权项目内同步任务的低敏运行日志，用于项目交付、故障定位和执行复盘。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '运营人员查看同步运行日志', 'OPERATOR', 'GET', '/api/sync/sync-tasks/*/executions/*/logs',
 'SYNC_EXECUTION', 'VIEW', 'ALLOW', 784, TRUE,
 '运营人员可查看租户内同步运行日志，用于客户支持、容量治理、事故定位和恢复建议。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '租户管理员查看同步运行日志', 'TENANT_ADMINISTRATOR', 'GET', '/api/sync/sync-tasks/*/executions/*/logs',
 'SYNC_EXECUTION', 'VIEW', 'ALLOW', 764, TRUE,
 '租户管理员可查看本租户同步任务运行日志，用于租户级运行治理和故障复盘。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '审计员查看同步运行日志', 'AUDITOR', 'GET', '/api/sync/sync-tasks/*/executions/*/logs',
 'SYNC_EXECUTION', 'VIEW', 'ALLOW', 119, TRUE,
 '审计员可只读查看低敏同步运行日志证据，用于复核运行链路、关键阶段和失败摘要；不能触发任何执行或恢复动作。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '平台管理员查看同步运行日志', 'PLATFORM_ADMINISTRATOR', 'GET', '/api/sync/sync-tasks/*/executions/*/logs',
 'SYNC_EXECUTION', 'VIEW', 'ALLOW', 1000, TRUE,
 '平台管理员可跨租户查看低敏同步运行日志，用于平台级排障、实施交付和安全响应。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT DO NOTHING;

INSERT INTO permission_data_scope_policy
(tenant_id, role_code, resource_type, scope_level, scope_expression, approval_required, enabled, description, create_time, update_time)
VALUES
(0, 'ORDINARY_USER', 'SYNC_EXECUTION', 'SELF', 'owner_id = ${actorId} OR triggered_by = ${actorId}', FALSE, TRUE,
 '普通用户只能查看自己拥有或自己触发的同步执行记录、运行日志和低敏执行证据。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, 'AUDITOR', 'SYNC_EXECUTION', 'TENANT', 'tenant_id = ${tenantId}', FALSE, TRUE,
 '审计员可在本租户内只读查看同步执行记录、运行日志和低敏执行证据，用于审计复核。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (tenant_id, role_code, resource_type) DO UPDATE
SET scope_level = EXCLUDED.scope_level,
    scope_expression = EXCLUDED.scope_expression,
    approval_required = EXCLUDED.approval_required,
    enabled = EXCLUDED.enabled,
    description = EXCLUDED.description,
    update_time = CURRENT_TIMESTAMP;
