-- permission-admin: productized tenant provisioning and unified project-owner approval policy.
--
-- Product hierarchy is tenant/company -> FlashSync application -> project. Workspace is intentionally absent from
-- this provisioning flow. Projects are created later through the project-creation approval workflow.

SET search_path TO permission_admin, public;

CREATE SEQUENCE IF NOT EXISTS permission_tenant_id_seq
    START WITH 1000
    INCREMENT BY 1;

SELECT setval(
    'permission_admin.permission_tenant_id_seq',
    GREATEST((SELECT COALESCE(MAX(tenant_id), 0) FROM permission_tenant), 999),
    TRUE
);

CREATE SEQUENCE IF NOT EXISTS permission_application_id_seq
    START WITH 100000
    INCREMENT BY 1;

SELECT setval(
    'permission_admin.permission_application_id_seq',
    GREATEST((SELECT COALESCE(MAX(application_id), 0) FROM permission_application), 99999),
    TRUE
);

COMMENT ON SEQUENCE permission_tenant_id_seq IS
    '平台开租租户 ID 序列。ID 由数据库分配，前端和管理员均不手工填写。';
COMMENT ON SEQUENCE permission_application_id_seq IS
    '租户应用 ID 序列。开租时自动创建 FlashSync 应用，不创建工作空间。';

INSERT INTO permission_menu
(menu_code, parent_code, menu_name, path, icon, sort_order, enabled, description, create_time, update_time)
VALUES
('tenant-management', NULL, '租户管理', '/tenants', 'ApartmentOutlined', 52, TRUE,
 '平台超级管理员执行开租、资料维护、暂停、恢复和关闭；开租只初始化租户与 FlashSync 应用。',
 CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (menu_code) DO UPDATE
SET menu_name = EXCLUDED.menu_name,
    path = EXCLUDED.path,
    icon = EXCLUDED.icon,
    sort_order = EXCLUDED.sort_order,
    enabled = EXCLUDED.enabled,
    description = EXCLUDED.description,
    update_time = CURRENT_TIMESTAMP;

INSERT INTO permission_role_menu_binding
(tenant_id, role_code, menu_code, enabled, binding_source, note, create_time, update_time)
VALUES
(0, 'PLATFORM_ADMINISTRATOR', 'tenant-management', TRUE, 'BOOTSTRAP',
 '只有平台超级管理员可见租户开通和生命周期管理入口。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (tenant_id, role_code, menu_code) DO UPDATE
SET enabled = EXCLUDED.enabled,
    note = EXCLUDED.note,
    update_time = CURRENT_TIMESTAMP;

-- Ordinary identities can act as project owners only when the service finds an enabled OWNER membership for the
-- concrete project. These narrow route allows do not grant access by themselves; service and SQL scope remain final.
INSERT INTO permission_route_policy
(tenant_id, policy_name, role_code, http_method, path_pattern, resource_type, action, effect, priority, enabled,
 description, create_time, update_time)
VALUES
(0, '普通用户查询本人负责项目的加入待办', 'ORDINARY_USER', 'GET',
 '/api/permission/approval-center/pending', NULL, NULL, 'ALLOW', 965, TRUE,
 '仅返回数据库成员关系中 actor 为 OWNER 的项目加入申请。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '普通项目负责人审批加入申请', 'ORDINARY_USER', 'POST',
 '/api/permission/approval-center/PROJECT_JOIN/*/approve', NULL, NULL, 'ALLOW', 965, TRUE,
 '服务层校验租户边界和目标项目 OWNER 成员关系，不能审批项目创建或授予 OWNER。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '普通项目负责人拒绝加入申请', 'ORDINARY_USER', 'POST',
 '/api/permission/approval-center/PROJECT_JOIN/*/reject', NULL, NULL, 'ALLOW', 965, TRUE,
 '服务层校验租户边界和目标项目 OWNER 成员关系。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '兼容项目负责人查询加入待办', 'PROJECT_OWNER', 'GET',
 '/api/permission/approval-center/pending', NULL, NULL, 'ALLOW', 965, TRUE,
 '迁移期 PROJECT_OWNER claim 仍必须具备数据库 OWNER 成员关系。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '兼容项目负责人审批加入申请', 'PROJECT_OWNER', 'POST',
 '/api/permission/approval-center/PROJECT_JOIN/*/approve', NULL, NULL, 'ALLOW', 965, TRUE,
 '迁移期兼容策略；不能审批项目创建或授予 OWNER。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '兼容项目负责人拒绝加入申请', 'PROJECT_OWNER', 'POST',
 '/api/permission/approval-center/PROJECT_JOIN/*/reject', NULL, NULL, 'ALLOW', 965, TRUE,
 '迁移期兼容策略。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '平台超级管理员管理租户', 'PLATFORM_ADMINISTRATOR', 'ANY',
 '/api/permission/tenants/**', 'TENANT', NULL, 'ALLOW', 1000, TRUE,
 '平台超级管理员可跨租户开通和管理租户，所有写动作进入权限审计。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '普通用户禁止租户管理', 'ORDINARY_USER', 'ANY',
 '/api/permission/tenants/**', 'TENANT', NULL, 'DENY', 990, TRUE,
 '普通用户不能读取平台租户名录或执行开租。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '项目负责人禁止租户管理', 'PROJECT_OWNER', 'ANY',
 '/api/permission/tenants/**', 'TENANT', NULL, 'DENY', 990, TRUE,
 '项目角色不能升级为平台租户管理权限。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '租户管理员禁止平台开租', 'TENANT_ADMINISTRATOR', 'ANY',
 '/api/permission/tenants/**', 'TENANT', NULL, 'DENY', 990, TRUE,
 '租户管理员只能管理本租户业务，不能新增或管理其他租户。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '运营人员禁止租户管理', 'OPERATOR', 'ANY',
 '/api/permission/tenants/**', 'TENANT', NULL, 'DENY', 990, TRUE,
 '运营角色不具备平台商业开通权限。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '审计员禁止租户变更', 'AUDITOR', 'ANY',
 '/api/permission/tenants/**', 'TENANT', NULL, 'DENY', 990, TRUE,
 '当前租户管理页仅对平台超级管理员开放，审计员通过审计记录查看变更证据。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT DO NOTHING;

INSERT INTO permission_data_scope_policy
(tenant_id, role_code, resource_type, scope_level, scope_expression, approval_required, enabled,
 description, create_time, update_time)
VALUES
(0, 'PLATFORM_ADMINISTRATOR', 'TENANT', 'PLATFORM', '1 = 1', FALSE, TRUE,
 '平台超级管理员可管理全平台租户；Service 仍校验状态机和稳定编码。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (tenant_id, role_code, resource_type) DO UPDATE
SET scope_level = EXCLUDED.scope_level,
    scope_expression = EXCLUDED.scope_expression,
    enabled = EXCLUDED.enabled,
    description = EXCLUDED.description,
    update_time = CURRENT_TIMESTAMP;
