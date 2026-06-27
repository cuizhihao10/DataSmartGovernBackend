-- ---------------------------------------------------------------------------
-- DataSmart Govern permission-admin 初始化脚本
-- ---------------------------------------------------------------------------
-- 本脚本独立于 init.sql，是为了让权限中心的表结构和种子数据有清晰模块边界。
-- Docker MySQL 初始化目录会执行其中的 .sql 文件，因此本文件可以作为 permission-admin 的模块化初始化入口。
--
-- 当前版本覆盖：
-- 1. 角色表：定义平台默认角色和未来租户角色。
-- 2. 菜单表：定义后台管理入口。
-- 3. 角色菜单绑定表：定义不同角色能看到哪些菜单。
-- 4. 路由策略表：定义 gateway 或业务服务可消费的路由访问策略。
-- 5. 数据范围策略表：定义不同角色能访问哪些业务数据范围。
-- 6. 项目成员授权表：定义用户在租户内可访问哪些项目，用于物化 PROJECT 数据范围。
-- 7. 权限审计表：记录权限判定、权限变更和高风险动作。
-- 8. 权限事件 outbox 表：可靠暂存待投递的权限变更事件，支撑 gateway 缓存自动失效。
-- ---------------------------------------------------------------------------

USE datasmart_govern;

