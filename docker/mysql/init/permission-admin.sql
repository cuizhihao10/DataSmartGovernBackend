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
    WHEN http_method = 'POST' AND path_pattern = '/api/agent/plan-ingestions' THEN 'INGEST_PLAN'
    WHEN http_method = 'POST' AND path_pattern LIKE '/api/agent/%' THEN 'EXECUTE'
    WHEN http_method = 'POST' AND path_pattern LIKE '/api/permission/project-memberships%/batch-upsert' THEN 'IMPORT'
    WHEN http_method = 'POST' AND path_pattern LIKE '/api/permission/project-memberships%/enable' THEN 'ENABLE'
    WHEN http_method = 'POST' AND path_pattern LIKE '/api/permission/project-memberships%/disable' THEN 'DISABLE'
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
