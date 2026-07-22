-- Agent 同步恢复公开路由策略。
--
-- 这些 ALLOW 规则只负责网关第一层角色准入。data-sync 与 datasource-management
-- 仍会按 tenant/project/owner/grant 做数据范围检查；所有变更工具还必须经过 Agent
-- Runtime 的审批、摘要绑定、审计和同一 Run 输出引用校验。

DELETE FROM permission_route_policy
WHERE path_pattern IN (
    '/api/sync/sync-tasks/*/agent-diagnosis',
    '/api/sync/sync-tasks/*/errors/quarantine/preview',
    '/api/sync/sync-tasks/*/errors/quarantine/apply',
    '/api/sync/sync-tasks/*/agent-recovery-cases',
    '/api/datasource/datasources/*/schema-repair-plans/preview',
    '/api/datasource/datasources/*/schema-repair-plans/apply'
);

INSERT INTO permission_route_policy
(tenant_id, policy_name, role_code, http_method, path_pattern, resource_type, action,
 effect, priority, enabled, description, create_time, update_time)
SELECT 0,
       role_code || ' 查看同步执行诊断',
       role_code,
       'GET',
       '/api/sync/sync-tasks/*/agent-diagnosis',
       'SYNC_TASK',
       'VIEW',
       'ALLOW',
       priority,
       TRUE,
       '仅返回当前授权项目内的低敏执行根因、计数、修复动作编码和案例引用。',
       CURRENT_TIMESTAMP,
       CURRENT_TIMESTAMP
FROM (VALUES
    ('ORDINARY_USER', 140),
    ('PROJECT_OWNER', 160),
    ('OPERATOR', 780),
    ('TENANT_ADMINISTRATOR', 820),
    ('PLATFORM_ADMINISTRATOR', 900)
) AS roles(role_code, priority);

INSERT INTO permission_route_policy
(tenant_id, policy_name, role_code, http_method, path_pattern, resource_type, action,
 effect, priority, enabled, description, create_time, update_time)
SELECT 0,
       role_code || ' 预览同步坏行隔离',
       role_code,
       'POST',
       '/api/sync/sync-tasks/*/errors/quarantine/preview',
       'SYNC_TASK',
       'VIEW',
       'ALLOW',
       priority,
       TRUE,
       '只生成精确坏行隔离范围和确认摘要，不删除源数据、不改变任务状态。',
       CURRENT_TIMESTAMP,
       CURRENT_TIMESTAMP
FROM (VALUES
    ('ORDINARY_USER', 140),
    ('PROJECT_OWNER', 160),
    ('OPERATOR', 780),
    ('TENANT_ADMINISTRATOR', 820),
    ('PLATFORM_ADMINISTRATOR', 900)
) AS roles(role_code, priority);

INSERT INTO permission_route_policy
(tenant_id, policy_name, role_code, http_method, path_pattern, resource_type, action,
 effect, priority, enabled, description, create_time, update_time)
SELECT 0,
       role_code || ' 应用同步坏行隔离',
       role_code,
       'POST',
       '/api/sync/sync-tasks/*/errors/quarantine/apply',
       'SYNC_TASK',
       'REPLAY_DIRTY_RECORDS',
       'ALLOW',
       priority,
       TRUE,
       '只允许应用同一 Agent Run 中经用户确认的摘要绑定预览，源端记录不会被物理删除。',
       CURRENT_TIMESTAMP,
       CURRENT_TIMESTAMP
FROM (VALUES
    ('ORDINARY_USER', 145),
    ('PROJECT_OWNER', 165),
    ('OPERATOR', 785),
    ('TENANT_ADMINISTRATOR', 825),
    ('PLATFORM_ADMINISTRATOR', 905)
) AS roles(role_code, priority);

INSERT INTO permission_route_policy
(tenant_id, policy_name, role_code, http_method, path_pattern, resource_type, action,
 effect, priority, enabled, description, create_time, update_time)
SELECT 0,
       role_code || ' 发布已验证同步恢复案例',
       role_code,
       'POST',
       '/api/sync/sync-tasks/*/agent-recovery-cases',
       'SYNC_TASK',
       'CREATE',
       'ALLOW',
       priority,
       TRUE,
       '仅当原失败执行和恢复后零失败成功执行可验证时，发布项目级低敏案例。',
       CURRENT_TIMESTAMP,
       CURRENT_TIMESTAMP
FROM (VALUES
    ('ORDINARY_USER', 140),
    ('PROJECT_OWNER', 160),
    ('OPERATOR', 780),
    ('TENANT_ADMINISTRATOR', 820),
    ('PLATFORM_ADMINISTRATOR', 900)
) AS roles(role_code, priority);

INSERT INTO permission_route_policy
(tenant_id, policy_name, role_code, http_method, path_pattern, resource_type, action,
 effect, priority, enabled, description, create_time, update_time)
SELECT 0,
       role_code || ' 预览数据源结构修复',
       role_code,
       'POST',
       '/api/datasource/datasources/*/schema-repair-plans/preview',
       'DATASOURCE',
       'UPDATE',
       'ALLOW',
       priority,
       TRUE,
       '调用方还必须是数据源 owner 或获得 MANAGE 授权；预览不执行 DDL。',
       CURRENT_TIMESTAMP,
       CURRENT_TIMESTAMP
FROM (VALUES
    ('ORDINARY_USER', 145),
    ('PROJECT_OWNER', 165),
    ('OPERATOR', 785),
    ('TENANT_ADMINISTRATOR', 825),
    ('PLATFORM_ADMINISTRATOR', 905)
) AS roles(role_code, priority);

INSERT INTO permission_route_policy
(tenant_id, policy_name, role_code, http_method, path_pattern, resource_type, action,
 effect, priority, enabled, description, create_time, update_time)
SELECT 0,
       role_code || ' 应用数据源白名单结构修复',
       role_code,
       'POST',
       '/api/datasource/datasources/*/schema-repair-plans/apply',
       'DATASOURCE',
       'UPDATE',
       'ALLOW',
       priority,
       TRUE,
       '调用方还必须具备数据源 MANAGE 授权，并通过 Agent 摘要确认；危险 DDL 不属于该工具。',
       CURRENT_TIMESTAMP,
       CURRENT_TIMESTAMP
FROM (VALUES
    ('ORDINARY_USER', 150),
    ('PROJECT_OWNER', 170),
    ('OPERATOR', 790),
    ('TENANT_ADMINISTRATOR', 830),
    ('PLATFORM_ADMINISTRATOR', 910)
) AS roles(role_code, priority);
