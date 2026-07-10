-- Allow a user to inspect the low-sensitive result of a tool node from their own Agent run.
-- Agent Runtime still enforces tenant/project/actor ownership; this policy only aligns the gateway route action.
SET search_path TO permission_admin, public;

INSERT INTO permission_route_policy
(tenant_id, policy_name, role_code, http_method, path_pattern, resource_type, action, effect, priority, enabled,
 description, create_time, update_time)
VALUES
(0, '普通用户查看本人 Agent 工具结果详情', 'ORDINARY_USER', 'GET',
 '/api/agent/sessions/*/runs/*/tool-executions/*/result', 'AI_RUNTIME', 'VIEW', 'ALLOW', 955, TRUE,
 '只允许读取本人当前项目 Run 的低敏工具结果；Agent Runtime 继续校验 session、run、audit 和 actor 归属。',
 CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '项目负责人查看本人 Agent 工具结果详情', 'PROJECT_OWNER', 'GET',
 '/api/agent/sessions/*/runs/*/tool-executions/*/result', 'AI_RUNTIME', 'VIEW', 'ALLOW', 965, TRUE,
 '项目负责人只能读取本人发起 Run 的低敏节点结果，不能借项目角色接管其他用户会话。',
 CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT DO NOTHING;
