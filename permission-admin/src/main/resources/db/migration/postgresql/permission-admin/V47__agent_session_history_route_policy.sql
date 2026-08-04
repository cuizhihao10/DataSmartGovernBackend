-- Open the durable Agent conversation history endpoints to interactive users.
--
-- Security boundary:
-- 1. The route policy only decides whether the role may enter the endpoint.
-- 2. V35 keeps AI_RUNTIME scoped to actor_id + authorized project ids.
-- 3. agent-runtime performs the final tenant/project/actor ownership check for every session id.
-- Therefore a PROJECT_OWNER can manage only their own assistant conversations here; this migration does not
-- turn the personal conversation history into a project-wide audit console.
SET search_path TO permission_admin, public;

INSERT INTO permission_route_policy
(tenant_id, policy_name, role_code, http_method, path_pattern, resource_type, action, effect, priority, enabled,
 description, create_time, update_time)
VALUES
(0, 'Ordinary user lists own Agent conversations', 'ORDINARY_USER', 'GET', '/api/agent/sessions',
 'AI_RUNTIME', 'VIEW', 'ALLOW', 956, TRUE,
 'Lists only the current actor conversations in the selected authorized project; agent-runtime rebuilds and validates the complete ownership scope.',
 CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, 'Ordinary user opens own Agent conversation', 'ORDINARY_USER', 'GET', '/api/agent/sessions/*',
 'AI_RUNTIME', 'VIEW', 'ALLOW', 956, TRUE,
 'Loads one durable conversation aggregate only after tenant, project and actor ownership checks succeed.',
 CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, 'Ordinary user pins own Agent conversation', 'ORDINARY_USER', 'PATCH', '/api/agent/sessions/*/pin',
 'AI_RUNTIME', 'UPDATE', 'ALLOW', 957, TRUE,
 'Changes only the display order of a conversation owned by the current actor and does not modify a Run or tool approval.',
 CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, 'Ordinary user archives own Agent conversation', 'ORDINARY_USER', 'PATCH', '/api/agent/sessions/*/archive',
 'AI_RUNTIME', 'UPDATE', 'ALLOW', 957, TRUE,
 'Moves only the current actor conversation between active and archived history without deleting execution evidence.',
 CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, 'Project owner lists own Agent conversations', 'PROJECT_OWNER', 'GET', '/api/agent/sessions',
 'AI_RUNTIME', 'VIEW', 'ALLOW', 966, TRUE,
 'Lists the project owner personal conversations in the selected authorized project; the project role does not grant access to member conversations.',
 CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, 'Project owner opens own Agent conversation', 'PROJECT_OWNER', 'GET', '/api/agent/sessions/*',
 'AI_RUNTIME', 'VIEW', 'ALLOW', 966, TRUE,
 'Loads one project owner conversation only after agent-runtime verifies tenant, project and actor ownership.',
 CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, 'Project owner pins own Agent conversation', 'PROJECT_OWNER', 'PATCH', '/api/agent/sessions/*/pin',
 'AI_RUNTIME', 'UPDATE', 'ALLOW', 967, TRUE,
 'Changes only the display order of the project owner personal conversation and grants no project-wide conversation management power.',
 CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, 'Project owner archives own Agent conversation', 'PROJECT_OWNER', 'PATCH', '/api/agent/sessions/*/archive',
 'AI_RUNTIME', 'UPDATE', 'ALLOW', 967, TRUE,
 'Archives or restores only the project owner personal conversation while retaining messages, Runs and audit evidence.',
 CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT DO NOTHING;
