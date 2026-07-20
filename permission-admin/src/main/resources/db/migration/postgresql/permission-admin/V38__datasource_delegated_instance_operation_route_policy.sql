-- DataSmart Govern Backend
-- Author: Cui
-- Date: 2026-07-21
-- Description: 允许普通项目成员进入已授权数据源的实例级使用和管理入口。
--
-- 安全边界：
-- 1. 本迁移只解决 gateway 的“角色 + 路由动作”准入，不直接授予任意数据源访问权；
-- 2. datasource-management 会按 datasourceId 再查询 datasource_authorization，只有具备
--    USE/MANAGE 的有效授权、资源 owner 或项目管理者才能真正执行；
-- 3. 因此不能把整个 /api/datasource/** 的 POST 都放开，只允许连接测试、元数据发现、
--    只读 SQL、编辑连接测试、启用和禁用这些明确的实例端点；
-- 4. 数据源删除仍不在委托 MANAGE 权限中，避免被授权协作者删除 owner 的主资源。
SET search_path TO permission_admin, public;

INSERT INTO permission_route_policy
    (tenant_id, policy_name, role_code, http_method, path_pattern, resource_type, action,
     effect, priority, enabled, description, create_time, update_time)
VALUES
    (0, '普通用户测试已授权数据源连接', 'ORDINARY_USER', 'POST',
     '/api/datasource/datasources/*/test', 'DATASOURCE', 'EXECUTE',
     'ALLOW', 180, TRUE,
     '普通用户可请求测试已授权数据源；datasource-management 必须继续校验该 datasourceId 的 USE 权限。',
     LOCALTIMESTAMP, LOCALTIMESTAMP),
    (0, '普通用户发现已授权数据源元数据', 'ORDINARY_USER', 'POST',
     '/api/datasource/datasources/*/metadata/discover', 'DATASOURCE', 'EXECUTE',
     'ALLOW', 180, TRUE,
     '普通用户可发现已授权连接的低敏结构元数据；datasource-management 必须继续校验实例 USE 权限。',
     LOCALTIMESTAMP, LOCALTIMESTAMP),
    (0, '普通用户执行已授权数据源只读查询', 'ORDINARY_USER', 'POST',
     '/api/datasource/datasources/*/sql/read-only/execute', 'DATASOURCE', 'EXECUTE',
     'ALLOW', 180, TRUE,
     '普通用户可在实例 USE 授权范围内执行受控只读 SQL；下游仍应用 SQL 白名单、限额和低敏审计。',
     LOCALTIMESTAMP, LOCALTIMESTAMP),
    (0, '普通用户编辑被授予管理权的数据源', 'ORDINARY_USER', 'PUT',
     '/api/datasource/datasources/*', 'DATASOURCE', 'UPDATE',
     'ALLOW', 180, TRUE,
     '普通用户只有在 datasource_authorization 明确包含 MANAGE 时才能编辑该数据源，路由准入不替代实例鉴权。',
     LOCALTIMESTAMP, LOCALTIMESTAMP),
    (0, '普通用户测试被授予管理权的数据源草稿连接', 'ORDINARY_USER', 'POST',
     '/api/datasource/datasources/*/connection-test', 'DATASOURCE', 'UPDATE',
     'ALLOW', 180, TRUE,
     '普通用户编辑已授权数据源时可测试未保存连接参数；下游必须继续校验实例 MANAGE 权限。',
     LOCALTIMESTAMP, LOCALTIMESTAMP),
    (0, '普通用户启用被授予管理权的数据源', 'ORDINARY_USER', 'POST',
     '/api/datasource/datasources/*/enable', 'DATASOURCE', 'ENABLE',
     'ALLOW', 180, TRUE,
     '普通用户只有在实例 MANAGE 授权有效时才能启用数据源；状态变更继续记录审计。',
     LOCALTIMESTAMP, LOCALTIMESTAMP),
    (0, '普通用户禁用被授予管理权的数据源', 'ORDINARY_USER', 'POST',
     '/api/datasource/datasources/*/disable', 'DATASOURCE', 'DISABLE',
     'ALLOW', 180, TRUE,
     '普通用户只有在实例 MANAGE 授权有效时才能禁用数据源；禁用后任务选源和执行入口会继续阻断。',
     LOCALTIMESTAMP, LOCALTIMESTAMP)
ON CONFLICT DO NOTHING;
