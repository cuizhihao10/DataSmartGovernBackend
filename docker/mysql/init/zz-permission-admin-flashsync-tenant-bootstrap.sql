-- permission-admin fresh-init 后置脚本：FlashSync 开租主数据基线。
--
-- docker/mysql/init 目录只会在 MySQL 数据卷第一次初始化时执行；已经存在的 MySQL 卷请执行
-- docker/mysql/migrations/20260718_tenant_application_workspace_bootstrap.sql。
-- 本文件保持与 PostgreSQL 主迁移 V12 的业务语义一致：开租由平台管理员/安装脚本完成，
-- 普通用户不需要也不应该手工填写 tenantId、applicationId、projectId 或 workspaceId。

USE datasmart_govern;

CREATE TABLE IF NOT EXISTS permission_tenant (
    tenant_id BIGINT PRIMARY KEY COMMENT '租户数字 ID，会进入 X-DataSmart-Tenant-Id、业务表 tenant_id、审计和 Agent 命名空间',
    tenant_code VARCHAR(64) NOT NULL COMMENT '租户稳定编码，适合配置、脚本、日志和人工排障使用',
    tenant_name VARCHAR(128) NOT NULL COMMENT '租户展示名称',
    tenant_type VARCHAR(32) NOT NULL DEFAULT 'BUSINESS' COMMENT '租户类型：PLATFORM/BUSINESS/INTERNAL',
    plan_code VARCHAR(64) NOT NULL DEFAULT 'STANDARD' COMMENT '租户套餐编码，用于能力裁剪、配额和工具预算策略',
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE' COMMENT '租户生命周期状态：ACTIVE/SUSPENDED/CLOSED',
    default_application_code VARCHAR(64) COMMENT '租户默认应用编码',
    owner_actor_id BIGINT COMMENT '租户默认负责人 actorId',
    opened_by BIGINT NOT NULL DEFAULT 0 COMMENT '执行开租动作的平台管理员或安装主体 actorId；0 表示系统 bootstrap',
    opened_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '开租完成时间',
    description VARCHAR(1000) COMMENT '租户说明',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_permission_tenant_code (tenant_code),
    KEY idx_permission_tenant_status (status, update_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='租户主数据表，开租时由平台管理员或安装脚本创建';

CREATE TABLE IF NOT EXISTS permission_application (
    application_id BIGINT PRIMARY KEY COMMENT '应用数字 ID，表示租户开通的产品/应用能力，用于应用级菜单、配额、审计和多应用切换',
    tenant_id BIGINT NOT NULL COMMENT '应用所属租户 ID',
    application_code VARCHAR(64) NOT NULL COMMENT '应用稳定编码，例如 FLASHSYNC',
    application_name VARCHAR(128) NOT NULL COMMENT '应用展示名称',
    application_type VARCHAR(64) NOT NULL DEFAULT 'DATA_SYNC_AGENT_APP' COMMENT '应用类型',
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE' COMMENT '应用状态：ACTIVE/DISABLED/ARCHIVED',
    homepage_path VARCHAR(256) COMMENT '应用默认首页路径',
    default_project_id BIGINT COMMENT '应用默认业务项目 ID，用户进入应用但未选择项目时使用',
    default_workspace_id BIGINT COMMENT '应用默认工作空间 ID',
    owner_actor_id BIGINT COMMENT '应用默认负责人 actorId',
    description VARCHAR(1000) COMMENT '应用说明',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_permission_application_tenant_code (tenant_id, application_code),
    KEY idx_permission_application_tenant_status (tenant_id, status, update_time),
    CONSTRAINT fk_permission_application_tenant_init FOREIGN KEY (tenant_id) REFERENCES permission_tenant (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='租户应用主数据表；Application 表示租户购买、开通或启用的产品/应用能力，不是业务项目';

CREATE TABLE IF NOT EXISTS permission_project (
    project_id BIGINT PRIMARY KEY COMMENT '业务项目数字 ID，会被 permission_project_membership 物化到授权项目集合',
    tenant_id BIGINT NOT NULL COMMENT '项目所属租户 ID',
    application_id BIGINT NOT NULL COMMENT '项目所属应用 ID，表达该业务项目属于哪个产品能力',
    project_code VARCHAR(64) NOT NULL COMMENT '业务项目稳定编码',
    project_name VARCHAR(128) NOT NULL COMMENT '业务项目展示名称',
    project_type VARCHAR(64) NOT NULL DEFAULT 'DATA_GOVERNANCE' COMMENT '业务项目类型，例如默认治理项目、生产项目、沙箱项目或客户交付项目',
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE' COMMENT '项目状态：ACTIVE/DISABLED/ARCHIVED',
    default_workspace_id BIGINT COMMENT '项目默认工作空间 ID',
    owner_actor_id BIGINT COMMENT '业务项目负责人 actorId',
    description VARCHAR(1000) COMMENT '业务项目说明',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_permission_project_tenant_code (tenant_id, project_code),
    KEY idx_permission_project_tenant_status (tenant_id, status, update_time),
    CONSTRAINT fk_permission_project_tenant_init FOREIGN KEY (tenant_id) REFERENCES permission_tenant (tenant_id),
    CONSTRAINT fk_permission_project_application_init FOREIGN KEY (application_id) REFERENCES permission_application (application_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='业务项目/数据域主数据表；Project 位于 Application 之下，用于数据源、同步任务、质量规则和 Agent 会话的数据范围隔离';

CREATE TABLE IF NOT EXISTS permission_workspace (
    workspace_id BIGINT PRIMARY KEY COMMENT '工作空间数字 ID，服务 Java 业务表、数据范围过滤、索引和统计',
    tenant_id BIGINT NOT NULL COMMENT '工作空间所属租户 ID',
    application_id BIGINT NOT NULL COMMENT '工作空间所属应用 ID',
    project_id BIGINT COMMENT '工作空间所属业务项目 ID；系统空间可按项目归属也可后续扩展为空',
    workspace_code VARCHAR(64) NOT NULL COMMENT '工作空间稳定编码',
    external_workspace_key VARCHAR(128) NOT NULL COMMENT '外部工作空间键，用于 OIDC claim 或前端上下文，例如 workspace-a',
    workspace_name VARCHAR(128) NOT NULL COMMENT '工作空间展示名称',
    environment_type VARCHAR(32) NOT NULL DEFAULT 'DEV' COMMENT '环境类型：DEV/TEST/STAGING/PROD/SYSTEM',
    risk_level VARCHAR(32) NOT NULL DEFAULT 'NORMAL' COMMENT '风险等级：LOW/NORMAL/HIGH/SYSTEM',
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE' COMMENT '工作空间状态：ACTIVE/DISABLED/ARCHIVED',
    default_workspace TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否为当前项目或应用默认工作空间',
    sort_order INT NOT NULL DEFAULT 100 COMMENT '展示排序',
    description VARCHAR(1000) COMMENT '工作空间说明',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_permission_workspace_tenant_project_code (tenant_id, project_id, workspace_code),
    UNIQUE KEY uk_permission_workspace_external_key (tenant_id, external_workspace_key),
    KEY idx_permission_workspace_tenant_status (tenant_id, status, sort_order),
    KEY idx_permission_workspace_project (tenant_id, project_id, status, sort_order),
    CONSTRAINT fk_permission_workspace_tenant_init FOREIGN KEY (tenant_id) REFERENCES permission_tenant (tenant_id),
    CONSTRAINT fk_permission_workspace_application_init FOREIGN KEY (application_id) REFERENCES permission_application (application_id),
    CONSTRAINT fk_permission_workspace_project_init FOREIGN KEY (project_id) REFERENCES permission_project (project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='工作空间主数据表；Workspace 位于业务项目之下，用于研发/测试/生产/系统执行空间隔离';

CREATE TABLE IF NOT EXISTS permission_identity_user (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '影子身份主键',
    tenant_id BIGINT NOT NULL DEFAULT 0 COMMENT '租户 ID；租户管理员只能管理本租户，平台管理员可跨租户',
    actor_id BIGINT NOT NULL COMMENT 'DataSmart 内部主体 ID，会进入 gateway Header、审计和业务授权',
    provider_mode VARCHAR(64) NOT NULL COMMENT '身份提供模式，例如 KEYCLOAK_ADMIN_API、KEYCLOAK_REALM_IMPORT、ENTERPRISE_IDP、SCIM',
    provider_user_id VARCHAR(128) NOT NULL COMMENT '外部 IdP 用户标识；本地 realm import 样例使用 datasmart/username 形式',
    username VARCHAR(128) NOT NULL COMMENT '登录用户名；用于检索和审计，不保存密码',
    email VARCHAR(255) COMMENT '邮箱，属于个人信息，API 响应应按场景脱敏',
    actor_role VARCHAR(64) NOT NULL COMMENT 'DataSmart 角色编码',
    actor_type VARCHAR(64) NOT NULL DEFAULT 'USER' COMMENT '主体类型：USER/SERVICE_ACCOUNT/AGENT/SYSTEM_SCHEDULER',
    workspace_id VARCHAR(128) COMMENT '默认 workspace claim 或外部工作空间键',
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE' COMMENT '本地影子状态：ACTIVE/DISABLED',
    disabled_reason VARCHAR(500) COMMENT '禁用原因，用于审计复盘',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_permission_identity_tenant_actor (tenant_id, actor_id),
    UNIQUE KEY uk_permission_identity_provider_user (provider_mode, provider_user_id),
    UNIQUE KEY uk_permission_identity_provider_username (provider_mode, username),
    KEY idx_permission_identity_tenant_status (tenant_id, status, update_time),
    KEY idx_permission_identity_actor (actor_id),
    KEY idx_permission_identity_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='DataSmart 低敏影子身份表，不保存密码、token 或 client secret';

INSERT INTO permission_tenant
(tenant_id, tenant_code, tenant_name, tenant_type, plan_code, status, default_application_code,
 owner_actor_id, opened_by, opened_at, description, create_time, update_time)
VALUES
(1, 'DATASMART_PLATFORM', 'DataSmart 平台管理租户', 'PLATFORM', 'ENTERPRISE', 'ACTIVE',
 'DATASMART_PLATFORM', 9001, 0, NOW(),
 '平台自身管理租户，用于平台管理员、全局配置、跨租户审计和系统运维，不承载客户业务数据。', NOW(), NOW()),
(10, 'FLASHSYNC', 'FlashSync', 'BUSINESS', 'PROFESSIONAL', 'ACTIVE',
 'FLASHSYNC', 1001, 9001, NOW(),
 'FlashSync 开租基线租户，面向数据同步、离线传输、实时同步和 Agent 协同配置场景。', NOW(), NOW())
ON DUPLICATE KEY UPDATE tenant_name = VALUES(tenant_name), plan_code = VALUES(plan_code), status = VALUES(status),
default_application_code = VALUES(default_application_code), owner_actor_id = VALUES(owner_actor_id),
description = VALUES(description), update_time = NOW();

INSERT INTO permission_application
(application_id, tenant_id, application_code, application_name, application_type, status,
 homepage_path, default_project_id, default_workspace_id, owner_actor_id, description, create_time, update_time)
VALUES
(9000, 1, 'DATASMART_PLATFORM', 'DataSmart 平台控制台', 'PLATFORM_ADMIN_APP', 'ACTIVE',
 '/system', 900, 90001, 9001, '平台级控制台应用，用于租户开通、权限策略、身份供应、审计和系统配置。', NOW(), NOW()),
(10010, 10, 'FLASHSYNC', 'FlashSync', 'DATA_SYNC_AGENT_APP', 'ACTIVE',
 '/data-sync', 101, 10001, 1001, 'FlashSync 数据同步应用，承载数据源接入、离线传输、实时同步、任务调度、质量校验和 Agent 辅助配置。', NOW(), NOW())
ON DUPLICATE KEY UPDATE application_name = VALUES(application_name), status = VALUES(status),
homepage_path = VALUES(homepage_path), default_project_id = VALUES(default_project_id),
default_workspace_id = VALUES(default_workspace_id), owner_actor_id = VALUES(owner_actor_id),
description = VALUES(description), update_time = NOW();

INSERT INTO permission_project
(project_id, tenant_id, application_id, project_code, project_name, project_type, status,
 default_workspace_id, owner_actor_id, description, create_time, update_time)
VALUES
(900, 1, 9000, 'PLATFORM_ADMINISTRATION', '平台管理项目', 'PLATFORM_ADMINISTRATION', 'ACTIVE',
 90001, 9001, '平台管理员使用的内部业务项目，用于平台级权限、身份、配置和审计操作归属。', NOW(), NOW()),
(101, 10, 10010, 'FLASHSYNC_DEFAULT', 'FlashSync 默认项目', 'DATA_GOVERNANCE', 'ACTIVE',
 10001, 1001, 'FlashSync 开租默认业务项目/数据域。用户首次创建数据源、同步模板、同步任务、质量规则或 Agent 会话时默认落在该项目下。', NOW(), NOW())
ON DUPLICATE KEY UPDATE project_name = VALUES(project_name), status = VALUES(status),
default_workspace_id = VALUES(default_workspace_id), owner_actor_id = VALUES(owner_actor_id),
description = VALUES(description), update_time = NOW();

INSERT INTO permission_workspace
(workspace_id, tenant_id, application_id, project_id, workspace_code, external_workspace_key,
 workspace_name, environment_type, risk_level, status, default_workspace, sort_order, description, create_time, update_time)
VALUES
(90001, 1, 9000, 900, 'PLATFORM', 'platform', '平台管理空间', 'SYSTEM', 'SYSTEM', 'ACTIVE', 1, 10,
 '平台管理员的系统工作空间，不面向普通业务任务创建。', NOW(), NOW()),
(10001, 10, 10010, 101, 'WORKSPACE_A', 'workspace-a', 'FlashSync 默认工作空间', 'DEV', 'NORMAL', 'ACTIVE', 1, 10,
 'FlashSync 默认业务工作空间，当前本地 Keycloak 样例用户 ordinary-user/project-owner/operator/auditor 均指向该空间。', NOW(), NOW()),
(10002, 10, 10010, 101, 'SYSTEM_SYNC', 'system-sync', 'FlashSync 系统同步空间', 'SYSTEM', 'SYSTEM', 'ACTIVE', 0, 90,
 'FlashSync 内部调度器、同步 worker 和服务账号使用的系统空间，避免机器任务与人工配置空间混在一起。', NOW(), NOW())
ON DUPLICATE KEY UPDATE workspace_name = VALUES(workspace_name), environment_type = VALUES(environment_type),
risk_level = VALUES(risk_level), status = VALUES(status), default_workspace = VALUES(default_workspace),
sort_order = VALUES(sort_order), description = VALUES(description), update_time = NOW();

INSERT INTO permission_project_membership
(tenant_id, actor_id, project_id, workspace_id, project_role, grant_source, enabled, create_time, update_time)
VALUES
(1, 9001, 900, 90001, 'OWNER', 'TENANT_BOOTSTRAP', 1, NOW(), NOW()),
(10, 1001, 101, 10001, 'OWNER', 'TENANT_BOOTSTRAP', 1, NOW(), NOW()),
(10, 1002, 101, 10001, 'MAINTAINER', 'TENANT_BOOTSTRAP', 1, NOW(), NOW()),
(10, 1003, 101, 10001, 'VIEWER', 'TENANT_BOOTSTRAP', 1, NOW(), NOW()),
(10, 1004, 101, 10001, 'MEMBER', 'TENANT_BOOTSTRAP', 1, NOW(), NOW()),
(10, 9101, 101, 10002, 'SERVICE', 'TENANT_BOOTSTRAP', 1, NOW(), NOW())
ON DUPLICATE KEY UPDATE workspace_id = VALUES(workspace_id), project_role = VALUES(project_role),
grant_source = VALUES(grant_source), enabled = VALUES(enabled), update_time = NOW();

INSERT INTO permission_identity_user
(tenant_id, actor_id, provider_mode, provider_user_id, username, email, actor_role, actor_type,
 workspace_id, status, disabled_reason, create_time, update_time)
VALUES
(1, 9001, 'KEYCLOAK_REALM_IMPORT', 'datasmart/platform-admin', 'platform-admin', 'platform-admin@example.local',
 'PLATFORM_ADMINISTRATOR', 'USER', 'platform', 'ACTIVE', NULL, NOW(), NOW()),
(10, 1001, 'KEYCLOAK_REALM_IMPORT', 'datasmart/project-owner', 'project-owner', 'project-owner@example.local',
 'PROJECT_OWNER', 'USER', 'workspace-a', 'ACTIVE', NULL, NOW(), NOW()),
(10, 1002, 'KEYCLOAK_REALM_IMPORT', 'datasmart/operator', 'operator', 'operator@example.local',
 'OPERATOR', 'USER', 'workspace-a', 'ACTIVE', NULL, NOW(), NOW()),
(10, 1003, 'KEYCLOAK_REALM_IMPORT', 'datasmart/auditor', 'auditor', 'auditor@example.local',
 'AUDITOR', 'USER', 'workspace-a', 'ACTIVE', NULL, NOW(), NOW()),
(10, 1004, 'KEYCLOAK_REALM_IMPORT', 'datasmart/ordinary-user', 'ordinary-user', 'ordinary-user@example.local',
 'ORDINARY_USER', 'USER', 'workspace-a', 'ACTIVE', NULL, NOW(), NOW()),
(10, 9101, 'KEYCLOAK_REALM_IMPORT', 'datasmart/sync-service', 'sync-service', 'sync-service@example.local',
 'SERVICE_ACCOUNT', 'SERVICE_ACCOUNT', 'system-sync', 'ACTIVE', NULL, NOW(), NOW())
ON DUPLICATE KEY UPDATE provider_user_id = VALUES(provider_user_id), username = VALUES(username),
email = VALUES(email), actor_role = VALUES(actor_role), actor_type = VALUES(actor_type),
workspace_id = VALUES(workspace_id), status = VALUES(status), disabled_reason = VALUES(disabled_reason),
update_time = NOW();

INSERT INTO permission_audit_record
(trace_id, tenant_id, actor_id, actor_role, resource_type, resource_id, action, result, summary, detail_json, create_time)
SELECT 'bootstrap-flashsync-tenant', 10, 9001, 'PLATFORM_ADMINISTRATOR',
       'TENANT_ONBOARDING', 'tenant:10/application:FLASHSYNC', 'OPEN_TENANT', 'SUCCESS',
       'FlashSync 开租基线已初始化：租户、应用、默认业务项目、默认工作空间、普通用户及管理样例账号影子身份和项目成员授权已落库。',
       '{"tenantId":10,"tenantCode":"FLASHSYNC","applicationId":10010,"applicationCode":"FLASHSYNC","defaultProjectId":101,"defaultWorkspaceId":10001,"workspaceKey":"workspace-a","payloadPolicy":"LOW_SENSITIVE_BOOTSTRAP_FACT_NO_PASSWORD_NO_TOKEN"}',
       NOW()
WHERE NOT EXISTS (
    SELECT 1
    FROM permission_audit_record
    WHERE trace_id = 'bootstrap-flashsync-tenant'
      AND tenant_id = 10
      AND action = 'OPEN_TENANT'
);
