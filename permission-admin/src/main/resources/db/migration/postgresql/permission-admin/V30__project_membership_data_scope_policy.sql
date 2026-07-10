-- Give the project member console an explicit data boundary.
-- Route policies only decide whether an entry point may be called; these policies tell gateway which selected
-- project context may be propagated and let the service layer apply the final membership and management checks.
SET search_path TO permission_admin, public;

INSERT INTO permission_data_scope_policy
(tenant_id, role_code, resource_type, scope_level, scope_expression, approval_required, enabled, description,
 create_time, update_time)
VALUES
(0, 'ORDINARY_USER', 'PROJECT_MEMBERSHIP', 'PROJECT', 'project_id IN ${actorProjectIds}', FALSE, TRUE,
 '普通用户只能查看自己已加入项目的成员关系；项目 OWNER 的写操作继续由服务层校验。',
 CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, 'PROJECT_OWNER', 'PROJECT_MEMBERSHIP', 'PROJECT', 'project_id IN ${actorProjectIds}', FALSE, TRUE,
 '项目负责人只能查看和管理本人负责项目的成员关系。',
 CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, 'OPERATOR', 'PROJECT_MEMBERSHIP', 'TENANT', 'tenant_id = ${tenantId}', FALSE, TRUE,
 '运营人员按租户范围只读查看项目成员关系。',
 CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, 'AUDITOR', 'PROJECT_MEMBERSHIP', 'TENANT', 'tenant_id = ${tenantId}', FALSE, TRUE,
 '审计人员按租户范围只读查看项目成员授权事实。',
 CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, 'TENANT_ADMINISTRATOR', 'PROJECT_MEMBERSHIP', 'TENANT', 'tenant_id = ${tenantId}', FALSE, TRUE,
 '租户管理员管理本租户项目成员关系。',
 CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, 'PLATFORM_ADMINISTRATOR', 'PROJECT_MEMBERSHIP', 'PLATFORM', '1 = 1', FALSE, TRUE,
 '平台管理员可跨租户管理项目成员关系。',
 CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (tenant_id, role_code, resource_type) DO UPDATE
SET scope_level = EXCLUDED.scope_level,
    scope_expression = EXCLUDED.scope_expression,
    approval_required = EXCLUDED.approval_required,
    enabled = EXCLUDED.enabled,
    description = EXCLUDED.description,
    update_time = CURRENT_TIMESTAMP;
