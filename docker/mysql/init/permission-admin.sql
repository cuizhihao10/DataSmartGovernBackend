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
-- 6. 权限审计表：记录权限判定、权限变更和高风险动作。
-- 7. 权限事件 outbox 表：可靠暂存待投递的权限变更事件，支撑 gateway 缓存自动失效。
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
    effect VARCHAR(16) NOT NULL COMMENT '策略效果：ALLOW 或 DENY',
    priority INT NOT NULL DEFAULT 100 COMMENT '优先级，数值越大越优先；同优先级下 DENY 应优先于 ALLOW',
    enabled TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否启用',
    description VARCHAR(1000) COMMENT '策略说明',
    create_time DATETIME NOT NULL COMMENT '创建时间',
    update_time DATETIME NOT NULL COMMENT '更新时间',
    UNIQUE KEY uk_permission_route_policy (tenant_id, role_code, http_method, path_pattern, effect),
    INDEX idx_permission_route_role_priority (tenant_id, role_code, enabled, priority)
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
('quality', NULL, '数据质量', '/quality', 'CheckCircleOutlined', 40, 1, '质量规则、质量报告和异常分析入口。', NOW(), NOW()),
('permission', NULL, '权限管理', '/permissions', 'SafetyOutlined', 50, 1, '角色、菜单、路由策略、数据范围和审批策略入口。', NOW(), NOW()),
('audit', NULL, '审计中心', '/audit', 'FileSearchOutlined', 60, 1, '权限变更、任务操作和高风险动作审计入口。', NOW(), NOW()),
('system', NULL, '系统设置', '/system', 'SettingOutlined', 90, 1, '平台级配置、租户配置、模型和运行时策略入口。', NOW(), NOW());

