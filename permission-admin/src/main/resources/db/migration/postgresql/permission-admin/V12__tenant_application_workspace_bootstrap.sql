-- permission-admin：FlashSync 开租主数据基线。
--
-- 设计说明：
-- 1. “开租”不是让终端用户在页面里手填 tenantId、workspaceId、applicationId，而是平台管理员在安装、
--    交付或租户启用阶段创建一组受控的主数据事实。
-- 2. Keycloak/企业 IdP 仍然负责账号、密码、MFA、会话和 Token 签发；permission-admin 负责保存
--    租户、应用、项目、工作空间以及影子身份映射这些低敏控制面事实。
-- 3. 现有业务表已经大量使用 tenant_id/project_id/workspace_id，本迁移不是推翻它们，而是把这些
--    数字 ID 背后的业务语义补齐，避免后续用户、前端或 Agent 只能看到一串没有解释的数字。
-- 4. 本地样例 Keycloak 用户仍然沿用 tenantId=10、actorId=1001/1002/1003/9101，
--    因此 FlashSync 租户也固定使用 tenant_id=10，降低当前联调链路的迁移成本。
-- 5. workspace 同时保存数字 workspace_id 和字符串 external_workspace_key：
--    - 数字 workspace_id 服务 Java 业务表、统计索引和数据范围查询；
--    - external_workspace_key 对齐 Keycloak 本地样例 claim，例如 workspace-a、system-sync；
--    后续若网关把 workspace claim 统一改成数字，也可以通过这张表做平滑映射。

SET search_path TO permission_admin, public;

CREATE TABLE IF NOT EXISTS permission_tenant (
    tenant_id BIGINT PRIMARY KEY,
    tenant_code VARCHAR(64) NOT NULL,
    tenant_name VARCHAR(128) NOT NULL,
    tenant_type VARCHAR(32) NOT NULL DEFAULT 'BUSINESS',
    plan_code VARCHAR(64) NOT NULL DEFAULT 'STANDARD',
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    default_application_code VARCHAR(64),
    owner_actor_id BIGINT,
    opened_by BIGINT NOT NULL DEFAULT 0,
    opened_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    description VARCHAR(1000),
    create_time TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_permission_tenant_code UNIQUE (tenant_code),
    CONSTRAINT ck_permission_tenant_positive CHECK (tenant_id > 0),
    CONSTRAINT ck_permission_tenant_type CHECK (tenant_type IN ('PLATFORM', 'BUSINESS', 'INTERNAL')),
    CONSTRAINT ck_permission_tenant_status CHECK (status IN ('ACTIVE', 'SUSPENDED', 'CLOSED'))
);
COMMENT ON TABLE permission_tenant IS
    '租户主数据表。租户是商业交付和数据隔离的最高业务边界，开租时由平台管理员或安装脚本创建。';
COMMENT ON COLUMN permission_tenant.tenant_id IS
    '租户数字 ID。该 ID 会进入 X-DataSmart-Tenant-Id、业务表 tenant_id、审计记录和 Agent 记忆命名空间。';
COMMENT ON COLUMN permission_tenant.tenant_code IS
    '租户稳定编码，适合配置、脚本、日志和人工排障使用；示例：FLASHSYNC。';
COMMENT ON COLUMN permission_tenant.tenant_name IS
    '租户展示名称，面向管理后台和审计报表。';
COMMENT ON COLUMN permission_tenant.tenant_type IS
    '租户类型：PLATFORM 表示平台自身，BUSINESS 表示真实业务租户，INTERNAL 表示内部系统租户。';
COMMENT ON COLUMN permission_tenant.plan_code IS
    '租户套餐编码。后续可用于工具预算、并发额度、模型调用额度、连接器数量和高级能力开关。';
COMMENT ON COLUMN permission_tenant.status IS
    '租户生命周期状态：ACTIVE 可用，SUSPENDED 暂停，CLOSED 关闭。关闭不等于物理删除，应保留审计证据。';
COMMENT ON COLUMN permission_tenant.default_application_code IS
    '默认应用编码。一个租户未来可购买多个应用，本字段用于本地样例和单应用租户的默认入口。';
COMMENT ON COLUMN permission_tenant.owner_actor_id IS
    '租户默认负责人 actorId，通常是租户管理员或项目负责人。';
