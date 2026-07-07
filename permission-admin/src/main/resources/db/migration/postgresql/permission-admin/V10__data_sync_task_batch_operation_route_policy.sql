-- permission-admin：data-sync 任务批量运营路由策略。
--
-- 背景：
-- 1. 批量导入/导出、批量下线、批量回收和批量彻底删除都不是普通 CRUD。
--    它们会一次性影响多个任务定义，因此必须有独立 action，方便授权、审计和事故复盘。
-- 2. 批量删除仍遵守“下线 -> 回收站 -> 逻辑彻底删除”的产品治理链路。
--    普通用户可以批量下线和移入回收站，但不能批量彻底删除。
-- 3. 审计员只允许批量导出低敏任务定义证据，不能导入、调度、下线或删除。
-- 4. 服务账号不应调用这些人工运营入口；机器调度和 worker 执行应继续走 internal scheduler/worker-loop 协议。

SET search_path TO permission_admin, public;

INSERT INTO permission_route_policy
(tenant_id, policy_name, role_code, http_method, path_pattern, resource_type, action, effect, priority, enabled, description, create_time, update_time)
VALUES
(0, '普通用户批量导出自己的同步任务定义', 'ORDINARY_USER', 'POST', '/api/sync/sync-tasks/batch/export', 'SYNC_TASK', 'BATCH_EXPORT', 'ALLOW', 130, TRUE, '普通用户可按选中 ID 批量导出 SELF 数据范围内的低敏同步任务定义。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '普通用户批量导入自己的同步任务定义', 'ORDINARY_USER', 'POST', '/api/sync/sync-tasks/batch/import', 'SYNC_TASK', 'BATCH_IMPORT', 'ALLOW', 130, TRUE, '普通用户可通过批量导入语义入口导入自有范围内同步任务定义；服务层仍会校验模板可见性、唯一键冲突和立即执行边界。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '普通用户批量手工调度自己的同步任务', 'ORDINARY_USER', 'POST', '/api/sync/sync-tasks/batch/manual-dispatch', 'SYNC_TASK', 'BATCH_MANUAL_DISPATCH', 'ALLOW', 130, TRUE, '普通用户可批量手工调度自有任务；每条任务仍独立预检并创建 MANUAL execution。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '普通用户批量下线自己的同步任务', 'ORDINARY_USER', 'POST', '/api/sync/sync-tasks/batch/offline', 'SYNC_TASK', 'BATCH_OFFLINE', 'ALLOW', 130, TRUE, '普通用户可批量下线自己的非活跃同步任务，关闭后续自动调度。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '普通用户批量删除自己的同步任务到回收站', 'ORDINARY_USER', 'POST', '/api/sync/sync-tasks/batch/recycle', 'SYNC_TASK', 'BATCH_RECYCLE', 'ALLOW', 130, TRUE, '普通用户可批量把已下线的自有同步任务移入回收站，但不能彻底删除。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '普通用户禁止批量彻底删除同步任务', 'ORDINARY_USER', 'POST', '/api/sync/sync-tasks/batch/hard-delete', 'SYNC_TASK', 'BATCH_HARD_DELETE', 'DENY', 840, TRUE, '批量彻底删除属于高影响治理动作，普通用户即使拥有任务也不能绕过回收站治理直接逻辑删除。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),

(0, '项目负责人批量导出项目同步任务定义', 'PROJECT_OWNER', 'POST', '/api/sync/sync-tasks/batch/export', 'SYNC_TASK', 'BATCH_EXPORT', 'ALLOW', 151, TRUE, '项目负责人可按选中 ID 批量导出授权项目内低敏同步任务定义。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '项目负责人批量导入项目同步任务定义', 'PROJECT_OWNER', 'POST', '/api/sync/sync-tasks/batch/import', 'SYNC_TASK', 'BATCH_IMPORT', 'ALLOW', 151, TRUE, '项目负责人可批量导入授权项目内同步任务定义。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '项目负责人批量手工调度项目同步任务', 'PROJECT_OWNER', 'POST', '/api/sync/sync-tasks/batch/manual-dispatch', 'SYNC_TASK', 'BATCH_MANUAL_DISPATCH', 'ALLOW', 151, TRUE, '项目负责人可批量手工调度授权项目内同步任务。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '项目负责人批量下线项目同步任务', 'PROJECT_OWNER', 'POST', '/api/sync/sync-tasks/batch/offline', 'SYNC_TASK', 'BATCH_OFFLINE', 'ALLOW', 151, TRUE, '项目负责人可批量下线授权项目内同步任务。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '项目负责人批量删除项目同步任务到回收站', 'PROJECT_OWNER', 'POST', '/api/sync/sync-tasks/batch/recycle', 'SYNC_TASK', 'BATCH_RECYCLE', 'ALLOW', 151, TRUE, '项目负责人可批量把已下线的项目任务移入回收站。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '项目负责人批量彻底删除回收站同步任务', 'PROJECT_OWNER', 'POST', '/api/sync/sync-tasks/batch/hard-delete', 'SYNC_TASK', 'BATCH_HARD_DELETE', 'ALLOW', 151, TRUE, '项目负责人可按授权项目范围批量逻辑彻底删除回收站任务，历史审计证据仍保留。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),

