-- DataSmart Govern Backend
-- Author: Cui
-- Date: 2026-07-08 22:45
-- Description: 为 data-sync 创建向导草稿保存入口补齐正式路由策略。
--
-- 业务背景：
-- 1. 新建同步任务从第二步开始就要落库为 DRAFT，用户关闭页面后可以在任务列表继续编辑；
-- 2. 该入口是写操作，但不会触发预检查、发布、调度或真实 worker 执行；
-- 3. 如果不显式声明路由策略，gateway 可能按通配规则把它解释成普通任务详情或兜底执行动作，导致误拒绝或误授权。
SET search_path TO permission_admin, public;

INSERT INTO permission_route_policy
    (tenant_id, policy_name, role_code, http_method, path_pattern, resource_type, action, effect, priority, enabled, description, create_time, update_time)
VALUES
    (0, 'data-sync-create-wizard-draft-project-owner', 'PROJECT_OWNER', 'POST',
     '/api/sync/sync-tasks/create-wizard/drafts', 'SYNC_TASK', 'SAVE_DRAFT', 'ALLOW', 126, TRUE,
     '项目负责人可在授权项目内保存同步任务创建向导草稿；草稿状态为 DRAFT，不触发执行。', LOCALTIMESTAMP, LOCALTIMESTAMP),
    (0, 'data-sync-create-wizard-draft-ordinary-user', 'ORDINARY_USER', 'POST',
     '/api/sync/sync-tasks/create-wizard/drafts', 'SYNC_TASK', 'SAVE_DRAFT', 'ALLOW', 127, TRUE,
     '普通用户可保存本人项目内的同步任务创建向导草稿；具体项目范围继续由 SYNC_TASK 数据范围策略收敛。', LOCALTIMESTAMP, LOCALTIMESTAMP),
    (0, 'data-sync-create-wizard-draft-operator', 'OPERATOR', 'POST',
     '/api/sync/sync-tasks/create-wizard/drafts', 'SYNC_TASK', 'SAVE_DRAFT', 'ALLOW', 760, TRUE,
     '运营人员可在租户范围内协助保存同步任务草稿，用于客户迁移、批量配置和故障修复前置准备。', LOCALTIMESTAMP, LOCALTIMESTAMP),
    (0, 'data-sync-create-wizard-draft-tenant-admin', 'TENANT_ADMINISTRATOR', 'POST',
     '/api/sync/sync-tasks/create-wizard/drafts', 'SYNC_TASK', 'SAVE_DRAFT', 'ALLOW', 740, TRUE,
     '租户管理员可在本租户范围内保存同步任务草稿。', LOCALTIMESTAMP, LOCALTIMESTAMP),
    (0, 'data-sync-create-wizard-draft-platform-admin', 'PLATFORM_ADMINISTRATOR', 'POST',
     '/api/sync/sync-tasks/create-wizard/drafts', 'SYNC_TASK', 'SAVE_DRAFT', 'ALLOW', 700, TRUE,
     '平台管理员可保存任意租户项目下的同步任务草稿，用于平台运维与迁移。', LOCALTIMESTAMP, LOCALTIMESTAMP),
    (0, 'data-sync-create-wizard-draft-auditor-deny', 'AUDITOR', 'POST',
     '/api/sync/sync-tasks/create-wizard/drafts', 'SYNC_TASK', 'SAVE_DRAFT', 'DENY', 900, TRUE,
     '审计员只能查看和审计同步配置，不能创建或修改同步任务草稿。', LOCALTIMESTAMP, LOCALTIMESTAMP),
    (0, 'data-sync-create-wizard-draft-service-account-deny', 'SERVICE_ACCOUNT', 'POST',
     '/api/sync/sync-tasks/create-wizard/drafts', 'SYNC_TASK', 'SAVE_DRAFT', 'DENY', 910, TRUE,
     '普通服务账号不通过用户创建向导保存草稿；内部批处理应使用专用服务间接口和审计身份。', LOCALTIMESTAMP, LOCALTIMESTAMP)
ON CONFLICT DO NOTHING;
