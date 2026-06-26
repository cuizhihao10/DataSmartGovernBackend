-- data-sync：同步回放与补数路由策略
--
-- 背景：
-- 1. replay/backfill 已作为独立 API 暴露，不能继续被 POST /sync-tasks/** 的 CREATE 或 RUN 语义隐式覆盖；
-- 2. 回放和补数都可能影响大量历史数据，权限矩阵、按钮权限、审计报表和后续审批流必须能按动作区分；
-- 3. 服务账号默认不开放这些人工恢复按钮，避免 worker 或内部系统绕过人类责任链直接补数。

UPDATE permission_route_policy
SET action = CASE
    WHEN action IS NOT NULL THEN action
    WHEN http_method = 'POST' AND path_pattern LIKE '%/replay' THEN 'REPLAY'
    WHEN http_method = 'POST' AND path_pattern LIKE '%/backfill' THEN 'BACKFILL'
    ELSE action
END
WHERE action IS NULL
  AND http_method = 'POST'
  AND (path_pattern LIKE '%/replay' OR path_pattern LIKE '%/backfill');

INSERT IGNORE INTO permission_route_policy
(tenant_id, policy_name, role_code, http_method, path_pattern, resource_type, action, effect, priority, enabled, description, create_time, update_time)
VALUES
(0, '普通用户回放自己的同步任务', 'ORDINARY_USER', 'POST', '/api/sync/sync-tasks/*/replay', 'SYNC_TASK', 'REPLAY', 'ALLOW', 124, 1, '普通用户可在自己数据范围内从历史 execution 或 checkpoint 发起回放；data-sync 服务层仍会校验任务归属、来源执行记录和低敏恢复计划。', NOW(), NOW()),
(0, '普通用户补数自己的同步任务', 'ORDINARY_USER', 'POST', '/api/sync/sync-tasks/*/backfill', 'SYNC_TASK', 'BACKFILL', 'ALLOW', 124, 1, '普通用户可为自己数据范围内任务提交低敏补数窗口；大规模补数、批量补数和跨项目补数后续应接审批或管理员入口。', NOW(), NOW()),
(0, '项目负责人回放项目同步任务', 'PROJECT_OWNER', 'POST', '/api/sync/sync-tasks/*/replay', 'SYNC_TASK', 'REPLAY', 'ALLOW', 144, 1, '项目负责人可在授权项目内发起同步回放，用于失败恢复、下游重建或错误写入修复；恢复计划只保存低敏来源坐标。', NOW(), NOW()),
(0, '项目负责人补数项目同步任务', 'PROJECT_OWNER', 'POST', '/api/sync/sync-tasks/*/backfill', 'SYNC_TASK', 'BACKFILL', 'ALLOW', 144, 1, '项目负责人可在授权项目内提交历史补数窗口；后续可按补数规模接入审批、容量预估和执行窗口策略。', NOW(), NOW()),
(0, '运营人员回放同步任务', 'OPERATOR', 'POST', '/api/sync/sync-tasks/*/replay', 'SYNC_TASK', 'REPLAY', 'ALLOW', 779, 1, '运营人员可在事故恢复、客户修复和下游重建场景发起同步回放；所有动作进入 data-sync 审计。', NOW(), NOW()),
(0, '运营人员补数同步任务', 'OPERATOR', 'POST', '/api/sync/sync-tasks/*/backfill', 'SYNC_TASK', 'BACKFILL', 'ALLOW', 779, 1, '运营人员可提交租户内低敏补数窗口，用于历史缺口修复、晚到数据补齐和分区级重刷。', NOW(), NOW()),
(0, '租户管理员回放同步任务', 'TENANT_ADMINISTRATOR', 'POST', '/api/sync/sync-tasks/*/replay', 'SYNC_TASK', 'REPLAY', 'ALLOW', 759, 1, '租户管理员可在本租户范围内发起同步回放，适合租户级恢复和客户支持场景。', NOW(), NOW()),
(0, '租户管理员补数同步任务', 'TENANT_ADMINISTRATOR', 'POST', '/api/sync/sync-tasks/*/backfill', 'SYNC_TASK', 'BACKFILL', 'ALLOW', 759, 1, '租户管理员可在本租户范围内提交补数窗口；超大规模补数后续应结合容量与审批策略。', NOW(), NOW()),
(0, '服务账号禁止人工回放同步任务', 'SERVICE_ACCOUNT', 'POST', '/api/sync/sync-tasks/*/replay', 'SYNC_TASK', 'REPLAY', 'DENY', 900, 1, '服务账号默认不能调用人工回放入口；worker 应通过受控 execution 租约和恢复计划消费链路运行。', NOW(), NOW()),
(0, '服务账号禁止人工补数同步任务', 'SERVICE_ACCOUNT', 'POST', '/api/sync/sync-tasks/*/backfill', 'SYNC_TASK', 'BACKFILL', 'DENY', 900, 1, '服务账号默认不能调用人工补数入口，避免机器身份绕过确认、审计和后续审批流程。', NOW(), NOW());
