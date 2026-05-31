-- DataSmart Govern - Agent 异步工具入箱权限策略补充
--
-- 背景说明：
-- 1. selected-node outbox enqueue 是 4.66 引入的推荐生产入口：调用方必须先 dry-run，再携带服务端指纹确认
--    某一批明确选中的 ready 异步节点进入 outbox。
-- 2. run-level enqueue 仍然保留，但它粒度更粗，可能一次推进整个 Run 中所有可入箱异步工具，更适合管理员补偿、
--    联调或事故恢复，不应该被普通会话权限隐式放行。
-- 3. 本迁移只补 permission-admin 的策略事实，不改变 agent-runtime 的执行逻辑；真正执行前仍需要
--    Agent Runtime 重新 dry-run、校验 selectionFingerprint、检查批量上限和 outbox 幂等键。

INSERT IGNORE INTO permission_route_policy
(tenant_id, policy_name, role_code, http_method, path_pattern, resource_type, action, effect, priority, enabled, description, create_time, update_time)
VALUES
(0, '服务账号确认 DAG 选中节点异步入箱', 'SERVICE_ACCOUNT', 'POST',
 '/api/agent/sessions/*/runs/*/tool-executions/dag-selected-node-outbox/enqueue',
 'AI_RUNTIME', 'ENQUEUE_SELECTED_ASYNC_TOOL', 'ALLOW', 860, 1,
 '允许受信 Agent Runtime 服务账号在代表用户完成 dry-run 指纹复核后，把明确选中的异步工具节点写入命令 outbox；该策略不代表服务账号拥有任意工具执行权限，仍需下游工具二次校验和数据范围约束。',
 NOW(), NOW()),
(0, '项目负责人确认 DAG 选中节点异步入箱', 'PROJECT_OWNER', 'POST',
 '/api/agent/sessions/*/runs/*/tool-executions/dag-selected-node-outbox/enqueue',
 'AI_RUNTIME', 'ENQUEUE_SELECTED_ASYNC_TOOL', 'ALLOW', 146, 1,
 '项目负责人可在负责项目范围内确认已 dry-run 的异步工具节点入箱；后续应结合项目数据范围、审批单和租户配额继续收口。',
 NOW(), NOW()),
(0, '运营人员确认 DAG 选中节点异步入箱', 'OPERATOR', 'POST',
 '/api/agent/sessions/*/runs/*/tool-executions/dag-selected-node-outbox/enqueue',
 'AI_RUNTIME', 'ENQUEUE_SELECTED_ASYNC_TOOL', 'ALLOW', 142, 1,
 '运营人员可在排障和受控执行场景中确认 selected-node 入箱，但仍必须依赖 dry-run 指纹、批量上限和审计记录，避免绕过用户确认链路。',
 NOW(), NOW()),
(0, '普通用户禁止直接批量入箱整个 Agent Run', 'ORDINARY_USER', 'POST',
 '/api/agent/sessions/*/runs/*/async-task-commands/outbox/enqueue',
 'AI_RUNTIME', 'ENQUEUE_RUN_ASYNC_TOOLS', 'DENY', 900, 1,
 'Run 级异步工具入箱粒度过粗，普通用户必须走 selected-node 确认入口，避免一次性推进未逐项确认的后台工具动作。',
 NOW(), NOW()),
(0, '服务账号禁止粗粒度 Run 级异步入箱默认放行', 'SERVICE_ACCOUNT', 'POST',
 '/api/agent/sessions/*/runs/*/async-task-commands/outbox/enqueue',
 'AI_RUNTIME', 'ENQUEUE_RUN_ASYNC_TOOLS', 'DENY', 900, 1,
 '服务账号默认不应使用 Run 级粗粒度入箱入口；如确需内部补偿，应通过更高优先级、更窄范围的临时策略或审批流程显式授权。',
 NOW(), NOW()),
(0, '平台管理员 Run 级异步入箱补偿入口', 'PLATFORM_ADMINISTRATOR', 'POST',
 '/api/agent/sessions/*/runs/*/async-task-commands/outbox/enqueue',
 'AI_RUNTIME', 'ENQUEUE_RUN_ASYNC_TOOLS', 'ALLOW', 910, 1,
 '平台管理员可在事故恢复或联调场景使用 Run 级异步入箱补偿入口；生产操作应配合审计、变更单、租户配额和回滚预案。',
 NOW(), NOW());
