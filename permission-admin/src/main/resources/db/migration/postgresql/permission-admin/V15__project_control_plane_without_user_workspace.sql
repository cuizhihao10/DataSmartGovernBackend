-- permission-admin：项目控制面与用户可见 workspace 退场说明。
--
-- 背景：
-- 1. 当前产品层级已经从“租户 -> 项目 -> 工作空间 -> 资源”收敛为“租户 -> 项目 -> 资源”；
-- 2. workspace_id 列暂时不能物理删除，因为历史执行事实、审计记录、Agent 内部工作区和旧迁移脚本仍可能引用；
-- 3. 但正式页面不应再让用户填写、切换或理解 workspaceId，新建项目、新建数据源、新建同步任务都应只绑定项目；
-- 4. 本迁移补齐 permission_project 的 ID 序列、字段说明和路由策略，使项目可以被前端创建和查询。

SET search_path TO permission_admin, public;

CREATE SEQUENCE IF NOT EXISTS permission_project_id_seq
    START WITH 100000
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

SELECT setval(
    'permission_admin.permission_project_id_seq',
    GREATEST((SELECT COALESCE(MAX(project_id), 0) + 1 FROM permission_project), 100000),
    FALSE
);

COMMENT ON SEQUENCE permission_project_id_seq IS
    '项目主数据 ID 序列。前端不再手填 projectId，permission-admin 创建项目时通过该序列生成稳定数字 ID。';

COMMENT ON TABLE permission_project IS
    '项目主数据表。当前用户可见业务层级为“租户 -> 项目”，数据源、同步任务、质量规则和 Agent 会话都应归属项目。';
COMMENT ON COLUMN permission_project.application_id IS
    '项目所属内部应用 ID。普通页面不要求用户填写，创建项目时由后端根据租户默认应用自动推导。';
COMMENT ON COLUMN permission_project.default_workspace_id IS
    '历史兼容字段：早期项目可绑定默认 workspace。当前正式产品页面不再展示或写入该字段，新建项目保持为空。';

COMMENT ON COLUMN permission_application.default_workspace_id IS
    '历史兼容字段：早期应用可绑定默认 workspace。当前正式产品页面只切换项目，不再要求用户切换工作空间。';
COMMENT ON TABLE permission_workspace IS
    '工作空间主数据表。当前仅作为历史执行事实、Agent 内部工作区、旧 Keycloak claim 映射和迁移期兼容表保留，不再作为普通用户可见层级。';
COMMENT ON COLUMN permission_project_membership.workspace_id IS
    '历史兼容字段：项目成员授权当前按 projectId 生效，workspace_id 不再作为正式页面的数据范围输入。';

