-- permission-admin: allow authenticated users to discover low-sensitive project names before applying to join.
--
-- The endpoint returns only active project ID/code/name/type. Service-layer tenant checks remain mandatory and are
-- the final protection against cross-company project-directory disclosure.
SET search_path TO permission_admin, public;

INSERT INTO permission_route_policy
(tenant_id, policy_name, role_code, http_method, path_pattern, resource_type, action, effect, priority, enabled,
 description, create_time, update_time)
VALUES
(0, '普通用户查询本租户可加入项目名称', 'ORDINARY_USER', 'GET',
 '/api/permission/project-join-requests/candidates', 'PROJECT_JOIN_REQUEST', 'DISCOVER_PROJECT', 'ALLOW', 765, TRUE,
 '仅返回本租户启用项目的低敏 ID、编码、名称和类型，用于按名称提交加入申请。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '运营人员查询本租户可加入项目名称', 'OPERATOR', 'GET',
 '/api/permission/project-join-requests/candidates', 'PROJECT_JOIN_REQUEST', 'DISCOVER_PROJECT', 'ALLOW', 765, TRUE,
 '运营人员可以发现本租户项目名称，是否能够提交申请仍由申请接口策略独立判定。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '审计员查询本租户可加入项目名称', 'AUDITOR', 'GET',
 '/api/permission/project-join-requests/candidates', 'PROJECT_JOIN_REQUEST', 'DISCOVER_PROJECT', 'ALLOW', 765, TRUE,
 '审计身份只读取低敏项目目录，不获得项目业务数据范围。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '项目负责人查询本租户可加入项目名称', 'PROJECT_OWNER', 'GET',
 '/api/permission/project-join-requests/candidates', 'PROJECT_JOIN_REQUEST', 'DISCOVER_PROJECT', 'ALLOW', 785, TRUE,
 '项目负责人查询本租户低敏项目目录，服务层仍执行租户隔离。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '租户管理员查询本租户可加入项目名称', 'TENANT_ADMINISTRATOR', 'GET',
 '/api/permission/project-join-requests/candidates', 'PROJECT_JOIN_REQUEST', 'DISCOVER_PROJECT', 'ALLOW', 805, TRUE,
 '租户管理员查询本租户低敏项目目录。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '平台管理员查询项目名称目录', 'PLATFORM_ADMINISTRATOR', 'GET',
 '/api/permission/project-join-requests/candidates', 'PROJECT_JOIN_REQUEST', 'DISCOVER_PROJECT', 'ALLOW', 905, TRUE,
 '平台管理员可指定目标租户查询低敏项目目录；未指定时使用当前有效租户上下文。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT DO NOTHING;
