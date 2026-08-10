-- Expose only the low-sensitive LangGraph latest/events read surface through the authenticated Gateway.
-- Python Runtime performs a second tenant/project/actor object check after Gateway HMAC verification.
-- pause/resume/fork are deliberately absent: interactive users must continue through Java approval and execution facts.
SET search_path TO permission_admin, public;

INSERT INTO permission_route_policy
(tenant_id, policy_name, role_code, http_method, path_pattern, resource_type, action, effect, priority, enabled,
 description, create_time, update_time)
SELECT 0,
       role_code || ' 读取 LangGraph checkpoint ' || path_pattern,
       role_code,
       'GET',
       path_pattern,
       'AI_RUNTIME',
       'VIEW_CHECKPOINT',
       'ALLOW',
       priority,
       TRUE,
       '只返回低敏 checkpoint/event 摘要；Gateway HMAC 和 Python 对象范围校验必须同时通过。',
       CURRENT_TIMESTAMP,
       CURRENT_TIMESTAMP
FROM (VALUES
    ('ORDINARY_USER', 974),
    ('PROJECT_OWNER', 984),
    ('OPERATOR', 994),
    ('AUDITOR', 994)
) AS roles(role_code, priority)
CROSS JOIN (VALUES
    ('/api/agent/langgraph/checkpoints/latest'),
    ('/api/agent/langgraph/checkpoints/events')
) AS routes(path_pattern)
ON CONFLICT DO NOTHING;
