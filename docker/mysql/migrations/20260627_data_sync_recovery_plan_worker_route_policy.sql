-- data-sync：同步恢复计划 worker 消费路由策略
--
-- 背景：
-- 1. replay/backfill 控制面已经可以创建恢复计划，但 worker 真正读取和消费计划必须走机器协议；
-- 2. 恢复计划 claim/consume 会推进 plan_state，属于执行面状态变更，不应开放给普通用户、项目负责人、运营人员或租户管理员；
-- 3. SERVICE_ACCOUNT 只能拿到低敏恢复坐标，data-sync 服务层仍会校验 HMAC、executorId、RUNNING 状态和计划范围一致性。

UPDATE permission_route_policy
SET action = CASE
    WHEN action IS NOT NULL THEN action
    WHEN http_method = 'POST' AND path_pattern LIKE '/api/sync/sync-executions/%/recovery-plan/claim' THEN 'CLAIM_RECOVERY_PLAN'
    WHEN http_method = 'POST' AND path_pattern LIKE '/api/sync/sync-executions/%/recovery-plan/consume' THEN 'CONSUME_RECOVERY_PLAN'
    ELSE action
END
WHERE action IS NULL
  AND http_method = 'POST'
  AND path_pattern LIKE '/api/sync/sync-executions/%/recovery-plan/%';

INSERT IGNORE INTO permission_route_policy
(tenant_id, policy_name, role_code, http_method, path_pattern, resource_type, action, effect, priority, enabled, description, create_time, update_time)
VALUES
(0, '服务账号认领同步恢复计划', 'SERVICE_ACCOUNT', 'POST', '/api/sync/sync-executions/*/recovery-plan/claim', 'SYNC_EXECUTION', 'CLAIM_RECOVERY_PLAN', 'ALLOW', 805, 1, '服务账号可在持有 execution 租约后读取 replay/backfill 的低敏恢复计划。', NOW(), NOW()),
(0, '服务账号消费同步恢复计划', 'SERVICE_ACCOUNT', 'POST', '/api/sync/sync-executions/*/recovery-plan/consume', 'SYNC_EXECUTION', 'CONSUME_RECOVERY_PLAN', 'ALLOW', 805, 1, '服务账号可确认 worker 已把恢复计划加载为本地执行上下文，后续仍需走 checkpoint/complete/fail 回调。', NOW(), NOW()),
(0, '普通用户禁止伪造同步恢复计划消费', 'ORDINARY_USER', 'POST', '/api/sync/sync-executions/*/recovery-plan/**', 'SYNC_EXECUTION', NULL, 'DENY', 820, 1, '恢复计划 claim/consume 是 worker 机器协议，普通用户不能直接调用。', NOW(), NOW()),
(0, '项目负责人禁止伪造同步恢复计划消费', 'PROJECT_OWNER', 'POST', '/api/sync/sync-executions/*/recovery-plan/**', 'SYNC_EXECUTION', NULL, 'DENY', 820, 1, '项目负责人可发起 replay/backfill，但不能伪造 worker 读取或消费恢复计划。', NOW(), NOW()),
(0, '运营人员禁止伪造同步恢复计划消费', 'OPERATOR', 'POST', '/api/sync/sync-executions/*/recovery-plan/**', 'SYNC_EXECUTION', NULL, 'DENY', 820, 1, '运营人员可管理恢复动作，但 worker 执行协议必须由服务账号完成。', NOW(), NOW()),
(0, '租户管理员禁止伪造同步恢复计划消费', 'TENANT_ADMINISTRATOR', 'POST', '/api/sync/sync-executions/*/recovery-plan/**', 'SYNC_EXECUTION', NULL, 'DENY', 820, 1, '租户管理员即使拥有同步管理权限，也不能用人类身份推进 worker 恢复计划状态。', NOW(), NOW()),
(0, '审计员禁止伪造同步恢复计划消费', 'AUDITOR', 'POST', '/api/sync/sync-executions/*/recovery-plan/**', 'SYNC_EXECUTION', NULL, 'DENY', 820, 1, '审计员只能查看证据，不能调用 worker 机器协议改变恢复计划状态。', NOW(), NOW());
