-- permission-admin：租户 / 应用 / 项目三层隔离语义收敛。
--
-- 设计背景：
-- 1. 租户表示客户公司或组织，是平台最高数据隔离边界；不同公司的 tenant_id 必须不同。
-- 2. 应用表示该租户开通的产品能力，例如 FlashSync。它是云平台上的一个产品入口，不等同于业务项目。
-- 3. 项目表示租户在某个应用下创建的业务隔离单元，数据源、同步任务、质量规则和 Agent 会话都应归属项目。
-- 4. workspace 已经退出普通用户可见层级。物理表和列暂时保留，只服务历史执行事实、Agent 内部工作区和兼容脚本。
-- 5. 项目内角色收敛为 OWNER / MANAGER / READER / SERVICE，避免旧的 MAINTAINER、MEMBER、VIEWER 在下游继续扩散。

SET search_path TO permission_admin, public;

COMMENT ON TABLE permission_tenant IS
    '租户主数据表。租户表示一个客户公司、组织或平台自身，是 DataSmart 多租户数据隔离的最高业务边界。';
COMMENT ON COLUMN permission_tenant.tenant_id IS
    '租户数字 ID。不同公司必须使用不同 tenant_id；业务表、网关 Header、审计、Agent 记忆命名空间都应携带该边界。';
COMMENT ON COLUMN permission_tenant.default_application_code IS
    '租户默认应用编码。一个租户可开通多个应用；当前本地样例默认进入 FlashSync。';

COMMENT ON TABLE permission_application IS
    '租户应用主数据表。Application 表示租户开通的产品能力，例如 FlashSync、IAM、ALB、数据质量或资产目录；它不是业务项目。';
COMMENT ON COLUMN permission_application.application_id IS
    '应用数字 ID。它用于产品入口、应用级菜单、订阅、能力开关、配额和审计；不要把它当成 projectId。';
COMMENT ON COLUMN permission_application.application_code IS
    '应用稳定编码。示例 FLASHSYNC 表示租户开通的 FlashSync 数据同步与 Agent 应用。';
COMMENT ON COLUMN permission_application.default_project_id IS
    '应用默认项目 ID。用户进入应用但尚未主动选择项目时，可落到该业务项目。';
COMMENT ON COLUMN permission_application.default_workspace_id IS
    '历史兼容字段：workspace 已退出普通用户可见层级，正式业务链路不再依赖该字段选择资源范围。';

COMMENT ON TABLE permission_project IS
    '项目主数据表。Project 是某个租户在某个应用下的业务隔离单元；数据源、同步任务、质量规则和 Agent 会话都应挂到项目。';
COMMENT ON COLUMN permission_project.project_id IS
    '项目数字 ID。项目 ID 进入 X-DataSmart-Project-Id，并作为数据源、同步任务、质量规则等资源的隔离字段。';
COMMENT ON COLUMN permission_project.application_id IS
    '项目所属应用 ID。它表达“这个业务项目属于哪个产品能力”，例如 FlashSync 默认项目属于 FlashSync 应用。';
COMMENT ON COLUMN permission_project.default_workspace_id IS
    '历史兼容字段：新建项目不再写入默认 workspace，普通页面也不再让用户切换 workspace。';

COMMENT ON TABLE permission_workspace IS
    '工作空间兼容表。workspace 不再是普通用户可见层级，仅保留给历史执行事实、Agent 内部工作区、旧脚本和迁移期兼容。';
COMMENT ON COLUMN permission_workspace.project_id IS
    '历史兼容归属字段。新业务资源以 project_id 为边界，普通业务查询不再要求 workspace_id。';

COMMENT ON TABLE permission_project_membership IS
    '项目成员授权关系表。它负责把 actor 可访问的项目及项目内角色物化给 gateway，支撑项目级读写隔离。';
COMMENT ON COLUMN permission_project_membership.workspace_id IS
    '历史兼容字段：项目成员授权当前只按 project_id 生效，不再按 workspace_id 缩小普通用户业务范围。';
COMMENT ON COLUMN permission_project_membership.project_role IS
    '项目内应用角色：OWNER 项目负责人，MANAGER 可管理项目内资源，READER 只读查看，SERVICE 受控机器身份。';

COMMENT ON COLUMN permission_identity_user.workspace_id IS
    '历史兼容字段：身份影子表不再保存用户默认 workspace。登录态项目候选集合来自项目成员关系和 Keycloak/企业 IdP claim。';

UPDATE permission_application
SET default_workspace_id = NULL,
    update_time = CURRENT_TIMESTAMP
WHERE default_workspace_id IS NOT NULL;

UPDATE permission_project
SET default_workspace_id = NULL,
    update_time = CURRENT_TIMESTAMP
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
    update_time = CURRENT_TIMESTAMP
WHERE workspace_id IS NOT NULL
   OR UPPER(project_role) NOT IN ('OWNER', 'MANAGER', 'READER', 'SERVICE');

UPDATE permission_identity_user
SET workspace_id = NULL,
    update_time = CURRENT_TIMESTAMP
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
