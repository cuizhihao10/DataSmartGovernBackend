-- permission-admin：data-sync 内部 worker-loop / task-scheduler 机器协议策略
--
-- 背景：
-- 1. 真实平台 E2E 不应该只验证 gateway rewrite，也必须证明请求进入 permission-admin 授权判定；
-- 2. data-sync 的 `/internal/sync-workers/run-once` 与 `/internal/sync-task-schedulers/dispatch-due`
--    都是有副作用的机器协议入口，不能被项目负责人、租户管理员或普通用户通过 `/api/sync/**`
--    的宽泛管理权限误调用；
-- 3. 因此这里用“更具体路径 + 更高优先级 DENY/ALLOW”把内部入口收口给 SERVICE_ACCOUNT，
--    同时保留 PLATFORM_ADMINISTRATOR 的全局兜底能力用于极端排障。

INSERT INTO permission_route_policy
(tenant_id, policy_name, role_code, http_method, path_pattern, resource_type, action, effect, priority, enabled, description, create_time, update_time)
VALUES
(0, '服务账号运行同步 worker-loop', 'SERVICE_ACCOUNT', 'POST', '/api/sync/internal/sync-workers/run-once', 'SYNC_EXECUTION', 'CLAIM', 'ALLOW', 805, TRUE, '服务账号可调用受控 worker-loop 单次执行入口；data-sync 服务层仍会校验租约、幂等、执行器身份和任务状态。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '普通用户禁止运行同步 worker-loop', 'ORDINARY_USER', 'POST', '/api/sync/internal/sync-workers/run-once', 'SYNC_EXECUTION', 'CLAIM', 'DENY', 830, TRUE, 'worker-loop 是机器协议，普通用户不能直接认领或推进 execution。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '项目负责人禁止运行同步 worker-loop', 'PROJECT_OWNER', 'POST', '/api/sync/internal/sync-workers/run-once', 'SYNC_EXECUTION', 'CLAIM', 'DENY', 830, TRUE, '项目负责人可以创建和运行自己的同步任务，但不能伪造执行器协议推进 execution。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '运营人员禁止直接运行同步 worker-loop', 'OPERATOR', 'POST', '/api/sync/internal/sync-workers/run-once', 'SYNC_EXECUTION', 'CLAIM', 'DENY', 830, TRUE, '运营人员应通过恢复、重试或运维审批入口处理任务，不直接调用机器 worker-loop。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '审计员禁止运行同步 worker-loop', 'AUDITOR', 'POST', '/api/sync/internal/sync-workers/run-once', 'SYNC_EXECUTION', 'CLAIM', 'DENY', 830, TRUE, '审计员只能查看证据，不能触发真实数据搬运。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '租户管理员禁止运行同步 worker-loop', 'TENANT_ADMINISTRATOR', 'POST', '/api/sync/internal/sync-workers/run-once', 'SYNC_EXECUTION', 'CLAIM', 'DENY', 830, TRUE, '租户管理员可以管理租户同步任务，但不能直接伪造机器执行协议。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '服务账号派发到期同步任务', 'SERVICE_ACCOUNT', 'POST', '/api/sync/internal/sync-task-schedulers/dispatch-due', 'SYNC_TASK', 'SCHEDULE_DISPATCH', 'ALLOW', 805, TRUE, '服务账号可调用任务级 scheduler 到期派发入口，把 SCHEDULED 任务转换为受控 execution。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '普通用户禁止派发到期同步任务', 'ORDINARY_USER', 'POST', '/api/sync/internal/sync-task-schedulers/dispatch-due', 'SYNC_TASK', 'SCHEDULE_DISPATCH', 'DENY', 830, TRUE, '任务调度派发属于后台调度器协议，普通用户不能人为推进调度游标。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '项目负责人禁止派发到期同步任务', 'PROJECT_OWNER', 'POST', '/api/sync/internal/sync-task-schedulers/dispatch-due', 'SYNC_TASK', 'SCHEDULE_DISPATCH', 'DENY', 830, TRUE, '项目负责人可以配置定时任务，但不能直接调用后台派发协议。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '运营人员禁止直接派发到期同步任务', 'OPERATOR', 'POST', '/api/sync/internal/sync-task-schedulers/dispatch-due', 'SYNC_TASK', 'SCHEDULE_DISPATCH', 'DENY', 830, TRUE, '运营人员应通过调度控制台或审批后的运维动作处理，不直接调用内部派发协议。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '审计员禁止派发到期同步任务', 'AUDITOR', 'POST', '/api/sync/internal/sync-task-schedulers/dispatch-due', 'SYNC_TASK', 'SCHEDULE_DISPATCH', 'DENY', 830, TRUE, '审计员不能触发后台调度写入。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '租户管理员禁止派发到期同步任务', 'TENANT_ADMINISTRATOR', 'POST', '/api/sync/internal/sync-task-schedulers/dispatch-due', 'SYNC_TASK', 'SCHEDULE_DISPATCH', 'DENY', 830, TRUE, '租户管理员不能直接推进后台调度游标，避免绕过计划窗口和容量控制。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT DO NOTHING;

INSERT INTO permission_data_scope_policy
(tenant_id, role_code, resource_type, scope_level, scope_expression, approval_required, enabled, description, create_time, update_time)
VALUES
(0, 'SERVICE_ACCOUNT', 'SYNC_EXECUTION', 'TENANT', 'tenant_id = ${tenantId}', FALSE, TRUE, '服务账号执行同步 worker 协议时默认限制在当前租户范围，避免机器身份跨租户认领 execution。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT DO NOTHING;

INSERT INTO permission_project_membership
(tenant_id, actor_id, project_id, workspace_id, project_role, grant_source, enabled, create_time, update_time)
VALUES
(10, 1001, 101, 301, 'OWNER', 'LOCAL_E2E_SEED', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (tenant_id, actor_id, project_id) DO UPDATE
SET workspace_id = EXCLUDED.workspace_id,
    project_role = EXCLUDED.project_role,
    grant_source = EXCLUDED.grant_source,
    enabled = TRUE,
    update_time = CURRENT_TIMESTAMP;
