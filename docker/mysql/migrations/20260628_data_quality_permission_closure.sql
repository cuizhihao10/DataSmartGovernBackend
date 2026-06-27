-- ---------------------------------------------------------------------------
-- DataSmart Govern permission-admin 迁移脚本
-- 版本：20260628_data_quality_permission_closure
-- ---------------------------------------------------------------------------
-- 背景：
-- data-quality 已经从“质量规则 CRUD”扩展到治理总览、质量报告、异常工作台、执行器诊断和 worker 回调。
-- 旧权限矩阵只把 /api/quality/** 归为 QUALITY_RULE，会导致以下问题：
-- 1. 普通用户只想看低敏治理态势，却可能被迫依赖规则管理权限；
-- 2. 审计员需要只读查看报告和异常证据，但不应具备运行或配置规则的权限；
-- 3. 运营人员需要诊断执行器和触发补跑，但不能伪造 worker 回调；
-- 4. SERVICE_ACCOUNT 才应调用执行器回调协议，避免人类角色直接改写执行事实。
--
-- 使用方式：
-- mysql -uroot -ppassword datasmart_govern < docker/mysql/migrations/20260628_data_quality_permission_closure.sql
--
-- 设计说明：
-- 本脚本只补齐菜单、路由策略和数据范围策略，不新增业务表，也不改变 data-quality 的执行状态机。
-- 已有数据库升级后，gateway 会把细粒度 resourceType/action 发送给 permission-admin，
-- permission-admin 再返回 PROJECT/TENANT/PLATFORM 数据范围给 data-quality 服务做查询收口。
-- ---------------------------------------------------------------------------

USE datasmart_govern;

UPDATE permission_route_policy
SET resource_type = 'QUALITY_RULE'
WHERE resource_type IS NULL
  AND path_pattern LIKE '/api/quality/%';

INSERT IGNORE INTO permission_menu
(menu_code, parent_code, menu_name, path, icon, sort_order, enabled, description, create_time, update_time)
VALUES
('quality-governance', 'quality', '质量治理总览', '/quality/governance', 'DashboardOutlined', 41, 1, '查看项目或租户范围内质量评分、风险等级、报告通过率和异常分布。', NOW(), NOW()),
('quality-rule', 'quality', '质量规则', '/quality/rules', 'ProfileOutlined', 42, 1, '创建、配置、启用、禁用、归档和恢复质量规则。', NOW(), NOW()),
('quality-report', 'quality', '质量报告', '/quality/reports', 'FileTextOutlined', 43, 1, '查看质量检测报告、规则结果快照和低敏质量证据。', NOW(), NOW()),
('quality-anomaly', 'quality', '异常工作台', '/quality/anomalies', 'WarningOutlined', 44, 1, '查看质量异常聚合、异常类型分布和后续清洗任务线索。', NOW(), NOW()),
('quality-executor', 'quality', '质量执行器', '/quality/executor', 'ThunderboltOutlined', 45, 1, '查看执行器诊断、触发质量检测和排查执行积压。', NOW(), NOW());

INSERT IGNORE INTO permission_role_menu_binding
(tenant_id, role_code, menu_code, enabled, binding_source, note, create_time, update_time)
VALUES
(0, 'ORDINARY_USER', 'quality', 1, 'BOOTSTRAP', '普通用户可进入授权项目范围内的数据质量入口。', NOW(), NOW()),
(0, 'ORDINARY_USER', 'quality-governance', 1, 'BOOTSTRAP', '普通用户可查看低敏治理态势。', NOW(), NOW()),
(0, 'ORDINARY_USER', 'quality-report', 1, 'BOOTSTRAP', '普通用户可查看授权项目内质量报告。', NOW(), NOW()),
(0, 'ORDINARY_USER', 'quality-anomaly', 1, 'BOOTSTRAP', '普通用户可查看授权项目内低敏异常聚合。', NOW(), NOW()),
(0, 'PROJECT_OWNER', 'quality-governance', 1, 'BOOTSTRAP', '项目负责人可查看项目质量态势。', NOW(), NOW()),
(0, 'PROJECT_OWNER', 'quality-rule', 1, 'BOOTSTRAP', '项目负责人可管理项目质量规则。', NOW(), NOW()),
(0, 'PROJECT_OWNER', 'quality-report', 1, 'BOOTSTRAP', '项目负责人可查看项目质量报告。', NOW(), NOW()),
(0, 'PROJECT_OWNER', 'quality-anomaly', 1, 'BOOTSTRAP', '项目负责人可查看项目异常工作台。', NOW(), NOW()),
(0, 'PROJECT_OWNER', 'quality-executor', 1, 'BOOTSTRAP', '项目负责人可触发项目范围内手动检测，但不能伪造 worker 回调。', NOW(), NOW()),
(0, 'OPERATOR', 'quality', 1, 'BOOTSTRAP', '运营人员可进入质量运行与排障入口。', NOW(), NOW()),
(0, 'OPERATOR', 'quality-governance', 1, 'BOOTSTRAP', '运营人员可查看租户质量态势。', NOW(), NOW()),
(0, 'OPERATOR', 'quality-report', 1, 'BOOTSTRAP', '运营人员可查看租户质量报告。', NOW(), NOW()),
(0, 'OPERATOR', 'quality-anomaly', 1, 'BOOTSTRAP', '运营人员可查看异常工作台。', NOW(), NOW()),
(0, 'OPERATOR', 'quality-executor', 1, 'BOOTSTRAP', '运营人员可诊断执行器并触发受控检测。', NOW(), NOW()),
(0, 'AUDITOR', 'quality', 1, 'BOOTSTRAP', '审计员可进入质量只读审计入口。', NOW(), NOW()),
(0, 'AUDITOR', 'quality-governance', 1, 'BOOTSTRAP', '审计员可查看质量治理态势。', NOW(), NOW()),
(0, 'AUDITOR', 'quality-report', 1, 'BOOTSTRAP', '审计员可查看质量报告证据。', NOW(), NOW()),
(0, 'AUDITOR', 'quality-anomaly', 1, 'BOOTSTRAP', '审计员可查看低敏异常聚合。', NOW(), NOW()),
(0, 'TENANT_ADMINISTRATOR', 'quality', 1, 'BOOTSTRAP', '租户管理员可进入本租户质量治理入口。', NOW(), NOW()),
(0, 'TENANT_ADMINISTRATOR', 'quality-governance', 1, 'BOOTSTRAP', '租户管理员可查看租户质量态势。', NOW(), NOW()),
(0, 'TENANT_ADMINISTRATOR', 'quality-rule', 1, 'BOOTSTRAP', '租户管理员可管理本租户质量规则。', NOW(), NOW()),
(0, 'TENANT_ADMINISTRATOR', 'quality-report', 1, 'BOOTSTRAP', '租户管理员可查看本租户质量报告。', NOW(), NOW()),
(0, 'TENANT_ADMINISTRATOR', 'quality-anomaly', 1, 'BOOTSTRAP', '租户管理员可查看本租户异常工作台。', NOW(), NOW()),
(0, 'TENANT_ADMINISTRATOR', 'quality-executor', 1, 'BOOTSTRAP', '租户管理员可诊断和触发本租户质量执行。', NOW(), NOW()),
(0, 'PLATFORM_ADMINISTRATOR', 'quality-governance', 1, 'BOOTSTRAP', '平台管理员可查看全平台质量态势。', NOW(), NOW()),
(0, 'PLATFORM_ADMINISTRATOR', 'quality-rule', 1, 'BOOTSTRAP', '平台管理员可管理全平台质量规则。', NOW(), NOW()),
(0, 'PLATFORM_ADMINISTRATOR', 'quality-report', 1, 'BOOTSTRAP', '平台管理员可查看全平台质量报告。', NOW(), NOW()),
(0, 'PLATFORM_ADMINISTRATOR', 'quality-anomaly', 1, 'BOOTSTRAP', '平台管理员可查看全平台异常工作台。', NOW(), NOW()),
(0, 'PLATFORM_ADMINISTRATOR', 'quality-executor', 1, 'BOOTSTRAP', '平台管理员可诊断全平台质量执行。', NOW(), NOW());

INSERT IGNORE INTO permission_route_policy
(tenant_id, policy_name, role_code, http_method, path_pattern, resource_type, action, effect, priority, enabled, description, create_time, update_time)
VALUES
(0, '普通用户查看质量治理总览', 'ORDINARY_USER', 'GET', '/api/quality/quality-rules/governance/overview', 'QUALITY_GOVERNANCE', 'VIEW', 'ALLOW', 122, 1, '普通用户可在项目授权范围内查看低敏质量治理总览。', NOW(), NOW()),
(0, '普通用户查看质量报告', 'ORDINARY_USER', 'GET', '/api/quality/quality-rules/reports/**', 'QUALITY_REPORT', 'VIEW', 'ALLOW', 122, 1, '普通用户可查看授权项目内质量报告摘要。', NOW(), NOW()),
(0, '普通用户查看质量异常', 'ORDINARY_USER', 'GET', '/api/quality/quality-rules/anomalies/**', 'QUALITY_ANOMALY', 'VIEW', 'ALLOW', 122, 1, '普通用户可查看授权项目内低敏异常聚合，不包含样本值或敏感明细。', NOW(), NOW()),
(0, '普通用户查看质量报告异常', 'ORDINARY_USER', 'GET', '/api/quality/quality-rules/reports/*/anomalies', 'QUALITY_ANOMALY', 'VIEW', 'ALLOW', 123, 1, '普通用户可查看授权报告下的低敏异常聚合。', NOW(), NOW()),
(0, '普通用户只读查看质量规则', 'ORDINARY_USER', 'GET', '/api/quality/quality-rules/**', 'QUALITY_RULE', 'VIEW', 'ALLOW', 122, 1, '普通用户可查看授权项目内质量规则定义摘要。', NOW(), NOW()),
(0, '项目负责人查看质量治理总览', 'PROJECT_OWNER', 'GET', '/api/quality/quality-rules/governance/overview', 'QUALITY_GOVERNANCE', 'VIEW', 'ALLOW', 145, 1, '项目负责人可查看项目质量治理总览。', NOW(), NOW()),
(0, '项目负责人查看质量报告', 'PROJECT_OWNER', 'GET', '/api/quality/quality-rules/reports/**', 'QUALITY_REPORT', 'VIEW', 'ALLOW', 145, 1, '项目负责人可查看项目质量报告。', NOW(), NOW()),
(0, '项目负责人查看质量异常', 'PROJECT_OWNER', 'GET', '/api/quality/quality-rules/anomalies/**', 'QUALITY_ANOMALY', 'VIEW', 'ALLOW', 145, 1, '项目负责人可查看项目异常工作台。', NOW(), NOW()),
(0, '项目负责人查看报告异常', 'PROJECT_OWNER', 'GET', '/api/quality/quality-rules/reports/*/anomalies', 'QUALITY_ANOMALY', 'VIEW', 'ALLOW', 146, 1, '项目负责人可查看单份报告下的异常聚合。', NOW(), NOW()),
(0, '项目负责人查看质量执行历史', 'PROJECT_OWNER', 'GET', '/api/quality/quality-rules/*/executions', 'QUALITY_EXECUTION', 'VIEW', 'ALLOW', 146, 1, '项目负责人可查看授权项目内质量执行历史。', NOW(), NOW()),
(0, '项目负责人触发质量检测', 'PROJECT_OWNER', 'POST', '/api/quality/quality-rules/*/run-check', 'QUALITY_EXECUTION', 'RUN', 'ALLOW', 146, 1, '项目负责人可在授权项目内触发单条规则检测。', NOW(), NOW()),
(0, '项目负责人配置质量调度', 'PROJECT_OWNER', 'POST', '/api/quality/quality-rules/*/schedule-task', 'QUALITY_EXECUTION', 'CONFIGURE', 'ALLOW', 146, 1, '项目负责人可配置项目内规则调度任务。', NOW(), NOW()),
(0, '运营人员查看质量治理总览', 'OPERATOR', 'GET', '/api/quality/quality-rules/governance/overview', 'QUALITY_GOVERNANCE', 'VIEW', 'ALLOW', 150, 1, '运营人员可查看租户质量治理态势，用于排查质量风险趋势。', NOW(), NOW()),
(0, '运营人员查看质量报告', 'OPERATOR', 'GET', '/api/quality/quality-rules/reports/**', 'QUALITY_REPORT', 'VIEW', 'ALLOW', 150, 1, '运营人员可查看租户质量报告。', NOW(), NOW()),
(0, '运营人员查看质量异常', 'OPERATOR', 'GET', '/api/quality/quality-rules/anomalies/**', 'QUALITY_ANOMALY', 'VIEW', 'ALLOW', 150, 1, '运营人员可查看租户异常工作台。', NOW(), NOW()),
(0, '运营人员查看报告异常', 'OPERATOR', 'GET', '/api/quality/quality-rules/reports/*/anomalies', 'QUALITY_ANOMALY', 'VIEW', 'ALLOW', 151, 1, '运营人员可查看报告异常聚合，用于定位质量事件。', NOW(), NOW()),
(0, '运营人员查看质量执行历史', 'OPERATOR', 'GET', '/api/quality/quality-rules/*/executions', 'QUALITY_EXECUTION', 'VIEW', 'ALLOW', 151, 1, '运营人员可查看质量执行历史和低敏执行状态。', NOW(), NOW()),
(0, '运营人员诊断质量执行器', 'OPERATOR', 'GET', '/api/quality/quality-rules/executor/diagnostics', 'QUALITY_EXECUTION', 'DIAGNOSE', 'ALLOW', 152, 1, '运营人员可查看质量执行器健康、积压和故障诊断。', NOW(), NOW()),
(0, '运营人员触发质量检测', 'OPERATOR', 'POST', '/api/quality/quality-rules/*/run-check', 'QUALITY_EXECUTION', 'RUN', 'ALLOW', 152, 1, '运营人员可在租户范围内触发受控质量检测。', NOW(), NOW()),
(0, '运营人员触发质量批量调度', 'OPERATOR', 'POST', '/api/quality/quality-rules/executor/coordinator/**', 'QUALITY_EXECUTION', 'RUN', 'ALLOW', 152, 1, '运营人员可触发受控质量执行协调器，用于排障或补跑。', NOW(), NOW()),
(0, '审计员查看质量治理总览', 'AUDITOR', 'GET', '/api/quality/quality-rules/governance/overview', 'QUALITY_GOVERNANCE', 'VIEW', 'ALLOW', 120, 1, '审计员可只读查看质量治理态势。', NOW(), NOW()),
(0, '审计员查看质量报告', 'AUDITOR', 'GET', '/api/quality/quality-rules/reports/**', 'QUALITY_REPORT', 'VIEW', 'ALLOW', 120, 1, '审计员可只读查看质量报告证据。', NOW(), NOW()),
(0, '审计员查看质量异常', 'AUDITOR', 'GET', '/api/quality/quality-rules/anomalies/**', 'QUALITY_ANOMALY', 'VIEW', 'ALLOW', 120, 1, '审计员可只读查看低敏异常聚合。', NOW(), NOW()),
(0, '审计员查看报告异常', 'AUDITOR', 'GET', '/api/quality/quality-rules/reports/*/anomalies', 'QUALITY_ANOMALY', 'VIEW', 'ALLOW', 121, 1, '审计员可查看报告异常聚合证据。', NOW(), NOW()),
(0, '审计员只读查看质量规则', 'AUDITOR', 'GET', '/api/quality/quality-rules/**', 'QUALITY_RULE', 'VIEW', 'ALLOW', 120, 1, '审计员可只读查看质量规则定义和生命周期状态。', NOW(), NOW()),
(0, '租户管理员查看质量治理总览', 'TENANT_ADMINISTRATOR', 'GET', '/api/quality/quality-rules/governance/overview', 'QUALITY_GOVERNANCE', 'VIEW', 'ALLOW', 161, 1, '租户管理员可查看本租户质量治理总览。', NOW(), NOW()),
(0, '租户管理员查看质量报告', 'TENANT_ADMINISTRATOR', 'GET', '/api/quality/quality-rules/reports/**', 'QUALITY_REPORT', 'VIEW', 'ALLOW', 161, 1, '租户管理员可查看本租户质量报告。', NOW(), NOW()),
(0, '租户管理员查看质量异常', 'TENANT_ADMINISTRATOR', 'GET', '/api/quality/quality-rules/anomalies/**', 'QUALITY_ANOMALY', 'VIEW', 'ALLOW', 161, 1, '租户管理员可查看本租户异常工作台。', NOW(), NOW()),
(0, '租户管理员诊断质量执行器', 'TENANT_ADMINISTRATOR', 'GET', '/api/quality/quality-rules/executor/diagnostics', 'QUALITY_EXECUTION', 'DIAGNOSE', 'ALLOW', 161, 1, '租户管理员可诊断本租户质量执行器。', NOW(), NOW()),
(0, '租户管理员查看质量执行历史', 'TENANT_ADMINISTRATOR', 'GET', '/api/quality/quality-rules/*/executions', 'QUALITY_EXECUTION', 'VIEW', 'ALLOW', 161, 1, '租户管理员可查看本租户质量执行历史。', NOW(), NOW()),
(0, '租户管理员触发质量检测', 'TENANT_ADMINISTRATOR', 'POST', '/api/quality/quality-rules/*/run-check', 'QUALITY_EXECUTION', 'RUN', 'ALLOW', 161, 1, '租户管理员可触发本租户质量检测。', NOW(), NOW()),
(0, '租户管理员触发质量批量调度', 'TENANT_ADMINISTRATOR', 'POST', '/api/quality/quality-rules/executor/coordinator/**', 'QUALITY_EXECUTION', 'RUN', 'ALLOW', 161, 1, '租户管理员可触发本租户质量执行协调器。', NOW(), NOW()),
(0, '租户管理员配置质量调度', 'TENANT_ADMINISTRATOR', 'POST', '/api/quality/quality-rules/*/schedule-task', 'QUALITY_EXECUTION', 'CONFIGURE', 'ALLOW', 161, 1, '租户管理员可配置本租户质量调度任务。', NOW(), NOW()),
(0, '服务账号质量执行器回调', 'SERVICE_ACCOUNT', 'POST', '/api/quality/quality-rules/executor/executions/**', 'QUALITY_EXECUTION', 'CALLBACK', 'ALLOW', 910, 1, 'SERVICE_ACCOUNT 可提交质量执行器 start/succeed/fail 回调，data-quality 服务层继续做执行状态和幂等校验。', NOW(), NOW()),
(0, '服务账号质量协调器调度', 'SERVICE_ACCOUNT', 'POST', '/api/quality/quality-rules/executor/coordinator/**', 'QUALITY_EXECUTION', 'RUN', 'ALLOW', 900, 1, 'SERVICE_ACCOUNT 可代表受控调度器触发质量执行协调器。', NOW(), NOW()),
(0, '普通用户禁止质量执行器回调', 'ORDINARY_USER', 'POST', '/api/quality/quality-rules/executor/executions/**', 'QUALITY_EXECUTION', 'CALLBACK', 'DENY', 1050, 1, '执行器回调是机器协议，普通用户不能伪造执行结果。', NOW(), NOW()),
(0, '项目负责人禁止质量执行器回调', 'PROJECT_OWNER', 'POST', '/api/quality/quality-rules/executor/executions/**', 'QUALITY_EXECUTION', 'CALLBACK', 'DENY', 1050, 1, '项目负责人可管理规则和触发检测，但不能伪造 worker 回调。', NOW(), NOW()),
(0, '运营人员禁止质量执行器回调', 'OPERATOR', 'POST', '/api/quality/quality-rules/executor/executions/**', 'QUALITY_EXECUTION', 'CALLBACK', 'DENY', 1050, 1, '运营人员可诊断和触发执行，但不能伪造 worker 回调。', NOW(), NOW()),
(0, '审计员禁止质量执行器回调', 'AUDITOR', 'POST', '/api/quality/quality-rules/executor/executions/**', 'QUALITY_EXECUTION', 'CALLBACK', 'DENY', 1050, 1, '审计员只能复核证据，不能改变执行状态。', NOW(), NOW()),
(0, '租户管理员禁止质量执行器回调', 'TENANT_ADMINISTRATOR', 'POST', '/api/quality/quality-rules/executor/executions/**', 'QUALITY_EXECUTION', 'CALLBACK', 'DENY', 1050, 1, '租户管理员不能用人类身份伪造 worker 回调。', NOW(), NOW()),
(0, '平台管理员禁止质量执行器回调', 'PLATFORM_ADMINISTRATOR', 'POST', '/api/quality/quality-rules/executor/executions/**', 'QUALITY_EXECUTION', 'CALLBACK', 'DENY', 1050, 1, '平台管理员也应通过受控服务账号或 break-glass 流程处理机器回调，避免人工伪造执行事实。', NOW(), NOW());

