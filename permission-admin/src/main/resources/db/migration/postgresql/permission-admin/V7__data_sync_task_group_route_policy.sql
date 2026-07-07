-- permission-admin：data-sync 任务分组路由策略。
--
-- 背景：
-- 1. 任务分组会影响运营视图、批量导出、Agent 批量编排、告警聚合和后续组级调度；
-- 2. 分组列表是只读低敏动作，可以开放给具备任务可见性的角色；
-- 3. 移组是写管理动作，必须审计，审计员不能执行，服务账号也不能通过人工管理入口替用户改分组。

SET search_path TO permission_admin, public;

INSERT INTO permission_route_policy
(tenant_id, policy_name, role_code, http_method, path_pattern, resource_type, action, effect, priority, enabled, description, create_time, update_time)
VALUES
(0, '普通用户查看自己的同步任务分组', 'ORDINARY_USER', 'GET', '/api/sync/sync-tasks/groups', 'SYNC_TASK', 'LIST_GROUPS', 'ALLOW', 127, TRUE, '普通用户可查看 SELF 数据范围内的同步任务分组汇总；data-sync 服务层仍会按 ownerId 收口。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '普通用户调整自己的同步任务分组', 'ORDINARY_USER', 'POST', '/api/sync/sync-tasks/*/group', 'SYNC_TASK', 'UPDATE_GROUP', 'ALLOW', 127, TRUE, '普通用户可调整自有同步任务分组，用于个人任务整理、导入导出和 Agent 编排确认。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),

(0, '项目负责人查看项目同步任务分组', 'PROJECT_OWNER', 'GET', '/api/sync/sync-tasks/groups', 'SYNC_TASK', 'LIST_GROUPS', 'ALLOW', 148, TRUE, '项目负责人可查看授权项目范围内的同步任务分组汇总。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '项目负责人调整项目同步任务分组', 'PROJECT_OWNER', 'POST', '/api/sync/sync-tasks/*/group', 'SYNC_TASK', 'UPDATE_GROUP', 'ALLOW', 148, TRUE, '项目负责人可调整授权项目内同步任务分组，用于业务域治理、迁移批次管理和组级运营。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),

(0, '运营人员查看同步任务分组', 'OPERATOR', 'GET', '/api/sync/sync-tasks/groups', 'SYNC_TASK', 'LIST_GROUPS', 'ALLOW', 784, TRUE, '运营人员可查看租户内同步任务分组汇总，用于故障定位、容量治理和批量运营。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '运营人员调整同步任务分组', 'OPERATOR', 'POST', '/api/sync/sync-tasks/*/group', 'SYNC_TASK', 'UPDATE_GROUP', 'ALLOW', 784, TRUE, '运营人员可调整租户内同步任务分组，用于事故复盘、客户迁移批次管理和任务整理。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),

(0, '租户管理员查看同步任务分组', 'TENANT_ADMINISTRATOR', 'GET', '/api/sync/sync-tasks/groups', 'SYNC_TASK', 'LIST_GROUPS', 'ALLOW', 764, TRUE, '租户管理员可查看本租户同步任务分组汇总。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '租户管理员调整同步任务分组', 'TENANT_ADMINISTRATOR', 'POST', '/api/sync/sync-tasks/*/group', 'SYNC_TASK', 'UPDATE_GROUP', 'ALLOW', 764, TRUE, '租户管理员可调整本租户同步任务分组。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),

(0, '审计员查看同步任务分组', 'AUDITOR', 'GET', '/api/sync/sync-tasks/groups', 'SYNC_TASK', 'LIST_GROUPS', 'ALLOW', 118, TRUE, '审计员可只读查看同步任务分组汇总，用于复核任务组织和批量操作范围。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '审计员禁止调整同步任务分组', 'AUDITOR', 'POST', '/api/sync/sync-tasks/*/group', 'SYNC_TASK', 'UPDATE_GROUP', 'DENY', 900, TRUE, '审计员只能复核分组证据，不能修改任务分组。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),

(0, '服务账号禁止人工调整同步任务分组', 'SERVICE_ACCOUNT', 'POST', '/api/sync/sync-tasks/*/group', 'SYNC_TASK', 'UPDATE_GROUP', 'DENY', 910, TRUE, '服务账号不应调用人工分组管理入口；机器身份如需批量整理任务，应走受控内部协议和委托证据。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT DO NOTHING;