COMMENT ON COLUMN permission_tenant.opened_by IS
    '执行开租动作的平台管理员或安装主体 actorId。0 表示系统 bootstrap。';
COMMENT ON COLUMN permission_tenant.opened_at IS
    '开租完成时间，用于交付追踪、审计和后续计费起点。';
CREATE INDEX IF NOT EXISTS idx_permission_tenant_status
    ON permission_tenant (status, update_time);

CREATE TABLE IF NOT EXISTS permission_application (
    application_id BIGINT PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    application_code VARCHAR(64) NOT NULL,
    application_name VARCHAR(128) NOT NULL,
    application_type VARCHAR(64) NOT NULL DEFAULT 'DATA_SYNC_AGENT_APP',
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    homepage_path VARCHAR(256),
    default_project_id BIGINT,
    default_workspace_id BIGINT,
    owner_actor_id BIGINT,
    description VARCHAR(1000),
    create_time TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_permission_application_tenant_code UNIQUE (tenant_id, application_code),
    CONSTRAINT ck_permission_application_positive CHECK (application_id > 0),
    CONSTRAINT ck_permission_application_status CHECK (status IN ('ACTIVE', 'DISABLED', 'ARCHIVED')),
    CONSTRAINT fk_permission_application_tenant
        FOREIGN KEY (tenant_id) REFERENCES permission_tenant (tenant_id)
);
COMMENT ON TABLE permission_application IS
    '租户应用主数据表。应用是租户内可见产品能力的入口，例如 FlashSync 数据同步 Agent 应用。';
COMMENT ON COLUMN permission_application.application_id IS
    '应用数字 ID。后续如需应用级菜单、配额、审计、订阅或多应用切换，可稳定引用该字段。';
COMMENT ON COLUMN permission_application.application_code IS
    '应用稳定编码，适合进入 Keycloak claim、日志、配置和 Agent 工具上下文；示例：FLASHSYNC。';
COMMENT ON COLUMN permission_application.application_type IS
    '应用类型。当前 FlashSync 定位为数据同步 + Agent 协同应用，未来可扩展为质量、资产、合规等应用。';
COMMENT ON COLUMN permission_application.homepage_path IS
    '应用默认首页路径，便于前端根据当前应用跳转到正确产品入口。';
COMMENT ON COLUMN permission_application.default_project_id IS
    '应用默认项目 ID。创建同步任务、质量规则或 Agent 会话时，如果用户未显式选择项目，可使用该默认值。';
COMMENT ON COLUMN permission_application.default_workspace_id IS
    '应用默认工作空间 ID。用于本地样例、单工作空间租户和 Agent 默认 workspace。';
CREATE INDEX IF NOT EXISTS idx_permission_application_tenant_status
    ON permission_application (tenant_id, status, update_time);

CREATE TABLE IF NOT EXISTS permission_project (
    project_id BIGINT PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    application_id BIGINT NOT NULL,
    project_code VARCHAR(64) NOT NULL,
    project_name VARCHAR(128) NOT NULL,
    project_type VARCHAR(64) NOT NULL DEFAULT 'DATA_GOVERNANCE',
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    default_workspace_id BIGINT,
    owner_actor_id BIGINT,
    description VARCHAR(1000),
    create_time TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_permission_project_tenant_code UNIQUE (tenant_id, project_code),
    CONSTRAINT ck_permission_project_positive CHECK (project_id > 0),
    CONSTRAINT ck_permission_project_status CHECK (status IN ('ACTIVE', 'DISABLED', 'ARCHIVED')),
    CONSTRAINT fk_permission_project_tenant
        FOREIGN KEY (tenant_id) REFERENCES permission_tenant (tenant_id),
    CONSTRAINT fk_permission_project_application
        FOREIGN KEY (application_id) REFERENCES permission_application (application_id)
);
COMMENT ON TABLE permission_project IS
    '项目主数据表。项目是 datasource、data-sync、data-quality 和 Agent workspace 最常用的数据范围边界。';
COMMENT ON COLUMN permission_project.project_id IS
    '项目数字 ID。permission_project_membership 会把 actor 可访问的 project_id 物化给 gateway 和业务服务。';
