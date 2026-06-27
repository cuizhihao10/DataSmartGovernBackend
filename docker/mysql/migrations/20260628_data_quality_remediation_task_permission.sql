-- ---------------------------------------------------------------------------
-- DataSmart Govern permission-admin 迁移脚本
-- 版本：20260628_data_quality_remediation_task_permission
-- ---------------------------------------------------------------------------
-- 背景：
-- data-quality 已经具备质量报告、异常工作台和治理总览。为了让“发现异常”继续闭环到
-- task-management，需要新增 POST /api/quality/quality-rules/remediation-tasks。
--
-- 设计说明：
-- 1. 该路由不是质量规则 CREATE，而是异常处置动作；
-- 2. gateway 会把它翻译为 QUALITY_ANOMALY + CREATE_REMEDIATION_TASK；
-- 3. 普通用户和审计员只允许看低敏异常，不允许创建治理任务；
-- 4. 项目负责人、运营人员、租户管理员、平台管理员可以按各自数据范围创建任务；
-- 5. data-quality 服务层仍会继续短路 PROJECT 空授权、校验 reportId/projectId，并且只提交低敏聚合 payload。
--
-- 使用方式：
-- mysql -uroot -ppassword datasmart_govern < docker/mysql/migrations/20260628_data_quality_remediation_task_permission.sql
-- ---------------------------------------------------------------------------

USE datasmart_govern;

INSERT IGNORE INTO permission_route_policy
(tenant_id, policy_name, role_code, http_method, path_pattern, resource_type, action, effect, priority, enabled, description, create_time, update_time)
VALUES
(0, '普通用户禁止创建质量异常治理任务', 'ORDINARY_USER', 'POST',
 '/api/quality/quality-rules/remediation-tasks',
 'QUALITY_ANOMALY', 'CREATE_REMEDIATION_TASK', 'DENY', 1040, 1,
 '普通用户可以查看授权项目内低敏异常，但不能把异常派发为治理任务，避免越权制造待办或误触发治理流程。',
 NOW(), NOW()),
(0, '审计员禁止创建质量异常治理任务', 'AUDITOR', 'POST',
 '/api/quality/quality-rules/remediation-tasks',
 'QUALITY_ANOMALY', 'CREATE_REMEDIATION_TASK', 'DENY', 1040, 1,
 '审计员职责是只读复核证据，不能创建或推动治理任务，否则会破坏审计独立性。',
 NOW(), NOW()),
(0, '项目负责人创建质量异常治理任务', 'PROJECT_OWNER', 'POST',
 '/api/quality/quality-rules/remediation-tasks',
 'QUALITY_ANOMALY', 'CREATE_REMEDIATION_TASK', 'ALLOW', 146, 1,
 '项目负责人可在授权项目范围内把低敏质量异常聚合转成治理/复核任务，并由 data-quality 服务层继续按 projectId 收口。',
 NOW(), NOW()),
(0, '运营人员创建质量异常治理任务', 'OPERATOR', 'POST',
 '/api/quality/quality-rules/remediation-tasks',
 'QUALITY_ANOMALY', 'CREATE_REMEDIATION_TASK', 'ALLOW', 152, 1,
 '运营人员可在租户范围内根据异常聚合创建治理任务，用于质量事件跟进、派单和补偿流程。',
 NOW(), NOW()),
(0, '租户管理员创建质量异常治理任务', 'TENANT_ADMINISTRATOR', 'POST',
 '/api/quality/quality-rules/remediation-tasks',
 'QUALITY_ANOMALY', 'CREATE_REMEDIATION_TASK', 'ALLOW', 161, 1,
 '租户管理员可在本租户范围内创建质量异常治理任务，但 payload 仍只允许低敏聚合摘要。',
 NOW(), NOW()),
(0, '平台管理员创建质量异常治理任务', 'PLATFORM_ADMINISTRATOR', 'POST',
 '/api/quality/quality-rules/remediation-tasks',
 'QUALITY_ANOMALY', 'CREATE_REMEDIATION_TASK', 'ALLOW', 910, 1,
 '平台管理员可在全平台范围内创建质量异常治理任务，主要用于跨租户排障、演示环境治理或 break-glass 场景。',
 NOW(), NOW());
