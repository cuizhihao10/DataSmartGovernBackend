-- Allow every interactive Agent role to stop only its own in-flight planning request.
-- Tenant, project and actor isolation are rebuilt from the signed gateway context;
-- requestId is only a correlation key and is never accepted as an authorization credential.
SET search_path TO permission_admin, public;

INSERT INTO permission_route_policy
(tenant_id, policy_name, role_code, http_method, path_pattern, resource_type, action, effect, priority, enabled,
 description, create_time, update_time)
VALUES
(0, 'Ordinary user cancels own Agent inference', 'ORDINARY_USER', 'POST', '/api/agent/plans/cancel',
 'AI_RUNTIME', 'CANCEL_INFERENCE', 'ALLOW', 952, TRUE,
 'Allows an ordinary user to stop an in-flight Agent request in the current authorized project. The runtime also requires an exact tenant, project, actor and request match.',
 CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, 'Project owner cancels own Agent inference', 'PROJECT_OWNER', 'POST', '/api/agent/plans/cancel',
 'AI_RUNTIME', 'CANCEL_INFERENCE', 'ALLOW', 962, TRUE,
 'Allows a project owner to stop an in-flight Agent request in the current authorized project without expanding tool, datasource or task permissions.',
 CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT DO NOTHING;
