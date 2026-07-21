-- Keep authenticated NDJSON planning under the same permission boundary as the ordinary Agent plan endpoint.
SET search_path TO permission_admin, public;

INSERT INTO permission_route_policy
(tenant_id, policy_name, role_code, http_method, path_pattern, resource_type, action, effect, priority, enabled,
 description, create_time, update_time)
VALUES
(0, '普通用户实时生成受控 Agent 计划', 'ORDINARY_USER', 'POST', '/api/agent/plans/stream', 'AI_RUNTIME', 'PLAN',
 'ALLOW', 951, TRUE,
 '只允许在本人已加入的当前项目中逐步接收低敏规划事实；工具执行仍必须经过确认、项目范围复核和真实适配器权限校验。',
 CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '项目负责人实时生成受控 Agent 计划', 'PROJECT_OWNER', 'POST', '/api/agent/plans/stream', 'AI_RUNTIME', 'PLAN',
 'ALLOW', 961, TRUE,
 '项目负责人可在已授权项目中接收实时规划事实；流式交付不扩大其会话、工具、数据源或任务操作权限。',
 CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT DO NOTHING;
