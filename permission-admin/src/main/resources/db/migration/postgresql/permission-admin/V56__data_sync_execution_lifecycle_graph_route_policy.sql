-- DataSmart Govern - 同步 execution 统一生命周期图只读路由策略。
--
-- 为什么必须单独登记：
-- 1. Gateway 的路径规则只负责把请求解释为 SYNC_EXECUTION + VIEW，permission-admin 仍是最终授权事实源；
-- 2. 没有精确策略时，权限中心会按 fail-closed 原则拒绝真实用户，不能依赖更宽的 /api/sync/** 兜底；
-- 3. 生命周期图会串联 Agent、Kafka、Java 审计、worker 和 Recovery，虽然响应经过低敏裁剪，仍必须继续
--    服从任务、租户、项目和角色的数据范围，不能因为它是 GET 就绕过权限中心。
--
-- 本迁移只开放 GET + VIEW，不开放确认、执行、恢复、重试、回调或任何图状态修改动作。data-sync Controller
-- 仍会先校验任务可见性，再校验 execution 归属；这里的路由 ALLOW 不能替代服务层对象级校验。
SET search_path TO permission_admin, public;

INSERT INTO permission_route_policy
(tenant_id, policy_name, role_code, http_method, path_pattern, resource_type, action, effect, priority, enabled,
 description, create_time, update_time)
VALUES
(0, '普通用户查看自己的同步执行全链路图', 'ORDINARY_USER', 'GET',
 '/api/sync/sync-tasks/*/executions/*/lifecycle-graph',
 'SYNC_EXECUTION', 'VIEW', 'ALLOW', 130, TRUE,
 '普通用户可在 SELF 数据范围内查看低敏全链路状态；接口不返回 prompt、SQL、工具参数、凭据或原始日志。',
 CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '项目负责人查看项目同步执行全链路图', 'PROJECT_OWNER', 'GET',
 '/api/sync/sync-tasks/*/executions/*/lifecycle-graph',
 'SYNC_EXECUTION', 'VIEW', 'ALLOW', 150, TRUE,
 '项目负责人可查看授权项目内同步执行的 Agent、Kafka、审计、worker、Recovery 与最终验证低敏事实。',
 CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '运营人员查看租户同步执行全链路图', 'OPERATOR', 'GET',
 '/api/sync/sync-tasks/*/executions/*/lifecycle-graph',
 'SYNC_EXECUTION', 'VIEW', 'ALLOW', 786, TRUE,
 '运营人员可在租户范围内查看统一执行链路，用于运行排障；该权限不授予任何恢复或重试动作。',
 CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '租户管理员查看租户同步执行全链路图', 'TENANT_ADMINISTRATOR', 'GET',
 '/api/sync/sync-tasks/*/executions/*/lifecycle-graph',
 'SYNC_EXECUTION', 'VIEW', 'ALLOW', 766, TRUE,
 '租户管理员可在租户边界内查看低敏执行链路，不得借此读取其他租户或推进执行状态。',
 CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '审计员查看同步执行全链路证据', 'AUDITOR', 'GET',
 '/api/sync/sync-tasks/*/executions/*/lifecycle-graph',
 'SYNC_EXECUTION', 'VIEW', 'ALLOW', 121, TRUE,
 '审计员可只读复核来源、时间、可信度和低敏引用，不能执行、确认、恢复、重试或提交 worker 回调。',
 CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '平台管理员查看同步执行全链路图', 'PLATFORM_ADMINISTRATOR', 'GET',
 '/api/sync/sync-tasks/*/executions/*/lifecycle-graph',
 'SYNC_EXECUTION', 'VIEW', 'ALLOW', 1000, TRUE,
 '平台管理员可按平台治理职责查看低敏全链路事实，用于实施支持和事故响应。',
 CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT DO NOTHING;