COMMENT ON COLUMN permission_project.application_id IS
    '项目所属应用 ID。当前 FlashSync 默认项目挂在 FlashSync 应用下，未来多应用租户可按应用拆项目。';
COMMENT ON COLUMN permission_project.project_type IS
    '项目类型。当前使用 DATA_GOVERNANCE，后续可扩展为 SANDBOX、PRODUCTION、CUSTOMER_DELIVERY 等。';
CREATE INDEX IF NOT EXISTS idx_permission_project_tenant_status
    ON permission_project (tenant_id, status, update_time);

CREATE TABLE IF NOT EXISTS permission_workspace (
    workspace_id BIGINT PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    application_id BIGINT NOT NULL,
    project_id BIGINT,
    workspace_code VARCHAR(64) NOT NULL,
    external_workspace_key VARCHAR(128) NOT NULL,
    workspace_name VARCHAR(128) NOT NULL,
    environment_type VARCHAR(32) NOT NULL DEFAULT 'DEV',
    risk_level VARCHAR(32) NOT NULL DEFAULT 'NORMAL',
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    default_workspace BOOLEAN NOT NULL DEFAULT FALSE,
    sort_order INTEGER NOT NULL DEFAULT 100,
    description VARCHAR(1000),
    create_time TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_permission_workspace_tenant_project_code UNIQUE (tenant_id, project_id, workspace_code),
    CONSTRAINT uk_permission_workspace_external_key UNIQUE (tenant_id, external_workspace_key),
    CONSTRAINT ck_permission_workspace_positive CHECK (workspace_id > 0),
    CONSTRAINT ck_permission_workspace_environment CHECK (environment_type IN ('DEV', 'TEST', 'STAGING', 'PROD', 'SYSTEM')),
    CONSTRAINT ck_permission_workspace_risk CHECK (risk_level IN ('LOW', 'NORMAL', 'HIGH', 'SYSTEM')),
    CONSTRAINT ck_permission_workspace_status CHECK (status IN ('ACTIVE', 'DISABLED', 'ARCHIVED')),
    CONSTRAINT fk_permission_workspace_tenant
        FOREIGN KEY (tenant_id) REFERENCES permission_tenant (tenant_id),
    CONSTRAINT fk_permission_workspace_application
        FOREIGN KEY (application_id) REFERENCES permission_application (application_id),
    CONSTRAINT fk_permission_workspace_project
        FOREIGN KEY (project_id) REFERENCES permission_project (project_id)
);
COMMENT ON TABLE permission_workspace IS
    '工作空间主数据表。workspace 用于项目内研发/测试/生产/系统执行空间隔离，也用于 Agent 工作区和工具预算策略。';
COMMENT ON COLUMN permission_workspace.workspace_id IS
    '工作空间数字 ID，服务 Java 业务表、数据范围过滤、索引和统计。';
COMMENT ON COLUMN permission_workspace.external_workspace_key IS
    '外部工作空间键，服务 OIDC claim 或前端路由上下文；本地 Keycloak 当前使用 workspace-a、system-sync。';
COMMENT ON COLUMN permission_workspace.environment_type IS
    '环境类型。不同环境可绑定不同数据源、同步任务、审批策略和执行并发额度。';
COMMENT ON COLUMN permission_workspace.risk_level IS
    '风险等级。Agent 工具调用、Skill 可见性、模型缓存复用和危险操作审批可按该字段收紧。';
COMMENT ON COLUMN permission_workspace.default_workspace IS
    '是否为当前项目/应用默认工作空间。创建任务或 Agent 会话时没有显式选择 workspace 时可使用默认项。';
CREATE INDEX IF NOT EXISTS idx_permission_workspace_tenant_status
    ON permission_workspace (tenant_id, status, sort_order);
CREATE INDEX IF NOT EXISTS idx_permission_workspace_project
    ON permission_workspace (tenant_id, project_id, status, sort_order);

INSERT INTO permission_tenant
(tenant_id, tenant_code, tenant_name, tenant_type, plan_code, status, default_application_code,
 owner_actor_id, opened_by, opened_at, description, create_time, update_time)
