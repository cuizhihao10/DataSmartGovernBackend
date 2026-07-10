-- Reproducible local ordinary-user identities for tenant and project-isolation acceptance tests.
--
-- The passwords remain owned by Keycloak. These rows only map external identities to stable DataSmart actors.
-- No project membership is granted here: each user must use the normal project join approval workflow.
SET search_path TO permission_admin, public;

INSERT INTO permission_identity_user
(tenant_id, actor_id, provider_mode, provider_user_id, username, email, actor_role, actor_type, workspace_id,
 status, disabled_reason, create_time, update_time)
VALUES
(10, 100001, 'KEYCLOAK_REALM_IMPORT', '00000000-0000-0000-0000-000000010010',
 'test10', 'test10@example.local', 'ORDINARY_USER', 'USER', NULL,
 'ACTIVE', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(10, 100002, 'KEYCLOAK_REALM_IMPORT', '00000000-0000-0000-0000-000000010007',
 'test7', 'test7@example.local', 'ORDINARY_USER', 'USER', NULL,
 'ACTIVE', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(10, 100003, 'KEYCLOAK_REALM_IMPORT', '00000000-0000-0000-0000-000000010037',
 'test37', 'test37@example.local', 'ORDINARY_USER', 'USER', NULL,
 'ACTIVE', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (tenant_id, actor_id) DO UPDATE
SET username = EXCLUDED.username,
    email = EXCLUDED.email,
    actor_role = EXCLUDED.actor_role,
    actor_type = EXCLUDED.actor_type,
    workspace_id = NULL,
    status = EXCLUDED.status,
    disabled_reason = NULL,
    update_time = CURRENT_TIMESTAMP;

SELECT setval(
    'permission_admin.permission_identity_actor_id_seq',
    GREATEST((SELECT COALESCE(MAX(actor_id), 0) FROM permission_identity_user), 100003),
    TRUE
);