INSERT INTO permission_route_policy
(tenant_id, policy_name, role_code, http_method, path_pattern, resource_type, action, effect, priority, enabled, description, create_time, update_time)
VALUES
(0, '普通用户查询项目列表', 'ORDINARY_USER', 'GET', '/api/permission/projects', 'PROJECT', 'VIEW', 'ALLOW', 760, TRUE, '普通用户可以查询自己通过项目成员关系被授权的项目列表，用于项目切换器；服务层仍会按成员关系过滤。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '普通用户查询可见项目', 'ORDINARY_USER', 'GET', '/api/permission/projects/**', 'PROJECT', 'VIEW', 'ALLOW', 760, TRUE, '普通用户可以查询自己通过项目成员关系被授权的项目，用于项目切换器；服务层仍会按成员关系过滤。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '项目负责人查询项目列表', 'PROJECT_OWNER', 'GET', '/api/permission/projects', 'PROJECT', 'VIEW', 'ALLOW', 760, TRUE, '项目负责人可以查询自己负责或被授权的项目列表。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '项目负责人查询可见项目', 'PROJECT_OWNER', 'GET', '/api/permission/projects/**', 'PROJECT', 'VIEW', 'ALLOW', 760, TRUE, '项目负责人可以查询自己负责或被授权的项目。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '运营人员查询项目列表', 'OPERATOR', 'GET', '/api/permission/projects', 'PROJECT', 'VIEW', 'ALLOW', 760, TRUE, '运营人员可以只读查询本租户项目列表，用于运行排障和项目上下文切换。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '运营人员查询租户项目', 'OPERATOR', 'GET', '/api/permission/projects/**', 'PROJECT', 'VIEW', 'ALLOW', 760, TRUE, '运营人员可以只读查询本租户项目，用于运行排障和项目上下文切换。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '审计员查询项目列表', 'AUDITOR', 'GET', '/api/permission/projects', 'PROJECT', 'VIEW', 'ALLOW', 760, TRUE, '审计员可以只读查询本租户项目列表，用于审计复核和证据定位。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '审计员查询租户项目', 'AUDITOR', 'GET', '/api/permission/projects/**', 'PROJECT', 'VIEW', 'ALLOW', 760, TRUE, '审计员可以只读查询本租户项目，用于审计复核和证据定位。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '租户管理员查询项目列表', 'TENANT_ADMINISTRATOR', 'GET', '/api/permission/projects', 'PROJECT', 'VIEW', 'ALLOW', 760, TRUE, '租户管理员可以查询本租户项目列表。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '租户管理员查询项目', 'TENANT_ADMINISTRATOR', 'GET', '/api/permission/projects/**', 'PROJECT', 'VIEW', 'ALLOW', 760, TRUE, '租户管理员可以查询本租户项目。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '项目负责人创建项目', 'PROJECT_OWNER', 'POST', '/api/permission/projects', 'PROJECT', 'CREATE', 'ALLOW', 760, TRUE, '项目负责人可以在本租户自助创建项目，创建后系统会自动授予本人 OWNER。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '租户管理员创建项目', 'TENANT_ADMINISTRATOR', 'POST', '/api/permission/projects', 'PROJECT', 'CREATE', 'ALLOW', 760, TRUE, '租户管理员可以在本租户创建项目并指定负责人。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '运营人员禁止创建项目', 'OPERATOR', 'POST', '/api/permission/projects', 'PROJECT', 'CREATE', 'DENY', 860, TRUE, '运营人员默认只读项目上下文，不能扩展项目边界。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '审计员禁止创建项目', 'AUDITOR', 'POST', '/api/permission/projects', 'PROJECT', 'CREATE', 'DENY', 860, TRUE, '审计员只能复核证据，不能创建项目。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '普通用户禁止创建项目', 'ORDINARY_USER', 'POST', '/api/permission/projects', 'PROJECT', 'CREATE', 'DENY', 860, TRUE, '普通用户默认不能创建项目；如果后续要支持个人项目，应通过租户级开关和配额策略单独放开。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT DO NOTHING;

INSERT INTO permission_data_scope_policy
(tenant_id, role_code, resource_type, scope_level, scope_expression, approval_required, enabled, description, create_time, update_time)
VALUES
(0, 'ORDINARY_USER', 'PROJECT', 'PROJECT', 'project_id IN ${actorProjectIds}', FALSE, TRUE, '普通用户只可见项目成员关系授权的项目。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, 'PROJECT_OWNER', 'PROJECT', 'PROJECT', 'project_id IN ${actorProjectIds}', FALSE, TRUE, '项目负责人只可见自己负责或被授权的项目。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, 'OPERATOR', 'PROJECT', 'TENANT', 'tenant_id = ${tenantId}', FALSE, TRUE, '运营人员可只读查看本租户项目。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, 'AUDITOR', 'PROJECT', 'TENANT', 'tenant_id = ${tenantId}', FALSE, TRUE, '审计员可只读查看本租户项目。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, 'TENANT_ADMINISTRATOR', 'PROJECT', 'TENANT', 'tenant_id = ${tenantId}', FALSE, TRUE, '租户管理员可管理本租户项目。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, 'PLATFORM_ADMINISTRATOR', 'PROJECT', 'PLATFORM', '1 = 1', FALSE, TRUE, '平台管理员可跨租户管理项目。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (tenant_id, role_code, resource_type) DO UPDATE
SET scope_level = EXCLUDED.scope_level,
    scope_expression = EXCLUDED.scope_expression,
    approval_required = EXCLUDED.approval_required,
    enabled = EXCLUDED.enabled,
    description = EXCLUDED.description,
    update_time = CURRENT_TIMESTAMP;