CREATE TABLE IF NOT EXISTS permission_role (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '角色主键',
    tenant_id BIGINT NOT NULL DEFAULT 0 COMMENT '租户 ID，0 表示平台全局默认角色',
    role_code VARCHAR(64) NOT NULL COMMENT '角色编码，例如 PLATFORM_ADMINISTRATOR、TENANT_ADMINISTRATOR、OPERATOR',
    role_name VARCHAR(128) NOT NULL COMMENT '角色名称，面向管理后台展示',
    description VARCHAR(1000) COMMENT '角色职责说明，用于解释该角色的权限边界',
    system_role TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否系统内置角色，内置角色不建议普通管理员删除',
    enabled TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否启用，禁用后该角色不应继续参与权限判定',
    created_by BIGINT NOT NULL DEFAULT 0 COMMENT '创建人 ID，0 表示系统初始化',
    updated_by BIGINT COMMENT '最后更新人 ID',
    create_time DATETIME NOT NULL COMMENT '创建时间',
    update_time DATETIME NOT NULL COMMENT '更新时间',
    UNIQUE KEY uk_permission_role_tenant_code (tenant_id, role_code),
    INDEX idx_permission_role_enabled (tenant_id, enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='平台角色表';

CREATE TABLE IF NOT EXISTS permission_menu (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '菜单主键',
    menu_code VARCHAR(128) NOT NULL COMMENT '菜单编码，作为角色绑定菜单时的稳定标识',
    parent_code VARCHAR(128) COMMENT '父菜单编码，用于构建多级菜单树',
    menu_name VARCHAR(128) NOT NULL COMMENT '菜单名称',
    path VARCHAR(256) NOT NULL COMMENT '前端路由路径或逻辑路径',
    icon VARCHAR(128) COMMENT '前端图标标识',
    sort_order INT NOT NULL DEFAULT 100 COMMENT '排序值，数值越小越靠前',
    enabled TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否启用',
    description VARCHAR(1000) COMMENT '菜单说明',
    create_time DATETIME NOT NULL COMMENT '创建时间',
    update_time DATETIME NOT NULL COMMENT '更新时间',
    UNIQUE KEY uk_permission_menu_code (menu_code),
    INDEX idx_permission_menu_parent_sort (parent_code, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='平台菜单资源表';

CREATE TABLE IF NOT EXISTS permission_role_menu_binding (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '角色菜单绑定主键',
    tenant_id BIGINT NOT NULL DEFAULT 0 COMMENT '租户 ID，0 表示平台全局默认绑定',
    role_code VARCHAR(64) NOT NULL COMMENT '角色编码',
    menu_code VARCHAR(128) NOT NULL COMMENT '菜单编码',
    enabled TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否启用当前绑定',
    binding_source VARCHAR(64) NOT NULL DEFAULT 'BOOTSTRAP' COMMENT '绑定来源，例如 BOOTSTRAP、MANUAL、IMPORT',
    note VARCHAR(1000) COMMENT '绑定说明或变更原因',
    create_time DATETIME NOT NULL COMMENT '创建时间',
    update_time DATETIME NOT NULL COMMENT '更新时间',
    UNIQUE KEY uk_permission_role_menu (tenant_id, role_code, menu_code),
    INDEX idx_permission_role_menu_role (tenant_id, role_code, enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='角色菜单绑定表';

CREATE TABLE IF NOT EXISTS permission_route_policy (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '路由策略主键',
    tenant_id BIGINT NOT NULL DEFAULT 0 COMMENT '租户 ID，0 表示平台全局默认策略',
    policy_name VARCHAR(128) NOT NULL COMMENT '策略名称',
    role_code VARCHAR(64) NOT NULL COMMENT '角色编码',
    http_method VARCHAR(16) NOT NULL DEFAULT 'ANY' COMMENT 'HTTP 方法，例如 GET、POST、PUT、DELETE、ANY',
    path_pattern VARCHAR(256) NOT NULL COMMENT '路径模式，例如 /api/task/**',
    resource_type VARCHAR(64) COMMENT '业务资源类型，例如 SYNC_TASK、SYNC_EXECUTION、SYNC_INCIDENT；为空表示路径级通配策略',
    action VARCHAR(64) COMMENT '业务动作，例如 VIEW、CREATE、CALLBACK、RECOVER；为空表示动作级通配策略',
    effect VARCHAR(16) NOT NULL COMMENT '策略效果：ALLOW 或 DENY',
    priority INT NOT NULL DEFAULT 100 COMMENT '优先级，数值越大越优先；同优先级下 DENY 应优先于 ALLOW',
    enabled TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否启用',
    description VARCHAR(1000) COMMENT '策略说明',
    create_time DATETIME NOT NULL COMMENT '创建时间',
    update_time DATETIME NOT NULL COMMENT '更新时间',
    UNIQUE KEY uk_permission_route_policy (tenant_id, role_code, http_method, path_pattern, resource_type, action, effect),
    INDEX idx_permission_route_role_priority (tenant_id, role_code, enabled, priority),
    INDEX idx_permission_route_semantic (tenant_id, role_code, resource_type, action, enabled, priority)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='路由权限策略表';

CREATE TABLE IF NOT EXISTS permission_data_scope_policy (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '数据范围策略主键',
    tenant_id BIGINT NOT NULL DEFAULT 0 COMMENT '租户 ID，0 表示平台全局默认策略',
    role_code VARCHAR(64) NOT NULL COMMENT '角色编码',
    resource_type VARCHAR(64) NOT NULL COMMENT '资源类型，例如 DATASOURCE、SYNC_TASK、QUALITY_RULE',
    scope_level VARCHAR(32) NOT NULL COMMENT '数据范围级别：SELF、PROJECT、TENANT、PLATFORM',
    scope_expression VARCHAR(1000) COMMENT '范围表达式，后续可演进为 JSON DSL',
    approval_required TINYINT(1) NOT NULL DEFAULT 0 COMMENT '当前范围下的操作是否需要审批',
    enabled TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否启用',
    description VARCHAR(1000) COMMENT '策略说明',
    create_time DATETIME NOT NULL COMMENT '创建时间',
    update_time DATETIME NOT NULL COMMENT '更新时间',
    UNIQUE KEY uk_permission_data_scope (tenant_id, role_code, resource_type),
    INDEX idx_permission_data_scope_role_resource (tenant_id, role_code, resource_type, enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='数据范围权限策略表';

CREATE TABLE IF NOT EXISTS permission_project_membership (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '项目成员授权关系主键',
    tenant_id BIGINT NOT NULL DEFAULT 0 COMMENT '租户 ID；项目授权必须带租户边界，避免不同租户 project_id 相同导致串权',
    actor_id BIGINT NOT NULL COMMENT '操作者 ID；可对应用户、服务账号或未来外部身份映射',
    project_id BIGINT NOT NULL COMMENT '项目 ID；permission-admin 会把同一 actor 的项目集合物化为 X-DataSmart-Authorized-Project-Ids',
    workspace_id BIGINT COMMENT '工作空间 ID；为后续空间级数据范围和空间级看板预留',
    project_role VARCHAR(64) NOT NULL DEFAULT 'MEMBER' COMMENT '项目内角色，例如 OWNER、MAINTAINER、VIEWER；后续可影响导出、审批和事故处置',
    grant_source VARCHAR(64) NOT NULL DEFAULT 'MANUAL' COMMENT '授权来源，例如 MANUAL、IMPORT、IDP_GROUP、APPROVAL',
    enabled TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否启用；成员离开项目时优先禁用以保留历史审计线索',
    create_time DATETIME NOT NULL COMMENT '创建时间',
    update_time DATETIME NOT NULL COMMENT '更新时间',
    UNIQUE KEY uk_permission_project_member (tenant_id, actor_id, project_id),
    INDEX idx_permission_project_actor_enabled (tenant_id, actor_id, enabled, project_id),
    INDEX idx_permission_project_project_role (tenant_id, project_id, project_role, enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='项目成员授权关系表';

CREATE TABLE IF NOT EXISTS permission_audit_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '权限审计主键',
    trace_id VARCHAR(128) COMMENT '链路追踪 ID',
    tenant_id BIGINT NOT NULL DEFAULT 0 COMMENT '租户 ID',
    actor_id BIGINT COMMENT '操作者 ID',
    actor_role VARCHAR(64) COMMENT '操作者角色',
    resource_type VARCHAR(64) COMMENT '资源类型',
    resource_id VARCHAR(256) COMMENT '资源 ID 或路径',
    action VARCHAR(64) NOT NULL COMMENT '动作名称',
    result VARCHAR(32) NOT NULL COMMENT '操作结果，例如 SUCCESS、FAILED、DENIED',
    summary VARCHAR(1000) NOT NULL COMMENT '审计摘要',
    detail_json TEXT COMMENT '结构化详情 JSON',
    create_time DATETIME NOT NULL COMMENT '创建时间',
    INDEX idx_permission_audit_tenant_time (tenant_id, create_time),
    INDEX idx_permission_audit_actor_time (actor_id, create_time),
    INDEX idx_permission_audit_resource_time (resource_type, resource_id, create_time),
    INDEX idx_permission_audit_trace (trace_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='权限审计记录表';

CREATE TABLE IF NOT EXISTS permission_event_outbox (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'outbox 主键',
    event_id VARCHAR(128) NOT NULL COMMENT '事件 ID，用于幂等和排障',
    event_type VARCHAR(128) NOT NULL COMMENT '事件类型，例如 ROUTE_POLICY_CREATED',
    topic VARCHAR(255) NOT NULL COMMENT 'Kafka Topic',
    event_key VARCHAR(255) COMMENT 'Kafka Key，当前通常为 tenantId',
    payload_json JSON NOT NULL COMMENT '事件载荷 JSON',
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING' COMMENT '状态：PENDING、SENDING、SENT、FAILED、DEAD、IGNORED；IGNORED 表示管理员确认不再投递',
    attempt_count INT NOT NULL DEFAULT 0 COMMENT '已尝试发送次数',
    max_attempts INT NOT NULL DEFAULT 10 COMMENT '最大尝试次数',
    next_retry_time DATETIME COMMENT '下次允许重试时间',
    last_error VARCHAR(1000) COMMENT '最近一次发送错误',
    sent_time DATETIME COMMENT '发送成功时间',
    tenant_id BIGINT NOT NULL DEFAULT 0 COMMENT '事件所属租户，0 表示平台全局事件',
    resource_type VARCHAR(64) COMMENT '关联资源类型',
    resource_id VARCHAR(256) COMMENT '关联资源 ID',
    trace_id VARCHAR(128) COMMENT '链路追踪 ID',
    create_time DATETIME NOT NULL COMMENT '创建时间',
    update_time DATETIME NOT NULL COMMENT '更新时间',
    UNIQUE KEY uk_permission_event_outbox_event_id (event_id),
    INDEX idx_permission_event_outbox_dispatch (status, next_retry_time, create_time),
    INDEX idx_permission_event_outbox_tenant_time (tenant_id, create_time),
    INDEX idx_permission_event_outbox_resource (resource_type, resource_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='权限事件 outbox 表';

INSERT IGNORE INTO permission_role
(tenant_id, role_code, role_name, description, system_role, enabled, created_by, create_time, update_time)
VALUES
(0, 'ORDINARY_USER', '普通用户', '只能访问自己或被授权范围内的数据、任务和质量结果。', 1, 1, 0, NOW(), NOW()),
(0, 'PROJECT_OWNER', '项目负责人', '管理项目或业务域内的数据接入、任务模板和成员协作。', 1, 1, 0, NOW(), NOW()),
(0, 'OPERATOR', '运营人员', '负责运行监控、告警处理、任务恢复和日常运维操作。', 1, 1, 0, NOW(), NOW()),
(0, 'AUDITOR', '审计员', '以只读方式查看审计、变更历史和合规证据。', 1, 1, 0, NOW(), NOW()),
(0, 'TENANT_ADMINISTRATOR', '租户管理员', '管理本租户内角色、菜单、数据范围和租户配置。', 1, 1, 0, NOW(), NOW()),
(0, 'PLATFORM_ADMINISTRATOR', '平台管理员', '管理全平台策略、跨租户能力、系统配置和高风险治理动作。', 1, 1, 0, NOW(), NOW()),
(0, 'SERVICE_ACCOUNT', '服务账号', '供执行器、调度器、Agent 和内部系统调用使用，必须与人类用户分开审计。', 1, 1, 0, NOW(), NOW());

INSERT IGNORE INTO permission_menu
(menu_code, parent_code, menu_name, path, icon, sort_order, enabled, description, create_time, update_time)
VALUES
('dashboard', NULL, '治理总览', '/dashboard', 'DashboardOutlined', 10, 1, '平台治理总览和关键指标入口。', NOW(), NOW()),
('datasource', NULL, '数据源管理', '/datasources', 'DatabaseOutlined', 20, 1, '数据源登记、连接测试、元数据发现和同步配置入口。', NOW(), NOW()),
('task', NULL, '任务中心', '/tasks', 'ScheduleOutlined', 30, 1, '任务创建、调度、执行记录和运维控制入口。', NOW(), NOW()),
('data-sync', NULL, '数据同步', '/data-sync', 'SwapOutlined', 35, 1, '跨数据源同步模板、同步任务、执行记录、人工介入和事故处理入口。', NOW(), NOW()),
('data-sync-template', 'data-sync', '同步模板', '/data-sync/templates', 'ProfileOutlined', 36, 1, '管理源端、目标端、同步模式、字段映射和写入策略等可复用同步模板。', NOW(), NOW()),
('data-sync-task', 'data-sync', '同步任务', '/data-sync/tasks', 'PartitionOutlined', 37, 1, '创建、运行、查询和审计数据同步任务，支持全量、增量、回放、补数等模式。', NOW(), NOW()),
('data-sync-operation', 'data-sync', '同步运维', '/data-sync/operations', 'ThunderboltOutlined', 38, 1, '处理人工介入、过期租约恢复、执行器异常和同步事故等高风险运营动作。', NOW(), NOW()),
('quality', NULL, '数据质量', '/quality', 'CheckCircleOutlined', 40, 1, '质量规则、质量报告和异常分析入口。', NOW(), NOW()),
('permission', NULL, '权限管理', '/permissions', 'SafetyOutlined', 50, 1, '角色、菜单、路由策略、数据范围和审批策略入口。', NOW(), NOW()),
('audit', NULL, '审计中心', '/audit', 'FileSearchOutlined', 60, 1, '权限变更、任务操作和高风险动作审计入口。', NOW(), NOW()),
('system', NULL, '系统设置', '/system', 'SettingOutlined', 90, 1, '平台级配置、租户配置、模型和运行时策略入口。', NOW(), NOW());

INSERT IGNORE INTO permission_role_menu_binding
(tenant_id, role_code, menu_code, enabled, binding_source, note, create_time, update_time)
VALUES
(0, 'ORDINARY_USER', 'dashboard', 1, 'BOOTSTRAP', '普通用户可查看基础总览。', NOW(), NOW()),
(0, 'ORDINARY_USER', 'datasource', 1, 'BOOTSTRAP', '普通用户可访问授权范围内的数据源。', NOW(), NOW()),
(0, 'ORDINARY_USER', 'data-sync', 1, 'BOOTSTRAP', '普通用户可进入数据同步入口。', NOW(), NOW()),
(0, 'ORDINARY_USER', 'data-sync-template', 1, 'BOOTSTRAP', '普通用户可查看授权范围内同步模板。', NOW(), NOW()),
(0, 'ORDINARY_USER', 'data-sync-task', 1, 'BOOTSTRAP', '普通用户可查看或创建授权范围内同步任务。', NOW(), NOW()),
(0, 'ORDINARY_USER', 'task', 1, 'BOOTSTRAP', '普通用户可查看或创建授权范围内任务。', NOW(), NOW()),
(0, 'PROJECT_OWNER', 'dashboard', 1, 'BOOTSTRAP', '项目负责人可查看项目治理总览。', NOW(), NOW()),
(0, 'PROJECT_OWNER', 'datasource', 1, 'BOOTSTRAP', '项目负责人可管理项目范围数据源。', NOW(), NOW()),
(0, 'PROJECT_OWNER', 'data-sync', 1, 'BOOTSTRAP', '项目负责人可进入项目级数据同步入口。', NOW(), NOW()),
(0, 'PROJECT_OWNER', 'data-sync-template', 1, 'BOOTSTRAP', '项目负责人可管理项目级同步模板。', NOW(), NOW()),
(0, 'PROJECT_OWNER', 'data-sync-task', 1, 'BOOTSTRAP', '项目负责人可管理项目级同步任务。', NOW(), NOW()),
(0, 'PROJECT_OWNER', 'task', 1, 'BOOTSTRAP', '项目负责人可管理项目范围任务。', NOW(), NOW()),
(0, 'PROJECT_OWNER', 'quality', 1, 'BOOTSTRAP', '项目负责人可管理项目质量规则。', NOW(), NOW()),
(0, 'OPERATOR', 'dashboard', 1, 'BOOTSTRAP', '运营人员可查看运行总览。', NOW(), NOW()),
(0, 'OPERATOR', 'data-sync', 1, 'BOOTSTRAP', '运营人员可进入数据同步运维入口。', NOW(), NOW()),
(0, 'OPERATOR', 'data-sync-task', 1, 'BOOTSTRAP', '运营人员可查看同步任务运行状态。', NOW(), NOW()),
(0, 'OPERATOR', 'data-sync-operation', 1, 'BOOTSTRAP', '运营人员可处理同步人工介入、事故和执行器恢复。', NOW(), NOW()),
(0, 'OPERATOR', 'task', 1, 'BOOTSTRAP', '运营人员可处理任务运行异常。', NOW(), NOW()),
(0, 'OPERATOR', 'audit', 1, 'BOOTSTRAP', '运营人员可查看与运维相关审计。', NOW(), NOW()),
(0, 'AUDITOR', 'audit', 1, 'BOOTSTRAP', '审计员可查看审计中心。', NOW(), NOW()),
(0, 'AUDITOR', 'data-sync', 1, 'BOOTSTRAP', '审计员可进入数据同步只读审计入口。', NOW(), NOW()),
(0, 'AUDITOR', 'data-sync-task', 1, 'BOOTSTRAP', '审计员可查看同步任务和执行历史。', NOW(), NOW()),
(0, 'AUDITOR', 'data-sync-operation', 1, 'BOOTSTRAP', '审计员可查看同步事故和人工介入记录，但不执行处置动作。', NOW(), NOW()),
(0, 'TENANT_ADMINISTRATOR', 'permission', 1, 'BOOTSTRAP', '租户管理员可管理本租户权限。', NOW(), NOW()),
(0, 'TENANT_ADMINISTRATOR', 'data-sync', 1, 'BOOTSTRAP', '租户管理员可管理本租户数据同步能力。', NOW(), NOW()),
(0, 'TENANT_ADMINISTRATOR', 'data-sync-template', 1, 'BOOTSTRAP', '租户管理员可管理本租户同步模板。', NOW(), NOW()),
(0, 'TENANT_ADMINISTRATOR', 'data-sync-task', 1, 'BOOTSTRAP', '租户管理员可管理本租户同步任务。', NOW(), NOW()),
(0, 'TENANT_ADMINISTRATOR', 'data-sync-operation', 1, 'BOOTSTRAP', '租户管理员可查看和处理本租户同步运维问题。', NOW(), NOW()),
(0, 'TENANT_ADMINISTRATOR', 'audit', 1, 'BOOTSTRAP', '租户管理员可查看本租户审计。', NOW(), NOW()),
(0, 'PLATFORM_ADMINISTRATOR', 'dashboard', 1, 'BOOTSTRAP', '平台管理员可查看全平台总览。', NOW(), NOW()),
(0, 'PLATFORM_ADMINISTRATOR', 'datasource', 1, 'BOOTSTRAP', '平台管理员可查看全平台数据源。', NOW(), NOW()),
(0, 'PLATFORM_ADMINISTRATOR', 'data-sync', 1, 'BOOTSTRAP', '平台管理员可查看和管理全平台数据同步能力。', NOW(), NOW()),
(0, 'PLATFORM_ADMINISTRATOR', 'data-sync-template', 1, 'BOOTSTRAP', '平台管理员可管理全平台同步模板。', NOW(), NOW()),
(0, 'PLATFORM_ADMINISTRATOR', 'data-sync-task', 1, 'BOOTSTRAP', '平台管理员可管理全平台同步任务。', NOW(), NOW()),
(0, 'PLATFORM_ADMINISTRATOR', 'data-sync-operation', 1, 'BOOTSTRAP', '平台管理员可处理跨租户同步事故、恢复和治理动作。', NOW(), NOW()),
(0, 'PLATFORM_ADMINISTRATOR', 'task', 1, 'BOOTSTRAP', '平台管理员可管理全平台任务。', NOW(), NOW()),
(0, 'PLATFORM_ADMINISTRATOR', 'quality', 1, 'BOOTSTRAP', '平台管理员可管理全平台质量能力。', NOW(), NOW()),
(0, 'PLATFORM_ADMINISTRATOR', 'permission', 1, 'BOOTSTRAP', '平台管理员可管理全平台权限。', NOW(), NOW()),
(0, 'PLATFORM_ADMINISTRATOR', 'audit', 1, 'BOOTSTRAP', '平台管理员可查看全平台审计。', NOW(), NOW()),
(0, 'PLATFORM_ADMINISTRATOR', 'system', 1, 'BOOTSTRAP', '平台管理员可管理系统配置。', NOW(), NOW());

INSERT IGNORE INTO permission_route_policy
(tenant_id, policy_name, role_code, http_method, path_pattern, effect, priority, enabled, description, create_time, update_time)
VALUES
(0, '普通用户查看数据源', 'ORDINARY_USER', 'GET', '/api/datasource/**', 'ALLOW', 100, 1, '普通用户可在数据范围内查看数据源和同步信息。', NOW(), NOW()),
(0, '普通用户查看任务', 'ORDINARY_USER', 'GET', '/api/task/**', 'ALLOW', 100, 1, '普通用户可查看被授权范围内的任务。', NOW(), NOW()),
(0, '普通用户查看同步模板', 'ORDINARY_USER', 'GET', '/api/sync/sync-templates/**', 'ALLOW', 110, 1, '普通用户可查看授权范围内同步模板，但不能直接处理事故和执行器协议。', NOW(), NOW()),
(0, '普通用户创建和查看同步任务', 'ORDINARY_USER', 'ANY', '/api/sync/sync-tasks/**', 'ALLOW', 110, 1, '普通用户可创建、查看和运行自己数据范围内的同步任务；具体数据范围由 SYNC_TASK 策略限制。', NOW(), NOW()),
(0, '普通用户禁止同步人工介入', 'ORDINARY_USER', 'ANY', '/api/sync/sync-tasks/*/attention/**', 'DENY', 760, 1, '人工介入包含确认、重跑、取消、归档和创建事故，属于运营动作，普通用户不能直接执行。', NOW(), NOW()),
(0, '普通用户禁止同步执行器回调', 'ORDINARY_USER', 'ANY', '/api/sync/sync-tasks/*/executions/*/**', 'DENY', 770, 1, '执行器回调只能由服务账号或受控 worker 调用，普通用户不能伪造 start/checkpoint/complete/fail。', NOW(), NOW()),
(0, '普通用户禁止同步事故工作台', 'ORDINARY_USER', 'ANY', '/api/sync/sync-incidents/**', 'DENY', 760, 1, '事故工作台可能暴露运行故障、数据源和业务影响信息，普通用户默认不能访问。', NOW(), NOW()),
(0, '普通用户禁止同步执行租约', 'ORDINARY_USER', 'ANY', '/api/sync/sync-executions/**', 'DENY', 760, 1, '执行租约接口涉及 worker 认领、心跳、延期和恢复，不应开放给普通用户。', NOW(), NOW()),
(0, '普通用户禁止访问任务运营接口', 'ORDINARY_USER', 'ANY', '/api/task/operations/**', 'DENY', 650, 1, '任务运营接口包含队列健康、死信、延期和执行器租约等跨任务运行信息，普通用户即使拥有任务查看权限也不能访问。', NOW(), NOW()),
(0, '普通用户禁止系统配置', 'ORDINARY_USER', 'ANY', '/api/permission/**', 'DENY', 200, 1, '普通用户不能访问权限管理接口。', NOW(), NOW()),
(0, '项目负责人访问数据源', 'PROJECT_OWNER', 'ANY', '/api/datasource/**', 'ALLOW', 120, 1, '项目负责人可管理项目范围内数据源和同步配置。', NOW(), NOW()),
(0, '项目负责人管理同步模板', 'PROJECT_OWNER', 'ANY', '/api/sync/sync-templates/**', 'ALLOW', 125, 1, '项目负责人可管理项目范围同步模板。', NOW(), NOW()),
(0, '项目负责人管理同步任务', 'PROJECT_OWNER', 'ANY', '/api/sync/sync-tasks/**', 'ALLOW', 125, 1, '项目负责人可管理项目范围同步任务。', NOW(), NOW()),
(0, '项目负责人禁止同步人工介入', 'PROJECT_OWNER', 'ANY', '/api/sync/sync-tasks/*/attention/**', 'DENY', 760, 1, '项目负责人不默认具备运营恢复权限，避免业务管理权限扩大为事故处置权限。', NOW(), NOW()),
(0, '项目负责人禁止同步执行器回调', 'PROJECT_OWNER', 'ANY', '/api/sync/sync-tasks/*/executions/*/**', 'DENY', 770, 1, '执行器回调属于服务账号协议，项目负责人不能伪造执行结果。', NOW(), NOW()),
(0, '项目负责人禁止同步事故处置', 'PROJECT_OWNER', 'ANY', '/api/sync/sync-incidents/**', 'DENY', 760, 1, '事故接手、分派、解决和关闭默认交给运营或管理员处理。', NOW(), NOW()),
(0, '项目负责人访问任务', 'PROJECT_OWNER', 'ANY', '/api/task/**', 'ALLOW', 120, 1, '项目负责人可管理项目范围内任务。', NOW(), NOW()),
(0, '项目负责人禁止访问任务运营接口', 'PROJECT_OWNER', 'ANY', '/api/task/operations/**', 'DENY', 650, 1, '项目负责人管理业务范围内任务，但平台队列、执行器租约和死信处置属于运营视角，避免误把项目管理权限扩大为平台运维权限。', NOW(), NOW()),
(0, '项目负责人访问质量', 'PROJECT_OWNER', 'ANY', '/api/quality/**', 'ALLOW', 120, 1, '项目负责人可管理项目范围质量规则。', NOW(), NOW()),
(0, '普通用户调用智能体模型入口', 'ORDINARY_USER', 'POST', '/api/agent/models/chat', 'ALLOW', 118, 1, '普通用户可调用 Agent Runtime 的模型入口；真实数据访问仍由下游工具权限和项目数据范围控制。', NOW(), NOW()),
(0, '普通用户查看智能体工具目录', 'ORDINARY_USER', 'GET', '/api/agent/tools/**', 'ALLOW', 118, 1, '普通用户可查看可绑定 Agent 工具、风险等级和输入参数，但真实执行仍由工具权限和项目范围控制。', NOW(), NOW()),
(0, '普通用户管理智能体会话', 'ORDINARY_USER', 'ANY', '/api/agent/sessions/**', 'ALLOW', 119, 1, '普通用户可在授权项目范围内创建和查看自己的 Agent 会话；真实工具访问仍由下游权限和项目范围控制。', NOW(), NOW()),
(0, '项目负责人调用智能体模型入口', 'PROJECT_OWNER', 'POST', '/api/agent/models/chat', 'ALLOW', 128, 1, '项目负责人可调用 Agent Runtime 进行项目范围内的数据治理问答、规则辅助和任务规划。', NOW(), NOW()),
(0, '项目负责人查看智能体工具目录', 'PROJECT_OWNER', 'GET', '/api/agent/tools/**', 'ALLOW', 128, 1, '项目负责人可查看项目治理可用工具目录，用于创建会话和绑定工具前确认风险。', NOW(), NOW()),
(0, '项目负责人管理智能体会话', 'PROJECT_OWNER', 'ANY', '/api/agent/sessions/**', 'ALLOW', 129, 1, '项目负责人可在负责项目内创建会话、绑定工具和发起 Agent Run；高风险工具仍应进入审批或服务层校验。', NOW(), NOW()),
(0, '运营人员访问任务', 'OPERATOR', 'ANY', '/api/task/**', 'ALLOW', 130, 1, '运营人员可处理任务运行、重试、恢复等运维动作。', NOW(), NOW()),
(0, '运营人员查看同步模板', 'OPERATOR', 'GET', '/api/sync/sync-templates/**', 'ALLOW', 130, 1, '运营人员可查看同步模板配置，用于判断故障是否来自映射、批量或写入策略。', NOW(), NOW()),
(0, '运营人员查看同步任务', 'OPERATOR', 'GET', '/api/sync/sync-tasks/**', 'ALLOW', 130, 1, '运营人员可查看同步任务、执行历史、checkpoint、错误样本和审计记录。', NOW(), NOW()),
(0, '运营人员处理同步人工介入', 'OPERATOR', 'POST', '/api/sync/sync-tasks/*/attention/**', 'ALLOW', 760, 1, '运营人员可确认、解决、重跑、取消、归档人工介入任务，并创建事故记录。', NOW(), NOW()),
(0, '运营人员管理同步事故', 'OPERATOR', 'ANY', '/api/sync/sync-incidents/**', 'ALLOW', 760, 1, '运营人员可查看、接手、分派、解决和关闭同步事故。', NOW(), NOW()),
(0, '运营人员恢复过期同步租约', 'OPERATOR', 'POST', '/api/sync/sync-executions/recover-expired-leases', 'ALLOW', 760, 1, '运营人员可在执行器失联或租约过期后触发恢复扫描。', NOW(), NOW()),
(0, '运营人员查看任务运营接口', 'OPERATOR', 'GET', '/api/task/operations/**', 'ALLOW', 650, 1, '运营人员可查看任务队列、延期、死信和执行器租约等运行健康信息，用于告警排查、容量判断和人工恢复前诊断。', NOW(), NOW()),
(0, '运营人员访问观测', 'OPERATOR', 'ANY', '/api/observability/**', 'ALLOW', 130, 1, '运营人员可查看可观测性和告警信息。', NOW(), NOW()),
(0, '运营人员查看模型路由', 'OPERATOR', 'GET', '/api/agent/models/routes', 'ALLOW', 134, 1, '运营人员可查看 Agent Runtime 模型路由，用于排查模型 Provider、工作负载和超时配置。', NOW(), NOW()),
(0, '运营人员查看智能体工具目录', 'OPERATOR', 'GET', '/api/agent/tools/**', 'ALLOW', 134, 1, '运营人员可查看 Agent 工具目录，用于排查工具是否启用、风险等级和下游服务映射。', NOW(), NOW()),
(0, '运营人员调用智能体模型入口', 'OPERATOR', 'POST', '/api/agent/models/chat', 'ALLOW', 134, 1, '运营人员可调用 Agent Runtime 辅助事故排查、运行分析和治理建议生成。', NOW(), NOW()),
(0, '运营人员管理智能体会话', 'OPERATOR', 'ANY', '/api/agent/sessions/**', 'ALLOW', 134, 1, '运营人员可创建和取消排障类 Agent Run，用于事故分析、运行诊断和治理建议生成。', NOW(), NOW()),
(0, '运营人员查看权限运维面', 'OPERATOR', 'GET', '/api/permission/operations/**', 'ALLOW', 135, 1, '运营人员可查看权限 outbox 和审计记录，用于排查策略事件投递、缓存失效和系统一致性问题。', NOW(), NOW()),
(0, '运营人员重试权限 outbox', 'OPERATOR', 'POST', '/api/permission/operations/outbox/events/**', 'ALLOW', 136, 1, '运营人员可触发 outbox 人工重试；忽略事件仍由 permission-admin 服务层限制为平台管理员。', NOW(), NOW()),
(0, '运营人员查看项目成员授权', 'OPERATOR', 'GET', '/api/permission/project-memberships/**', 'ALLOW', 137, 1, '运营人员可只读查看项目成员授权，用于排查 PROJECT 数据范围为空、项目授权缺失或授权来源异常。', NOW(), NOW()),
(0, '项目负责人管理项目成员', 'PROJECT_OWNER', 'ANY', '/api/permission/project-memberships/**', 'ALLOW', 145, 1, '项目负责人可管理自己拥有 OWNER 授权的项目成员；服务层会限制其不能授予 OWNER 角色或跨项目操作。', NOW(), NOW()),
(0, '审计员查看审计', 'AUDITOR', 'GET', '/api/permission/**', 'ALLOW', 110, 1, '审计员可查询权限矩阵和审计相关只读信息。', NOW(), NOW()),
(0, '审计员查看模型路由', 'AUDITOR', 'GET', '/api/agent/models/routes', 'ALLOW', 111, 1, '审计员可只读查看 Agent Runtime 模型路由，用于复核 AI 调用链路与模型选择。', NOW(), NOW()),
(0, '审计员查看智能体工具目录', 'AUDITOR', 'GET', '/api/agent/tools/**', 'ALLOW', 111, 1, '审计员可只读查看 Agent 工具目录，用于复核工具风险、审批要求和下游服务映射。', NOW(), NOW()),
(0, '审计员查看智能体会话', 'AUDITOR', 'GET', '/api/agent/sessions/**', 'ALLOW', 112, 1, '审计员可只读查看 Agent 会话、工具绑定和运行状态，用于复核 AI 辅助治理过程。', NOW(), NOW()),
(0, '审计员查看同步运行证据', 'AUDITOR', 'GET', '/api/sync/**', 'ALLOW', 115, 1, '审计员可以只读查看同步任务、执行历史、事故和审计证据，但不能执行恢复或关闭动作。', NOW(), NOW()),
(0, '审计员禁止同步写操作', 'AUDITOR', 'POST', '/api/sync/**', 'DENY', 760, 1, '审计员是只读角色，不能触发运行、回调、恢复或事故处置。', NOW(), NOW()),
(0, '租户管理员管理权限', 'TENANT_ADMINISTRATOR', 'ANY', '/api/permission/**', 'ALLOW', 150, 1, '租户管理员可管理本租户权限策略。', NOW(), NOW()),
(0, '租户管理员查看智能体工具目录', 'TENANT_ADMINISTRATOR', 'GET', '/api/agent/tools/**', 'ALLOW', 155, 1, '租户管理员可查看本租户可用 Agent 工具目录，用于治理能力配置和排障。', NOW(), NOW()),
(0, '租户管理员管理智能体会话', 'TENANT_ADMINISTRATOR', 'ANY', '/api/agent/sessions/**', 'ALLOW', 155, 1, '租户管理员可管理本租户 Agent 会话，用于租户级治理问答、权限分析和任务排障。', NOW(), NOW()),
(0, '租户管理员管理同步能力', 'TENANT_ADMINISTRATOR', 'ANY', '/api/sync/**', 'ALLOW', 150, 1, '租户管理员可管理本租户同步模板、任务、事故和运行状态。', NOW(), NOW()),
(0, '租户管理员禁止执行器回调', 'TENANT_ADMINISTRATOR', 'ANY', '/api/sync/sync-tasks/*/executions/*/**', 'DENY', 770, 1, '执行器回调应由服务账号完成，租户管理员不能直接伪造 worker 回调。', NOW(), NOW()),
(0, '租户管理员查看任务运营接口', 'TENANT_ADMINISTRATOR', 'GET', '/api/task/operations/**', 'ALLOW', 650, 1, '租户管理员可查看本租户任务队列和异常运行状态，但后续批量恢复、批量取消等高风险动作仍建议单独配置审批或更高权限。', NOW(), NOW()),
(0, '平台管理员全平台权限', 'PLATFORM_ADMINISTRATOR', 'ANY', '/api/**', 'ALLOW', 1000, 1, '平台管理员拥有全平台管理权限。', NOW(), NOW()),
(0, '服务账号任务与数据源调用', 'SERVICE_ACCOUNT', 'ANY', '/api/task/**', 'ALLOW', 500, 1, '服务账号可调用任务中心执行内部编排。', NOW(), NOW()),
(0, '服务账号接入 AgentPlan', 'SERVICE_ACCOUNT', 'POST', '/api/agent/plan-ingestions', 'ALLOW', 820, 1, '服务账号可将 Python AI Runtime 生成的 AgentPlan 接入 Java agent-runtime 控制面；接口只创建 Run 和工具审计，不直接执行工具。', NOW(), NOW()),
(0, '普通用户禁止接入 AgentPlan', 'ORDINARY_USER', 'POST', '/api/agent/plan-ingestions', 'DENY', 830, 1, 'AgentPlan 接入口属于内部服务协议，普通用户应通过会话或智能网关入口提交治理目标，不能直接伪造 Python 计划。', NOW(), NOW()),
(0, '项目负责人禁止接入 AgentPlan', 'PROJECT_OWNER', 'POST', '/api/agent/plan-ingestions', 'DENY', 830, 1, '项目负责人不能直接调用 AgentPlan 内部接入口，避免绕过 Python Runtime 的模型网关、Skill 和记忆治理链路。', NOW(), NOW()),
(0, '运营人员禁止接入 AgentPlan', 'OPERATOR', 'POST', '/api/agent/plan-ingestions', 'DENY', 830, 1, '运营人员可以使用 Agent 辅助排障，但不能直接伪造 Python AgentPlan 接入 Java 控制面。', NOW(), NOW()),
(0, '租户管理员禁止接入 AgentPlan', 'TENANT_ADMINISTRATOR', 'POST', '/api/agent/plan-ingestions', 'DENY', 830, 1, '租户管理员不应直接调用 AgentPlan 内部接入口；如需平台级排障，应走服务账号或平台管理员 break-glass 流程。', NOW(), NOW()),
(0, '服务账号同步执行器租约', 'SERVICE_ACCOUNT', 'POST', '/api/sync/sync-executions/**', 'ALLOW', 780, 1, '服务账号可认领同步 execution、上报心跳、延期回队列和参与受控恢复。', NOW(), NOW()),
(0, '服务账号同步执行器回调', 'SERVICE_ACCOUNT', 'POST', '/api/sync/sync-tasks/*/executions/*/**', 'ALLOW', 790, 1, '服务账号可提交 start、checkpoint、complete、fail 等执行器回调，所有回调仍由 data-sync 做租约和幂等校验。', NOW(), NOW()),
(0, '服务账号禁止同步事故工作台', 'SERVICE_ACCOUNT', 'ANY', '/api/sync/sync-incidents/**', 'DENY', 800, 1, '服务账号不应默认接手、关闭或分派人工事故，避免机器身份替代人类责任链。', NOW(), NOW()),
(0, '服务账号禁止同步人工介入', 'SERVICE_ACCOUNT', 'ANY', '/api/sync/sync-tasks/*/attention/**', 'DENY', 800, 1, '人工介入是运营人员动作，服务账号不能默认执行确认、取消、归档和创建事故。', NOW(), NOW()),
(0, '服务账号禁止访问任务运营接口', 'SERVICE_ACCOUNT', 'ANY', '/api/task/operations/**', 'DENY', 650, 1, '服务账号用于执行器、调度器和内部编排，不应默认读取运营工作台数据，避免机器身份被滥用为跨队列巡检入口。', NOW(), NOW()),
(0, '服务账号数据源调用', 'SERVICE_ACCOUNT', 'ANY', '/api/datasource/**', 'ALLOW', 500, 1, '服务账号可调用数据源控制面执行内部任务。', NOW(), NOW());

-- ---------------------------------------------------------------------------
-- 权限策略语义补全：resource_type + action
-- ---------------------------------------------------------------------------
-- 上面的 INSERT 仍保留旧列清单，是为了兼容早期已经存在的路由策略导入脚本。
-- 这里再用 UPDATE 将关键策略补上业务资源和业务动作。
-- 设计原则：
-- 1. resource_type 尽量都补齐，让策略矩阵能看出它保护的是同步任务、同步执行、同步事故还是同步运营动作。
-- 2. action 只在语义稳定时补齐；如果某条策略故意代表“该路径下所有动作”，则保留为空，作为动作级通配策略。
-- 3. DENY 策略允许 action 为空，用于一口气拒绝某类资源下的所有危险动作，例如普通用户禁止访问同步事故工作台。

UPDATE permission_route_policy
SET resource_type = 'DATASOURCE'
WHERE resource_type IS NULL AND path_pattern LIKE '/api/datasource/%';

UPDATE permission_route_policy
SET resource_type = 'TASK_OPERATION'
WHERE resource_type IS NULL AND path_pattern LIKE '/api/task/operations/%';

UPDATE permission_route_policy
SET resource_type = 'TASK'
WHERE resource_type IS NULL AND path_pattern LIKE '/api/task/%';

UPDATE permission_route_policy
SET resource_type = 'QUALITY_RULE'
WHERE resource_type IS NULL AND path_pattern LIKE '/api/quality/%';

UPDATE permission_route_policy
SET resource_type = 'AI_RUNTIME'
WHERE resource_type IS NULL AND path_pattern LIKE '/api/agent/%';

UPDATE permission_route_policy
SET resource_type = 'SYSTEM_SETTING'
WHERE resource_type IS NULL AND path_pattern LIKE '/api/permission/%';

UPDATE permission_route_policy
SET resource_type = 'PROJECT_MEMBERSHIP'
WHERE path_pattern LIKE '/api/permission/project-memberships%';

UPDATE permission_route_policy
SET resource_type = 'AUDIT_LOG'
WHERE resource_type IS NULL AND path_pattern LIKE '/api/observability/%';

UPDATE permission_route_policy
SET resource_type = 'SYNC_TEMPLATE'
WHERE resource_type IS NULL AND path_pattern LIKE '/api/sync/sync-templates%';

UPDATE permission_route_policy
SET resource_type = 'SYNC_EXECUTION'
WHERE resource_type IS NULL
  AND (path_pattern LIKE '/api/sync/sync-executions%' OR path_pattern LIKE '/api/sync/sync-tasks/%/executions/%');

UPDATE permission_route_policy
SET resource_type = 'SYNC_INCIDENT'
WHERE resource_type IS NULL AND path_pattern LIKE '/api/sync/sync-incidents%';

UPDATE permission_route_policy
SET resource_type = 'SYNC_OPERATION'
WHERE resource_type IS NULL AND path_pattern LIKE '/api/sync/sync-tasks/%/attention%';

UPDATE permission_route_policy
SET resource_type = 'SYNC_TASK'
WHERE resource_type IS NULL AND path_pattern LIKE '/api/sync/%';

UPDATE permission_route_policy
SET action = CASE
    WHEN action IS NOT NULL THEN action
    WHEN http_method = 'GET' THEN 'VIEW'
    WHEN http_method = 'POST' AND path_pattern LIKE '%/validate' THEN 'VALIDATE'
    WHEN http_method = 'POST' AND path_pattern LIKE '%/run' THEN 'RUN'
    WHEN http_method = 'POST' AND path_pattern LIKE '%/pause' THEN 'PAUSE'
    WHEN http_method = 'POST' AND path_pattern LIKE '%/resume' THEN 'RESUME'
    WHEN http_method = 'POST' AND path_pattern LIKE '%/retry' THEN 'RETRY'
    WHEN http_method = 'POST' AND path_pattern LIKE '%/cancel' THEN 'CANCEL'
    WHEN http_method = 'POST' AND path_pattern LIKE '%/replay' THEN 'REPLAY'
    WHEN http_method = 'POST' AND path_pattern LIKE '%/backfill' THEN 'BACKFILL'
    WHEN http_method = 'POST' AND path_pattern = '/api/agent/plan-ingestions' THEN 'INGEST_PLAN'
    WHEN http_method = 'POST' AND path_pattern LIKE '/api/agent/%' THEN 'EXECUTE'
    WHEN http_method = 'POST' AND path_pattern LIKE '/api/permission/project-memberships%/batch-upsert' THEN 'IMPORT'
    WHEN http_method = 'POST' AND path_pattern LIKE '/api/permission/project-memberships%/enable' THEN 'ENABLE'
    WHEN http_method = 'POST' AND path_pattern LIKE '/api/permission/project-memberships%/disable' THEN 'DISABLE'
    WHEN http_method = 'POST' AND path_pattern LIKE '/api/sync/sync-executions/%/recovery-plan/claim' THEN 'CLAIM_RECOVERY_PLAN'
    WHEN http_method = 'POST' AND path_pattern LIKE '/api/sync/sync-executions/%/recovery-plan/consume' THEN 'CONSUME_RECOVERY_PLAN'
    WHEN http_method = 'POST' AND path_pattern LIKE '/api/sync/sync-tasks/%/executions/%' THEN 'CALLBACK'
    WHEN http_method = 'POST' AND path_pattern = '/api/sync/sync-executions/recover-expired-leases' THEN 'RECOVER'
    WHEN http_method = 'POST' THEN 'CREATE'
    WHEN http_method IN ('PUT', 'PATCH') THEN 'UPDATE'
    WHEN http_method = 'DELETE' THEN 'DELETE'
    ELSE action
END
WHERE action IS NULL
  AND http_method IN ('GET', 'POST', 'PUT', 'PATCH', 'DELETE');

-- ---------------------------------------------------------------------------
-- Agent Runtime 运行时事件中心策略
-- ---------------------------------------------------------------------------
-- 运行时事件会暴露一次 Agent 执行过程中的模型规划、工具调用、审批等待、异常原因和关联 ID。
-- 因此这里不把它简单复用为普通 AI_RUNTIME + VIEW，而是显式拆成 VIEW_EVENTS 与 DIAGNOSE：
-- 1. VIEW_EVENTS：查看 run/session/request 维度的事件投影，适合审计员和运维人员；
-- 2. DIAGNOSE：查看 consumer、topic、投影窗口、拒绝原因和处理耗时，默认只开放给运维人员和平台管理员兜底策略；
-- 3. 普通用户和项目负责人暂不默认开放该接口，因为当前 agent-runtime 查询 API 还没有把 gateway 数据范围强制落到查询条件里。
INSERT IGNORE INTO permission_route_policy
(tenant_id, policy_name, role_code, http_method, path_pattern, resource_type, action, effect, priority, enabled, description, create_time, update_time)
VALUES
(0, '审计员查看 Agent 运行时事件', 'AUDITOR', 'GET', '/api/agent/runtime-events/**', 'AI_RUNTIME', 'VIEW_EVENTS', 'ALLOW', 113, 1, '审计员可查看租户内 Agent 运行事件投影，用于复核模型规划、工具调用、审批等待和异常链路；后续应结合数据范围与脱敏策略进一步约束。', NOW(), NOW()),
(0, '运营人员查看 Agent 运行时事件', 'OPERATOR', 'GET', '/api/agent/runtime-events/**', 'AI_RUNTIME', 'VIEW_EVENTS', 'ALLOW', 136, 1, '运营人员可查看 Agent 运行事件投影，用于排查执行失败、事件缺失、工具调用异常和用户反馈问题。', NOW(), NOW()),
(0, '运营人员诊断 Agent 运行时事件消费', 'OPERATOR', 'GET', '/api/agent/runtime-events/diagnostics', 'AI_RUNTIME', 'DIAGNOSE', 'ALLOW', 137, 1, '运营人员可查看 runtime event consumer、topic/groupId、投影窗口、拒绝原因和处理耗时，辅助定位 Kafka 消费与控制面投影问题。', NOW(), NOW());

-- ---------------------------------------------------------------------------
-- Agent Runtime DAG selected-node 确认记录审计策略
-- ---------------------------------------------------------------------------
-- confirmation 是 human-in-the-loop 与 durable action 的证据：它记录确认人、dry-run 指纹、策略版本、
-- 委托证据和 outbox/command 关联。它不保存工具参数或 prompt，但仍能反映高风险动作是否被确认，
-- 因此不复用普通 VIEW，也不直接复用 VIEW_EVENTS。
INSERT IGNORE INTO permission_route_policy
(tenant_id, policy_name, role_code, http_method, path_pattern, resource_type, action, effect, priority, enabled, description, create_time, update_time)
VALUES
(0, '审计员查看 Agent DAG 确认记录', 'AUDITOR', 'GET',
 '/api/agent/sessions/*/runs/*/tool-executions/dag-confirmations/**',
 'AI_RUNTIME', 'VIEW_TOOL_CONFIRMATIONS', 'ALLOW', 114, 1,
 '审计员可查看租户范围内 selected-node 确认记录，用于复核 human-in-the-loop 确认、策略版本、委托证据和 outbox 关联；服务层仍会按租户与数据范围过滤。',
 NOW(), NOW()),
(0, '审计员查看 Agent DAG 确认记录列表', 'AUDITOR', 'GET',
 '/api/agent/sessions/*/runs/*/tool-executions/dag-confirmations',
 'AI_RUNTIME', 'VIEW_TOOL_CONFIRMATIONS', 'ALLOW', 114, 1,
 '审计员可按 run 查看 selected-node 确认记录列表；单独配置列表路径是为了避免不同路径匹配器对 /** 是否匹配空尾段产生差异。',
 NOW(), NOW()),
(0, '运营人员查看 Agent DAG 确认记录', 'OPERATOR', 'GET',
 '/api/agent/sessions/*/runs/*/tool-executions/dag-confirmations/**',
 'AI_RUNTIME', 'VIEW_TOOL_CONFIRMATIONS', 'ALLOW', 138, 1,
 '运营人员可查看 selected-node 确认记录，用于排查用户已确认但异步命令未推进、outbox 未投递或策略版本不一致等问题。',
 NOW(), NOW()),
(0, '运营人员查看 Agent DAG 确认记录列表', 'OPERATOR', 'GET',
 '/api/agent/sessions/*/runs/*/tool-executions/dag-confirmations',
 'AI_RUNTIME', 'VIEW_TOOL_CONFIRMATIONS', 'ALLOW', 138, 1,
 '运营人员可按 run 查看 selected-node 确认记录列表，用于排障确认历史和 outbox 关联。',
 NOW(), NOW()),
(0, '项目负责人查看项目内 Agent DAG 确认记录', 'PROJECT_OWNER', 'GET',
 '/api/agent/sessions/*/runs/*/tool-executions/dag-confirmations/**',
 'AI_RUNTIME', 'VIEW_TOOL_CONFIRMATIONS', 'ALLOW', 147, 1,
 '项目负责人可查看授权项目范围内 selected-node 确认记录，便于解释项目成员确认了哪些异步治理动作；agent-runtime 会继续按 authorizedProjectIds 收口。',
 NOW(), NOW()),
(0, '项目负责人查看项目内 Agent DAG 确认记录列表', 'PROJECT_OWNER', 'GET',
 '/api/agent/sessions/*/runs/*/tool-executions/dag-confirmations',
 'AI_RUNTIME', 'VIEW_TOOL_CONFIRMATIONS', 'ALLOW', 147, 1,
 '项目负责人可按 run 查看授权项目范围内 selected-node 确认记录列表；服务层仍按 authorizedProjectIds 做二次过滤。',
 NOW(), NOW());

-- ---------------------------------------------------------------------------
-- data-quality 权限闭环：菜单、路由动作、数据范围
-- ---------------------------------------------------------------------------
-- 背景：
-- data-quality 当前已经具备质量规则、治理总览、质量报告、异常聚合和执行器诊断/回调等能力。
-- 如果仍然只用 QUALITY_RULE 一个资源类型兜底，权限中心就无法区分“看低敏治理态势”“修改质量标准”
-- “触发检测执行”“伪造 worker 回调”这些风险完全不同的行为。
--
-- 设计原则：
-- 1. 菜单层让不同角色只看到职责相关入口，避免普通用户误以为自己具备执行器运维能力。
-- 2. 路由层使用 QUALITY_GOVERNANCE、QUALITY_REPORT、QUALITY_ANOMALY、QUALITY_EXECUTION 拆分风险面。
-- 3. 执行器回调 CALLBACK 只开放给 SERVICE_ACCOUNT；所有人类角色都显式 DENY，防止伪造执行结果。
-- 4. 数据范围层继续按 PROJECT/TENANT/PLATFORM 下发，业务服务用 gateway 透传 Header 做二次查询收口。

INSERT IGNORE INTO permission_menu
(menu_code, parent_code, menu_name, path, icon, sort_order, enabled, description, create_time, update_time)
VALUES
('quality-governance', 'quality', '质量治理总览', '/quality/governance', 'DashboardOutlined', 41, 1, '查看项目或租户范围内质量评分、风险等级、报告通过率和异常分布。', NOW(), NOW()),
('quality-rule', 'quality', '质量规则', '/quality/rules', 'ProfileOutlined', 42, 1, '创建、配置、启用、禁用、归档和恢复质量规则。', NOW(), NOW()),
('quality-report', 'quality', '质量报告', '/quality/reports', 'FileTextOutlined', 43, 1, '查看质量检测报告、规则结果快照和低敏质量证据。', NOW(), NOW()),
('quality-anomaly', 'quality', '异常工作台', '/quality/anomalies', 'WarningOutlined', 44, 1, '查看质量异常聚合、异常类型分布和后续清洗任务线索。', NOW(), NOW()),
('quality-executor', 'quality', '质量执行器', '/quality/executor', 'ThunderboltOutlined', 45, 1, '查看执行器诊断、触发质量检测和排查执行积压。', NOW(), NOW());

INSERT IGNORE INTO permission_role_menu_binding
(tenant_id, role_code, menu_code, enabled, binding_source, note, create_time, update_time)
VALUES
(0, 'ORDINARY_USER', 'quality', 1, 'BOOTSTRAP', '普通用户可进入授权项目范围内的数据质量入口。', NOW(), NOW()),
(0, 'ORDINARY_USER', 'quality-governance', 1, 'BOOTSTRAP', '普通用户可查看低敏治理态势。', NOW(), NOW()),
(0, 'ORDINARY_USER', 'quality-report', 1, 'BOOTSTRAP', '普通用户可查看授权项目内质量报告。', NOW(), NOW()),
(0, 'ORDINARY_USER', 'quality-anomaly', 1, 'BOOTSTRAP', '普通用户可查看授权项目内低敏异常聚合。', NOW(), NOW()),
(0, 'PROJECT_OWNER', 'quality-governance', 1, 'BOOTSTRAP', '项目负责人可查看项目质量态势。', NOW(), NOW()),
(0, 'PROJECT_OWNER', 'quality-rule', 1, 'BOOTSTRAP', '项目负责人可管理项目质量规则。', NOW(), NOW()),
(0, 'PROJECT_OWNER', 'quality-report', 1, 'BOOTSTRAP', '项目负责人可查看项目质量报告。', NOW(), NOW()),
(0, 'PROJECT_OWNER', 'quality-anomaly', 1, 'BOOTSTRAP', '项目负责人可查看项目异常工作台。', NOW(), NOW()),
(0, 'PROJECT_OWNER', 'quality-executor', 1, 'BOOTSTRAP', '项目负责人可触发项目范围内手动检测，但不能伪造 worker 回调。', NOW(), NOW()),
(0, 'OPERATOR', 'quality', 1, 'BOOTSTRAP', '运营人员可进入质量运行与排障入口。', NOW(), NOW()),
(0, 'OPERATOR', 'quality-governance', 1, 'BOOTSTRAP', '运营人员可查看租户质量态势。', NOW(), NOW()),
(0, 'OPERATOR', 'quality-report', 1, 'BOOTSTRAP', '运营人员可查看租户质量报告。', NOW(), NOW()),
(0, 'OPERATOR', 'quality-anomaly', 1, 'BOOTSTRAP', '运营人员可查看异常工作台。', NOW(), NOW()),
(0, 'OPERATOR', 'quality-executor', 1, 'BOOTSTRAP', '运营人员可诊断执行器并触发受控检测。', NOW(), NOW()),
(0, 'AUDITOR', 'quality', 1, 'BOOTSTRAP', '审计员可进入质量只读审计入口。', NOW(), NOW()),
(0, 'AUDITOR', 'quality-governance', 1, 'BOOTSTRAP', '审计员可查看质量治理态势。', NOW(), NOW()),
(0, 'AUDITOR', 'quality-report', 1, 'BOOTSTRAP', '审计员可查看质量报告证据。', NOW(), NOW()),
(0, 'AUDITOR', 'quality-anomaly', 1, 'BOOTSTRAP', '审计员可查看低敏异常聚合。', NOW(), NOW()),
(0, 'TENANT_ADMINISTRATOR', 'quality', 1, 'BOOTSTRAP', '租户管理员可进入本租户质量治理入口。', NOW(), NOW()),
(0, 'TENANT_ADMINISTRATOR', 'quality-governance', 1, 'BOOTSTRAP', '租户管理员可查看租户质量态势。', NOW(), NOW()),
(0, 'TENANT_ADMINISTRATOR', 'quality-rule', 1, 'BOOTSTRAP', '租户管理员可管理本租户质量规则。', NOW(), NOW()),
(0, 'TENANT_ADMINISTRATOR', 'quality-report', 1, 'BOOTSTRAP', '租户管理员可查看本租户质量报告。', NOW(), NOW()),
(0, 'TENANT_ADMINISTRATOR', 'quality-anomaly', 1, 'BOOTSTRAP', '租户管理员可查看本租户异常工作台。', NOW(), NOW()),
(0, 'TENANT_ADMINISTRATOR', 'quality-executor', 1, 'BOOTSTRAP', '租户管理员可诊断和触发本租户质量执行。', NOW(), NOW()),
(0, 'PLATFORM_ADMINISTRATOR', 'quality-governance', 1, 'BOOTSTRAP', '平台管理员可查看全平台质量态势。', NOW(), NOW()),
(0, 'PLATFORM_ADMINISTRATOR', 'quality-rule', 1, 'BOOTSTRAP', '平台管理员可管理全平台质量规则。', NOW(), NOW()),
(0, 'PLATFORM_ADMINISTRATOR', 'quality-report', 1, 'BOOTSTRAP', '平台管理员可查看全平台质量报告。', NOW(), NOW()),
(0, 'PLATFORM_ADMINISTRATOR', 'quality-anomaly', 1, 'BOOTSTRAP', '平台管理员可查看全平台异常工作台。', NOW(), NOW()),
(0, 'PLATFORM_ADMINISTRATOR', 'quality-executor', 1, 'BOOTSTRAP', '平台管理员可诊断全平台质量执行。', NOW(), NOW());

INSERT IGNORE INTO permission_route_policy
(tenant_id, policy_name, role_code, http_method, path_pattern, resource_type, action, effect, priority, enabled, description, create_time, update_time)
VALUES
(0, '普通用户查看质量治理总览', 'ORDINARY_USER', 'GET', '/api/quality/quality-rules/governance/overview', 'QUALITY_GOVERNANCE', 'VIEW', 'ALLOW', 122, 1, '普通用户可在项目授权范围内查看低敏质量治理总览。', NOW(), NOW()),
(0, '普通用户查看质量报告', 'ORDINARY_USER', 'GET', '/api/quality/quality-rules/reports/**', 'QUALITY_REPORT', 'VIEW', 'ALLOW', 122, 1, '普通用户可查看授权项目内质量报告摘要。', NOW(), NOW()),
(0, '普通用户查看质量异常', 'ORDINARY_USER', 'GET', '/api/quality/quality-rules/anomalies/**', 'QUALITY_ANOMALY', 'VIEW', 'ALLOW', 122, 1, '普通用户可查看授权项目内低敏异常聚合，不包含样本值或敏感明细。', NOW(), NOW()),
(0, '普通用户查看质量报告异常', 'ORDINARY_USER', 'GET', '/api/quality/quality-rules/reports/*/anomalies', 'QUALITY_ANOMALY', 'VIEW', 'ALLOW', 123, 1, '普通用户可查看授权报告下的低敏异常聚合。', NOW(), NOW()),
(0, '普通用户只读查看质量规则', 'ORDINARY_USER', 'GET', '/api/quality/quality-rules/**', 'QUALITY_RULE', 'VIEW', 'ALLOW', 122, 1, '普通用户可查看授权项目内质量规则定义摘要。', NOW(), NOW()),
(0, '项目负责人查看质量治理总览', 'PROJECT_OWNER', 'GET', '/api/quality/quality-rules/governance/overview', 'QUALITY_GOVERNANCE', 'VIEW', 'ALLOW', 145, 1, '项目负责人可查看项目质量治理总览。', NOW(), NOW()),
(0, '项目负责人查看质量报告', 'PROJECT_OWNER', 'GET', '/api/quality/quality-rules/reports/**', 'QUALITY_REPORT', 'VIEW', 'ALLOW', 145, 1, '项目负责人可查看项目质量报告。', NOW(), NOW()),
(0, '项目负责人查看质量异常', 'PROJECT_OWNER', 'GET', '/api/quality/quality-rules/anomalies/**', 'QUALITY_ANOMALY', 'VIEW', 'ALLOW', 145, 1, '项目负责人可查看项目异常工作台。', NOW(), NOW()),
(0, '项目负责人查看报告异常', 'PROJECT_OWNER', 'GET', '/api/quality/quality-rules/reports/*/anomalies', 'QUALITY_ANOMALY', 'VIEW', 'ALLOW', 146, 1, '项目负责人可查看单份报告下的异常聚合。', NOW(), NOW()),
(0, '项目负责人查看质量执行历史', 'PROJECT_OWNER', 'GET', '/api/quality/quality-rules/*/executions', 'QUALITY_EXECUTION', 'VIEW', 'ALLOW', 146, 1, '项目负责人可查看授权项目内质量执行历史。', NOW(), NOW()),
(0, '项目负责人触发质量检测', 'PROJECT_OWNER', 'POST', '/api/quality/quality-rules/*/run-check', 'QUALITY_EXECUTION', 'RUN', 'ALLOW', 146, 1, '项目负责人可在授权项目内触发单条规则检测。', NOW(), NOW()),
(0, '项目负责人配置质量调度', 'PROJECT_OWNER', 'POST', '/api/quality/quality-rules/*/schedule-task', 'QUALITY_EXECUTION', 'CONFIGURE', 'ALLOW', 146, 1, '项目负责人可配置项目内规则调度任务。', NOW(), NOW()),
(0, '运营人员查看质量治理总览', 'OPERATOR', 'GET', '/api/quality/quality-rules/governance/overview', 'QUALITY_GOVERNANCE', 'VIEW', 'ALLOW', 150, 1, '运营人员可查看租户质量治理态势，用于排查质量风险趋势。', NOW(), NOW()),
(0, '运营人员查看质量报告', 'OPERATOR', 'GET', '/api/quality/quality-rules/reports/**', 'QUALITY_REPORT', 'VIEW', 'ALLOW', 150, 1, '运营人员可查看租户质量报告。', NOW(), NOW()),
(0, '运营人员查看质量异常', 'OPERATOR', 'GET', '/api/quality/quality-rules/anomalies/**', 'QUALITY_ANOMALY', 'VIEW', 'ALLOW', 150, 1, '运营人员可查看租户异常工作台。', NOW(), NOW()),
(0, '运营人员查看报告异常', 'OPERATOR', 'GET', '/api/quality/quality-rules/reports/*/anomalies', 'QUALITY_ANOMALY', 'VIEW', 'ALLOW', 151, 1, '运营人员可查看报告异常聚合，用于定位质量事件。', NOW(), NOW()),
(0, '运营人员查看质量执行历史', 'OPERATOR', 'GET', '/api/quality/quality-rules/*/executions', 'QUALITY_EXECUTION', 'VIEW', 'ALLOW', 151, 1, '运营人员可查看质量执行历史和低敏执行状态。', NOW(), NOW()),
(0, '运营人员诊断质量执行器', 'OPERATOR', 'GET', '/api/quality/quality-rules/executor/diagnostics', 'QUALITY_EXECUTION', 'DIAGNOSE', 'ALLOW', 152, 1, '运营人员可查看质量执行器健康、积压和故障诊断。', NOW(), NOW()),
(0, '运营人员触发质量检测', 'OPERATOR', 'POST', '/api/quality/quality-rules/*/run-check', 'QUALITY_EXECUTION', 'RUN', 'ALLOW', 152, 1, '运营人员可在租户范围内触发受控质量检测。', NOW(), NOW()),
(0, '运营人员触发质量批量调度', 'OPERATOR', 'POST', '/api/quality/quality-rules/executor/coordinator/**', 'QUALITY_EXECUTION', 'RUN', 'ALLOW', 152, 1, '运营人员可触发受控质量执行协调器，用于排障或补跑。', NOW(), NOW()),
(0, '审计员查看质量治理总览', 'AUDITOR', 'GET', '/api/quality/quality-rules/governance/overview', 'QUALITY_GOVERNANCE', 'VIEW', 'ALLOW', 120, 1, '审计员可只读查看质量治理态势。', NOW(), NOW()),
(0, '审计员查看质量报告', 'AUDITOR', 'GET', '/api/quality/quality-rules/reports/**', 'QUALITY_REPORT', 'VIEW', 'ALLOW', 120, 1, '审计员可只读查看质量报告证据。', NOW(), NOW()),
(0, '审计员查看质量异常', 'AUDITOR', 'GET', '/api/quality/quality-rules/anomalies/**', 'QUALITY_ANOMALY', 'VIEW', 'ALLOW', 120, 1, '审计员可只读查看低敏异常聚合。', NOW(), NOW()),
(0, '审计员查看报告异常', 'AUDITOR', 'GET', '/api/quality/quality-rules/reports/*/anomalies', 'QUALITY_ANOMALY', 'VIEW', 'ALLOW', 121, 1, '审计员可查看报告异常聚合证据。', NOW(), NOW()),
(0, '审计员只读查看质量规则', 'AUDITOR', 'GET', '/api/quality/quality-rules/**', 'QUALITY_RULE', 'VIEW', 'ALLOW', 120, 1, '审计员可只读查看质量规则定义和生命周期状态。', NOW(), NOW()),
(0, '审计员禁止质量写操作', 'AUDITOR', 'POST', '/api/quality/**', NULL, NULL, 'DENY', 900, 1, '审计员是只读角色，不能创建、配置或执行质量规则。', NOW(), NOW()),
(0, '租户管理员查看质量治理总览', 'TENANT_ADMINISTRATOR', 'GET', '/api/quality/quality-rules/governance/overview', 'QUALITY_GOVERNANCE', 'VIEW', 'ALLOW', 161, 1, '租户管理员可查看本租户质量治理总览。', NOW(), NOW()),
(0, '租户管理员查看质量报告', 'TENANT_ADMINISTRATOR', 'GET', '/api/quality/quality-rules/reports/**', 'QUALITY_REPORT', 'VIEW', 'ALLOW', 161, 1, '租户管理员可查看本租户质量报告。', NOW(), NOW()),
(0, '租户管理员查看质量异常', 'TENANT_ADMINISTRATOR', 'GET', '/api/quality/quality-rules/anomalies/**', 'QUALITY_ANOMALY', 'VIEW', 'ALLOW', 161, 1, '租户管理员可查看本租户异常工作台。', NOW(), NOW()),
(0, '租户管理员管理质量执行', 'TENANT_ADMINISTRATOR', 'ANY', '/api/quality/quality-rules/executor/**', 'QUALITY_EXECUTION', NULL, 'ALLOW', 161, 1, '租户管理员可诊断和触发本租户质量执行，但不能伪造 worker 回调。', NOW(), NOW()),
(0, '服务账号质量执行器回调', 'SERVICE_ACCOUNT', 'POST', '/api/quality/quality-rules/executor/executions/**', 'QUALITY_EXECUTION', 'CALLBACK', 'ALLOW', 910, 1, 'SERVICE_ACCOUNT 可提交质量执行器 start/succeed/fail 回调，data-quality 服务层继续做执行状态和幂等校验。', NOW(), NOW()),
(0, '服务账号质量协调器调度', 'SERVICE_ACCOUNT', 'POST', '/api/quality/quality-rules/executor/coordinator/**', 'QUALITY_EXECUTION', 'RUN', 'ALLOW', 900, 1, 'SERVICE_ACCOUNT 可代表受控调度器触发质量执行协调器。', NOW(), NOW()),
(0, '普通用户禁止质量执行器回调', 'ORDINARY_USER', 'POST', '/api/quality/quality-rules/executor/executions/**', 'QUALITY_EXECUTION', 'CALLBACK', 'DENY', 1050, 1, '执行器回调是机器协议，普通用户不能伪造执行结果。', NOW(), NOW()),
(0, '项目负责人禁止质量执行器回调', 'PROJECT_OWNER', 'POST', '/api/quality/quality-rules/executor/executions/**', 'QUALITY_EXECUTION', 'CALLBACK', 'DENY', 1050, 1, '项目负责人可管理规则和触发检测，但不能伪造 worker 回调。', NOW(), NOW()),
(0, '运营人员禁止质量执行器回调', 'OPERATOR', 'POST', '/api/quality/quality-rules/executor/executions/**', 'QUALITY_EXECUTION', 'CALLBACK', 'DENY', 1050, 1, '运营人员可诊断和触发执行，但不能伪造 worker 回调。', NOW(), NOW()),
(0, '审计员禁止质量执行器回调', 'AUDITOR', 'POST', '/api/quality/quality-rules/executor/executions/**', 'QUALITY_EXECUTION', 'CALLBACK', 'DENY', 1050, 1, '审计员只能复核证据，不能改变执行状态。', NOW(), NOW()),
(0, '租户管理员禁止质量执行器回调', 'TENANT_ADMINISTRATOR', 'POST', '/api/quality/quality-rules/executor/executions/**', 'QUALITY_EXECUTION', 'CALLBACK', 'DENY', 1050, 1, '租户管理员不能用人类身份伪造 worker 回调。', NOW(), NOW()),
(0, '平台管理员禁止质量执行器回调', 'PLATFORM_ADMINISTRATOR', 'POST', '/api/quality/quality-rules/executor/executions/**', 'QUALITY_EXECUTION', 'CALLBACK', 'DENY', 1050, 1, '平台管理员也应通过受控服务账号或 break-glass 流程处理机器回调，避免人工伪造执行事实。', NOW(), NOW());

-- ---------------------------------------------------------------------------
-- data-quality 异常治理任务权限策略
-- ---------------------------------------------------------------------------
-- POST /api/quality/quality-rules/remediation-tasks 表达的是“把低敏质量异常聚合转成治理/复核任务”。
-- 它不是质量规则 CREATE，也不是质量执行 RUN，因此 gateway 会映射成 QUALITY_ANOMALY + CREATE_REMEDIATION_TASK。
-- 只有对异常治理负责的角色才允许派发任务；普通用户和审计员可以看低敏异常，但不能创建处置任务。
INSERT IGNORE INTO permission_route_policy
(tenant_id, policy_name, role_code, http_method, path_pattern, resource_type, action, effect, priority, enabled, description, create_time, update_time)
VALUES
(0, '普通用户禁止创建质量异常治理任务', 'ORDINARY_USER', 'POST',
 '/api/quality/quality-rules/remediation-tasks',
 'QUALITY_ANOMALY', 'CREATE_REMEDIATION_TASK', 'DENY', 1040, 1,
 '普通用户可以查看授权项目内低敏异常，但不能把异常派发为治理任务，避免越权制造待办或误触发治理流程。',
 NOW(), NOW()),
(0, '审计员禁止创建质量异常治理任务', 'AUDITOR', 'POST',
 '/api/quality/quality-rules/remediation-tasks',
 'QUALITY_ANOMALY', 'CREATE_REMEDIATION_TASK', 'DENY', 1040, 1,
 '审计员职责是只读复核证据，不能创建或推动治理任务，否则会破坏审计独立性。',
 NOW(), NOW()),
(0, '项目负责人创建质量异常治理任务', 'PROJECT_OWNER', 'POST',
 '/api/quality/quality-rules/remediation-tasks',
 'QUALITY_ANOMALY', 'CREATE_REMEDIATION_TASK', 'ALLOW', 146, 1,
 '项目负责人可在授权项目范围内把低敏质量异常聚合转成治理/复核任务，并由 data-quality 服务层继续按 projectId 收口。',
 NOW(), NOW()),
(0, '运营人员创建质量异常治理任务', 'OPERATOR', 'POST',
 '/api/quality/quality-rules/remediation-tasks',
 'QUALITY_ANOMALY', 'CREATE_REMEDIATION_TASK', 'ALLOW', 152, 1,
 '运营人员可在租户范围内根据异常聚合创建治理任务，用于质量事件跟进、派单和补偿流程。',
 NOW(), NOW()),
(0, '租户管理员创建质量异常治理任务', 'TENANT_ADMINISTRATOR', 'POST',
 '/api/quality/quality-rules/remediation-tasks',
 'QUALITY_ANOMALY', 'CREATE_REMEDIATION_TASK', 'ALLOW', 161, 1,
 '租户管理员可在本租户范围内创建质量异常治理任务，但 payload 仍只允许低敏聚合摘要。',
 NOW(), NOW()),
(0, '平台管理员创建质量异常治理任务', 'PLATFORM_ADMINISTRATOR', 'POST',
 '/api/quality/quality-rules/remediation-tasks',
 'QUALITY_ANOMALY', 'CREATE_REMEDIATION_TASK', 'ALLOW', 910, 1,
 '平台管理员可在全平台范围内创建质量异常治理任务，主要用于跨租户排障、演示环境治理或 break-glass 场景。',
 NOW(), NOW());

INSERT IGNORE INTO permission_data_scope_policy
(tenant_id, role_code, resource_type, scope_level, scope_expression, approval_required, enabled, description, create_time, update_time)
VALUES
(0, 'ORDINARY_USER', 'QUALITY_GOVERNANCE', 'PROJECT', 'project_id IN ${actorProjectIds}', 0, 1, '普通用户只能查看被授权项目的质量治理总览。', NOW(), NOW()),
(0, 'ORDINARY_USER', 'QUALITY_REPORT', 'PROJECT', 'project_id IN ${actorProjectIds}', 0, 1, '普通用户只能查看被授权项目的质量报告。', NOW(), NOW()),
(0, 'ORDINARY_USER', 'QUALITY_ANOMALY', 'PROJECT', 'project_id IN ${actorProjectIds}', 0, 1, '普通用户只能查看被授权项目的低敏异常聚合。', NOW(), NOW()),
(0, 'PROJECT_OWNER', 'QUALITY_GOVERNANCE', 'PROJECT', 'project_id IN ${actorProjectIds}', 0, 1, '项目负责人查看负责项目的质量治理总览。', NOW(), NOW()),
(0, 'PROJECT_OWNER', 'QUALITY_REPORT', 'PROJECT', 'project_id IN ${actorProjectIds}', 0, 1, '项目负责人查看负责项目的质量报告。', NOW(), NOW()),
(0, 'PROJECT_OWNER', 'QUALITY_ANOMALY', 'PROJECT', 'project_id IN ${actorProjectIds}', 0, 1, '项目负责人查看负责项目的质量异常。', NOW(), NOW()),
(0, 'PROJECT_OWNER', 'QUALITY_EXECUTION', 'PROJECT', 'project_id IN ${actorProjectIds}', 0, 1, '项目负责人只能触发和查看负责项目内的质量执行。', NOW(), NOW()),
(0, 'OPERATOR', 'QUALITY_GOVERNANCE', 'TENANT', 'tenant_id = ${tenantId}', 0, 1, '运营人员查看当前租户质量治理态势。', NOW(), NOW()),
(0, 'OPERATOR', 'QUALITY_REPORT', 'TENANT', 'tenant_id = ${tenantId}', 0, 1, '运营人员查看当前租户质量报告。', NOW(), NOW()),
(0, 'OPERATOR', 'QUALITY_ANOMALY', 'TENANT', 'tenant_id = ${tenantId}', 0, 1, '运营人员查看当前租户质量异常。', NOW(), NOW()),
(0, 'OPERATOR', 'QUALITY_EXECUTION', 'TENANT', 'tenant_id = ${tenantId}', 0, 1, '运营人员诊断和触发当前租户质量执行。', NOW(), NOW()),
(0, 'AUDITOR', 'QUALITY_RULE', 'TENANT', 'tenant_id = ${tenantId}', 0, 1, '审计员只读查看当前租户质量规则。', NOW(), NOW()),
(0, 'AUDITOR', 'QUALITY_GOVERNANCE', 'TENANT', 'tenant_id = ${tenantId}', 0, 1, '审计员只读查看当前租户质量治理态势。', NOW(), NOW()),
(0, 'AUDITOR', 'QUALITY_REPORT', 'TENANT', 'tenant_id = ${tenantId}', 0, 1, '审计员只读查看当前租户质量报告证据。', NOW(), NOW()),
(0, 'AUDITOR', 'QUALITY_ANOMALY', 'TENANT', 'tenant_id = ${tenantId}', 0, 1, '审计员只读查看当前租户低敏质量异常。', NOW(), NOW()),
(0, 'TENANT_ADMINISTRATOR', 'QUALITY_GOVERNANCE', 'TENANT', 'tenant_id = ${tenantId}', 0, 1, '租户管理员查看本租户质量治理态势。', NOW(), NOW()),
(0, 'TENANT_ADMINISTRATOR', 'QUALITY_REPORT', 'TENANT', 'tenant_id = ${tenantId}', 0, 1, '租户管理员查看本租户质量报告。', NOW(), NOW()),
(0, 'TENANT_ADMINISTRATOR', 'QUALITY_ANOMALY', 'TENANT', 'tenant_id = ${tenantId}', 0, 1, '租户管理员查看本租户质量异常。', NOW(), NOW()),
(0, 'TENANT_ADMINISTRATOR', 'QUALITY_EXECUTION', 'TENANT', 'tenant_id = ${tenantId}', 0, 1, '租户管理员诊断和触发本租户质量执行。', NOW(), NOW()),
(0, 'SERVICE_ACCOUNT', 'QUALITY_EXECUTION', 'TENANT', 'tenant_id = ${tenantId}', 0, 1, '服务账号在当前租户边界内提交质量执行回调和受控调度。', NOW(), NOW()),
(0, 'PLATFORM_ADMINISTRATOR', 'QUALITY_GOVERNANCE', 'PLATFORM', '1 = 1', 0, 1, '平台管理员查看全平台质量治理态势。', NOW(), NOW()),
(0, 'PLATFORM_ADMINISTRATOR', 'QUALITY_REPORT', 'PLATFORM', '1 = 1', 0, 1, '平台管理员查看全平台质量报告。', NOW(), NOW()),
(0, 'PLATFORM_ADMINISTRATOR', 'QUALITY_ANOMALY', 'PLATFORM', '1 = 1', 0, 1, '平台管理员查看全平台质量异常。', NOW(), NOW()),
(0, 'PLATFORM_ADMINISTRATOR', 'QUALITY_EXECUTION', 'PLATFORM', '1 = 1', 0, 1, '平台管理员查看和诊断全平台质量执行。', NOW(), NOW());

-- ---------------------------------------------------------------------------
-- Agent Runtime 工具事件 outbox 查询与人工补偿策略
-- ---------------------------------------------------------------------------
-- outbox 是 Agent 工具状态事件的可靠投递缓冲区，既承载“事件是否已进入投递链路”的审计证据，
-- 也承载 dispatcher 失败重试、阻断记录和人工补偿入口。
-- 因此这里显式拆分：
-- 1. VIEW_OUTBOX_EVENTS：查看 outbox 投递证据，适合审计员和运营人员；
-- 2. DIAGNOSE：查看 outbox 状态分布和积压趋势，适合运营人员；
-- 3. REQUEUE_OUTBOX：修复下游故障后重新入队，属于恢复动作；
-- 4. ANNOTATE_OUTBOX：追加排障备注，便于交接和复盘；
-- 5. IGNORE_OUTBOX：停止继续自动补偿，默认只保留给平台管理员兜底，不开放给普通运营人员。
INSERT IGNORE INTO permission_route_policy
(tenant_id, policy_name, role_code, http_method, path_pattern, resource_type, action, effect, priority, enabled, description, create_time, update_time)
VALUES
(0, '审计员查看 Agent outbox 投递记录', 'AUDITOR', 'GET', '/api/agent/tool-execution-events/outbox/**', 'AI_RUNTIME', 'VIEW_OUTBOX_EVENTS', 'ALLOW', 114, 1, '审计员可只读查看 Agent 工具状态事件 outbox，用于复核事件是否进入可靠投递链路；后续应结合租户、项目与脱敏策略继续收紧。', NOW(), NOW()),
(0, '运营人员查看 Agent outbox 投递记录', 'OPERATOR', 'GET', '/api/agent/tool-execution-events/outbox/**', 'AI_RUNTIME', 'VIEW_OUTBOX_EVENTS', 'ALLOW', 138, 1, '运营人员可查看 Agent 工具状态事件 outbox，用于定位实时事件缺失、投递失败、payload 阻断和 dispatcher 堆积。', NOW(), NOW()),
(0, '运营人员诊断 Agent outbox 状态分布', 'OPERATOR', 'GET', '/api/agent/tool-execution-events/outbox/diagnostics', 'AI_RUNTIME', 'DIAGNOSE', 'ALLOW', 139, 1, '运营人员可查看 pending、publishing、failed、blocked、ignored 等 outbox 状态计数，用于告警判断和容量评估。', NOW(), NOW()),
(0, '运营人员重新入队 Agent outbox 事件', 'OPERATOR', 'POST', '/api/agent/tool-execution-events/outbox/*/requeue', 'AI_RUNTIME', 'REQUEUE_OUTBOX', 'ALLOW', 140, 1, '运营人员可在下游 topic、消费链路或 payload 契约修复后，把 FAILED/BLOCKED 事件重新置为 PENDING 等待 dispatcher 补偿投递。', NOW(), NOW()),
(0, '运营人员备注 Agent outbox 事件', 'OPERATOR', 'POST', '/api/agent/tool-execution-events/outbox/*/notes', 'AI_RUNTIME', 'ANNOTATE_OUTBOX', 'ALLOW', 140, 1, '运营人员可追加 outbox 排障备注，记录客户确认、下游修复计划或暂缓处理原因；备注不改变投递状态。', NOW(), NOW()),
(0, '运营人员禁止忽略 Agent outbox 事件', 'OPERATOR', 'POST', '/api/agent/tool-execution-events/outbox/*/ignore', 'AI_RUNTIME', 'IGNORE_OUTBOX', 'DENY', 900, 1, 'ignore 会把异常事件人工归档并停止自动补偿，默认不授予普通运营人员，需由平台管理员或后续审批流程执行。', NOW(), NOW()),
(0, '审计员禁止变更 Agent outbox 事件', 'AUDITOR', 'POST', '/api/agent/tool-execution-events/outbox/**', 'AI_RUNTIME', NULL, 'DENY', 900, 1, '审计员是只读角色，只能复核 outbox 投递证据，不能执行重新入队、忽略或备注等改变排障事实的动作。', NOW(), NOW()),
(0, '服务账号禁止人工处理 Agent outbox', 'SERVICE_ACCOUNT', 'ANY', '/api/agent/tool-execution-events/outbox/**', 'AI_RUNTIME', NULL, 'DENY', 900, 1, '服务账号用于内部协议调用，不应默认执行 outbox 人工补偿动作，避免机器身份替代人类责任链。', NOW(), NOW());

-- Agent 长期记忆写入候选权限策略。
-- 这里采用“查看与决策分离、人工责任链优先、机器身份默认禁止”的保守模型：
-- 1. VIEW_MEMORY_WRITE_CANDIDATES：查看候选列表和详情，适合审计员复核、运营人员排障、项目负责人处理本项目候选；
-- 2. APPROVE_MEMORY_WRITE：批准候选进入后续长期记忆写入 worker，风险高于普通 APPROVE，默认只给项目负责人；
-- 3. REJECT_MEMORY_WRITE：拒绝候选进入长期记忆，虽然不会写入存储，但会形成治理事实和拒绝原因，也必须可审计；
-- 4. SERVICE_ACCOUNT 默认禁止人工批准/拒绝，避免内部 worker 或模型链路用机器身份替代人类责任链。
INSERT IGNORE INTO permission_route_policy
(tenant_id, policy_name, role_code, http_method, path_pattern, resource_type, action, effect, priority, enabled, description, create_time, update_time)
VALUES
(0, '审计员查看 Agent 记忆写入候选', 'AUDITOR', 'GET', '/api/agent/memory/write-candidates/**', 'AI_RUNTIME', 'VIEW_MEMORY_WRITE_CANDIDATES', 'ALLOW', 115, 1, '审计员可只读查看长期记忆写入候选，用于复核哪些工具结果、治理经验或运行结论准备进入长期记忆；后续应结合脱敏和项目范围继续收紧。', NOW(), NOW()),
(0, '运营人员查看 Agent 记忆写入候选', 'OPERATOR', 'GET', '/api/agent/memory/write-candidates/**', 'AI_RUNTIME', 'VIEW_MEMORY_WRITE_CANDIDATES', 'ALLOW', 141, 1, '运营人员可查看记忆写入候选，用于排查候选生成、审批等待、拒绝原因和后续写入 worker 阻塞问题。', NOW(), NOW()),
(0, '项目负责人查看 Agent 记忆写入候选', 'PROJECT_OWNER', 'GET', '/api/agent/memory/write-candidates/**', 'AI_RUNTIME', 'VIEW_MEMORY_WRITE_CANDIDATES', 'ALLOW', 146, 1, '项目负责人可查看自己负责项目范围内的长期记忆候选，服务层和数据范围策略应继续限制 tenant/project/scope。', NOW(), NOW()),
(0, '项目负责人批准 Agent 记忆写入候选', 'PROJECT_OWNER', 'POST', '/api/agent/memory/write-candidates/*/approve', 'AI_RUNTIME', 'APPROVE_MEMORY_WRITE', 'ALLOW', 146, 1, '项目负责人可批准本项目候选进入长期记忆写入流程；批准后内容未来可能被 Agent 跨会话检索和复用，因此必须保留审批理由和操作者。', NOW(), NOW()),
(0, '项目负责人拒绝 Agent 记忆写入候选', 'PROJECT_OWNER', 'POST', '/api/agent/memory/write-candidates/*/reject', 'AI_RUNTIME', 'REJECT_MEMORY_WRITE', 'ALLOW', 146, 1, '项目负责人可拒绝敏感、越权、过期、质量不足或不适合长期沉淀的记忆候选，拒绝原因会成为后续策略优化和审计依据。', NOW(), NOW()),
(0, '审计员禁止变更 Agent 记忆写入候选', 'AUDITOR', 'POST', '/api/agent/memory/write-candidates/**', 'AI_RUNTIME', NULL, 'DENY', 900, 1, '审计员是只读复核角色，不能批准或拒绝长期记忆候选，避免审计职责与业务决策职责混在一起。', NOW(), NOW()),
(0, '运营人员禁止批准 Agent 记忆写入候选', 'OPERATOR', 'POST', '/api/agent/memory/write-candidates/*/approve', 'AI_RUNTIME', 'APPROVE_MEMORY_WRITE', 'DENY', 900, 1, '运营人员可以排障查看候选，但默认不批准内容进入长期记忆，避免运维权限扩大为业务知识沉淀权限。', NOW(), NOW()),
(0, '服务账号禁止人工决策 Agent 记忆写入候选', 'SERVICE_ACCOUNT', 'ANY', '/api/agent/memory/write-candidates/**', 'AI_RUNTIME', NULL, 'DENY', 900, 1, '服务账号用于内部协议和异步 worker，不应默认执行人工批准或拒绝，避免模型链路绕过人类责任链。', NOW(), NOW());

-- 这些补充策略不是为了替代上面的通配策略，而是把高风险动作显式沉淀为策略事实。
-- 管理员在权限矩阵里可以直接看到“谁能 CALLBACK、谁能 RECOVER、谁能 ACKNOWLEDGE/CLOSE”，
-- 后续接审批流或按钮权限时也可以按 action 精确迁移。
INSERT IGNORE INTO permission_route_policy
(tenant_id, policy_name, role_code, http_method, path_pattern, resource_type, action, effect, priority, enabled, description, create_time, update_time)
VALUES
(0, '运营人员查看项目成员授权明细', 'OPERATOR', 'GET', '/api/permission/project-memberships/**', 'PROJECT_MEMBERSHIP', 'VIEW', 'ALLOW', 137, 1, '运营人员可只读查看项目成员授权，用于排查 PROJECT 数据范围为空、项目授权缺失或授权来源异常。', NOW(), NOW()),
(0, '项目负责人查看项目成员授权', 'PROJECT_OWNER', 'GET', '/api/permission/project-memberships/**', 'PROJECT_MEMBERSHIP', 'VIEW', 'ALLOW', 145, 1, '项目负责人可查看自己拥有 OWNER 授权的项目成员，服务层继续限制项目范围。', NOW(), NOW()),
(0, '项目负责人批量导入项目成员', 'PROJECT_OWNER', 'POST', '/api/permission/project-memberships/batch-upsert', 'PROJECT_MEMBERSHIP', 'IMPORT', 'ALLOW', 145, 1, '项目负责人可批量维护自己负责项目的非 OWNER 成员，服务层继续限制项目范围和角色提升。', NOW(), NOW()),
(0, '项目负责人启用项目成员', 'PROJECT_OWNER', 'POST', '/api/permission/project-memberships/*/enable', 'PROJECT_MEMBERSHIP', 'ENABLE', 'ALLOW', 145, 1, '项目负责人可启用自己负责项目的非 OWNER 成员授权。', NOW(), NOW()),
(0, '项目负责人禁用项目成员', 'PROJECT_OWNER', 'POST', '/api/permission/project-memberships/*/disable', 'PROJECT_MEMBERSHIP', 'DISABLE', 'ALLOW', 145, 1, '项目负责人可禁用自己负责项目的非 OWNER 成员授权，禁用记录会保留审计。', NOW(), NOW()),
(0, '审计员查看项目成员授权', 'AUDITOR', 'GET', '/api/permission/project-memberships/**', 'PROJECT_MEMBERSHIP', 'VIEW', 'ALLOW', 110, 1, '审计员可只读查看项目成员授权，用于授权来源和越权排查。', NOW(), NOW()),
(0, '普通用户暂停自己的同步任务', 'ORDINARY_USER', 'POST', '/api/sync/sync-tasks/*/pause', 'SYNC_TASK', 'PAUSE', 'ALLOW', 125, 1, '普通用户可暂停自己数据范围内的同步任务；data-sync 服务层仍按 SELF 数据范围校验任务归属。', NOW(), NOW()),
(0, '普通用户恢复自己的同步任务', 'ORDINARY_USER', 'POST', '/api/sync/sync-tasks/*/resume', 'SYNC_TASK', 'RESUME', 'ALLOW', 125, 1, '普通用户可恢复自己已暂停的同步任务；恢复会创建新的待执行 execution 并写入审计。', NOW(), NOW()),
(0, '普通用户重试自己的同步任务', 'ORDINARY_USER', 'POST', '/api/sync/sync-tasks/*/retry', 'SYNC_TASK', 'RETRY', 'ALLOW', 125, 1, '普通用户可重试自己失败或部分成功的同步任务；人工介入任务不能绕过 attention 流程。', NOW(), NOW()),
(0, '普通用户取消自己的同步任务', 'ORDINARY_USER', 'POST', '/api/sync/sync-tasks/*/cancel', 'SYNC_TASK', 'CANCEL', 'ALLOW', 125, 1, '普通用户可取消自己数据范围内尚未归档的同步任务；执行器回调仍由服务账号协议控制。', NOW(), NOW()),
(0, '普通用户回放自己的同步任务', 'ORDINARY_USER', 'POST', '/api/sync/sync-tasks/*/replay', 'SYNC_TASK', 'REPLAY', 'ALLOW', 124, 1, '普通用户可在自己数据范围内从历史 execution 或 checkpoint 发起回放；data-sync 服务层仍会校验任务归属、来源执行记录和低敏恢复计划。', NOW(), NOW()),
(0, '普通用户补数自己的同步任务', 'ORDINARY_USER', 'POST', '/api/sync/sync-tasks/*/backfill', 'SYNC_TASK', 'BACKFILL', 'ALLOW', 124, 1, '普通用户可为自己数据范围内任务提交低敏补数窗口；大规模补数、批量补数和跨项目补数后续应接审批或管理员入口。', NOW(), NOW()),
(0, '项目负责人暂停项目同步任务', 'PROJECT_OWNER', 'POST', '/api/sync/sync-tasks/*/pause', 'SYNC_TASK', 'PAUSE', 'ALLOW', 145, 1, '项目负责人可暂停授权项目内同步任务，用于维护窗口、下游限流或业务冻结。', NOW(), NOW()),
(0, '项目负责人恢复项目同步任务', 'PROJECT_OWNER', 'POST', '/api/sync/sync-tasks/*/resume', 'SYNC_TASK', 'RESUME', 'ALLOW', 145, 1, '项目负责人可恢复授权项目内已暂停任务，服务层继续按 authorizedProjectIds 收口。', NOW(), NOW()),
(0, '项目负责人重试项目同步任务', 'PROJECT_OWNER', 'POST', '/api/sync/sync-tasks/*/retry', 'SYNC_TASK', 'RETRY', 'ALLOW', 145, 1, '项目负责人可重试项目范围内失败或部分成功任务，但不能绕过人工介入处置。', NOW(), NOW()),
(0, '项目负责人取消项目同步任务', 'PROJECT_OWNER', 'POST', '/api/sync/sync-tasks/*/cancel', 'SYNC_TASK', 'CANCEL', 'ALLOW', 145, 1, '项目负责人可取消项目范围内尚未归档的同步任务，取消动作会进入 data-sync 审计。', NOW(), NOW()),
(0, '项目负责人回放项目同步任务', 'PROJECT_OWNER', 'POST', '/api/sync/sync-tasks/*/replay', 'SYNC_TASK', 'REPLAY', 'ALLOW', 144, 1, '项目负责人可在授权项目内发起同步回放，用于失败恢复、下游重建或错误写入修复；恢复计划只保存低敏来源坐标。', NOW(), NOW()),
(0, '项目负责人补数项目同步任务', 'PROJECT_OWNER', 'POST', '/api/sync/sync-tasks/*/backfill', 'SYNC_TASK', 'BACKFILL', 'ALLOW', 144, 1, '项目负责人可在授权项目内提交历史补数窗口；后续可按补数规模接入审批、容量预估和执行窗口策略。', NOW(), NOW()),
(0, '运营人员暂停同步任务', 'OPERATOR', 'POST', '/api/sync/sync-tasks/*/pause', 'SYNC_TASK', 'PAUSE', 'ALLOW', 780, 1, '运营人员可在容量、故障或维护窗口场景暂停租户内同步任务，避免继续扩大运行风险。', NOW(), NOW()),
(0, '运营人员恢复同步任务', 'OPERATOR', 'POST', '/api/sync/sync-tasks/*/resume', 'SYNC_TASK', 'RESUME', 'ALLOW', 780, 1, '运营人员可在确认故障恢复或维护结束后恢复同步任务。', NOW(), NOW()),
(0, '运营人员重试同步任务', 'OPERATOR', 'POST', '/api/sync/sync-tasks/*/retry', 'SYNC_TASK', 'RETRY', 'ALLOW', 780, 1, '运营人员可对失败或部分成功同步任务发起普通重试；人工介入任务仍走 attention/rerun。', NOW(), NOW()),
(0, '运营人员取消同步任务', 'OPERATOR', 'POST', '/api/sync/sync-tasks/*/cancel', 'SYNC_TASK', 'CANCEL', 'ALLOW', 780, 1, '运营人员可取消无法继续执行或风险过高的普通同步任务；强制批量取消后续应单独建管理员入口。', NOW(), NOW()),
(0, '运营人员回放同步任务', 'OPERATOR', 'POST', '/api/sync/sync-tasks/*/replay', 'SYNC_TASK', 'REPLAY', 'ALLOW', 779, 1, '运营人员可在事故恢复、客户修复和下游重建场景发起同步回放；所有动作进入 data-sync 审计。', NOW(), NOW()),
(0, '运营人员补数同步任务', 'OPERATOR', 'POST', '/api/sync/sync-tasks/*/backfill', 'SYNC_TASK', 'BACKFILL', 'ALLOW', 779, 1, '运营人员可提交租户内低敏补数窗口，用于历史缺口修复、晚到数据补齐和分区级重刷。', NOW(), NOW()),
(0, '租户管理员暂停同步任务', 'TENANT_ADMINISTRATOR', 'POST', '/api/sync/sync-tasks/*/pause', 'SYNC_TASK', 'PAUSE', 'ALLOW', 760, 1, '租户管理员可暂停本租户同步任务，适合租户级维护窗口和风险止血。', NOW(), NOW()),
(0, '租户管理员恢复同步任务', 'TENANT_ADMINISTRATOR', 'POST', '/api/sync/sync-tasks/*/resume', 'SYNC_TASK', 'RESUME', 'ALLOW', 760, 1, '租户管理员可恢复本租户已暂停同步任务。', NOW(), NOW()),
(0, '租户管理员重试同步任务', 'TENANT_ADMINISTRATOR', 'POST', '/api/sync/sync-tasks/*/retry', 'SYNC_TASK', 'RETRY', 'ALLOW', 760, 1, '租户管理员可重试本租户失败或部分成功同步任务。', NOW(), NOW()),
(0, '租户管理员取消同步任务', 'TENANT_ADMINISTRATOR', 'POST', '/api/sync/sync-tasks/*/cancel', 'SYNC_TASK', 'CANCEL', 'ALLOW', 760, 1, '租户管理员可取消本租户尚未归档同步任务；执行器回调和人工介入仍由更细策略控制。', NOW(), NOW()),
(0, '租户管理员回放同步任务', 'TENANT_ADMINISTRATOR', 'POST', '/api/sync/sync-tasks/*/replay', 'SYNC_TASK', 'REPLAY', 'ALLOW', 759, 1, '租户管理员可在本租户范围内发起同步回放，适合租户级恢复和客户支持场景。', NOW(), NOW()),
(0, '租户管理员补数同步任务', 'TENANT_ADMINISTRATOR', 'POST', '/api/sync/sync-tasks/*/backfill', 'SYNC_TASK', 'BACKFILL', 'ALLOW', 759, 1, '租户管理员可在本租户范围内提交补数窗口；超大规模补数后续应结合容量与审批策略。', NOW(), NOW()),
(0, '服务账号禁止人工回放同步任务', 'SERVICE_ACCOUNT', 'POST', '/api/sync/sync-tasks/*/replay', 'SYNC_TASK', 'REPLAY', 'DENY', 900, 1, '服务账号默认不能调用人工回放入口；worker 应通过受控 execution 租约和恢复计划消费链路运行。', NOW(), NOW()),
(0, '服务账号禁止人工补数同步任务', 'SERVICE_ACCOUNT', 'POST', '/api/sync/sync-tasks/*/backfill', 'SYNC_TASK', 'BACKFILL', 'DENY', 900, 1, '服务账号默认不能调用人工补数入口，避免机器身份绕过确认、审计和后续审批流程。', NOW(), NOW()),
(0, '运营人员确认同步事故', 'OPERATOR', 'POST', '/api/sync/sync-incidents/*/acknowledge', 'SYNC_INCIDENT', 'ACKNOWLEDGE', 'ALLOW', 780, 1, '运营人员可确认同步事故已经接手，形成事故责任链起点。', NOW(), NOW()),
(0, '运营人员分派同步事故', 'OPERATOR', 'POST', '/api/sync/sync-incidents/*/assign', 'SYNC_INCIDENT', 'ASSIGN', 'ALLOW', 780, 1, '运营人员可把同步事故分派给具体负责人，避免事故无人跟进。', NOW(), NOW()),
(0, '运营人员解决同步事故', 'OPERATOR', 'POST', '/api/sync/sync-incidents/*/resolve', 'SYNC_INCIDENT', 'RESOLVE', 'ALLOW', 780, 1, '运营人员可标记同步事故已解决，但关闭仍会留下独立审计动作。', NOW(), NOW()),
(0, '运营人员关闭同步事故', 'OPERATOR', 'POST', '/api/sync/sync-incidents/*/close', 'SYNC_INCIDENT', 'CLOSE', 'ALLOW', 780, 1, '运营人员可关闭已解决事故，后续 P1/P2 可升级为审批动作。', NOW(), NOW()),
(0, '运营人员确认同步人工介入', 'OPERATOR', 'POST', '/api/sync/sync-tasks/*/attention/acknowledge', 'SYNC_OPERATION', 'ACKNOWLEDGE', 'ALLOW', 780, 1, '运营人员可确认人工介入任务已经接手。', NOW(), NOW()),
(0, '运营人员重跑同步人工介入任务', 'OPERATOR', 'POST', '/api/sync/sync-tasks/*/attention/rerun', 'SYNC_OPERATION', 'RETRY', 'ALLOW', 780, 1, '运营人员可在处理人工介入后重新入队执行同步任务。', NOW(), NOW()),
(0, '运营人员取消同步人工介入任务', 'OPERATOR', 'POST', '/api/sync/sync-tasks/*/attention/cancel', 'SYNC_OPERATION', 'CANCEL', 'ALLOW', 780, 1, '运营人员可取消无法继续执行的人工介入任务。', NOW(), NOW()),
(0, '服务账号认领同步执行', 'SERVICE_ACCOUNT', 'POST', '/api/sync/sync-executions/claim', 'SYNC_EXECUTION', 'CLAIM', 'ALLOW', 800, 1, '服务账号可代表受控 worker 认领下一条同步 execution。', NOW(), NOW()),
(0, '服务账号同步执行心跳', 'SERVICE_ACCOUNT', 'POST', '/api/sync/sync-executions/*/heartbeat', 'SYNC_EXECUTION', 'HEARTBEAT', 'ALLOW', 800, 1, '服务账号可代表受控 worker 续租并上报执行进度。', NOW(), NOW()),
(0, '服务账号同步执行延期', 'SERVICE_ACCOUNT', 'POST', '/api/sync/sync-executions/*/defer', 'SYNC_EXECUTION', 'DEFER', 'ALLOW', 800, 1, '服务账号可因容量、配额或维护窗口将 execution 延迟回队列。', NOW(), NOW()),
(0, '服务账号同步执行回调', 'SERVICE_ACCOUNT', 'POST', '/api/sync/sync-tasks/*/executions/*/**', 'SYNC_EXECUTION', 'CALLBACK', 'ALLOW', 810, 1, '服务账号可提交 start、checkpoint、complete、fail 等执行器回调。', NOW(), NOW()),
(0, '普通用户禁止伪造同步执行回调', 'ORDINARY_USER', 'POST', '/api/sync/sync-tasks/*/executions/*/**', 'SYNC_EXECUTION', 'CALLBACK', 'DENY', 820, 1, '普通用户不能伪造 worker 回调推进执行状态。', NOW(), NOW()),
(0, '项目负责人禁止伪造同步执行回调', 'PROJECT_OWNER', 'POST', '/api/sync/sync-tasks/*/executions/*/**', 'SYNC_EXECUTION', 'CALLBACK', 'DENY', 820, 1, '项目负责人不能伪造 worker 回调推进执行状态。', NOW(), NOW()),
(0, '租户管理员禁止伪造同步执行回调', 'TENANT_ADMINISTRATOR', 'POST', '/api/sync/sync-tasks/*/executions/*/**', 'SYNC_EXECUTION', 'CALLBACK', 'DENY', 820, 1, '租户管理员不能伪造 worker 回调推进执行状态。', NOW(), NOW());

-- data-sync：同步恢复计划 worker 消费协议策略。
-- 这些路由会推进 replay/backfill 计划状态，只允许 SERVICE_ACCOUNT 调用。
-- 人类角色即使可以发起 replay/backfill，也不能伪造 worker 读取或消费恢复计划。
INSERT IGNORE INTO permission_route_policy
(tenant_id, policy_name, role_code, http_method, path_pattern, resource_type, action, effect, priority, enabled, description, create_time, update_time)
VALUES
(0, '服务账号认领同步恢复计划', 'SERVICE_ACCOUNT', 'POST', '/api/sync/sync-executions/*/recovery-plan/claim', 'SYNC_EXECUTION', 'CLAIM_RECOVERY_PLAN', 'ALLOW', 805, 1, '服务账号可在持有 execution 租约后读取 replay/backfill 的低敏恢复计划。', NOW(), NOW()),
(0, '服务账号消费同步恢复计划', 'SERVICE_ACCOUNT', 'POST', '/api/sync/sync-executions/*/recovery-plan/consume', 'SYNC_EXECUTION', 'CONSUME_RECOVERY_PLAN', 'ALLOW', 805, 1, '服务账号可确认 worker 已把恢复计划加载为本地执行上下文，后续仍需走 checkpoint/complete/fail 回调。', NOW(), NOW()),
(0, '普通用户禁止伪造同步恢复计划消费', 'ORDINARY_USER', 'POST', '/api/sync/sync-executions/*/recovery-plan/**', 'SYNC_EXECUTION', NULL, 'DENY', 820, 1, '恢复计划 claim/consume 是 worker 机器协议，普通用户不能直接调用。', NOW(), NOW()),
(0, '项目负责人禁止伪造同步恢复计划消费', 'PROJECT_OWNER', 'POST', '/api/sync/sync-executions/*/recovery-plan/**', 'SYNC_EXECUTION', NULL, 'DENY', 820, 1, '项目负责人可发起 replay/backfill，但不能伪造 worker 读取或消费恢复计划。', NOW(), NOW()),
(0, '运营人员禁止伪造同步恢复计划消费', 'OPERATOR', 'POST', '/api/sync/sync-executions/*/recovery-plan/**', 'SYNC_EXECUTION', NULL, 'DENY', 820, 1, '运营人员可管理恢复动作，但 worker 执行协议必须由服务账号完成。', NOW(), NOW()),
(0, '租户管理员禁止伪造同步恢复计划消费', 'TENANT_ADMINISTRATOR', 'POST', '/api/sync/sync-executions/*/recovery-plan/**', 'SYNC_EXECUTION', NULL, 'DENY', 820, 1, '租户管理员即使拥有同步管理权限，也不能用人类身份推进 worker 恢复计划状态。', NOW(), NOW()),
(0, '审计员禁止伪造同步恢复计划消费', 'AUDITOR', 'POST', '/api/sync/sync-executions/*/recovery-plan/**', 'SYNC_EXECUTION', NULL, 'DENY', 820, 1, '审计员只能查看证据，不能调用 worker 机器协议改变恢复计划状态。', NOW(), NOW());

INSERT IGNORE INTO permission_data_scope_policy
(tenant_id, role_code, resource_type, scope_level, scope_expression, approval_required, enabled, description, create_time, update_time)
VALUES
(0, 'ORDINARY_USER', 'DATASOURCE', 'SELF', 'created_by = ${actorId} OR owner_id = ${actorId}', 0, 1, '普通用户只能访问自己创建或拥有的数据源。', NOW(), NOW()),
(0, 'ORDINARY_USER', 'SYNC_TASK', 'SELF', 'owner_id = ${actorId}', 0, 1, '普通用户只能访问自己的同步任务。', NOW(), NOW()),
(0, 'ORDINARY_USER', 'SYNC_TEMPLATE', 'SELF', 'created_by = ${actorId} OR owner_id = ${actorId}', 0, 1, '普通用户只能查看自己创建或被授权的同步模板。', NOW(), NOW()),
(0, 'PROJECT_OWNER', 'DATASOURCE', 'PROJECT', 'project_id IN ${actorProjectIds}', 0, 1, '项目负责人可访问项目范围数据源。', NOW(), NOW()),
(0, 'PROJECT_OWNER', 'SYNC_TASK', 'PROJECT', 'project_id IN ${actorProjectIds}', 0, 1, '项目负责人可访问项目范围同步任务。', NOW(), NOW()),
(0, 'PROJECT_OWNER', 'SYNC_TEMPLATE', 'PROJECT', 'project_id IN ${actorProjectIds}', 0, 1, '项目负责人可访问项目范围同步模板。', NOW(), NOW()),
(0, 'OPERATOR', 'TASK', 'TENANT', 'tenant_id = ${tenantId}', 0, 1, '运营人员可在租户内处理任务运行问题。', NOW(), NOW()),
(0, 'OPERATOR', 'TASK_OPERATION', 'TENANT', 'tenant_id = ${tenantId}', 0, 1, '运营人员查看任务运营工作台时默认限制在当前租户，避免跨租户暴露队列、执行器租约和死信信息。', NOW(), NOW()),
(0, 'OPERATOR', 'SYNC_TASK', 'TENANT', 'tenant_id = ${tenantId}', 0, 1, '运营人员可查看租户内同步任务运行状态。', NOW(), NOW()),
(0, 'OPERATOR', 'SYNC_EXECUTION', 'TENANT', 'tenant_id = ${tenantId}', 0, 1, '运营人员可查看和恢复租户内同步执行记录。', NOW(), NOW()),
(0, 'OPERATOR', 'SYNC_INCIDENT', 'TENANT', 'tenant_id = ${tenantId}', 0, 1, '运营人员可处理租户内同步事故。', NOW(), NOW()),
(0, 'OPERATOR', 'SYNC_OPERATION', 'TENANT', 'tenant_id = ${tenantId}', 0, 1, '运营人员可执行租户内人工介入和租约恢复动作。', NOW(), NOW()),
(0, 'AUDITOR', 'AUDIT_LOG', 'TENANT', 'tenant_id = ${tenantId}', 0, 1, '审计员可查看租户内审计日志。', NOW(), NOW()),
(0, 'AUDITOR', 'SYNC_TASK', 'TENANT', 'tenant_id = ${tenantId}', 0, 1, '审计员可只读查看租户内同步任务和执行证据。', NOW(), NOW()),
(0, 'AUDITOR', 'SYNC_INCIDENT', 'TENANT', 'tenant_id = ${tenantId}', 0, 1, '审计员可只读查看租户内同步事故和处置记录。', NOW(), NOW()),
(0, 'TENANT_ADMINISTRATOR', 'DATASOURCE', 'TENANT', 'tenant_id = ${tenantId}', 0, 1, '租户管理员可访问本租户数据源。', NOW(), NOW()),
(0, 'TENANT_ADMINISTRATOR', 'SYNC_TASK', 'TENANT', 'tenant_id = ${tenantId}', 0, 1, '租户管理员可访问本租户同步任务。', NOW(), NOW()),
(0, 'TENANT_ADMINISTRATOR', 'SYNC_TEMPLATE', 'TENANT', 'tenant_id = ${tenantId}', 0, 1, '租户管理员可访问本租户同步模板。', NOW(), NOW()),
(0, 'TENANT_ADMINISTRATOR', 'SYNC_EXECUTION', 'TENANT', 'tenant_id = ${tenantId}', 0, 1, '租户管理员可查看本租户同步执行状态。', NOW(), NOW()),
(0, 'TENANT_ADMINISTRATOR', 'SYNC_INCIDENT', 'TENANT', 'tenant_id = ${tenantId}', 0, 1, '租户管理员可查看和处理本租户同步事故。', NOW(), NOW()),
(0, 'TENANT_ADMINISTRATOR', 'SYNC_OPERATION', 'TENANT', 'tenant_id = ${tenantId}', 0, 1, '租户管理员可处理本租户同步运营问题；跨租户操作仍由平台管理员负责。', NOW(), NOW()),
(0, 'TENANT_ADMINISTRATOR', 'TASK_OPERATION', 'TENANT', 'tenant_id = ${tenantId}', 0, 1, '租户管理员可查看本租户任务运营状态，用于租户级容量、异常和 SLA 排查。', NOW(), NOW()),
(0, 'PLATFORM_ADMINISTRATOR', 'DATASOURCE', 'PLATFORM', '1 = 1', 1, 1, '平台管理员可跨租户访问数据源，高风险导出仍建议审批。', NOW(), NOW()),
(0, 'PLATFORM_ADMINISTRATOR', 'SYNC_TASK', 'PLATFORM', '1 = 1', 0, 1, '平台管理员可跨租户管理同步任务。', NOW(), NOW()),
(0, 'PLATFORM_ADMINISTRATOR', 'SYNC_TEMPLATE', 'PLATFORM', '1 = 1', 0, 1, '平台管理员可跨租户管理同步模板。', NOW(), NOW()),
(0, 'PLATFORM_ADMINISTRATOR', 'SYNC_EXECUTION', 'PLATFORM', '1 = 1', 0, 1, '平台管理员可跨租户查看和恢复同步执行记录。', NOW(), NOW()),
(0, 'PLATFORM_ADMINISTRATOR', 'SYNC_INCIDENT', 'PLATFORM', '1 = 1', 0, 1, '平台管理员可跨租户处理同步事故。', NOW(), NOW()),
(0, 'PLATFORM_ADMINISTRATOR', 'SYNC_OPERATION', 'PLATFORM', '1 = 1', 0, 1, '平台管理员可执行全平台同步运营动作。', NOW(), NOW()),
(0, 'PLATFORM_ADMINISTRATOR', 'AUDIT_LOG', 'PLATFORM', '1 = 1', 0, 1, '平台管理员可查看全平台审计。', NOW(), NOW()),
(0, 'PLATFORM_ADMINISTRATOR', 'TASK_OPERATION', 'PLATFORM', '1 = 1', 0, 1, '平台管理员可跨租户查看任务运营状态，用于全局容量治理、队列压测、死信恢复和事故复盘。', NOW(), NOW()),
(0, 'SERVICE_ACCOUNT', 'AI_RUNTIME', 'TENANT', 'tenant_id = ${tenantId}', 0, 1, '服务账号接入 AgentPlan、模型 usage 写回和内部智能运行时协议时，默认限制在调用上下文租户内。', NOW(), NOW()),
(0, 'SERVICE_ACCOUNT', 'SYNC_TASK', 'TENANT', 'tenant_id = ${tenantId}', 0, 1, '服务账号默认只在调用上下文租户内执行任务。', NOW(), NOW());

-- 项目授权样例数据。
-- 这些记录用于本地联调和学习理解：当 actorId=1001 且角色为 PROJECT_OWNER 时，
-- permission-admin 会把项目 101、102 物化到授权结果中，gateway 再透传给 data-sync 做 project_id IN 过滤。
-- 真实生产环境应由项目成员管理、组织同步、审批流或导入任务维护该表，而不是长期依赖静态种子。
INSERT IGNORE INTO permission_project_membership
(tenant_id, actor_id, project_id, workspace_id, project_role, grant_source, enabled, create_time, update_time)
VALUES
(0, 1001, 101, 10001, 'OWNER', 'BOOTSTRAP', 1, NOW(), NOW()),
(0, 1001, 102, 10001, 'OWNER', 'BOOTSTRAP', 1, NOW(), NOW()),
(0, 1002, 201, 20001, 'MAINTAINER', 'BOOTSTRAP', 1, NOW(), NOW());

-- Agent 长期记忆写入候选持久化表。
-- 这两张表虽然暂时放在 permission-admin 初始化脚本中创建，但业务语义属于 AI Runtime / Memory Governance：
-- 1. 主表保存“哪些工具结果准备进入长期记忆”的候选快照；
-- 2. 审计表保存批准/拒绝的决策时间线；
-- 3. 后续如果拆出独立 memory-service，可以把这两张表迁移到 memory schema，当前先保证本地 MySQL 初始化可用。
CREATE TABLE IF NOT EXISTS agent_memory_write_candidate (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '自增主键，仅用于数据库内部索引；业务引用统一使用 candidate_id。',
    candidate_id VARCHAR(128) NOT NULL COMMENT '长期记忆写入候选业务 ID，审批、拒绝、审计和异步写入 worker 均围绕该 ID 关联。',
    tenant_id VARCHAR(64) NOT NULL COMMENT '租户 ID，用于多租户隔离；候选查询和写入 worker 必须按租户过滤。',
    project_id VARCHAR(64) NOT NULL COMMENT '项目 ID，用于项目级数据范围隔离；项目负责人审批时也应基于该字段校验。',
    actor_id VARCHAR(64) NOT NULL COMMENT '候选来源操作者 ID，表示最初触发本次 Agent Run 或工具结果沉淀的人。',
    memory_type VARCHAR(32) NOT NULL COMMENT '候选希望写入的记忆类型：semantic、episodic、procedural、resource、short_term。',
    scope VARCHAR(32) NOT NULL COMMENT '候选未来可见范围：session、project、tenant、global；范围越大审批和脱敏要求越高。',
    status VARCHAR(32) NOT NULL COMMENT '候选生命周期状态：draft、pending_approval、approved、rejected、ignored。',
    title VARCHAR(255) NOT NULL COMMENT '面向审批台展示的人类可读标题，禁止放入完整敏感数据。',
    content_summary TEXT NOT NULL COMMENT '候选内容摘要，只保存脱敏摘要和引用说明，不保存完整工具输出。',
    source VARCHAR(64) NOT NULL COMMENT '候选来源，例如 agent-runtime-tool-feedback 或 agent-plan-tool-plan。',
    workspace_key VARCHAR(255) DEFAULT NULL COMMENT 'Agent 工作空间隔离键；同一项目内不同 workspace/session 不应共享长期记忆。',
    memory_namespace VARCHAR(255) DEFAULT NULL COMMENT '长期记忆命名空间，通常为 memory:{workspaceKey}；正式记忆检索必须按该字段过滤。',
    source_tool_name VARCHAR(128) DEFAULT NULL COMMENT '来源工具名称，用于按工具分析哪些结果经常进入长期记忆。',
    source_status VARCHAR(64) DEFAULT NULL COMMENT '来源工具反馈状态，例如 succeeded、failed、skipped，用于排查候选生成原因。',
    source_audit_id VARCHAR(128) DEFAULT NULL COMMENT 'Java agent-runtime 工具执行审计 ID，可追溯到真实工具执行事实。',
    source_run_id VARCHAR(128) DEFAULT NULL COMMENT 'Java agent-runtime run ID，用于和运行事件、WebSocket replay、审计回放关联。',
    output_ref VARCHAR(512) DEFAULT NULL COMMENT '完整工具结果的外部引用，例如 MinIO 路径或审计输出引用；候选表不直接保存完整输出。',
    approval_required TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否要求人工审批；高风险、敏感字段、租户/全局范围通常为 true。',
    retention_days INT NOT NULL DEFAULT 30 COMMENT '建议保留天数，后续遗忘/过期清理任务应基于该字段计算。',
    sensitivity_level VARCHAR(32) NOT NULL DEFAULT 'internal' COMMENT '敏感级别，例如 public、internal、sensitive、restricted。',
    privacy_notes_json JSON DEFAULT NULL COMMENT '隐私、脱敏、租户隔离和写入前注意事项，JSON 数组格式。',
    candidate_version INT NOT NULL DEFAULT 1 COMMENT '候选版本号，用于乐观锁和审计时间线；每次状态决策后递增。',
    idempotency_key VARCHAR(512) DEFAULT NULL COMMENT '幂等键，用于识别重复候选生成或消息重试。',
    decided_at DATETIME(3) DEFAULT NULL COMMENT '最近一次批准或拒绝时间；完整时间线以审计表为准。',
    decided_by VARCHAR(64) DEFAULT NULL COMMENT '最近一次决策操作者 ID；完整操作者记录以审计表为准。',
    decision_reason VARCHAR(1024) DEFAULT NULL COMMENT '最近一次决策原因摘要；完整决策记录以审计表为准。',
    attributes_json JSON DEFAULT NULL COMMENT '机器可读扩展属性，例如 resultKeys、riskLevel、governanceHintKeys。',
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '候选创建时间。',
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '候选最近更新时间。',
    UNIQUE KEY uk_agent_memory_write_candidate_id (candidate_id),
    UNIQUE KEY uk_agent_memory_write_candidate_idempotency (tenant_id, idempotency_key),
    KEY idx_agent_memory_write_candidate_scope_status (tenant_id, project_id, status, create_time),
    KEY idx_agent_memory_write_candidate_workspace (tenant_id, project_id, workspace_key, status, create_time),
    KEY idx_agent_memory_write_candidate_type_scope (tenant_id, memory_type, scope, status),
    KEY idx_agent_memory_write_candidate_source (source_audit_id, source_run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Agent 长期记忆写入候选表';

CREATE TABLE IF NOT EXISTS agent_memory_write_candidate_audit (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '自增主键。',
    candidate_id VARCHAR(128) NOT NULL COMMENT '被操作的长期记忆候选 ID。',
    tenant_id VARCHAR(64) NOT NULL COMMENT '租户 ID，便于审计查询按租户隔离。',
    project_id VARCHAR(64) NOT NULL COMMENT '项目 ID，便于项目级审批复核。',
    operator_id VARCHAR(64) NOT NULL COMMENT '执行批准或拒绝的操作者 ID。',
    action VARCHAR(32) NOT NULL COMMENT '决策动作：approve 或 reject；后续可扩展 archive、forget、reopen。',
    previous_status VARCHAR(32) NOT NULL COMMENT '操作前状态，用于还原状态流转。',
    next_status VARCHAR(32) NOT NULL COMMENT '操作后状态，用于审计报表和异常复盘。',
    reason VARCHAR(1024) NOT NULL COMMENT '操作者填写的决策原因，禁止为空。',
    candidate_version INT NOT NULL COMMENT '操作后候选版本号，用于和主表快照对齐。',
    decided_at DATETIME(3) DEFAULT NULL COMMENT '业务决策时间，通常来自 Runtime 或审批中心。',
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '审计记录写入时间。',
    KEY idx_agent_memory_write_candidate_audit_candidate (candidate_id, id),
    KEY idx_agent_memory_write_candidate_audit_operator (tenant_id, operator_id, create_time),
    KEY idx_agent_memory_write_candidate_audit_project (tenant_id, project_id, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Agent 长期记忆写入候选操作审计表';

CREATE TABLE IF NOT EXISTS agent_memory_materialization_receipt (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '自增主键，仅用于数据库内部索引。',
    receipt_id VARCHAR(128) NOT NULL COMMENT '长期记忆落成 receipt 业务 ID；当前按 candidate_id 稳定生成。',
    candidate_id VARCHAR(128) NOT NULL COMMENT '关联的长期记忆候选 ID。',
    tenant_id VARCHAR(64) NOT NULL COMMENT '租户 ID，用于补偿查询、指标分组和多租户隔离。',
    project_id VARCHAR(64) NOT NULL COMMENT '项目 ID，用于项目级补偿、审计和问题复盘。',
    workspace_key VARCHAR(255) NOT NULL COMMENT 'Agent 工作空间隔离键；正式记忆落成必须继承候选 workspace。',
    memory_namespace VARCHAR(255) NOT NULL COMMENT '长期记忆命名空间；后续检索和向量库 metadata filter 必须使用同一值。',
    status VARCHAR(32) NOT NULL COMMENT '落成执行状态：started、succeeded、failed。',
    attempt_count INT NOT NULL DEFAULT 1 COMMENT '同一 receipt 的处理尝试次数，用于识别 worker 抖动、超时重试或管理员补偿。',
    worker_id VARCHAR(128) DEFAULT NULL COMMENT '处理该候选的 worker/实例标识；同步调用可使用 python-ai-runtime-inline。',
    memory_id VARCHAR(128) DEFAULT NULL COMMENT '成功落成后的正式记忆 ID。',
    namespace_json JSON DEFAULT NULL COMMENT '正式记忆层级命名空间 tuple 的 JSON 表示，便于调试和后续迁移。',
    outcome VARCHAR(64) DEFAULT NULL COMMENT 'materializer 返回的结果，例如 materialized 或 already_materialized。',
    message VARCHAR(1024) DEFAULT NULL COMMENT '低敏成功说明，禁止保存工具原始输出。',
    error_message VARCHAR(1024) DEFAULT NULL COMMENT '低敏失败摘要，只保存异常类型和短消息。',
    started_at DATETIME(3) DEFAULT NULL COMMENT '最近一次处理开始时间。',
    finished_at DATETIME(3) DEFAULT NULL COMMENT '最近一次成功或失败完成时间。',
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'receipt 首次创建时间。',
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT 'receipt 最近更新时间。',
    UNIQUE KEY uk_agent_memory_materialization_receipt_id (receipt_id),
    UNIQUE KEY uk_agent_memory_materialization_candidate (candidate_id),
    KEY idx_agent_memory_materialization_scope_status (tenant_id, project_id, status, update_time),
    KEY idx_agent_memory_materialization_workspace (tenant_id, project_id, workspace_key, status, update_time),
    KEY idx_agent_memory_materialization_memory (memory_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Agent 长期记忆正式落成 receipt 表';

-- Agent 长期记忆落成 worker 租约表。
--
-- candidate 表保存审批事实，receipt 表保存执行证据，本表单独保存多实例 worker 的短时领取权。
-- lease_token 是内部 fencing token：旧 worker 即使在租约过期后晚到，也不能覆盖新 worker 的处理结果。
-- token 禁止输出到诊断接口、日志或前端；失败重试退避与 DLQ 将在后续阶段继续扩展。
CREATE TABLE IF NOT EXISTS agent_memory_materialization_lease (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '自增主键，仅用于数据库内部索引。',
    lease_id VARCHAR(160) NOT NULL COMMENT '稳定租约业务 ID，当前按 candidate_id 生成。',
    candidate_id VARCHAR(128) NOT NULL COMMENT '被领取的长期记忆候选 ID。',
    tenant_id VARCHAR(64) NOT NULL COMMENT '租户 ID，用于多租户隔离、补偿查询和指标分组。',
    project_id VARCHAR(64) NOT NULL COMMENT '项目 ID，用于项目级补偿、审计和问题复盘。',
    workspace_key VARCHAR(255) NOT NULL COMMENT 'Agent 工作空间隔离键，必须继承候选 workspace。',
    memory_namespace VARCHAR(255) NOT NULL COMMENT '长期记忆命名空间，必须继承候选 namespace。',
    status VARCHAR(32) NOT NULL COMMENT '租约状态：leased、succeeded、failed、dead_letter。',
    attempt_count INT NOT NULL DEFAULT 1 COMMENT '候选被领取的累计次数，用于识别 worker 抖动或毒性候选。',
    worker_id VARCHAR(128) NOT NULL COMMENT '当前或最近处理该候选的 worker 实例标识。',
    lease_token VARCHAR(128) NOT NULL COMMENT '内部 fencing token，禁止写入日志、诊断接口或前端。',
    leased_until DATETIME(3) NOT NULL COMMENT '租约过期时间；worker 崩溃后其他实例可在该时间之后接管。',
    next_retry_at DATETIME(3) DEFAULT NULL COMMENT '失败退避结束时间；未到该时间前 Runner 不自动重新领取，进入 dead_letter 时为空。',
    memory_id VARCHAR(128) DEFAULT NULL COMMENT '成功落成后的正式记忆 ID。',
    outcome VARCHAR(64) DEFAULT NULL COMMENT 'materializer 结果，例如 materialized 或 already_materialized。',
    message VARCHAR(1024) DEFAULT NULL COMMENT '低敏成功说明，禁止保存工具原始输出。',
    error_message VARCHAR(1024) DEFAULT NULL COMMENT '低敏失败摘要，禁止保存 prompt、SQL、样本或完整异常堆栈。',
    started_at DATETIME(3) DEFAULT NULL COMMENT '最近一次领取开始时间。',
    finished_at DATETIME(3) DEFAULT NULL COMMENT '最近一次成功或失败结束时间。',
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '租约记录首次创建时间。',
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '租约记录最近更新时间。',
    UNIQUE KEY uk_agent_memory_materialization_lease_id (lease_id),
    UNIQUE KEY uk_agent_memory_materialization_lease_candidate (candidate_id),
    KEY idx_agent_memory_materialization_lease_claim (status, leased_until, update_time),
    KEY idx_agent_memory_materialization_lease_retry (status, next_retry_at, leased_until, update_time),
    KEY idx_agent_memory_materialization_lease_scope (tenant_id, project_id, status, update_time),
    KEY idx_agent_memory_materialization_lease_workspace (tenant_id, project_id, workspace_key, status, update_time),
    KEY idx_agent_memory_materialization_lease_memory (memory_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Agent 长期记忆落成 worker 租约表';

-- Agent 长期记忆物化审计 outbox 表。
--
-- receipt 表回答“是否尝试落成”，lease 表回答“谁拥有处理权”，audit outbox 表回答
-- “worker 批次或管理员补偿动作是否已经留下可转交审计中心的事实”。
-- payload_json 只能保存低敏控制面字段，禁止保存 prompt、候选正文、正式记忆正文、SQL、
-- 样本数据、工具原始输出、lease token 或完整异常堆栈。
CREATE TABLE IF NOT EXISTS agent_memory_materialization_audit_outbox (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '自增主键，仅用于数据库内部索引。',
    outbox_id VARCHAR(128) NOT NULL COMMENT '审计 outbox 业务 ID；每条 worker 批次或补偿动作一条。',
    event_type VARCHAR(96) NOT NULL COMMENT '审计事实类型，例如 memory_materialization_run_completed 或 memory_materialization_requeue_recorded。',
    event_purpose VARCHAR(128) NOT NULL COMMENT '事件用途，例如 batch_observability 或 compensation_audit，便于后续审计路由。',
    aggregate_id VARCHAR(160) NOT NULL COMMENT '聚合对象 ID；补偿优先 candidateId，批次优先 workerId/runId。',
    tenant_id VARCHAR(64) DEFAULT NULL COMMENT '租户 ID；后台批次可能没有单一租户，管理员补偿通常有租户。',
    project_id VARCHAR(64) DEFAULT NULL COMMENT '项目 ID；后台批次可能为空，补偿通常继承 lease 项目。',
    actor_id VARCHAR(64) DEFAULT NULL COMMENT '操作者或服务账号 ID；worker 批次可为空或使用 worker 身份。',
    request_id VARCHAR(128) DEFAULT NULL COMMENT '管理请求 ID，用于串联 gateway/Java 管理台调用。',
    run_id VARCHAR(128) DEFAULT NULL COMMENT 'Runtime Event runId，用于 replay 和审计详情页跳转。',
    session_id VARCHAR(128) DEFAULT NULL COMMENT '会话 ID；后台补偿通常为空。',
    severity VARCHAR(32) NOT NULL COMMENT '事件严重级别：info、warning、error、audit。',
    action VARCHAR(64) DEFAULT NULL COMMENT '动作，例如 batch_completed、dry_run_requeue、scheduled_retry。',
    dry_run TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否 dry-run；真实重排和预览必须明确区分。',
    payload_json JSON NOT NULL COMMENT '低敏审计 payload，禁止保存正文、SQL、样本数据、工具原始输出或 lease token。',
    delivery_status VARCHAR(32) NOT NULL DEFAULT 'pending' COMMENT '投递状态：pending、dispatched、failed；当前阶段先写 pending。',
    attempt_count INT NOT NULL DEFAULT 0 COMMENT '后续 dispatcher 投递尝试次数；当前 append 阶段保持 0。',
    next_delivery_attempt_at DATETIME(3) DEFAULT NULL COMMENT '后续 dispatcher 下一次允许投递时间。',
    created_at DATETIME(3) NOT NULL COMMENT '审计事实产生时间。',
    updated_at DATETIME(3) NOT NULL COMMENT '审计 outbox 最近更新时间。',
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '数据库记录创建时间。',
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '数据库记录更新时间。',
    UNIQUE KEY uk_agent_memory_materialization_audit_outbox_id (outbox_id),
    KEY idx_agent_memory_materialization_audit_dispatch (delivery_status, next_delivery_attempt_at, update_time),
    KEY idx_agent_memory_materialization_audit_scope (tenant_id, project_id, event_type, created_at),
    KEY idx_agent_memory_materialization_audit_actor (tenant_id, actor_id, created_at),
    KEY idx_agent_memory_materialization_audit_aggregate (aggregate_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Agent 长期记忆物化审计 outbox 表';

-- ---------------------------------------------------------------------------
-- Agent Runtime DAG 异步工具入箱权限策略
-- ---------------------------------------------------------------------------
-- selected-node outbox enqueue 是推荐生产入口：调用方必须先通过 DAG dry-run，携带服务端 selectionFingerprint，
-- 再显式确认某一批 ready 异步节点进入命令 outbox。Run 级 enqueue 粒度更粗，默认收口为平台管理员补偿入口。
INSERT IGNORE INTO permission_route_policy
(tenant_id, policy_name, role_code, http_method, path_pattern, resource_type, action, effect, priority, enabled, description, create_time, update_time)
VALUES
(0, '服务账号确认 DAG 选中节点异步入箱', 'SERVICE_ACCOUNT', 'POST',
 '/api/agent/sessions/*/runs/*/tool-executions/dag-selected-node-outbox/enqueue',
 'AI_RUNTIME', 'ENQUEUE_SELECTED_ASYNC_TOOL', 'ALLOW', 860, 1,
 '允许受信 Agent Runtime 服务账号在代表用户完成 dry-run 指纹复核后，把明确选中的异步工具节点写入命令 outbox；该策略不代表服务账号拥有任意工具执行权限，仍需下游工具二次校验和数据范围约束。',
 NOW(), NOW()),
(0, '项目负责人确认 DAG 选中节点异步入箱', 'PROJECT_OWNER', 'POST',
 '/api/agent/sessions/*/runs/*/tool-executions/dag-selected-node-outbox/enqueue',
 'AI_RUNTIME', 'ENQUEUE_SELECTED_ASYNC_TOOL', 'ALLOW', 146, 1,
 '项目负责人可在负责项目范围内确认已 dry-run 的异步工具节点入箱；后续应结合项目数据范围、审批单和租户配额继续收口。',
 NOW(), NOW()),
(0, '运营人员确认 DAG 选中节点异步入箱', 'OPERATOR', 'POST',
 '/api/agent/sessions/*/runs/*/tool-executions/dag-selected-node-outbox/enqueue',
 'AI_RUNTIME', 'ENQUEUE_SELECTED_ASYNC_TOOL', 'ALLOW', 142, 1,
 '运营人员可在排障和受控执行场景中确认 selected-node 入箱，但仍必须依赖 dry-run 指纹、批量上限和审计记录，避免绕过用户确认链路。',
 NOW(), NOW()),
(0, '普通用户禁止直接批量入箱整个 Agent Run', 'ORDINARY_USER', 'POST',
 '/api/agent/sessions/*/runs/*/async-task-commands/outbox/enqueue',
 'AI_RUNTIME', 'ENQUEUE_RUN_ASYNC_TOOLS', 'DENY', 900, 1,
 'Run 级异步工具入箱粒度过粗，普通用户必须走 selected-node 确认入口，避免一次性推进未逐项确认的后台工具动作。',
 NOW(), NOW()),
(0, '服务账号禁止粗粒度 Run 级异步入箱默认放行', 'SERVICE_ACCOUNT', 'POST',
 '/api/agent/sessions/*/runs/*/async-task-commands/outbox/enqueue',
 'AI_RUNTIME', 'ENQUEUE_RUN_ASYNC_TOOLS', 'DENY', 900, 1,
 '服务账号默认不应使用 Run 级粗粒度入箱入口；如确需内部补偿，应通过更高优先级、更窄范围的临时策略或审批流程显式授权。',
 NOW(), NOW()),
(0, '平台管理员 Run 级异步入箱补偿入口', 'PLATFORM_ADMINISTRATOR', 'POST',
 '/api/agent/sessions/*/runs/*/async-task-commands/outbox/enqueue',
 'AI_RUNTIME', 'ENQUEUE_RUN_ASYNC_TOOLS', 'ALLOW', 910, 1,
 '平台管理员可在事故恢复或联调场景使用 Run 级异步入箱补偿入口；生产操作应配合审计、变更单、租户配额和回滚预案。',
 NOW(), NOW());

-- ---------------------------------------------------------------------------
-- Agent Runtime / Task Management 异步工具 worker 执行前授权策略
-- ---------------------------------------------------------------------------
-- selected-node 入箱只证明“某个确认动作产生了任务命令”，不等于 worker 执行副作用时权限仍然有效。
-- 因此 worker 使用独立动作 EXECUTE_CONFIRMED_ASYNC_TOOL，在执行前重新调用 permission-admin evaluate。
INSERT IGNORE INTO permission_route_policy
(tenant_id, policy_name, role_code, http_method, path_pattern, resource_type, action, effect, priority, enabled, description, create_time, update_time)
VALUES
(0, '服务账号执行已确认 Agent 异步工具', 'SERVICE_ACCOUNT', 'POST',
 '/internal/task-management/agent-async-tools/*/execute',
 'AI_RUNTIME', 'EXECUTE_CONFIRMED_ASYNC_TOOL', 'ALLOW', 870, 1,
 '允许 task-management Agent worker 在回查 confirmation、任务状态和工具白名单之后，以服务账号身份代表上游 actor 执行已确认异步工具；该策略只保护 worker 执行入口，不替代下游 data-sync/data-quality 等领域服务自己的幂等、租约和数据范围校验。',
 NOW(), NOW()),
(0, '普通用户禁止直接调用 Agent worker 执行入口', 'ORDINARY_USER', 'POST',
 '/internal/task-management/agent-async-tools/*/execute',
 'AI_RUNTIME', 'EXECUTE_CONFIRMED_ASYNC_TOOL', 'DENY', 900, 1,
 'Agent worker 执行入口只能由受控任务调度链路触发，普通用户不能绕过会话、确认、任务队列和 worker 复核直接调用。',
 NOW(), NOW()),
(0, '审计员禁止调用 Agent worker 执行入口', 'AUDITOR', 'POST',
 '/internal/task-management/agent-async-tools/*/execute',
 'AI_RUNTIME', 'EXECUTE_CONFIRMED_ASYNC_TOOL', 'DENY', 900, 1,
 '审计员只能复核 Agent 工具确认、任务和执行证据，不能触发真实工具副作用。',
 NOW(), NOW());
