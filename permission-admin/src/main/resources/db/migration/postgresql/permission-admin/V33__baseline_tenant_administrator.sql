-- Ensure the local FlashSync baseline has the same administrator hierarchy as dynamically opened tenants.
SET search_path TO permission_admin, public;

INSERT INTO permission_identity_user
(tenant_id, actor_id, provider_mode, provider_user_id, username, email, actor_role, actor_type, workspace_id,
 status, disabled_reason, create_time, update_time)
VALUES
(10, 100000, 'KEYCLOAK_REALM_IMPORT', 'datasmart/tenant-admin', 'tenant-admin',
 'tenant-admin@example.local', 'TENANT_ADMINISTRATOR', 'USER', NULL,
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

UPDATE permission_tenant
SET owner_actor_id = 100000,
    update_time = CURRENT_TIMESTAMP
WHERE tenant_id = 10;

UPDATE permission_application
SET owner_actor_id = 100000,
    update_time = CURRENT_TIMESTAMP
WHERE tenant_id = 10
  AND application_code = 'FLASHSYNC';

SELECT setval(
    'permission_admin.permission_identity_actor_id_seq',
    GREATEST((SELECT COALESCE(MAX(actor_id), 0) FROM permission_identity_user), 100000),
    TRUE
);