VALUES
(1, 'DATASMART_PLATFORM', 'DataSmart 平台管理租户', 'PLATFORM', 'ENTERPRISE', 'ACTIVE',
 'DATASMART_PLATFORM', 9001, 0, CURRENT_TIMESTAMP,
 '平台自身管理租户，用于平台管理员、全局配置、跨租户审计和系统运维，不承载客户业务数据。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(10, 'FLASHSYNC', 'FlashSync', 'BUSINESS', 'PROFESSIONAL', 'ACTIVE',
 'FLASHSYNC', 1001, 9001, CURRENT_TIMESTAMP,
 'FlashSync 开租基线租户，面向数据同步、离线传输、实时同步和 Agent 协同配置场景。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (tenant_id) DO UPDATE
SET tenant_code = EXCLUDED.tenant_code,
    tenant_name = EXCLUDED.tenant_name,
    tenant_type = EXCLUDED.tenant_type,
    plan_code = EXCLUDED.plan_code,
    status = EXCLUDED.status,
    default_application_code = EXCLUDED.default_application_code,
    owner_actor_id = EXCLUDED.owner_actor_id,
    description = EXCLUDED.description,
    update_time = CURRENT_TIMESTAMP;

INSERT INTO permission_application
(application_id, tenant_id, application_code, application_name, application_type, status,
 homepage_path, default_project_id, default_workspace_id, owner_actor_id, description, create_time, update_time)
VALUES
(9000, 1, 'DATASMART_PLATFORM', 'DataSmart 平台控制台', 'PLATFORM_ADMIN_APP', 'ACTIVE',
 '/system', 900, 90001, 9001,
 '平台级控制台应用，用于租户开通、权限策略、身份供应、审计和系统配置。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(10010, 10, 'FLASHSYNC', 'FlashSync', 'DATA_SYNC_AGENT_APP', 'ACTIVE',
 '/data-sync', 101, 10001, 1001,
 'FlashSync 数据同步应用，承载数据源接入、离线传输、实时同步、任务调度、质量校验和 Agent 辅助配置。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (application_id) DO UPDATE
SET tenant_id = EXCLUDED.tenant_id,
    application_code = EXCLUDED.application_code,
    application_name = EXCLUDED.application_name,
    application_type = EXCLUDED.application_type,
    status = EXCLUDED.status,
    homepage_path = EXCLUDED.homepage_path,
    default_project_id = EXCLUDED.default_project_id,
    default_workspace_id = EXCLUDED.default_workspace_id,
    owner_actor_id = EXCLUDED.owner_actor_id,
    description = EXCLUDED.description,
    update_time = CURRENT_TIMESTAMP;

INSERT INTO permission_project
(project_id, tenant_id, application_id, project_code, project_name, project_type, status,
 default_workspace_id, owner_actor_id, description, create_time, update_time)
VALUES
(900, 1, 9000, 'PLATFORM_ADMINISTRATION', '平台管理项目', 'PLATFORM_ADMINISTRATION', 'ACTIVE',
 90001, 9001,
 '平台管理员使用的内部项目，用于平台级权限、身份、配置和审计操作归属。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(101, 10, 10010, 'FLASHSYNC_DEFAULT', 'FlashSync 默认项目', 'DATA_GOVERNANCE', 'ACTIVE',
 10001, 1001,
 'FlashSync 开租默认项目。用户首次创建数据源、同步模板、同步任务、质量规则或 Agent 会话时默认落在该项目下。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (project_id) DO UPDATE
SET tenant_id = EXCLUDED.tenant_id,
    application_id = EXCLUDED.application_id,
    project_code = EXCLUDED.project_code,
    project_name = EXCLUDED.project_name,
    project_type = EXCLUDED.project_type,
    status = EXCLUDED.status,
    default_workspace_id = EXCLUDED.default_workspace_id,
    owner_actor_id = EXCLUDED.owner_actor_id,
    description = EXCLUDED.description,
    update_time = CURRENT_TIMESTAMP;

INSERT INTO permission_workspace
(workspace_id, tenant_id, application_id, project_id, workspace_code, external_workspace_key,
 workspace_name, environment_type, risk_level, status, default_workspace, sort_order, description, create_time, update_time)
VALUES
(90001, 1, 9000, 900, 'PLATFORM', 'platform',
 '平台管理空间', 'SYSTEM', 'SYSTEM', 'ACTIVE', TRUE, 10,
 '平台管理员的系统工作空间，不面向普通业务任务创建。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(10001, 10, 10010, 101, 'WORKSPACE_A', 'workspace-a',
 'FlashSync 默认工作空间', 'DEV', 'NORMAL', 'ACTIVE', TRUE, 10,
 'FlashSync 默认业务工作空间，当前本地 Keycloak 样例用户 project-owner/operator/auditor 均指向该空间。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(10002, 10, 10010, 101, 'SYSTEM_SYNC', 'system-sync',
 'FlashSync 系统同步空间', 'SYSTEM', 'SYSTEM', 'ACTIVE', FALSE, 90,
 'FlashSync 内部调度器、同步 worker 和服务账号使用的系统空间，避免机器任务与人工配置空间混在一起。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (workspace_id) DO UPDATE
SET tenant_id = EXCLUDED.tenant_id,
    application_id = EXCLUDED.application_id,
    project_id = EXCLUDED.project_id,
    workspace_code = EXCLUDED.workspace_code,
    external_workspace_key = EXCLUDED.external_workspace_key,
    workspace_name = EXCLUDED.workspace_name,
    environment_type = EXCLUDED.environment_type,
    risk_level = EXCLUDED.risk_level,
    status = EXCLUDED.status,
    default_workspace = EXCLUDED.default_workspace,
    sort_order = EXCLUDED.sort_order,
    description = EXCLUDED.description,
    update_time = CURRENT_TIMESTAMP;

INSERT INTO permission_project_membership
(tenant_id, actor_id, project_id, workspace_id, project_role, grant_source, enabled, create_time, update_time)
VALUES
(1, 9001, 900, 90001, 'OWNER', 'TENANT_BOOTSTRAP', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(10, 1001, 101, 10001, 'OWNER', 'TENANT_BOOTSTRAP', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(10, 1002, 101, 10001, 'MAINTAINER', 'TENANT_BOOTSTRAP', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(10, 1003, 101, 10001, 'VIEWER', 'TENANT_BOOTSTRAP', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(10, 9101, 101, 10002, 'SERVICE', 'TENANT_BOOTSTRAP', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
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
(1, 9001, 'KEYCLOAK_REALM_IMPORT', 'datasmart/platform-admin', 'platform-admin', 'platform-admin@example.local',
 'PLATFORM_ADMINISTRATOR', 'USER', 'platform', 'ACTIVE', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(10, 1001, 'KEYCLOAK_REALM_IMPORT', 'datasmart/project-owner', 'project-owner', 'project-owner@example.local',
 'PROJECT_OWNER', 'USER', 'workspace-a', 'ACTIVE', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(10, 1002, 'KEYCLOAK_REALM_IMPORT', 'datasmart/operator', 'operator', 'operator@example.local',
 'OPERATOR', 'USER', 'workspace-a', 'ACTIVE', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(10, 1003, 'KEYCLOAK_REALM_IMPORT', 'datasmart/auditor', 'auditor', 'auditor@example.local',
 'AUDITOR', 'USER', 'workspace-a', 'ACTIVE', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(10, 9101, 'KEYCLOAK_REALM_IMPORT', 'datasmart/sync-service', 'sync-service', 'sync-service@example.local',
 'SERVICE_ACCOUNT', 'SERVICE_ACCOUNT', 'system-sync', 'ACTIVE', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
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
SELECT 'bootstrap-flashsync-tenant', 10, 9001, 'PLATFORM_ADMINISTRATOR',
       'TENANT_ONBOARDING', 'tenant:10/application:FLASHSYNC', 'OPEN_TENANT', 'SUCCESS',
       'FlashSync 开租基线已初始化：租户、应用、默认项目、默认工作空间、样例账号影子身份和项目成员授权已落库。',
       '{"tenantId":10,"tenantCode":"FLASHSYNC","applicationId":10010,"applicationCode":"FLASHSYNC","defaultProjectId":101,"defaultWorkspaceId":10001,"workspaceKey":"workspace-a","payloadPolicy":"LOW_SENSITIVE_BOOTSTRAP_FACT_NO_PASSWORD_NO_TOKEN"}',
       CURRENT_TIMESTAMP
WHERE NOT EXISTS (
    SELECT 1
    FROM permission_audit_record
    WHERE trace_id = 'bootstrap-flashsync-tenant'
      AND tenant_id = 10
      AND action = 'OPEN_TENANT'
);
