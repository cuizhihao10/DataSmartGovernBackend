-- DataSmart Govern - Agent 异步工具 worker 执行前权限策略补充
--
-- 背景说明：
-- 1. 4.76 已经让 task-management worker 在执行前回查 agent-runtime confirmation，确认任务来自已确认 DAG 节点。
-- 2. 仅有 confirmation 仍不够：权限策略可能在入箱后、执行前发生变化，worker 必须重新询问 permission-admin。
-- 3. 本迁移增加独立的 EXECUTE_CONFIRMED_ASYNC_TOOL 动作，用于保护“worker 即将产生真实工具副作用”这一跳。
-- 4. 默认只允许 SERVICE_ACCOUNT 访问 task-management 的内部 Agent worker 执行语义；普通用户仍应通过会话、确认和任务链路间接触发。

INSERT IGNORE INTO permission_route_policy
(tenant_id, policy_name, role_code, http_method, path_pattern, resource_type, action, effect, priority, enabled, description, create_time, update_time)
VALUES
(0, '服务账号执行已确认 Agent 异步工具', 'SERVICE_ACCOUNT', 'POST',
 '/internal/task-management/agent-async-tools/*/execute',
 'AI_RUNTIME', 'EXECUTE_CONFIRMED_ASYNC_TOOL', 'ALLOW', 870, 1,
 '允许 task-management Agent worker 在回查 confirmation、任务状态和工具白名单之后，以服务账号身份代表上游 actor 执行已确认异步工具；该策略只保护 worker 执行入口，不替代下游 data-sync/data-quality 等领域服务自己的幂等、租约和数据范围校验。',
 NOW(), NOW()),
(0, '普通用户禁止直接调用 Agent worker 执行入口', 'ORDINARY_USER', 'POST',
 '/internal/task-management/agent-async-tools/*/execute',
 'AI_RUNTIME', 'EXECUTE_CONFIRMED_ASYNC_TOOL', 'DENY', 900, 1,
 'Agent worker 执行入口只能由受控任务调度链路触发，普通用户不能绕过会话、确认、任务队列和 worker 复核直接调用。',
 NOW(), NOW()),
(0, '审计员禁止调用 Agent worker 执行入口', 'AUDITOR', 'POST',
 '/internal/task-management/agent-async-tools/*/execute',
 'AI_RUNTIME', 'EXECUTE_CONFIRMED_ASYNC_TOOL', 'DENY', 900, 1,
 '审计员只能复核 Agent 工具确认、任务和执行证据，不能触发真实工具副作用。',
 NOW(), NOW());