(0, '运营人员批量导出同步任务定义', 'OPERATOR', 'POST', '/api/sync/sync-tasks/batch/export', 'SYNC_TASK', 'BATCH_EXPORT', 'ALLOW', 787, TRUE, '运营人员可按选中 ID 批量导出租户内低敏同步任务定义。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '运营人员批量导入同步任务定义', 'OPERATOR', 'POST', '/api/sync/sync-tasks/batch/import', 'SYNC_TASK', 'BATCH_IMPORT', 'ALLOW', 787, TRUE, '运营人员可批量导入租户内同步任务定义，用于迁移实施、批量恢复和受控上线。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '运营人员批量手工调度同步任务', 'OPERATOR', 'POST', '/api/sync/sync-tasks/batch/manual-dispatch', 'SYNC_TASK', 'BATCH_MANUAL_DISPATCH', 'ALLOW', 787, TRUE, '运营人员可在租户范围内批量手工调度同步任务。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '运营人员批量下线同步任务', 'OPERATOR', 'POST', '/api/sync/sync-tasks/batch/offline', 'SYNC_TASK', 'BATCH_OFFLINE', 'ALLOW', 787, TRUE, '运营人员可批量下线租户内同步任务，用于故障隔离、容量保护或客户退订。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '运营人员批量删除同步任务到回收站', 'OPERATOR', 'POST', '/api/sync/sync-tasks/batch/recycle', 'SYNC_TASK', 'BATCH_RECYCLE', 'ALLOW', 787, TRUE, '运营人员可批量把已下线任务移入回收站。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '运营人员批量彻底删除回收站同步任务', 'OPERATOR', 'POST', '/api/sync/sync-tasks/batch/hard-delete', 'SYNC_TASK', 'BATCH_HARD_DELETE', 'ALLOW', 787, TRUE, '运营人员可按租户治理流程批量逻辑彻底删除回收站任务。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),

(0, '租户管理员批量导出同步任务定义', 'TENANT_ADMINISTRATOR', 'POST', '/api/sync/sync-tasks/batch/export', 'SYNC_TASK', 'BATCH_EXPORT', 'ALLOW', 767, TRUE, '租户管理员可批量导出本租户低敏同步任务定义。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '租户管理员批量导入同步任务定义', 'TENANT_ADMINISTRATOR', 'POST', '/api/sync/sync-tasks/batch/import', 'SYNC_TASK', 'BATCH_IMPORT', 'ALLOW', 767, TRUE, '租户管理员可批量导入本租户同步任务定义。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '租户管理员批量手工调度同步任务', 'TENANT_ADMINISTRATOR', 'POST', '/api/sync/sync-tasks/batch/manual-dispatch', 'SYNC_TASK', 'BATCH_MANUAL_DISPATCH', 'ALLOW', 767, TRUE, '租户管理员可在本租户范围内批量手工调度同步任务。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '租户管理员批量下线同步任务', 'TENANT_ADMINISTRATOR', 'POST', '/api/sync/sync-tasks/batch/offline', 'SYNC_TASK', 'BATCH_OFFLINE', 'ALLOW', 767, TRUE, '租户管理员可批量下线本租户同步任务。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '租户管理员批量删除同步任务到回收站', 'TENANT_ADMINISTRATOR', 'POST', '/api/sync/sync-tasks/batch/recycle', 'SYNC_TASK', 'BATCH_RECYCLE', 'ALLOW', 767, TRUE, '租户管理员可批量把本租户已下线任务移入回收站。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '租户管理员批量彻底删除回收站同步任务', 'TENANT_ADMINISTRATOR', 'POST', '/api/sync/sync-tasks/batch/hard-delete', 'SYNC_TASK', 'BATCH_HARD_DELETE', 'ALLOW', 767, TRUE, '租户管理员可批量逻辑彻底删除本租户回收站任务。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),

