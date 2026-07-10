-- Align user-facing Agent actions with gateway route metadata.
SET search_path TO permission_admin, public;

INSERT INTO permission_route_policy
(tenant_id, policy_name, role_code, http_method, path_pattern, resource_type, action, effect, priority, enabled,
 description, create_time, update_time)
VALUES
(0, '普通用户生成受控 Agent 计划', 'ORDINARY_USER', 'POST', '/api/agent/plans', 'AI_RUNTIME', 'PLAN',
 'ALLOW', 950, TRUE, '只允许生成本人当前项目范围内的计划；真实写操作必须继续经过确认执行入口。',
 CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '项目负责人生成受控 Agent 计划', 'PROJECT_OWNER', 'POST', '/api/agent/plans', 'AI_RUNTIME', 'PLAN',
 'ALLOW', 960, TRUE, '项目负责人可在已授权项目中生成计划；不能借此接管其他用户 Run。',
 CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT DO NOTHING;
