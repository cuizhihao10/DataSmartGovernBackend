-- permission-admin 兼容策略：data-sync 脏数据修复重放路由
--
-- 该脚本服务 MySQL 兼容环境。PostgreSQL 主路径见 permission-admin Flyway V3。

INSERT IGNORE INTO permission_route_policy
(tenant_id, policy_name, role_code, http_method, path_pattern, resource_type, action, effect, priority, enabled, description, create_time, update_time)
VALUES
(0, '普通用户修复重放自己的脏数据样本', 'ORDINARY_USER', 'POST', '/api/sync/sync-tasks/*/errors/replay', 'SYNC_TASK', 'REPLAY_DIRTY_RECORDS', 'ALLOW', 126, 1, '普通用户可在 SELF 数据范围内对 retryable=true 的错误样本发起修复重放；data-sync 服务层仍要求 repairConfirmed=true 并校验任务和 execution 归属。', NOW(), NOW()),
(0, '项目负责人修复重放项目脏数据样本', 'PROJECT_OWNER', 'POST', '/api/sync/sync-tasks/*/errors/replay', 'SYNC_TASK', 'REPLAY_DIRTY_RECORDS', 'ALLOW', 146, 1, '项目负责人可在授权项目内发起脏数据修复重放，用于目标约束修复、字段映射修复或重复主键治理后的受控重放。', NOW(), NOW()),
(0, '运营人员修复重放同步脏数据样本', 'OPERATOR', 'POST', '/api/sync/sync-tasks/*/errors/replay', 'SYNC_TASK', 'REPLAY_DIRTY_RECORDS', 'ALLOW', 781, 1, '运营人员可在事故恢复和客户修复场景创建脏数据修复重放计划，所有动作进入 data-sync 审计。', NOW(), NOW()),
(0, '租户管理员修复重放同步脏数据样本', 'TENANT_ADMINISTRATOR', 'POST', '/api/sync/sync-tasks/*/errors/replay', 'SYNC_TASK', 'REPLAY_DIRTY_RECORDS', 'ALLOW', 761, 1, '租户管理员可在本租户范围内创建脏数据修复重放计划，适合租户级数据修复治理。', NOW(), NOW()),
(0, '服务账号禁止人工修复重放脏数据样本', 'SERVICE_ACCOUNT', 'POST', '/api/sync/sync-tasks/*/errors/replay', 'SYNC_TASK', 'REPLAY_DIRTY_RECORDS', 'DENY', 901, 1, '服务账号默认不能调用人工修复重放入口；worker 应通过受控 execution 租约和恢复计划消费链路运行。', NOW(), NOW());
