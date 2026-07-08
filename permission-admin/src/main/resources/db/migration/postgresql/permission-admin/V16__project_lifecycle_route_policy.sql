-- permission-admin：项目生命周期管理路由策略。
--
-- 设计说明：
-- 1. V15 已经让项目可以被创建和查询，本迁移继续补齐项目编辑、启用、禁用、归档和删除的权限语义；
-- 2. 删除项目在当前实现中是“归档式删除”，并不是物理删除 permission_project 行；
-- 3. 项目管理动作会影响下游 datasource-management、data-sync、data-quality 与 Agent 上下文的可用性，
--    因此只开放给 PROJECT_OWNER、TENANT_ADMINISTRATOR 和 PLATFORM_ADMINISTRATOR 这类管理角色；
-- 4. 普通用户、运营人员、审计人员可以查询项目用于切换或排障，但不能修改生命周期；
-- 5. 这里仍只描述路由/动作策略，具体“PROJECT_OWNER 是否真的拥有该项目 OWNER 成员关系”由 Service 层二次校验。
SET search_path TO permission_admin, public;

INSERT INTO permission_route_policy
(tenant_id, policy_name, role_code, http_method, path_pattern, resource_type, action, effect, priority, enabled, description, create_time, update_time)
VALUES
(0, '项目负责人更新项目', 'PROJECT_OWNER', 'PUT', '/api/permission/projects/**', 'PROJECT', 'UPDATE', 'ALLOW', 760, TRUE,
 '项目负责人可以更新自己拥有 OWNER 成员关系的项目基础资料；Service 层会继续校验成员关系，不能跨项目编辑。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '租户管理员更新项目', 'TENANT_ADMINISTRATOR', 'PUT', '/api/permission/projects/**', 'PROJECT', 'UPDATE', 'ALLOW', 760, TRUE,
 '租户管理员可以更新本租户项目基础资料。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '项目负责人管理项目生命周期', 'PROJECT_OWNER', 'POST', '/api/permission/projects/**', 'PROJECT', 'MANAGE_LIFECYCLE', 'ALLOW', 760, TRUE,
 '项目负责人可以启用、禁用或归档自己拥有 OWNER 成员关系的项目；归档前必须通过资源占用检查。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '租户管理员管理项目生命周期', 'TENANT_ADMINISTRATOR', 'POST', '/api/permission/projects/**', 'PROJECT', 'MANAGE_LIFECYCLE', 'ALLOW', 760, TRUE,
 '租户管理员可以启用、禁用或归档本租户项目；归档前必须通过资源占用检查。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '项目负责人删除项目', 'PROJECT_OWNER', 'DELETE', '/api/permission/projects/**', 'PROJECT', 'DELETE', 'ALLOW', 760, TRUE,
 '项目负责人可以对自己拥有 OWNER 成员关系且已清空下游资源占用的项目执行归档式删除。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '租户管理员删除项目', 'TENANT_ADMINISTRATOR', 'DELETE', '/api/permission/projects/**', 'PROJECT', 'DELETE', 'ALLOW', 760, TRUE,
 '租户管理员可以对本租户已清空下游资源占用的项目执行归档式删除。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '普通用户禁止更新项目', 'ORDINARY_USER', 'PUT', '/api/permission/projects/**', 'PROJECT', 'UPDATE', 'DENY', 860, TRUE,
 '普通用户只允许查询自己被授权的项目，不能更新项目基础资料。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '运营人员禁止更新项目', 'OPERATOR', 'PUT', '/api/permission/projects/**', 'PROJECT', 'UPDATE', 'DENY', 860, TRUE,
 '运营人员默认只读项目上下文，用于排障和运营，不直接修改项目主数据。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '审计员禁止更新项目', 'AUDITOR', 'PUT', '/api/permission/projects/**', 'PROJECT', 'UPDATE', 'DENY', 860, TRUE,
 '审计员只能复核项目事实和审计证据，不能修改项目主数据。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '普通用户禁止管理项目生命周期', 'ORDINARY_USER', 'POST', '/api/permission/projects/**', 'PROJECT', 'MANAGE_LIFECYCLE', 'DENY', 860, TRUE,
 '普通用户不能启用、禁用或归档项目。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '运营人员禁止管理项目生命周期', 'OPERATOR', 'POST', '/api/permission/projects/**', 'PROJECT', 'MANAGE_LIFECYCLE', 'DENY', 860, TRUE,
 '运营人员不能启用、禁用或归档项目，避免排障角色扩大项目边界。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '审计员禁止管理项目生命周期', 'AUDITOR', 'POST', '/api/permission/projects/**', 'PROJECT', 'MANAGE_LIFECYCLE', 'DENY', 860, TRUE,
 '审计员不能启用、禁用或归档项目。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '普通用户禁止删除项目', 'ORDINARY_USER', 'DELETE', '/api/permission/projects/**', 'PROJECT', 'DELETE', 'DENY', 860, TRUE,
 '普通用户不能删除项目。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '运营人员禁止删除项目', 'OPERATOR', 'DELETE', '/api/permission/projects/**', 'PROJECT', 'DELETE', 'DENY', 860, TRUE,
 '运营人员不能删除项目。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '审计员禁止删除项目', 'AUDITOR', 'DELETE', '/api/permission/projects/**', 'PROJECT', 'DELETE', 'DENY', 860, TRUE,
 '审计员不能删除项目。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT DO NOTHING;
