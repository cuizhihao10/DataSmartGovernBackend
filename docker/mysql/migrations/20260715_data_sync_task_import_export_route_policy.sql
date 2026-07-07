-- permission-admin MySQL 兼容策略：data-sync 任务定义导入/导出路由。
--
-- PostgreSQL 目标迁移见：
-- permission-admin/src/main/resources/db/migration/postgresql/permission-admin/V9__data_sync_task_import_export_route_policy.sql

INSERT IGNORE INTO permission_route_policy
(tenant_id, policy_name, role_code, http_method, path_pattern, resource_type, action, effect, priority, enabled, description, create_time, update_time)
VALUES
(0, '普通用户导出自己的同步任务定义', 'ORDINARY_USER', 'GET', '/api/sync/sync-tasks/export', 'SYNC_TASK', 'EXPORT', 'ALLOW', 129, 1, '普通用户可导出 SELF 数据范围内的低敏同步任务定义，用于个人备份、迁移或 Agent 复核。', NOW(), NOW()),
(0, '普通用户导入自己的同步任务定义', 'ORDINARY_USER', 'POST', '/api/sync/sync-tasks/import', 'SYNC_TASK', 'IMPORT', 'ALLOW', 129, 1, '普通用户可导入自有范围内同步任务定义；服务层会校验模板可见性、唯一键冲突、调度配置和立即执行安全边界。', NOW(), NOW()),

(0, '项目负责人导出项目同步任务定义', 'PROJECT_OWNER', 'GET', '/api/sync/sync-tasks/export', 'SYNC_TASK', 'EXPORT', 'ALLOW', 150, 1, '项目负责人可导出授权项目内低敏同步任务定义，用于项目迁移、交接和批量审查。', NOW(), NOW()),
(0, '项目负责人导入项目同步任务定义', 'PROJECT_OWNER', 'POST', '/api/sync/sync-tasks/import', 'SYNC_TASK', 'IMPORT', 'ALLOW', 150, 1, '项目负责人可导入授权项目内同步任务定义，导入后默认 DRAFT 或按选项立即执行。', NOW(), NOW()),

(0, '运营人员导出同步任务定义', 'OPERATOR', 'GET', '/api/sync/sync-tasks/export', 'SYNC_TASK', 'EXPORT', 'ALLOW', 786, 1, '运营人员可导出租户内低敏同步任务定义，用于客户支持、故障复盘和批量治理。', NOW(), NOW()),
(0, '运营人员导入同步任务定义', 'OPERATOR', 'POST', '/api/sync/sync-tasks/import', 'SYNC_TASK', 'IMPORT', 'ALLOW', 786, 1, '运营人员可导入租户内同步任务定义，用于迁移实施、批量恢复和受控上线。', NOW(), NOW()),

(0, '租户管理员导出同步任务定义', 'TENANT_ADMINISTRATOR', 'GET', '/api/sync/sync-tasks/export', 'SYNC_TASK', 'EXPORT', 'ALLOW', 766, 1, '租户管理员可导出本租户低敏同步任务定义。', NOW(), NOW()),
(0, '租户管理员导入同步任务定义', 'TENANT_ADMINISTRATOR', 'POST', '/api/sync/sync-tasks/import', 'SYNC_TASK', 'IMPORT', 'ALLOW', 766, 1, '租户管理员可导入本租户同步任务定义。', NOW(), NOW()),

(0, '审计员导出同步任务定义', 'AUDITOR', 'GET', '/api/sync/sync-tasks/export', 'SYNC_TASK', 'EXPORT', 'ALLOW', 120, 1, '审计员可导出低敏任务定义证据，用于复核任务配置和变更范围。', NOW(), NOW()),
(0, '审计员禁止导入同步任务定义', 'AUDITOR', 'POST', '/api/sync/sync-tasks/import', 'SYNC_TASK', 'IMPORT', 'DENY', 900, 1, '审计员只能复核，不能批量创建、发布或立即执行同步任务。', NOW(), NOW()),

(0, '服务账号禁止人工导出同步任务定义', 'SERVICE_ACCOUNT', 'GET', '/api/sync/sync-tasks/export', 'SYNC_TASK', 'EXPORT', 'DENY', 910, 1, '服务账号不应调用人工导出入口；机器导出如有需要应走受控内部证据归档协议。', NOW(), NOW()),
(0, '服务账号禁止人工导入同步任务定义', 'SERVICE_ACCOUNT', 'POST', '/api/sync/sync-tasks/import', 'SYNC_TASK', 'IMPORT', 'DENY', 910, 1, '服务账号不应调用人工导入入口，避免绕过审批、容量评估和用户确认。', NOW(), NOW());
