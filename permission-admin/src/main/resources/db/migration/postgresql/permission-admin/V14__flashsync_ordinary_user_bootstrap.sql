-- permission-admin：FlashSync 普通用户样例账号开通。
--
-- 背景说明：
-- 当前项目尚未实现面向终端用户的自助注册流程，登录账号由 Keycloak/企业 IdP 托管。
-- 因此本地闭环环境需要预置一个“普通用户”账号，用来验证非管理员视角下的
-- 数据源、同步任务、质量报告、Agent 会话和权限边界。
--
-- 设计边界：
-- 1. Keycloak realm import 保存真实登录账号和密码哈希；本迁移只保存 DataSmart 低敏影子身份映射。
-- 2. ordinary-user 的 DataSmart actorRole 为 ORDINARY_USER，不具备项目负责人、运营、审计或平台管理员权限。
-- 3. 项目成员关系授予 MEMBER，用于让 gateway/data-sync/data-quality/agent-runtime 能识别它属于
--    FlashSync 默认业务项目，但具体资源访问仍由 ORDINARY_USER 的 SELF 数据范围策略继续收口。
-- 4. 密码不会写入 permission-admin；本地 Keycloak 样例密码仍是 DataSmart@123。

SET search_path TO permission_admin, public;

INSERT INTO permission_project_membership
(tenant_id, actor_id, project_id, workspace_id, project_role, grant_source, enabled, create_time, update_time)
VALUES
(10, 1004, 101, 10001, 'MEMBER', 'TENANT_BOOTSTRAP', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (tenant_id, actor_id, project_id) DO UPDATE
SET workspace_id = EXCLUDED.workspace_id,
    project_role = EXCLUDED.project_role,
    grant_source = EXCLUDED.grant_source,
    enabled = EXCLUDED.enabled,
    update_time = CURRENT_TIMESTAMP;

INSERT INTO permission_identity_user
(tenant_id, actor_id, provider_mode, provider_user_id, username, email, actor_role, actor_type,
 workspace_id, status, disabled_reason, create_time, update_time)
VALUES
(10, 1004, 'KEYCLOAK_REALM_IMPORT', 'datasmart/ordinary-user', 'ordinary-user', 'ordinary-user@example.local',
 'ORDINARY_USER', 'USER', 'workspace-a', 'ACTIVE', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (tenant_id, actor_id) DO UPDATE
SET provider_mode = EXCLUDED.provider_mode,
    provider_user_id = EXCLUDED.provider_user_id,
    username = EXCLUDED.username,
    email = EXCLUDED.email,
    actor_role = EXCLUDED.actor_role,
    actor_type = EXCLUDED.actor_type,
    workspace_id = EXCLUDED.workspace_id,
    status = EXCLUDED.status,
    disabled_reason = EXCLUDED.disabled_reason,
    update_time = CURRENT_TIMESTAMP;

INSERT INTO permission_audit_record
(trace_id, tenant_id, actor_id, actor_role, resource_type, resource_id, action, result, summary, detail_json, create_time)
SELECT 'bootstrap-flashsync-ordinary-user', 10, 9001, 'PLATFORM_ADMINISTRATOR',
       'IDENTITY_ONBOARDING', 'tenant:10/actor:1004', 'OPEN_ORDINARY_USER', 'SUCCESS',
       'FlashSync 普通用户 ordinary-user 已初始化：Keycloak 登录账号由 realm import 托管，permission-admin 仅保存影子身份和项目成员授权。',
       '{"tenantId":10,"actorId":1004,"username":"ordinary-user","actorRole":"ORDINARY_USER","projectId":101,"workspaceId":10001,"workspaceKey":"workspace-a","passwordStorage":"KEYCLOAK_ONLY","passwordHintForLocalDev":"DataSmart@123"}',
       CURRENT_TIMESTAMP
WHERE NOT EXISTS (
    SELECT 1
    FROM permission_audit_record
    WHERE trace_id = 'bootstrap-flashsync-ordinary-user'
      AND tenant_id = 10
      AND action = 'OPEN_ORDINARY_USER'
);
