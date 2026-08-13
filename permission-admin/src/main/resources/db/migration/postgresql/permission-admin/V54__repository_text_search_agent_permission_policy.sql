-- Register the repository text search permission used by the Agent tool catalog.
--
-- `workspace.text.search` is the historical model-facing tool code. Its business meaning is
-- repository text search, so `agent:repository-text:search` is the stable permission code that
-- callers should carry across tool renames and transport changes.
--
-- The path below is a logical permission path for the control plane, not a public HTTP endpoint.
-- The existing AI_RUNTIME data-scope rows continue to enforce tenant/project/actor isolation.
-- The semantic existence check intentionally ignores effect and enabled state. Therefore a local
-- ALLOW, DENY, or disabled policy is preserved instead of being supplemented by this baseline.
-- The ON CONFLICT clause remains as a concurrent-migration safety net.
SET search_path TO permission_admin, public;

INSERT INTO permission_route_policy
(tenant_id, policy_name, role_code, http_method, path_pattern, resource_type, action, effect, priority, enabled,
 description, create_time, update_time)
SELECT 0,
       role_code || ' repository text search permission',
       role_code,
       'POST',
       '/internal/agent-runtime/tools/repository-text/search',
       'AI_RUNTIME',
       'agent:repository-text:search',
       'ALLOW',
       priority,
       TRUE,
       'Allows the interactive Agent to perform read-only literal text search inside the governed repository scope. '
           || 'The legacy workspace.text.search tool code maps to this stable repository permission.',
       CURRENT_TIMESTAMP,
       CURRENT_TIMESTAMP
FROM (VALUES
    ('ORDINARY_USER', 976),
    ('PROJECT_OWNER', 986)
) AS roles(role_code, priority)
WHERE NOT EXISTS (
    -- Check the complete authorization meaning, not only the unique index that also includes effect.
    SELECT 1
    FROM permission_route_policy existing_policy
    WHERE existing_policy.tenant_id = 0
      AND existing_policy.role_code = roles.role_code
      AND existing_policy.http_method = 'POST'
      AND existing_policy.path_pattern = '/internal/agent-runtime/tools/repository-text/search'
      AND COALESCE(existing_policy.resource_type, '') = 'AI_RUNTIME'
      AND COALESCE(existing_policy.action, '') = 'agent:repository-text:search'
)
ON CONFLICT DO NOTHING;
