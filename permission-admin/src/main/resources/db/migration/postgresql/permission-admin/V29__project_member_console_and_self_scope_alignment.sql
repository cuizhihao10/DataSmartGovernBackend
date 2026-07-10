-- Align project member visibility, datasource/task isolation and the project member console.
SET search_path TO permission_admin, public;

-- A group count is derived from sync tasks, so it must use exactly the same SELF scope as the task list.
UPDATE permission_data_scope_policy
SET scope_level = 'SELF',
    scope_expression = 'owner_id = ${actorId}',
    approval_required = FALSE,
    enabled = TRUE,
    description = '普通用户的同步任务分组统计只计算本人任务，禁止通过分组徽标泄露项目内其他用户的任务数量。',
    update_time = CURRENT_TIMESTAMP
WHERE role_code = 'ORDINARY_USER'
  AND resource_type = 'SYNC_TASK_GROUP';

-- The local ordinary-user seed started as legacy MEMBER and was previously promoted to MANAGER during cleanup.
-- Keep the identity ordinary and make its project role read-only; datasource use still requires instance ACL.
UPDATE permission_project_membership
SET project_role = 'READER',
    workspace_id = NULL,
    update_time = CURRENT_TIMESTAMP
WHERE tenant_id = 10
  AND actor_id = 1004
  AND project_id = 101
  AND grant_source = 'TENANT_BOOTSTRAP';

UPDATE permission_audit_record
SET detail_json = '{"tenantId":10,"actorId":1004,"username":"ordinary-user","actorRole":"ORDINARY_USER","projectId":101,"projectRole":"READER","workspacePolicy":"DEPRECATED_COMPATIBILITY_ONLY","passwordStorage":"KEYCLOAK_ONLY","passwordHintForLocalDev":"DataSmart@123"}'
WHERE trace_id = 'bootstrap-flashsync-ordinary-user'
  AND tenant_id = 10
  AND action = 'OPEN_ORDINARY_USER';

INSERT INTO permission_menu
(menu_code, parent_code, menu_name, path, icon, sort_order, enabled, description, create_time, update_time)
VALUES
('project-members', NULL, '项目成员', '/project-members', 'TeamOutlined', 47, TRUE,
 '查看当前项目成员、身份账号、项目角色和授权状态；项目 OWNER、租户管理员和平台管理员可调整角色与启停。',
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
(0, 'ORDINARY_USER', 'project-members', TRUE, 'BOOTSTRAP', '普通成员可查看自己已加入项目的成员与项目角色。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, 'PROJECT_OWNER', 'project-members', TRUE, 'BOOTSTRAP', '兼容项目负责人身份查看和管理负责项目成员。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, 'OPERATOR', 'project-members', TRUE, 'BOOTSTRAP', '运营人员可按租户范围只读排查项目成员关系。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, 'AUDITOR', 'project-members', TRUE, 'BOOTSTRAP', '审计人员可按租户范围只读查看成员授权事实。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, 'TENANT_ADMINISTRATOR', 'project-members', TRUE, 'BOOTSTRAP', '租户管理员可管理本租户项目成员。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, 'PLATFORM_ADMINISTRATOR', 'project-members', TRUE, 'BOOTSTRAP', '平台管理员可跨租户管理项目成员。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (tenant_id, role_code, menu_code) DO UPDATE
SET enabled = EXCLUDED.enabled,
    note = EXCLUDED.note,
    update_time = CURRENT_TIMESTAMP;

-- These route allows only open the HTTP entry. PermissionProjectMembershipService still enforces tenant,
-- current membership, project OWNER and target-role boundaries for every read and mutation.
INSERT INTO permission_route_policy
(tenant_id, policy_name, role_code, http_method, path_pattern, resource_type, action, effect, priority, enabled,
 description, create_time, update_time)
VALUES
(0, '普通用户查看所属项目成员', 'ORDINARY_USER', 'GET', '/api/permission/project-memberships/**', 'PROJECT_MEMBERSHIP', 'VIEW', 'ALLOW', 975, TRUE,
 '只允许读取本人已加入项目的成员列表，服务层按 permission_project_membership 再次收口。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '普通项目Owner调整项目成员', 'ORDINARY_USER', 'PUT', '/api/permission/project-memberships/*', 'PROJECT_MEMBERSHIP', 'UPDATE', 'ALLOW', 980, TRUE,
 '只有数据库中拥有目标项目 OWNER 成员关系的普通身份才能调整非 OWNER 角色。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '普通项目Owner启用项目成员', 'ORDINARY_USER', 'POST', '/api/permission/project-memberships/*/enable', 'PROJECT_MEMBERSHIP', 'ENABLE', 'ALLOW', 980, TRUE,
 '只有目标项目 OWNER 可启用成员关系，不能借此授予 OWNER。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '普通项目Owner禁用项目成员', 'ORDINARY_USER', 'POST', '/api/permission/project-memberships/*/disable', 'PROJECT_MEMBERSHIP', 'DISABLE', 'ALLOW', 980, TRUE,
 '只有目标项目 OWNER 可禁用成员关系，服务层保留审计记录。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '项目负责人管理项目成员', 'PROJECT_OWNER', 'ANY', '/api/permission/project-memberships/**', 'PROJECT_MEMBERSHIP', 'MANAGE', 'ALLOW', 980, TRUE,
 '兼容角色仍必须拥有目标项目 OWNER 成员事实。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '运营人员查看项目成员', 'OPERATOR', 'GET', '/api/permission/project-memberships/**', 'PROJECT_MEMBERSHIP', 'VIEW', 'ALLOW', 970, TRUE,
 '运营人员只读查看本租户成员关系用于排障。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '审计人员查看项目成员', 'AUDITOR', 'GET', '/api/permission/project-memberships/**', 'PROJECT_MEMBERSHIP', 'VIEW', 'ALLOW', 970, TRUE,
 '审计人员只读查看本租户成员授权事实。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '租户管理员管理项目成员', 'TENANT_ADMINISTRATOR', 'ANY', '/api/permission/project-memberships/**', 'PROJECT_MEMBERSHIP', 'MANAGE', 'ALLOW', 990, TRUE,
 '租户管理员仅能管理本租户项目成员。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '平台管理员管理项目成员', 'PLATFORM_ADMINISTRATOR', 'ANY', '/api/permission/project-memberships/**', 'PROJECT_MEMBERSHIP', 'MANAGE', 'ALLOW', 1000, TRUE,
 '平台管理员可跨租户管理项目成员并保留审计。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT DO NOTHING;