INSERT IGNORE INTO permission_data_scope_policy
(tenant_id, role_code, resource_type, scope_level, scope_expression, approval_required, enabled, description, create_time, update_time)
VALUES
(0, 'ORDINARY_USER', 'QUALITY_GOVERNANCE', 'PROJECT', 'project_id IN ${actorProjectIds}', 0, 1, '普通用户只能查看被授权项目的质量治理总览。', NOW(), NOW()),
(0, 'ORDINARY_USER', 'QUALITY_REPORT', 'PROJECT', 'project_id IN ${actorProjectIds}', 0, 1, '普通用户只能查看被授权项目的质量报告。', NOW(), NOW()),
(0, 'ORDINARY_USER', 'QUALITY_ANOMALY', 'PROJECT', 'project_id IN ${actorProjectIds}', 0, 1, '普通用户只能查看被授权项目的低敏异常聚合。', NOW(), NOW()),
(0, 'PROJECT_OWNER', 'QUALITY_GOVERNANCE', 'PROJECT', 'project_id IN ${actorProjectIds}', 0, 1, '项目负责人查看负责项目的质量治理总览。', NOW(), NOW()),
(0, 'PROJECT_OWNER', 'QUALITY_REPORT', 'PROJECT', 'project_id IN ${actorProjectIds}', 0, 1, '项目负责人查看负责项目的质量报告。', NOW(), NOW()),
(0, 'PROJECT_OWNER', 'QUALITY_ANOMALY', 'PROJECT', 'project_id IN ${actorProjectIds}', 0, 1, '项目负责人查看负责项目的质量异常。', NOW(), NOW()),
(0, 'PROJECT_OWNER', 'QUALITY_EXECUTION', 'PROJECT', 'project_id IN ${actorProjectIds}', 0, 1, '项目负责人只能触发和查看负责项目内的质量执行。', NOW(), NOW()),
(0, 'OPERATOR', 'QUALITY_GOVERNANCE', 'TENANT', 'tenant_id = ${tenantId}', 0, 1, '运营人员查看当前租户质量治理态势。', NOW(), NOW()),
(0, 'OPERATOR', 'QUALITY_REPORT', 'TENANT', 'tenant_id = ${tenantId}', 0, 1, '运营人员查看当前租户质量报告。', NOW(), NOW()),
(0, 'OPERATOR', 'QUALITY_ANOMALY', 'TENANT', 'tenant_id = ${tenantId}', 0, 1, '运营人员查看当前租户质量异常。', NOW(), NOW()),
(0, 'OPERATOR', 'QUALITY_EXECUTION', 'TENANT', 'tenant_id = ${tenantId}', 0, 1, '运营人员诊断和触发当前租户质量执行。', NOW(), NOW()),
(0, 'AUDITOR', 'QUALITY_RULE', 'TENANT', 'tenant_id = ${tenantId}', 0, 1, '审计员只读查看当前租户质量规则。', NOW(), NOW()),
(0, 'AUDITOR', 'QUALITY_GOVERNANCE', 'TENANT', 'tenant_id = ${tenantId}', 0, 1, '审计员只读查看当前租户质量治理态势。', NOW(), NOW()),
(0, 'AUDITOR', 'QUALITY_REPORT', 'TENANT', 'tenant_id = ${tenantId}', 0, 1, '审计员只读查看当前租户质量报告证据。', NOW(), NOW()),
(0, 'AUDITOR', 'QUALITY_ANOMALY', 'TENANT', 'tenant_id = ${tenantId}', 0, 1, '审计员只读查看当前租户低敏质量异常。', NOW(), NOW()),
(0, 'TENANT_ADMINISTRATOR', 'QUALITY_GOVERNANCE', 'TENANT', 'tenant_id = ${tenantId}', 0, 1, '租户管理员查看本租户质量治理态势。', NOW(), NOW()),
(0, 'TENANT_ADMINISTRATOR', 'QUALITY_REPORT', 'TENANT', 'tenant_id = ${tenantId}', 0, 1, '租户管理员查看本租户质量报告。', NOW(), NOW()),
(0, 'TENANT_ADMINISTRATOR', 'QUALITY_ANOMALY', 'TENANT', 'tenant_id = ${tenantId}', 0, 1, '租户管理员查看本租户质量异常。', NOW(), NOW()),
(0, 'TENANT_ADMINISTRATOR', 'QUALITY_EXECUTION', 'TENANT', 'tenant_id = ${tenantId}', 0, 1, '租户管理员诊断和触发本租户质量执行。', NOW(), NOW()),
(0, 'SERVICE_ACCOUNT', 'QUALITY_EXECUTION', 'TENANT', 'tenant_id = ${tenantId}', 0, 1, '服务账号在当前租户边界内提交质量执行回调和受控调度。', NOW(), NOW()),
(0, 'PLATFORM_ADMINISTRATOR', 'QUALITY_GOVERNANCE', 'PLATFORM', '1 = 1', 0, 1, '平台管理员查看全平台质量治理态势。', NOW(), NOW()),
(0, 'PLATFORM_ADMINISTRATOR', 'QUALITY_REPORT', 'PLATFORM', '1 = 1', 0, 1, '平台管理员查看全平台质量报告。', NOW(), NOW()),
(0, 'PLATFORM_ADMINISTRATOR', 'QUALITY_ANOMALY', 'PLATFORM', '1 = 1', 0, 1, '平台管理员查看全平台质量异常。', NOW(), NOW()),
(0, 'PLATFORM_ADMINISTRATOR', 'QUALITY_EXECUTION', 'PLATFORM', '1 = 1', 0, 1, '平台管理员查看和诊断全平台质量执行。', NOW(), NOW());
