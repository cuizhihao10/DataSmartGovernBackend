-- datasource-management 数据源实例级授权接口路由策略。
--
-- 背景说明：
-- 1. `/api/datasource/**` 这类粗粒度策略适合“查看/创建/编辑数据源主记录”，但不适合表达
--    “把某条数据源授权给某个用户、角色或服务账号”这种更高风险的治理动作。
-- 2. gateway 已经把 `/api/datasource/datasources/{id}/authorizations` 映射为
--    `resourceType=DATASOURCE`、`action=ASSIGN`；permission-admin 必须有对应策略，
--    否则普通 GET 策略可能无法准确描述授权关系管理，前端按钮也会因为权限判定不完整而不可用。
-- 3. 本迁移只控制“谁能进入授权管理接口”。某个被授权主体是否真的能使用具体数据源，
--    仍由 datasource-management 的 `datasource_authorization` 实例级 ACL 账本判断。
SET search_path TO permission_admin, public;

INSERT INTO permission_route_policy
(tenant_id, policy_name, role_code, http_method, path_pattern, resource_type, action, effect, priority, enabled, description, create_time, update_time)
VALUES
(0, '项目负责人管理数据源实例授权清单', 'PROJECT_OWNER', 'ANY', '/api/datasource/datasources/*/authorizations', 'DATASOURCE', 'ASSIGN', 'ALLOW', 760, TRUE,
 '项目负责人可在项目范围内查看并维护数据源实例授权清单；具体项目范围仍由数据范围策略和下游服务二次校验。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '项目负责人撤销数据源实例授权', 'PROJECT_OWNER', 'ANY', '/api/datasource/datasources/*/authorizations/*', 'DATASOURCE', 'ASSIGN', 'ALLOW', 760, TRUE,
 '项目负责人可撤销项目范围内数据源实例授权；撤销保留审计事实，不做物理删除。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '租户管理员管理数据源实例授权清单', 'TENANT_ADMINISTRATOR', 'ANY', '/api/datasource/datasources/*/authorizations', 'DATASOURCE', 'ASSIGN', 'ALLOW', 780, TRUE,
 '租户管理员可在本租户范围内治理数据源实例授权，用于人员离岗、临时排障和跨项目协作授权管理。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '租户管理员撤销数据源实例授权', 'TENANT_ADMINISTRATOR', 'ANY', '/api/datasource/datasources/*/authorizations/*', 'DATASOURCE', 'ASSIGN', 'ALLOW', 780, TRUE,
 '租户管理员可撤销本租户范围内数据源实例授权，防止历史授权长期残留。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '平台管理员管理数据源实例授权清单', 'PLATFORM_ADMINISTRATOR', 'ANY', '/api/datasource/datasources/*/authorizations', 'DATASOURCE', 'ASSIGN', 'ALLOW', 900, TRUE,
 '平台管理员可跨租户排障和治理数据源实例授权；生产环境仍建议结合审计和高风险操作确认。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '平台管理员撤销数据源实例授权', 'PLATFORM_ADMINISTRATOR', 'ANY', '/api/datasource/datasources/*/authorizations/*', 'DATASOURCE', 'ASSIGN', 'ALLOW', 900, TRUE,
 '平台管理员可撤销跨租户数据源实例授权，用于安全响应、误授权修复和客户环境运维。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT DO NOTHING;
