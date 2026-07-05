-- data-sync：同步模板离线作业计划路由策略
--
-- 背景：
-- 1. offline-job-plan 使用 POST 承载只读规划，是为了避免未来复杂规划上下文进入 URL、代理日志或浏览器历史；
-- 2. 该接口不创建任务、不入队、不执行 SQL，只返回低敏 Reader/Writer、调度、checkpoint、审批和 fail-closed 摘要；
-- 3. 因此权限动作应归类为 VIEW，而不是被 POST 默认归类为 CREATE。

UPDATE permission_route_policy
SET action = 'VIEW'
WHERE path_pattern = '/api/sync/sync-templates/*/offline-job-plan'
  AND http_method = 'POST'
  AND (action IS NULL OR action = 'CREATE');

INSERT IGNORE INTO permission_route_policy
(tenant_id, policy_name, role_code, http_method, path_pattern, resource_type, action, effect, priority, enabled, description, create_time, update_time)
VALUES
(0, '普通用户查看同步模板离线作业计划', 'ORDINARY_USER', 'POST', '/api/sync/sync-templates/*/offline-job-plan', 'SYNC_TEMPLATE', 'VIEW', 'ALLOW', 112, 1, '普通用户可在授权数据范围内查看同步模板的低敏离线作业计划；服务层仍会校验模板租户、项目和 SELF 范围。', NOW(), NOW()),
(0, '项目负责人查看同步模板离线作业计划', 'PROJECT_OWNER', 'POST', '/api/sync/sync-templates/*/offline-job-plan', 'SYNC_TEMPLATE', 'VIEW', 'ALLOW', 126, 1, '项目负责人可查看项目范围模板的 DataX-style 离线作业计划，用于任务创建、审批和容量评估。', NOW(), NOW()),
(0, '运营人员查看同步模板离线作业计划', 'OPERATOR', 'POST', '/api/sync/sync-templates/*/offline-job-plan', 'SYNC_TEMPLATE', 'VIEW', 'ALLOW', 131, 1, '运营人员可查看低敏离线作业计划，用于判断任务是否需要专用 runner、checkpoint handoff、调度窗口或审批。', NOW(), NOW()),
(0, '审计员查看同步模板离线作业计划', 'AUDITOR', 'POST', '/api/sync/sync-templates/*/offline-job-plan', 'SYNC_TEMPLATE', 'VIEW', 'ALLOW', 116, 1, '审计员可查看离线作业计划摘要，用于复核 SQL 自定义传输、整库迁移、覆盖写入等高风险配置是否进入审批边界。', NOW(), NOW()),
(0, '租户管理员查看同步模板离线作业计划', 'TENANT_ADMINISTRATOR', 'POST', '/api/sync/sync-templates/*/offline-job-plan', 'SYNC_TEMPLATE', 'VIEW', 'ALLOW', 151, 1, '租户管理员可查看本租户同步模板的低敏离线作业计划，用于租户级容量、调度和风险治理。', NOW(), NOW());
