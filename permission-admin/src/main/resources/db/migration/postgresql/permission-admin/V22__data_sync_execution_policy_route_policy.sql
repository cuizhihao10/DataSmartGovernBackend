-- DataSmart Govern - data-sync 执行策略与策略快照路由授权。
--
-- 设计说明：
-- 1. /api/sync/sync-execution-policies 是管理员/运营配置面，用于维护 channel、批大小、超时、重试、
--    脏数据阈值、自动分片目标行数等运行治理参数。它不属于普通用户新建任务向导，因此不向 ORDINARY_USER 开放。
-- 2. /api/sync/sync-tasks/*/executions/*/policy-snapshot 是运行详情只读证据，语义类似运行日志：
--    用户能查看自己可见任务的 execution，就应该能看到本次实际生效的低敏策略快照。
-- 3. 策略快照只保存低敏治理参数，不保存 SQL、where 原文、连接串、账号密码、token、样本行或真实分片边界。
SET search_path TO permission_admin, public;

INSERT INTO permission_route_policy
(tenant_id, policy_name, role_code, http_method, path_pattern, resource_type, action, effect, priority, enabled, description, create_time, update_time)
VALUES
(0, '项目负责人查看同步执行策略', 'PROJECT_OWNER', 'GET', '/api/sync/sync-execution-policies',
 'SYNC_OPERATION', 'VIEW', 'ALLOW', 149, TRUE,
 '项目负责人可查看授权项目相关执行策略，用于理解项目任务的并发、批大小、超时、重试和脏数据阈值来源。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '审计员查看同步执行策略', 'AUDITOR', 'GET', '/api/sync/sync-execution-policies',
 'SYNC_OPERATION', 'VIEW', 'ALLOW', 120, TRUE,
 '审计员可只读查看执行策略配置，用于复核管理员是否按租户、项目、连接器和任务维度做了合规治理。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '运营人员查看同步执行策略', 'OPERATOR', 'GET', '/api/sync/sync-execution-policies',
 'SYNC_OPERATION', 'VIEW', 'ALLOW', 785, TRUE,
 '运营人员可查看租户内同步执行策略，用于容量治理、故障诊断和客户支持。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '租户管理员查看同步执行策略', 'TENANT_ADMINISTRATOR', 'GET', '/api/sync/sync-execution-policies',
 'SYNC_OPERATION', 'VIEW', 'ALLOW', 765, TRUE,
 '租户管理员可查看本租户执行策略配置。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '平台管理员查看同步执行策略', 'PLATFORM_ADMINISTRATOR', 'GET', '/api/sync/sync-execution-policies',
 'SYNC_OPERATION', 'VIEW', 'ALLOW', 1001, TRUE,
 '平台管理员可跨租户查看执行策略配置。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '服务账号查看同步执行策略', 'SERVICE_ACCOUNT', 'GET', '/api/sync/sync-execution-policies',
 'SYNC_OPERATION', 'VIEW', 'ALLOW', 1002, TRUE,
 '服务账号可在受控内部流程中读取执行策略。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),

(0, '运营人员创建同步执行策略', 'OPERATOR', 'POST', '/api/sync/sync-execution-policies',
 'SYNC_OPERATION', 'CONFIGURE', 'ALLOW', 786, TRUE,
 '运营人员可为租户内项目、连接器、数据源或任务创建执行策略覆盖。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '租户管理员创建同步执行策略', 'TENANT_ADMINISTRATOR', 'POST', '/api/sync/sync-execution-policies',
 'SYNC_OPERATION', 'CONFIGURE', 'ALLOW', 766, TRUE,
 '租户管理员可为本租户创建执行策略覆盖。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '平台管理员创建同步执行策略', 'PLATFORM_ADMINISTRATOR', 'POST', '/api/sync/sync-execution-policies',
 'SYNC_OPERATION', 'CONFIGURE', 'ALLOW', 1003, TRUE,
 '平台管理员可创建全局、跨租户或客户现场级执行策略。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),

(0, '运营人员更新同步执行策略', 'OPERATOR', 'PUT', '/api/sync/sync-execution-policies/*',
 'SYNC_OPERATION', 'CONFIGURE', 'ALLOW', 787, TRUE,
 '运营人员可更新租户内执行策略；服务层仍会校验 tenant/project 数据范围。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '租户管理员更新同步执行策略', 'TENANT_ADMINISTRATOR', 'PUT', '/api/sync/sync-execution-policies/*',
 'SYNC_OPERATION', 'CONFIGURE', 'ALLOW', 767, TRUE,
 '租户管理员可更新本租户执行策略。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '平台管理员更新同步执行策略', 'PLATFORM_ADMINISTRATOR', 'PUT', '/api/sync/sync-execution-policies/*',
 'SYNC_OPERATION', 'CONFIGURE', 'ALLOW', 1004, TRUE,
 '平台管理员可更新全局和跨租户执行策略。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),

(0, '运营人员禁用同步执行策略', 'OPERATOR', 'DELETE', '/api/sync/sync-execution-policies/*',
 'SYNC_OPERATION', 'DELETE', 'ALLOW', 788, TRUE,
 '运营人员可软禁用租户内执行策略，保留审计痕迹。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '租户管理员禁用同步执行策略', 'TENANT_ADMINISTRATOR', 'DELETE', '/api/sync/sync-execution-policies/*',
 'SYNC_OPERATION', 'DELETE', 'ALLOW', 768, TRUE,
 '租户管理员可软禁用本租户执行策略，保留审计痕迹。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '平台管理员禁用同步执行策略', 'PLATFORM_ADMINISTRATOR', 'DELETE', '/api/sync/sync-execution-policies/*',
 'SYNC_OPERATION', 'DELETE', 'ALLOW', 1005, TRUE,
 '平台管理员可软禁用全局和跨租户执行策略。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),

(0, '普通用户查看同步执行策略快照', 'ORDINARY_USER', 'GET', '/api/sync/sync-tasks/*/executions/*/policy-snapshot',
 'SYNC_EXECUTION', 'VIEW', 'ALLOW', 129, TRUE,
 '普通用户可在自己可见任务的运行详情中查看本次执行低敏策略快照。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '项目负责人查看同步执行策略快照', 'PROJECT_OWNER', 'GET', '/api/sync/sync-tasks/*/executions/*/policy-snapshot',
 'SYNC_EXECUTION', 'VIEW', 'ALLOW', 150, TRUE,
 '项目负责人可查看授权项目内 execution 的低敏策略快照，用于解释并发、批大小和超时等运行表现。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '运营人员查看同步执行策略快照', 'OPERATOR', 'GET', '/api/sync/sync-tasks/*/executions/*/policy-snapshot',
 'SYNC_EXECUTION', 'VIEW', 'ALLOW', 789, TRUE,
 '运营人员可查看租户内 execution 的低敏策略快照，用于容量治理和故障排查。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '租户管理员查看同步执行策略快照', 'TENANT_ADMINISTRATOR', 'GET', '/api/sync/sync-tasks/*/executions/*/policy-snapshot',
 'SYNC_EXECUTION', 'VIEW', 'ALLOW', 769, TRUE,
 '租户管理员可查看本租户 execution 的低敏策略快照。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '审计员查看同步执行策略快照', 'AUDITOR', 'GET', '/api/sync/sync-tasks/*/executions/*/policy-snapshot',
 'SYNC_EXECUTION', 'VIEW', 'ALLOW', 121, TRUE,
 '审计员可只读查看 execution 低敏策略快照，用于复核历史运行为什么采用特定资源参数。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '平台管理员查看同步执行策略快照', 'PLATFORM_ADMINISTRATOR', 'GET', '/api/sync/sync-tasks/*/executions/*/policy-snapshot',
 'SYNC_EXECUTION', 'VIEW', 'ALLOW', 1006, TRUE,
 '平台管理员可跨租户查看 execution 低敏策略快照，用于平台级排障和交付验收。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT DO NOTHING;
