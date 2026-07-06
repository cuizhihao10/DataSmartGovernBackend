-- permission-admin：data-sync 脏数据修复重放路由策略
--
-- 背景：
-- 1. GET /api/sync/sync-tasks/{taskId}/errors 只是查看错误样本，属于 VIEW；
-- 2. POST /api/sync/sync-tasks/{taskId}/errors/replay 会创建新的 replay execution 和 recovery plan，
--    属于有副作用的恢复动作；
-- 3. 该动作需要独立于普通 REPLAY_TASK 管控，便于后续接入按钮权限、审批流、审计报表和高风险操作复核。

INSERT INTO permission_route_policy
(tenant_id, policy_name, role_code, http_method, path_pattern, resource_type, action, effect, priority, enabled, description, create_time, update_time)
VALUES
(0, '普通用户修复重放自己的脏数据样本', 'ORDINARY_USER', 'POST', '/api/sync/sync-tasks/*/errors/replay', 'SYNC_TASK', 'REPLAY_DIRTY_RECORDS', 'ALLOW', 126, TRUE, '普通用户可在 SELF 数据范围内对 retryable=true 的错误样本发起修复重放；data-sync 服务层仍要求 repairConfirmed=true 并校验任务和 execution 归属。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '项目负责人修复重放项目脏数据样本', 'PROJECT_OWNER', 'POST', '/api/sync/sync-tasks/*/errors/replay', 'SYNC_TASK', 'REPLAY_DIRTY_RECORDS', 'ALLOW', 146, TRUE, '项目负责人可在授权项目内发起脏数据修复重放，用于目标约束修复、字段映射修复或重复主键治理后的受控重放。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '运营人员修复重放同步脏数据样本', 'OPERATOR', 'POST', '/api/sync/sync-tasks/*/errors/replay', 'SYNC_TASK', 'REPLAY_DIRTY_RECORDS', 'ALLOW', 781, TRUE, '运营人员可在事故恢复和客户修复场景创建脏数据修复重放计划，所有动作进入 data-sync 审计。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '租户管理员修复重放同步脏数据样本', 'TENANT_ADMINISTRATOR', 'POST', '/api/sync/sync-tasks/*/errors/replay', 'SYNC_TASK', 'REPLAY_DIRTY_RECORDS', 'ALLOW', 761, TRUE, '租户管理员可在本租户范围内创建脏数据修复重放计划，适合租户级数据修复治理。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '服务账号禁止人工修复重放脏数据样本', 'SERVICE_ACCOUNT', 'POST', '/api/sync/sync-tasks/*/errors/replay', 'SYNC_TASK', 'REPLAY_DIRTY_RECORDS', 'DENY', 901, TRUE, '服务账号默认不能调用人工修复重放入口；worker 应通过受控 execution 租约和恢复计划消费链路运行。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT DO NOTHING;
