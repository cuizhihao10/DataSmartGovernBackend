-- Broad project membership management routes cover list, detail and mutation operations.
-- Keep resource type matching, but let the service layer distinguish VIEW/UPDATE/ENABLE/DISABLE semantics and
-- enforce project OWNER, tenant administrator and platform administrator boundaries for the concrete record.
SET search_path TO permission_admin, public;

UPDATE permission_route_policy
SET action = NULL,
    description = CASE role_code
        WHEN 'PROJECT_OWNER' THEN '兼容项目负责人角色可进入成员管理入口；服务层仍要求目标项目 OWNER 成员事实。'
        WHEN 'TENANT_ADMINISTRATOR' THEN '租户管理员可进入本租户项目成员管理入口；服务层继续校验租户边界。'
        WHEN 'PLATFORM_ADMINISTRATOR' THEN '平台管理员可进入跨租户项目成员管理入口并保留审计。'
        ELSE description
    END,
    update_time = CURRENT_TIMESTAMP
WHERE role_code IN ('PROJECT_OWNER', 'TENANT_ADMINISTRATOR', 'PLATFORM_ADMINISTRATOR')
  AND path_pattern = '/api/permission/project-memberships/**'
  AND resource_type = 'PROJECT_MEMBERSHIP'
  AND effect = 'ALLOW';
