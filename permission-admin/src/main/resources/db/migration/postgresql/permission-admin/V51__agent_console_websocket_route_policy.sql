-- Add the authenticated WebSocket handshake route that complements V50's
-- HTTP replay/control policies. The handshake is a GET and must use the same
-- governed SUBSCRIBE action as the Gateway default catalog.
SET search_path TO permission_admin, public;

INSERT INTO permission_route_policy
(tenant_id, policy_name, role_code, http_method, path_pattern, resource_type, action, effect, priority, enabled,
 description, create_time, update_time)
VALUES
(0, '普通用户订阅本人 Agent 实时事件', 'ORDINARY_USER', 'GET', '/api/agent/events/ws',
 'AI_RUNTIME', 'SUBSCRIBE', 'ALLOW', 973, TRUE,
 'WebSocket 握手只建立当前用户当前项目的低敏事件订阅；不授予工具执行、审批或业务数据写入权限。',
 CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '项目负责人订阅本人 Agent 实时事件', 'PROJECT_OWNER', 'GET', '/api/agent/events/ws',
 'AI_RUNTIME', 'SUBSCRIBE', 'ALLOW', 983, TRUE,
 '项目负责人仍按本人会话和当前授权项目订阅低敏 Agent 事件，实时通道不扩大项目数据范围。',
 CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '运营人员订阅 Agent 实时事件', 'OPERATOR', 'GET', '/api/agent/events/ws',
 'AI_RUNTIME', 'SUBSCRIBE', 'ALLOW', 993, TRUE,
 '运营人员只能在可信租户和项目范围内订阅低敏运行事件，不获得执行动作权限。',
 CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '审计员订阅 Agent 实时事件', 'AUDITOR', 'GET', '/api/agent/events/ws',
 'AI_RUNTIME', 'SUBSCRIBE', 'ALLOW', 993, TRUE,
 '审计员只能在可信租户和项目范围内订阅低敏运行事件，不获得执行动作权限。',
 CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT DO NOTHING;
