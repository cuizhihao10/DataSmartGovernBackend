-- Agent 任务导入制品由浏览器上传，但后续 dry-run、修复与提交必须继续经过
-- Durable Agent 工具链、权限确认和审计事件。这里因此只开放最小上传入口，
-- 不把普通用户的 POST 权限扩大到整个 /api/sync/**。
DELETE FROM permission_route_policy
WHERE role_code = 'ORDINARY_USER'
  AND http_method = 'POST'
  AND path_pattern = '/api/sync/sync-task-import-artifacts/upload';

INSERT INTO permission_route_policy
(tenant_id, policy_name, role_code, http_method, path_pattern, resource_type, action_code,
 effect, priority, enabled, description, created_at, updated_at)
VALUES
(0, '普通用户上传自己的同步任务导入制品', 'ORDINARY_USER', 'POST',
 '/api/sync/sync-task-import-artifacts/upload', 'SYNC_TASK', 'CREATE', 'ALLOW', 140, TRUE,
 '普通用户可在当前已授权项目中上传 CSV/XLSX 任务制品；服务层按租户、项目和 owner 隔离，后续试运行、修复与提交仍必须经过受控 Agent 工具链。',
 CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
