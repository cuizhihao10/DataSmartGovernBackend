-- Align project creation/join approval flows with gateway route metadata and the tenant-level workflow boundary.
SET search_path TO permission_admin, public;

-- Creation and join requests are tenant-level workflow facts. They are not scoped to the currently selected project:
-- a user with no project membership must still be able to request a first project or discover a project to join.
INSERT INTO permission_data_scope_policy
(tenant_id, role_code, resource_type, scope_level, scope_expression, approval_required, enabled, description,
 create_time, update_time)
VALUES
(0, 'ORDINARY_USER', 'PROJECT_CREATION_REQUEST', 'TENANT',
 'tenant_id = ${tenantId} AND applicant_actor_id = ${actorId}', FALSE, TRUE,
 '普通用户仅能在本租户提交并查看本人项目创建申请。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, 'PROJECT_OWNER', 'PROJECT_CREATION_REQUEST', 'TENANT',
 'tenant_id = ${tenantId} AND applicant_actor_id = ${actorId}', FALSE, TRUE,
 '项目负责人可在本租户申请新增项目并查看本人申请。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, 'OPERATOR', 'PROJECT_CREATION_REQUEST', 'TENANT',
 'tenant_id = ${tenantId} AND applicant_actor_id = ${actorId}', FALSE, TRUE,
 '运营人员可在本租户申请项目并查看本人申请。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, 'TENANT_ADMINISTRATOR', 'PROJECT_CREATION_REQUEST', 'TENANT',
 'tenant_id = ${tenantId}', FALSE, TRUE,
 '租户管理员审批本租户项目创建申请。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, 'PLATFORM_ADMINISTRATOR', 'PROJECT_CREATION_REQUEST', 'PLATFORM',
 '1 = 1', FALSE, TRUE,
 '平台管理员可跨租户审批项目创建申请。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, 'ORDINARY_USER', 'PROJECT_JOIN_REQUEST', 'TENANT',
 'tenant_id = ${tenantId} AND applicant_actor_id = ${actorId}', FALSE, TRUE,
 '普通用户可发现本租户项目并管理本人加入申请。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, 'PROJECT_OWNER', 'PROJECT_JOIN_REQUEST', 'TENANT',
 'tenant_id = ${tenantId}', FALSE, TRUE,
 '项目负责人可管理本人申请，并按服务层 OWNER 成员事实审批负责项目的加入申请。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, 'OPERATOR', 'PROJECT_JOIN_REQUEST', 'TENANT',
 'tenant_id = ${tenantId} AND applicant_actor_id = ${actorId}', FALSE, TRUE,
 '运营人员可发现本租户项目并管理本人加入申请。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, 'AUDITOR', 'PROJECT_JOIN_REQUEST', 'TENANT',
 'tenant_id = ${tenantId}', FALSE, TRUE,
 '审计人员只读发现本租户启用项目名称目录。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, 'TENANT_ADMINISTRATOR', 'PROJECT_JOIN_REQUEST', 'TENANT',
 'tenant_id = ${tenantId}', FALSE, TRUE,
 '租户管理员审批本租户项目加入申请。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, 'PLATFORM_ADMINISTRATOR', 'PROJECT_JOIN_REQUEST', 'PLATFORM',
 '1 = 1', FALSE, TRUE,
 '平台管理员可跨租户审批项目加入申请。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (tenant_id, role_code, resource_type) DO UPDATE
SET scope_level = EXCLUDED.scope_level,
    scope_expression = EXCLUDED.scope_expression,
    approval_required = EXCLUDED.approval_required,
    enabled = EXCLUDED.enabled,
    description = EXCLUDED.description,
    update_time = CURRENT_TIMESTAMP;

-- The UI allows PROJECT_OWNER and OPERATOR identities to apply for projects. Give their own-request actions explicit
-- policies so they do not collide with PROJECT_OWNER's separate REVIEW routes.
INSERT INTO permission_route_policy
(tenant_id, policy_name, role_code, http_method, path_pattern, resource_type, action, effect, priority, enabled,
 description, create_time, update_time)
VALUES
(0, '项目负责人提交本人项目加入申请', 'PROJECT_OWNER', 'POST',
 '/api/permission/project-join-requests', 'PROJECT_JOIN_REQUEST', 'APPLY', 'ALLOW', 790, TRUE,
 '项目负责人可以申请加入本租户其他项目，服务层禁止重复加入。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '项目负责人查看本人项目加入申请', 'PROJECT_OWNER', 'GET',
 '/api/permission/project-join-requests/my', 'PROJECT_JOIN_REQUEST', 'VIEW_OWN', 'ALLOW', 790, TRUE,
 '项目负责人只能通过本人列表查看自己发起的加入申请。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '项目负责人撤销本人项目加入申请', 'PROJECT_OWNER', 'POST',
 '/api/permission/project-join-requests/*/cancel', 'PROJECT_JOIN_REQUEST', 'CANCEL_OWN', 'ALLOW', 790, TRUE,
 '项目负责人只能撤销本人仍为 PENDING 的加入申请。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '运营人员提交本人项目加入申请', 'OPERATOR', 'POST',
 '/api/permission/project-join-requests', 'PROJECT_JOIN_REQUEST', 'APPLY', 'ALLOW', 770, TRUE,
 '运营人员可以申请加入本租户项目，服务层禁止重复加入。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '运营人员查看本人项目加入申请', 'OPERATOR', 'GET',
 '/api/permission/project-join-requests/my', 'PROJECT_JOIN_REQUEST', 'VIEW_OWN', 'ALLOW', 770, TRUE,
 '运营人员只能查看自己发起的加入申请。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '运营人员撤销本人项目加入申请', 'OPERATOR', 'POST',
 '/api/permission/project-join-requests/*/cancel', 'PROJECT_JOIN_REQUEST', 'CANCEL_OWN', 'ALLOW', 770, TRUE,
 '运营人员只能撤销本人仍为 PENDING 的加入申请。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT DO NOTHING;