INSERT IGNORE INTO permission_role_menu_binding
(tenant_id, role_code, menu_code, enabled, binding_source, note, create_time, update_time)
VALUES
(0, 'ORDINARY_USER', 'dashboard', 1, 'BOOTSTRAP', '普通用户可查看基础总览。', NOW(), NOW()),
(0, 'ORDINARY_USER', 'datasource', 1, 'BOOTSTRAP', '普通用户可访问授权范围内的数据源。', NOW(), NOW()),
(0, 'ORDINARY_USER', 'task', 1, 'BOOTSTRAP', '普通用户可查看或创建授权范围内任务。', NOW(), NOW()),
(0, 'PROJECT_OWNER', 'dashboard', 1, 'BOOTSTRAP', '项目负责人可查看项目治理总览。', NOW(), NOW()),
(0, 'PROJECT_OWNER', 'datasource', 1, 'BOOTSTRAP', '项目负责人可管理项目范围数据源。', NOW(), NOW()),
(0, 'PROJECT_OWNER', 'task', 1, 'BOOTSTRAP', '项目负责人可管理项目范围任务。', NOW(), NOW()),
(0, 'PROJECT_OWNER', 'quality', 1, 'BOOTSTRAP', '项目负责人可管理项目质量规则。', NOW(), NOW()),
(0, 'OPERATOR', 'dashboard', 1, 'BOOTSTRAP', '运营人员可查看运行总览。', NOW(), NOW()),
(0, 'OPERATOR', 'task', 1, 'BOOTSTRAP', '运营人员可处理任务运行异常。', NOW(), NOW()),
(0, 'OPERATOR', 'audit', 1, 'BOOTSTRAP', '运营人员可查看与运维相关审计。', NOW(), NOW()),
(0, 'AUDITOR', 'audit', 1, 'BOOTSTRAP', '审计员可查看审计中心。', NOW(), NOW()),
(0, 'TENANT_ADMINISTRATOR', 'permission', 1, 'BOOTSTRAP', '租户管理员可管理本租户权限。', NOW(), NOW()),
(0, 'TENANT_ADMINISTRATOR', 'audit', 1, 'BOOTSTRAP', '租户管理员可查看本租户审计。', NOW(), NOW()),
(0, 'PLATFORM_ADMINISTRATOR', 'dashboard', 1, 'BOOTSTRAP', '平台管理员可查看全平台总览。', NOW(), NOW()),
(0, 'PLATFORM_ADMINISTRATOR', 'datasource', 1, 'BOOTSTRAP', '平台管理员可查看全平台数据源。', NOW(), NOW()),
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
(0, '普通用户禁止访问任务运营接口', 'ORDINARY_USER', 'ANY', '/api/task/operations/**', 'DENY', 650, 1, '任务运营接口包含队列健康、死信、延期和执行器租约等跨任务运行信息，普通用户即使拥有任务查看权限也不能访问。', NOW(), NOW()),
(0, '普通用户禁止系统配置', 'ORDINARY_USER', 'ANY', '/api/permission/**', 'DENY', 200, 1, '普通用户不能访问权限管理接口。', NOW(), NOW()),
(0, '项目负责人访问数据源', 'PROJECT_OWNER', 'ANY', '/api/datasource/**', 'ALLOW', 120, 1, '项目负责人可管理项目范围内数据源和同步配置。', NOW(), NOW()),
(0, '项目负责人访问任务', 'PROJECT_OWNER', 'ANY', '/api/task/**', 'ALLOW', 120, 1, '项目负责人可管理项目范围内任务。', NOW(), NOW()),
(0, '项目负责人禁止访问任务运营接口', 'PROJECT_OWNER', 'ANY', '/api/task/operations/**', 'DENY', 650, 1, '项目负责人管理业务范围内任务，但平台队列、执行器租约和死信处置属于运营视角，避免误把项目管理权限扩大为平台运维权限。', NOW(), NOW()),
(0, '项目负责人访问质量', 'PROJECT_OWNER', 'ANY', '/api/quality/**', 'ALLOW', 120, 1, '项目负责人可管理项目范围质量规则。', NOW(), NOW()),
(0, '运营人员访问任务', 'OPERATOR', 'ANY', '/api/task/**', 'ALLOW', 130, 1, '运营人员可处理任务运行、重试、恢复等运维动作。', NOW(), NOW()),
(0, '运营人员查看任务运营接口', 'OPERATOR', 'GET', '/api/task/operations/**', 'ALLOW', 650, 1, '运营人员可查看任务队列、延期、死信和执行器租约等运行健康信息，用于告警排查、容量判断和人工恢复前诊断。', NOW(), NOW()),
(0, '运营人员访问观测', 'OPERATOR', 'ANY', '/api/observability/**', 'ALLOW', 130, 1, '运营人员可查看可观测性和告警信息。', NOW(), NOW()),
(0, '运营人员查看权限运维面', 'OPERATOR', 'GET', '/api/permission/operations/**', 'ALLOW', 135, 1, '运营人员可查看权限 outbox 和审计记录，用于排查策略事件投递、缓存失效和系统一致性问题。', NOW(), NOW()),
(0, '运营人员重试权限 outbox', 'OPERATOR', 'POST', '/api/permission/operations/outbox/events/**', 'ALLOW', 136, 1, '运营人员可触发 outbox 人工重试；忽略事件仍由 permission-admin 服务层限制为平台管理员。', NOW(), NOW()),
(0, '审计员查看审计', 'AUDITOR', 'GET', '/api/permission/**', 'ALLOW', 110, 1, '审计员可查询权限矩阵和审计相关只读信息。', NOW(), NOW()),
(0, '租户管理员管理权限', 'TENANT_ADMINISTRATOR', 'ANY', '/api/permission/**', 'ALLOW', 150, 1, '租户管理员可管理本租户权限策略。', NOW(), NOW()),
(0, '租户管理员查看任务运营接口', 'TENANT_ADMINISTRATOR', 'GET', '/api/task/operations/**', 'ALLOW', 650, 1, '租户管理员可查看本租户任务队列和异常运行状态，但后续批量恢复、批量取消等高风险动作仍建议单独配置审批或更高权限。', NOW(), NOW()),
(0, '平台管理员全平台权限', 'PLATFORM_ADMINISTRATOR', 'ANY', '/api/**', 'ALLOW', 1000, 1, '平台管理员拥有全平台管理权限。', NOW(), NOW()),
(0, '服务账号任务与数据源调用', 'SERVICE_ACCOUNT', 'ANY', '/api/task/**', 'ALLOW', 500, 1, '服务账号可调用任务中心执行内部编排。', NOW(), NOW()),
(0, '服务账号禁止访问任务运营接口', 'SERVICE_ACCOUNT', 'ANY', '/api/task/operations/**', 'DENY', 650, 1, '服务账号用于执行器、调度器和内部编排，不应默认读取运营工作台数据，避免机器身份被滥用为跨队列巡检入口。', NOW(), NOW()),
(0, '服务账号数据源调用', 'SERVICE_ACCOUNT', 'ANY', '/api/datasource/**', 'ALLOW', 500, 1, '服务账号可调用数据源控制面执行内部任务。', NOW(), NOW());

