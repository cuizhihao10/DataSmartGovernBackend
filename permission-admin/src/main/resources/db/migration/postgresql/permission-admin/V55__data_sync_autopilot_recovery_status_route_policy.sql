-- Register the public, low-sensitive Autopilot recovery status as an execution VIEW.
--
-- This path is deliberately separate from execution callbacks and recovery mutations. It lets a user,
-- operator, or auditor observe bounded unattended recovery without granting permission to record a model
-- decision, apply quarantine, retry failed objects, or advance a recovery case. The data-sync controller
-- still performs its normal task visibility check and then independently verifies execution ownership.
--
-- Returned data is restricted to identifiers, finite lifecycle codes, bounded counts, and timestamps. It
-- excludes raw logs, SQL, credentials, source rows, selected sample IDs, prompts, model output, tool arguments,
-- authorization/policy digests, repair fingerprints, and Kafka payloads.
SET search_path TO permission_admin, public;

INSERT INTO permission_route_policy
(tenant_id, policy_name, role_code, http_method, path_pattern, resource_type, action, effect, priority, enabled,
 description, create_time, update_time)
VALUES
(0, 'Ordinary user views own Autopilot recovery status', 'ORDINARY_USER', 'GET',
 '/api/sync/sync-tasks/*/executions/*/autopilot-recovery',
 'SYNC_EXECUTION', 'VIEW', 'ALLOW', 129, TRUE,
 'Allows an ordinary user to view low-sensitive Autopilot recovery facts for a sync task already visible in SELF scope.',
 CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, 'Project owner views Autopilot recovery status', 'PROJECT_OWNER', 'GET',
 '/api/sync/sync-tasks/*/executions/*/autopilot-recovery',
 'SYNC_EXECUTION', 'VIEW', 'ALLOW', 149, TRUE,
 'Allows a project owner to inspect bounded autonomous recovery for an authorized project without granting a recovery mutation.',
 CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, 'Operator views Autopilot recovery status', 'OPERATOR', 'GET',
 '/api/sync/sync-tasks/*/executions/*/autopilot-recovery',
 'SYNC_EXECUTION', 'VIEW', 'ALLOW', 785, TRUE,
 'Allows tenant operations staff to observe recovery cycles, quarantine counts, and terminal state for incident response.',
 CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, 'Tenant administrator views Autopilot recovery status', 'TENANT_ADMINISTRATOR', 'GET',
 '/api/sync/sync-tasks/*/executions/*/autopilot-recovery',
 'SYNC_EXECUTION', 'VIEW', 'ALLOW', 765, TRUE,
 'Allows a tenant administrator to view low-sensitive Autopilot recovery facts within the tenant boundary.',
 CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, 'Auditor views Autopilot recovery status', 'AUDITOR', 'GET',
 '/api/sync/sync-tasks/*/executions/*/autopilot-recovery',
 'SYNC_EXECUTION', 'VIEW', 'ALLOW', 120, TRUE,
 'Allows an auditor to read bounded recovery evidence without retry, quarantine, callback, or case-transition authority.',
 CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, 'Platform administrator views Autopilot recovery status', 'PLATFORM_ADMINISTRATOR', 'GET',
 '/api/sync/sync-tasks/*/executions/*/autopilot-recovery',
 'SYNC_EXECUTION', 'VIEW', 'ALLOW', 1000, TRUE,
 'Allows platform operations to inspect low-sensitive recovery state across tenants for support and incident response.',
 CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT DO NOTHING;
