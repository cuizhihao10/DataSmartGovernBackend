-- Expose task-level precheck without leaking the internal sync template identifier.
SET search_path TO permission_admin, public;

DELETE FROM permission_route_policy
WHERE path_pattern = '/api/sync/sync-tasks/*/precheck'
  AND http_method = 'POST';

INSERT INTO permission_route_policy
(tenant_id, policy_name, role_code, http_method, path_pattern, resource_type, action,
 effect, priority, enabled, description, create_time, update_time)
SELECT 0,
       role_code || ' 执行同步任务预检查',
       role_code,
       'POST',
       '/api/sync/sync-tasks/*/precheck',
       'SYNC_TASK',
       'PRECHECK',
       'ALLOW',
       priority,
       TRUE,
       '仅允许预检查当前可见任务；data-sync 继续按租户、项目和任务数据范围校验，并在内部解析关联定义。',
       CURRENT_TIMESTAMP,
       CURRENT_TIMESTAMP
FROM (VALUES
    ('ORDINARY_USER', 150),
    ('PROJECT_OWNER', 170),
    ('OPERATOR', 790),
    ('TENANT_ADMINISTRATOR', 830),
    ('PLATFORM_ADMINISTRATOR', 910)
) AS roles(role_code, priority);