INSERT IGNORE INTO permission_data_scope_policy
(tenant_id, role_code, resource_type, scope_level, scope_expression, approval_required, enabled, description, create_time, update_time)
VALUES
(0, 'ORDINARY_USER', 'DATASOURCE', 'SELF', 'created_by = ${actorId} OR owner_id = ${actorId}', 0, 1, '普通用户只能访问自己创建或拥有的数据源。', NOW(), NOW()),
(0, 'ORDINARY_USER', 'SYNC_TASK', 'SELF', 'owner_id = ${actorId}', 0, 1, '普通用户只能访问自己的同步任务。', NOW(), NOW()),
(0, 'PROJECT_OWNER', 'DATASOURCE', 'PROJECT', 'project_id IN ${actorProjectIds}', 0, 1, '项目负责人可访问项目范围数据源。', NOW(), NOW()),
(0, 'PROJECT_OWNER', 'SYNC_TASK', 'PROJECT', 'project_id IN ${actorProjectIds}', 0, 1, '项目负责人可访问项目范围同步任务。', NOW(), NOW()),
(0, 'OPERATOR', 'TASK', 'TENANT', 'tenant_id = ${tenantId}', 0, 1, '运营人员可在租户内处理任务运行问题。', NOW(), NOW()),
(0, 'OPERATOR', 'TASK_OPERATION', 'TENANT', 'tenant_id = ${tenantId}', 0, 1, '运营人员查看任务运营工作台时默认限制在当前租户，避免跨租户暴露队列、执行器租约和死信信息。', NOW(), NOW()),
(0, 'AUDITOR', 'AUDIT_LOG', 'TENANT', 'tenant_id = ${tenantId}', 0, 1, '审计员可查看租户内审计日志。', NOW(), NOW()),
(0, 'TENANT_ADMINISTRATOR', 'DATASOURCE', 'TENANT', 'tenant_id = ${tenantId}', 0, 1, '租户管理员可访问本租户数据源。', NOW(), NOW()),
(0, 'TENANT_ADMINISTRATOR', 'SYNC_TASK', 'TENANT', 'tenant_id = ${tenantId}', 0, 1, '租户管理员可访问本租户同步任务。', NOW(), NOW()),
(0, 'TENANT_ADMINISTRATOR', 'TASK_OPERATION', 'TENANT', 'tenant_id = ${tenantId}', 0, 1, '租户管理员可查看本租户任务运营状态，用于租户级容量、异常和 SLA 排查。', NOW(), NOW()),
(0, 'PLATFORM_ADMINISTRATOR', 'DATASOURCE', 'PLATFORM', '1 = 1', 1, 1, '平台管理员可跨租户访问数据源，高风险导出仍建议审批。', NOW(), NOW()),
(0, 'PLATFORM_ADMINISTRATOR', 'SYNC_TASK', 'PLATFORM', '1 = 1', 0, 1, '平台管理员可跨租户管理同步任务。', NOW(), NOW()),
(0, 'PLATFORM_ADMINISTRATOR', 'AUDIT_LOG', 'PLATFORM', '1 = 1', 0, 1, '平台管理员可查看全平台审计。', NOW(), NOW()),
(0, 'PLATFORM_ADMINISTRATOR', 'TASK_OPERATION', 'PLATFORM', '1 = 1', 0, 1, '平台管理员可跨租户查看任务运营状态，用于全局容量治理、队列压测、死信恢复和事故复盘。', NOW(), NOW()),
(0, 'SERVICE_ACCOUNT', 'SYNC_TASK', 'TENANT', 'tenant_id = ${tenantId}', 0, 1, '服务账号默认只在调用上下文租户内执行任务。', NOW(), NOW());
