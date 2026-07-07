-- permission-admin MySQL 兼容策略：data-sync 任务分组路由。
--
-- PostgreSQL 目标迁移见：
-- permission-admin/src/main/resources/db/migration/postgresql/permission-admin/V7__data_sync_task_group_route_policy.sql

INSERT IGNORE INTO permission_route_policy
(tenant_id, policy_name, role_code, http_method, path_pattern, resource_type, action, effect, priority, enabled, description, create_time, update_time)
VALUES
(0, '普通用户查看自己的同步任务分组', 'ORDINARY_USER', 'GET', '/api/sync/sync-tasks/groups', 'SYNC_TASK', 'LIST_GROUPS', 'ALLOW', 127, 1, '普通用户可查看 SELF 数据范围内的同步任务分组汇总；data-sync 服务层仍会按 ownerId 收口。', NOW(), NOW()),
(0, '普通用户调整自己的同步任务分组', 'ORDINARY_USER', 'POST', '/api/sync/sync-tasks/*/group', 'SYNC_TASK', 'UPDATE_GROUP', 'ALLOW', 127, 1, '普通用户可调整自有同步任务分组，用于个人任务整理、导入导出和 Agent 编排确认。', NOW(), NOW()),

(0, '项目负责人查看项目同步任务分组', 'PROJECT_OWNER', 'GET', '/api/sync/sync-tasks/groups', 'SYNC_TASK', 'LIST_GROUPS', 'ALLOW', 148, 1, '项目负责人可查看授权项目范围内的同步任务分组汇总。', NOW(), NOW()),
(0, '项目负责人调整项目同步任务分组', 'PROJECT_OWNER', 'POST', '/api/sync/sync-tasks/*/group', 'SYNC_TASK', 'UPDATE_GROUP', 'ALLOW', 148, 1, '项目负责人可调整授权项目内同步任务分组，用于业务域治理、迁移批次管理和组级运营。', NOW(), NOW()),

(0, '运营人员查看同步任务分组', 'OPERATOR', 'GET', '/api/sync/sync-tasks/groups', 'SYNC_TASK', 'LIST_GROUPS', 'ALLOW', 784, 1, '运营人员可查看租户内同步任务分组汇总，用于故障定位、容量治理和批量运营。', NOW(), NOW()),
(0, '运营人员调整同步任务分组', 'OPERATOR', 'POST', '/api/sync/sync-tasks/*/group', 'SYNC_TASK', 'UPDATE_GROUP', 'ALLOW', 784, 1, '运营人员可调整租户内同步任务分组，用于事故复盘、客户迁移批次管理和任务整理。', NOW(), NOW()),

(0, '租户管理员查看同步任务分组', 'TENANT_ADMINISTRATOR', 'GET', '/api/sync/sync-tasks/groups', 'SYNC_TASK', 'LIST_GROUPS', 'ALLOW', 764, 1, '租户管理员可查看本租户同步任务分组汇总。', NOW(), NOW()),
(0, '租户管理员调整同步任务分组', 'TENANT_ADMINISTRATOR', 'POST', '/api/sync/sync-tasks/*/group', 'SYNC_TASK', 'UPDATE_GROUP', 'ALLOW', 764, 1, '租户管理员可调整本租户同步任务分组。', NOW(), NOW()),

(0, '审计员查看同步任务分组', 'AUDITOR', 'GET', '/api/sync/sync-tasks/groups', 'SYNC_TASK', 'LIST_GROUPS', 'ALLOW', 118, 1, '审计员可只读查看同步任务分组汇总，用于复核任务组织和批量操作范围。', NOW(), NOW()),
(0, '审计员禁止调整同步任务分组', 'AUDITOR', 'POST', '/api/sync/sync-tasks/*/group', 'SYNC_TASK', 'UPDATE_GROUP', 'DENY', 900, 1, '审计员只能复核分组证据，不能修改任务分组。', NOW(), NOW()),

(0, '服务账号禁止人工调整同步任务分组', 'SERVICE_ACCOUNT', 'POST', '/api/sync/sync-tasks/*/group', 'SYNC_TASK', 'UPDATE_GROUP', 'DENY', 910, 1, '服务账号不应调用人工分组管理入口；机器身份如需批量整理任务，应走受控内部协议和委托证据。', NOW(), NOW());
