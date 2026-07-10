-- permission-admin: unified approval center, frontend menu alignment and menu-query access.
SET search_path TO permission_admin, public;

-- Keep existing stable menu codes while aligning their paths with the real React routes.
UPDATE permission_menu
SET path = '/sync',
    menu_name = '数据同步',
    update_time = CURRENT_TIMESTAMP
WHERE menu_code = 'data-sync';

UPDATE permission_menu
SET path = '/agent',
    menu_name = '智能体助手',
    update_time = CURRENT_TIMESTAMP
WHERE menu_code = 'agent-runtime';

INSERT INTO permission_menu
(menu_code, parent_code, menu_name, path, icon, sort_order, enabled, description, create_time, update_time)
VALUES
('approval-center', NULL, '申请与审批', '/approvals', 'AuditOutlined', 45, TRUE,
 '普通用户查看本人申请进度；租户和平台管理员处理所有待办审批。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('closure', NULL, '闭环验收', '/closure', 'CheckCircleOutlined', 95, TRUE,
 '平台交付、依赖健康、运行闭环与最终验收入口。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (menu_code) DO UPDATE
SET menu_name = EXCLUDED.menu_name,
    path = EXCLUDED.path,
    icon = EXCLUDED.icon,
    sort_order = EXCLUDED.sort_order,
    enabled = EXCLUDED.enabled,
    description = EXCLUDED.description,
    update_time = CURRENT_TIMESTAMP;

-- Fill the practical console menu matrix. The frontend consumes this matrix instead of rendering every menu statically.
INSERT INTO permission_role_menu_binding
(tenant_id, role_code, menu_code, enabled, binding_source, note, create_time, update_time)
VALUES
(0, 'ORDINARY_USER', 'approval-center', TRUE, 'BOOTSTRAP', '普通用户查看本人发起的项目创建和项目加入申请。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, 'PROJECT_OWNER', 'dashboard', TRUE, 'BOOTSTRAP', '项目负责人可查看项目治理总览。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, 'PROJECT_OWNER', 'approval-center', TRUE, 'BOOTSTRAP', '项目负责人查看本人申请进度。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, 'PROJECT_OWNER', 'agent-runtime', TRUE, 'BOOTSTRAP', '项目负责人可使用项目范围智能体助手。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, 'OPERATOR', 'dashboard', TRUE, 'BOOTSTRAP', '运营人员可查看运行总览。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, 'OPERATOR', 'approval-center', TRUE, 'BOOTSTRAP', '运营人员查看本人申请进度。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, 'TENANT_ADMINISTRATOR', 'dashboard', TRUE, 'BOOTSTRAP', '租户管理员可查看本租户总览。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, 'TENANT_ADMINISTRATOR', 'datasource', TRUE, 'BOOTSTRAP', '租户管理员可查看本租户数据源。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, 'TENANT_ADMINISTRATOR', 'task', TRUE, 'BOOTSTRAP', '租户管理员可管理本租户任务。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, 'TENANT_ADMINISTRATOR', 'quality', TRUE, 'BOOTSTRAP', '租户管理员可管理本租户质量能力。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, 'TENANT_ADMINISTRATOR', 'observability', TRUE, 'BOOTSTRAP', '租户管理员可查看本租户运行监控。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, 'TENANT_ADMINISTRATOR', 'approval-center', TRUE, 'BOOTSTRAP', '租户管理员处理本租户待办审批并查看本人申请。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, 'TENANT_ADMINISTRATOR', 'closure', TRUE, 'BOOTSTRAP', '租户管理员可查看租户交付闭环验收。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, 'PLATFORM_ADMINISTRATOR', 'observability', TRUE, 'BOOTSTRAP', '平台管理员可查看全平台运行监控。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, 'PLATFORM_ADMINISTRATOR', 'approval-center', TRUE, 'BOOTSTRAP', '平台管理员处理跨租户待办审批并查看本人申请。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, 'PLATFORM_ADMINISTRATOR', 'closure', TRUE, 'BOOTSTRAP', '平台管理员可查看最终交付闭环验收。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (tenant_id, role_code, menu_code) DO UPDATE
SET enabled = EXCLUDED.enabled,
    note = EXCLUDED.note,
    update_time = CURRENT_TIMESTAMP;

-- The existing ordinary-user deny rule for /api/permission/** has priority 700. These narrow paths use a higher
-- priority while the service layer still verifies actor, tenant, applicant and reviewer boundaries.
INSERT INTO permission_route_policy
(tenant_id, policy_name, role_code, http_method, path_pattern, resource_type, action, effect, priority, enabled, description, create_time, update_time)
VALUES
(0, '普通用户读取本人菜单', 'ORDINARY_USER', 'GET', '/api/permission/menus', NULL, NULL, 'ALLOW', 950, TRUE,
 '登录后读取角色菜单矩阵，仅用于前端菜单展示，不能替代后端路由鉴权。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '项目负责人读取本人菜单', 'PROJECT_OWNER', 'GET', '/api/permission/menus', NULL, NULL, 'ALLOW', 950, TRUE,
 '项目负责人读取角色菜单矩阵。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '运营人员读取本人菜单', 'OPERATOR', 'GET', '/api/permission/menus', NULL, NULL, 'ALLOW', 950, TRUE,
 '运营人员读取角色菜单矩阵。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '审计员读取本人菜单', 'AUDITOR', 'GET', '/api/permission/menus', NULL, NULL, 'ALLOW', 950, TRUE,
 '审计员读取只读审计菜单矩阵。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '租户管理员读取本人菜单', 'TENANT_ADMINISTRATOR', 'GET', '/api/permission/menus', NULL, NULL, 'ALLOW', 950, TRUE,
 '租户管理员读取本租户管理菜单矩阵。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '普通用户查看本人申请汇总', 'ORDINARY_USER', 'GET', '/api/permission/approval-center/my', NULL, NULL, 'ALLOW', 960, TRUE,
 '普通用户只能查看 applicantActorId 等于自己的申请。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '普通用户撤销本人待审批申请', 'ORDINARY_USER', 'POST', '/api/permission/approval-center/*/*/cancel', NULL, NULL, 'ALLOW', 960, TRUE,
 '服务层继续校验申请人和 PENDING 状态。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '项目负责人访问本人申请中心', 'PROJECT_OWNER', 'GET', '/api/permission/approval-center/my', NULL, NULL, 'ALLOW', 960, TRUE,
 '项目负责人查看本人发起的申请。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '项目负责人撤销本人待审批申请', 'PROJECT_OWNER', 'POST', '/api/permission/approval-center/*/*/cancel', NULL, NULL, 'ALLOW', 960, TRUE,
 '服务层继续校验申请人和 PENDING 状态。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '运营人员访问本人申请中心', 'OPERATOR', 'GET', '/api/permission/approval-center/my', NULL, NULL, 'ALLOW', 960, TRUE,
 '运营人员查看本人发起的申请。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '运营人员撤销本人待审批申请', 'OPERATOR', 'POST', '/api/permission/approval-center/*/*/cancel', NULL, NULL, 'ALLOW', 960, TRUE,
 '服务层继续校验申请人和 PENDING 状态。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '租户管理员处理审批中心', 'TENANT_ADMINISTRATOR', 'ANY', '/api/permission/approval-center/**', NULL, NULL, 'ALLOW', 970, TRUE,
 '租户管理员只能处理本租户待办，服务层再次校验租户边界。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '平台管理员处理审批中心', 'PLATFORM_ADMINISTRATOR', 'ANY', '/api/permission/approval-center/**', NULL, NULL, 'ALLOW', 1000, TRUE,
 '平台管理员可处理跨租户待办并保留审批审计。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT DO NOTHING;
