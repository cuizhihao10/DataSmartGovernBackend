-- permission-admin MySQL 兼容迁移：FlashSync 普通用户样例账号开通。
--
-- PostgreSQL 是 permission-admin 目标数据库，主迁移见
-- `V14__flashsync_ordinary_user_bootstrap.sql`。
-- 本脚本只服务仍在 MySQL 兼容路径上的本地环境或历史数据卷。
--
-- 注意：
-- 真实登录账号和密码由 Keycloak/企业 IdP 托管。
-- permission_identity_user 只保存低敏影子身份映射，不保存密码、token 或 client secret。

USE datasmart_govern;

INSERT INTO permission_project_membership
(tenant_id, actor_id, project_id, workspace_id, project_role, grant_source, enabled, create_time, update_time)
VALUES
(10, 1004, 101, 10001, 'MEMBER', 'TENANT_BOOTSTRAP', 1, NOW(), NOW())
ON DUPLICATE KEY UPDATE workspace_id = VALUES(workspace_id),
project_role = VALUES(project_role),
grant_source = VALUES(grant_source),
enabled = VALUES(enabled),
update_time = NOW();

INSERT INTO permission_identity_user
(tenant_id, actor_id, provider_mode, provider_user_id, username, email, actor_role, actor_type,
 workspace_id, status, disabled_reason, create_time, update_time)
VALUES
(10, 1004, 'KEYCLOAK_REALM_IMPORT', 'datasmart/ordinary-user', 'ordinary-user', 'ordinary-user@example.local',
 'ORDINARY_USER', 'USER', 'workspace-a', 'ACTIVE', NULL, NOW(), NOW())
ON DUPLICATE KEY UPDATE provider_mode = VALUES(provider_mode),
provider_user_id = VALUES(provider_user_id),
username = VALUES(username),
email = VALUES(email),
actor_role = VALUES(actor_role),
actor_type = VALUES(actor_type),
workspace_id = VALUES(workspace_id),
status = VALUES(status),
disabled_reason = VALUES(disabled_reason),
update_time = NOW();

INSERT INTO permission_audit_record
(trace_id, tenant_id, actor_id, actor_role, resource_type, resource_id, action, result, summary, detail_json, create_time)
SELECT 'bootstrap-flashsync-ordinary-user', 10, 9001, 'PLATFORM_ADMINISTRATOR',
       'IDENTITY_ONBOARDING', 'tenant:10/actor:1004', 'OPEN_ORDINARY_USER', 'SUCCESS',
       'FlashSync 普通用户 ordinary-user 已初始化：Keycloak 登录账号由 realm import 托管，permission-admin 仅保存影子身份和项目成员授权。',
       '{"tenantId":10,"actorId":1004,"username":"ordinary-user","actorRole":"ORDINARY_USER","projectId":101,"workspaceId":10001,"workspaceKey":"workspace-a","passwordStorage":"KEYCLOAK_ONLY","passwordHintForLocalDev":"DataSmart@123"}',
       NOW()
WHERE NOT EXISTS (
    SELECT 1
    FROM permission_audit_record
    WHERE trace_id = 'bootstrap-flashsync-ordinary-user'
      AND tenant_id = 10
      AND action = 'OPEN_ORDINARY_USER'
);
