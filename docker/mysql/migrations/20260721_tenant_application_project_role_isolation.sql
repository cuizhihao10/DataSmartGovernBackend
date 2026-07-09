-- permission-admin MySQL 兼容迁移：租户 / 应用 / 项目三层隔离语义收敛。
--
-- PostgreSQL 是 permission-admin 的目标数据库，主迁移见：
-- `V23__tenant_application_project_role_isolation.sql`。
-- 本脚本只用于仍在 MySQL 兼容路径上的本地环境或历史数据卷。

USE datasmart_govern;

UPDATE permission_application
SET default_workspace_id = NULL,
    update_time = NOW()
WHERE default_workspace_id IS NOT NULL;

UPDATE permission_project
SET default_workspace_id = NULL,
    update_time = NOW()
WHERE default_workspace_id IS NOT NULL;

UPDATE permission_project_membership
SET workspace_id = NULL,
    project_role = CASE UPPER(project_role)
        WHEN 'OWNER' THEN 'OWNER'
        WHEN 'MAINTAINER' THEN 'MANAGER'
        WHEN 'MEMBER' THEN 'MANAGER'
        WHEN 'MANAGER' THEN 'MANAGER'
        WHEN 'VIEWER' THEN 'READER'
        WHEN 'READER' THEN 'READER'
        WHEN 'SERVICE' THEN 'SERVICE'
        WHEN 'SERVICE_ACCOUNT' THEN 'SERVICE'
        ELSE 'READER'
    END,
    update_time = NOW()
WHERE workspace_id IS NOT NULL
   OR UPPER(project_role) NOT IN ('OWNER', 'MANAGER', 'READER', 'SERVICE');

UPDATE permission_identity_user
SET workspace_id = NULL,
    update_time = NOW()
WHERE workspace_id IS NOT NULL;

UPDATE permission_audit_record
SET summary = 'FlashSync 开租基线已初始化：租户、应用、默认项目、样例账号影子身份和项目成员授权已落库；workspace 仅作历史兼容保留。',
    detail_json = '{"tenantId":10,"tenantCode":"FLASHSYNC","applicationId":10010,"applicationCode":"FLASHSYNC","defaultProjectId":101,"workspacePolicy":"DEPRECATED_COMPATIBILITY_ONLY","payloadPolicy":"LOW_SENSITIVE_BOOTSTRAP_FACT_NO_PASSWORD_NO_TOKEN"}'
WHERE trace_id = 'bootstrap-flashsync-tenant'
  AND tenant_id = 10
  AND action = 'OPEN_TENANT';

UPDATE permission_audit_record
SET detail_json = '{"tenantId":10,"actorId":1004,"username":"ordinary-user","actorRole":"ORDINARY_USER","projectId":101,"projectRole":"MANAGER","workspacePolicy":"DEPRECATED_COMPATIBILITY_ONLY","passwordStorage":"KEYCLOAK_ONLY","passwordHintForLocalDev":"DataSmart@123"}'
WHERE trace_id = 'bootstrap-flashsync-ordinary-user'
  AND tenant_id = 10
  AND action = 'OPEN_ORDINARY_USER';