(0, '审计员批量导出同步任务定义', 'AUDITOR', 'POST', '/api/sync/sync-tasks/batch/export', 'SYNC_TASK', 'BATCH_EXPORT', 'ALLOW', 121, TRUE, '审计员可按选中 ID 批量导出低敏任务定义证据，用于复核任务配置和变更范围。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '审计员禁止批量导入同步任务定义', 'AUDITOR', 'POST', '/api/sync/sync-tasks/batch/import', 'SYNC_TASK', 'BATCH_IMPORT', 'DENY', 900, TRUE, '审计员只能复核，不能批量创建、发布或立即执行同步任务。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '审计员禁止批量手工调度同步任务', 'AUDITOR', 'POST', '/api/sync/sync-tasks/batch/manual-dispatch', 'SYNC_TASK', 'BATCH_MANUAL_DISPATCH', 'DENY', 900, TRUE, '审计员不能批量触发同步任务执行。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '审计员禁止批量下线同步任务', 'AUDITOR', 'POST', '/api/sync/sync-tasks/batch/offline', 'SYNC_TASK', 'BATCH_OFFLINE', 'DENY', 900, TRUE, '审计员不能批量下线同步任务。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '审计员禁止批量删除同步任务到回收站', 'AUDITOR', 'POST', '/api/sync/sync-tasks/batch/recycle', 'SYNC_TASK', 'BATCH_RECYCLE', 'DENY', 900, TRUE, '审计员不能批量删除同步任务到回收站。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '审计员禁止批量彻底删除同步任务', 'AUDITOR', 'POST', '/api/sync/sync-tasks/batch/hard-delete', 'SYNC_TASK', 'BATCH_HARD_DELETE', 'DENY', 900, TRUE, '审计员不能批量彻底删除同步任务。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),

(0, '服务账号禁止人工批量导出同步任务定义', 'SERVICE_ACCOUNT', 'POST', '/api/sync/sync-tasks/batch/export', 'SYNC_TASK', 'BATCH_EXPORT', 'DENY', 910, TRUE, '服务账号不应调用人工批量导出入口；机器证据归档应走受控内部协议。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '服务账号禁止人工批量导入同步任务定义', 'SERVICE_ACCOUNT', 'POST', '/api/sync/sync-tasks/batch/import', 'SYNC_TASK', 'BATCH_IMPORT', 'DENY', 910, TRUE, '服务账号不应调用人工批量导入入口，避免绕过审批、容量评估和用户确认。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '服务账号禁止人工批量手工调度同步任务', 'SERVICE_ACCOUNT', 'POST', '/api/sync/sync-tasks/batch/manual-dispatch', 'SYNC_TASK', 'BATCH_MANUAL_DISPATCH', 'DENY', 910, TRUE, '服务账号不应调用人工批量手工调度入口；机器调度应使用 internal scheduler。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '服务账号禁止人工批量下线同步任务', 'SERVICE_ACCOUNT', 'POST', '/api/sync/sync-tasks/batch/offline', 'SYNC_TASK', 'BATCH_OFFLINE', 'DENY', 910, TRUE, '服务账号不应调用人工批量下线入口。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '服务账号禁止人工批量删除同步任务到回收站', 'SERVICE_ACCOUNT', 'POST', '/api/sync/sync-tasks/batch/recycle', 'SYNC_TASK', 'BATCH_RECYCLE', 'DENY', 910, TRUE, '服务账号不应调用人工批量删除入口。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '服务账号禁止人工批量彻底删除同步任务', 'SERVICE_ACCOUNT', 'POST', '/api/sync/sync-tasks/batch/hard-delete', 'SYNC_TASK', 'BATCH_HARD_DELETE', 'DENY', 910, TRUE, '服务账号不应调用人工批量彻底删除入口。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT DO NOTHING;
