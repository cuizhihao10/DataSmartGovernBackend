-- permission-admin MySQL 兼容策略：data-sync 任务编辑、发布和回收站路由。
--
-- PostgreSQL 目标迁移见：
-- permission-admin/src/main/resources/db/migration/postgresql/permission-admin/V8__data_sync_task_edit_publish_route_policy.sql

INSERT IGNORE INTO permission_route_policy
(tenant_id, policy_name, role_code, http_method, path_pattern, resource_type, action, effect, priority, enabled, description, create_time, update_time)
VALUES
(0, '普通用户查看自己的同步任务回收站', 'ORDINARY_USER', 'GET', '/api/sync/sync-tasks/recycle-bin', 'SYNC_TASK', 'VIEW_RECYCLE_BIN', 'ALLOW', 128, 1, '普通用户可查看 SELF 数据范围内已进入回收站的同步任务，用于误删复核、克隆或申请彻底删除。', NOW(), NOW()),
(0, '普通用户编辑自己的同步任务定义', 'ORDINARY_USER', 'PUT', '/api/sync/sync-tasks/*', 'SYNC_TASK', 'UPDATE', 'ALLOW', 128, 1, '普通用户可编辑自有同步任务定义；服务层会禁止活跃执行态编辑，并在调度配置变更时退回 DRAFT。', NOW(), NOW()),
(0, '普通用户发布自己的同步任务定义', 'ORDINARY_USER', 'POST', '/api/sync/sync-tasks/*/publish', 'SYNC_TASK', 'PUBLISH', 'ALLOW', 128, 1, '普通用户可发布自有低风险同步任务；高风险任务仍必须由可信角色提交审批确认事实。', NOW(), NOW()),

(0, '项目负责人查看项目同步任务回收站', 'PROJECT_OWNER', 'GET', '/api/sync/sync-tasks/recycle-bin', 'SYNC_TASK', 'VIEW_RECYCLE_BIN', 'ALLOW', 149, 1, '项目负责人可查看授权项目范围内回收站任务。', NOW(), NOW()),
(0, '项目负责人编辑项目同步任务定义', 'PROJECT_OWNER', 'PUT', '/api/sync/sync-tasks/*', 'SYNC_TASK', 'UPDATE', 'ALLOW', 149, 1, '项目负责人可编辑授权项目内同步任务定义，用于调度窗口、负责人和运营分组调整。', NOW(), NOW()),
(0, '项目负责人发布项目同步任务定义', 'PROJECT_OWNER', 'POST', '/api/sync/sync-tasks/*/publish', 'SYNC_TASK', 'PUBLISH', 'ALLOW', 149, 1, '项目负责人可发布授权项目内同步任务；服务层仍会执行预检、审批和调度闸门。', NOW(), NOW()),

(0, '运营人员查看同步任务回收站', 'OPERATOR', 'GET', '/api/sync/sync-tasks/recycle-bin', 'SYNC_TASK', 'VIEW_RECYCLE_BIN', 'ALLOW', 785, 1, '运营人员可查看租户内回收站任务，用于误删恢复建议、审计复核和客户支持。', NOW(), NOW()),
(0, '运营人员编辑同步任务定义', 'OPERATOR', 'PUT', '/api/sync/sync-tasks/*', 'SYNC_TASK', 'UPDATE', 'ALLOW', 785, 1, '运营人员可编辑租户内同步任务定义，用于故障修复、维护窗口调整和任务治理。', NOW(), NOW()),
(0, '运营人员发布同步任务定义', 'OPERATOR', 'POST', '/api/sync/sync-tasks/*/publish', 'SYNC_TASK', 'PUBLISH', 'ALLOW', 785, 1, '运营人员可发布同步任务，并可作为可信角色提交审批确认事实。', NOW(), NOW()),

(0, '租户管理员查看同步任务回收站', 'TENANT_ADMINISTRATOR', 'GET', '/api/sync/sync-tasks/recycle-bin', 'SYNC_TASK', 'VIEW_RECYCLE_BIN', 'ALLOW', 765, 1, '租户管理员可查看本租户回收站任务。', NOW(), NOW()),
(0, '租户管理员编辑同步任务定义', 'TENANT_ADMINISTRATOR', 'PUT', '/api/sync/sync-tasks/*', 'SYNC_TASK', 'UPDATE', 'ALLOW', 765, 1, '租户管理员可编辑本租户同步任务定义。', NOW(), NOW()),
(0, '租户管理员发布同步任务定义', 'TENANT_ADMINISTRATOR', 'POST', '/api/sync/sync-tasks/*/publish', 'SYNC_TASK', 'PUBLISH', 'ALLOW', 765, 1, '租户管理员可发布本租户同步任务，并可提交审批确认事实。', NOW(), NOW()),

(0, '审计员查看同步任务回收站', 'AUDITOR', 'GET', '/api/sync/sync-tasks/recycle-bin', 'SYNC_TASK', 'VIEW_RECYCLE_BIN', 'ALLOW', 119, 1, '审计员可只读查看回收站任务，用于复核删除链路和配置来源。', NOW(), NOW()),
(0, '审计员禁止编辑同步任务定义', 'AUDITOR', 'PUT', '/api/sync/sync-tasks/*', 'SYNC_TASK', 'UPDATE', 'DENY', 900, 1, '审计员只能查看和复核，不能修改任务定义。', NOW(), NOW()),
(0, '审计员禁止发布同步任务定义', 'AUDITOR', 'POST', '/api/sync/sync-tasks/*/publish', 'SYNC_TASK', 'PUBLISH', 'DENY', 900, 1, '审计员不能把任务从编辑态推进到可执行或等待调度状态。', NOW(), NOW()),

(0, '服务账号禁止人工编辑同步任务定义', 'SERVICE_ACCOUNT', 'PUT', '/api/sync/sync-tasks/*', 'SYNC_TASK', 'UPDATE', 'DENY', 910, 1, '服务账号不应调用人工任务编辑入口；机器身份如果需要修复配置，应走受控内部补偿协议并携带委托证据。', NOW(), NOW()),
(0, '服务账号禁止人工发布同步任务定义', 'SERVICE_ACCOUNT', 'POST', '/api/sync/sync-tasks/*/publish', 'SYNC_TASK', 'PUBLISH', 'DENY', 910, 1, '服务账号不应替代人类完成任务发布，避免绕过审批、容量评估和上线窗口确认。', NOW(), NOW());
