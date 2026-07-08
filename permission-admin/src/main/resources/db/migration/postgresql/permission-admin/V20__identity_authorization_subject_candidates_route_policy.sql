-- DataSmart Govern - 授权主体候选查询路由策略。
--
-- 设计目的：
-- 1. 数据源、同步任务、质量规则、Agent 工具等资源都需要“授权给某个用户/角色”的管理弹窗。
--    如果前端只能手工输入 actorId/roleCode，真实使用时很容易出现误授权、重复授权和排障困难。
-- 2. 账号注册、禁用、重置仍是高风险身份生命周期管理动作，只允许租户管理员/平台管理员执行；
--    本脚本只开放 /api/identity/authorization-subjects 的 GET + VIEW，只返回低敏候选信息，不开放账号管理写操作。
-- 3. V2 中已有 PROJECT_OWNER 对 /api/identity/** 的显式 DENY，用于禁止项目负责人管理登录账号。
--    因此这里必须用更精确路径和更高 priority 覆盖，让项目负责人能查询自己项目成员候选，但不能访问其他 identity 管理接口。
SET search_path TO permission_admin, public;

INSERT INTO permission_route_policy
(tenant_id, policy_name, role_code, http_method, path_pattern, resource_type, action, effect, priority, enabled, description, create_time, update_time)
VALUES
(0, '项目负责人查看授权主体候选', 'PROJECT_OWNER', 'GET', '/api/identity/authorization-subjects', 'IDENTITY_USER', 'VIEW', 'ALLOW', 860, TRUE,
 '允许项目负责人查询自己已授权项目内的低敏用户/角色候选，用于数据源、同步任务等资源授权弹窗；不允许账号创建、禁用或重置。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '审计员查看授权主体候选', 'AUDITOR', 'GET', '/api/identity/authorization-subjects', 'IDENTITY_USER', 'VIEW', 'ALLOW', 860, TRUE,
 '允许审计员只读查看授权主体候选来源，便于复盘授权链路；不返回密码、token、Keycloak secret 或完整邮箱。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '租户管理员查看授权主体候选', 'TENANT_ADMINISTRATOR', 'GET', '/api/identity/authorization-subjects', 'IDENTITY_USER', 'VIEW', 'ALLOW', 860, TRUE,
 '允许租户管理员查询本租户低敏用户/角色候选，用于资源授权、人员离岗回收和权限排障。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '平台管理员查看授权主体候选', 'PLATFORM_ADMINISTRATOR', 'GET', '/api/identity/authorization-subjects', 'IDENTITY_USER', 'VIEW', 'ALLOW', 1000, TRUE,
 '允许平台管理员跨租户查询低敏授权主体候选，用于客户环境排障、安全响应和权限治理。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT DO NOTHING;
