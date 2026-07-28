-- Sync configuration is now an inseparable task definition addressed by taskId.
DELETE FROM permission_route_policy
WHERE path_pattern LIKE '/api/sync/sync-templates%'
   OR resource_type = 'SYNC_TEMPLATE';

DELETE FROM permission_data_scope_policy
WHERE resource_type = 'SYNC_TEMPLATE';

UPDATE permission_menu
SET description = '数据同步任务、执行记录、人工介入和事故处理入口。',
    update_time = CURRENT_TIMESTAMP
WHERE menu_code = 'data-sync';
