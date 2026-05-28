-- DataSmart Govern - Agent Runtime 路由权限策略补充
--
-- 背景：
-- 1. agent-runtime 是 Java 侧 AI 控制面，后续会承载模型路由、Agent 编排和工具调用入口；
-- 2. 即使当前模型调用仍是 DRY_RUN，也必须先进入 gateway + permission-admin 的权限矩阵；
-- 3. 这样后续接入真实模型、工具调用、工作区隔离时，不会出现 AI 入口绕过 RBAC 的架构漏洞。

INSERT IGNORE INTO permission_route_policy
(tenant_id, policy_name, role_code, http_method, path_pattern, resource_type, action, effect, priority, enabled, description, create_time, update_time)
VALUES
(0, '普通用户调用智能体模型入口', 'ORDINARY_USER', 'POST', '/api/agent/models/chat', 'AI_RUNTIME', 'EXECUTE', 'ALLOW', 118, 1, '普通用户可调用 Agent Runtime 的模型入口；真实数据访问仍由下游工具权限和项目数据范围控制。', NOW(), NOW()),
(0, '普通用户查看智能体工具目录', 'ORDINARY_USER', 'GET', '/api/agent/tools/**', 'AI_RUNTIME', 'VIEW', 'ALLOW', 118, 1, '普通用户可查看可绑定 Agent 工具、风险等级和输入参数，但真实执行仍由工具权限和项目范围控制。', NOW(), NOW()),
(0, '普通用户创建智能体会话', 'ORDINARY_USER', 'POST', '/api/agent/sessions/**', 'AI_RUNTIME', 'CREATE', 'ALLOW', 119, 1, '普通用户可在授权项目范围内创建 Agent 会话；会话内工具执行仍需要下游数据范围和工具权限校验。', NOW(), NOW()),
(0, '普通用户查看智能体会话', 'ORDINARY_USER', 'GET', '/api/agent/sessions/**', 'AI_RUNTIME', 'VIEW', 'ALLOW', 119, 1, '普通用户可查看自己项目范围内的 Agent 会话上下文和运行状态。', NOW(), NOW()),
(0, '普通用户执行智能体运行', 'ORDINARY_USER', 'POST', '/api/agent/sessions/*/runs', 'AI_RUNTIME', 'EXECUTE', 'ALLOW', 119, 1, '普通用户可在已创建会话内发起 Agent Run；真实工具执行仍由工具适配器二次校验。', NOW(), NOW()),
(0, '普通用户取消智能体运行', 'ORDINARY_USER', 'POST', '/api/agent/sessions/*/runs/*/cancel', 'AI_RUNTIME', 'CANCEL', 'ALLOW', 119, 1, '普通用户可取消自己会话中尚未完成的 Agent Run。', NOW(), NOW()),
(0, '项目负责人调用智能体模型入口', 'PROJECT_OWNER', 'POST', '/api/agent/models/chat', 'AI_RUNTIME', 'EXECUTE', 'ALLOW', 128, 1, '项目负责人可调用 Agent Runtime 进行项目范围内的数据治理问答、规则辅助和任务规划。', NOW(), NOW()),
(0, '项目负责人查看智能体工具目录', 'PROJECT_OWNER', 'GET', '/api/agent/tools/**', 'AI_RUNTIME', 'VIEW', 'ALLOW', 128, 1, '项目负责人可查看项目治理可用工具目录，用于创建会话和绑定工具前确认风险。', NOW(), NOW()),
(0, '项目负责人创建智能体会话', 'PROJECT_OWNER', 'POST', '/api/agent/sessions/**', 'AI_RUNTIME', 'CREATE', 'ALLOW', 129, 1, '项目负责人可在负责项目内创建 Agent 会话、绑定治理工具和发起规划。', NOW(), NOW()),
(0, '项目负责人查看智能体会话', 'PROJECT_OWNER', 'GET', '/api/agent/sessions/**', 'AI_RUNTIME', 'VIEW', 'ALLOW', 129, 1, '项目负责人可查看负责项目内的 Agent 会话、工具绑定和运行状态。', NOW(), NOW()),
(0, '项目负责人执行智能体运行', 'PROJECT_OWNER', 'POST', '/api/agent/sessions/*/runs', 'AI_RUNTIME', 'EXECUTE', 'ALLOW', 129, 1, '项目负责人可发起项目范围内的 Agent Run，用于数据治理问答、规则辅助和任务规划。', NOW(), NOW()),
(0, '项目负责人取消智能体运行', 'PROJECT_OWNER', 'POST', '/api/agent/sessions/*/runs/*/cancel', 'AI_RUNTIME', 'CANCEL', 'ALLOW', 129, 1, '项目负责人可取消负责项目内尚未完成的 Agent Run。', NOW(), NOW()),
(0, '运营人员查看模型路由', 'OPERATOR', 'GET', '/api/agent/models/routes', 'AI_RUNTIME', 'VIEW', 'ALLOW', 134, 1, '运营人员可查看 Agent Runtime 模型路由，用于排查模型 Provider、工作负载和超时配置。', NOW(), NOW()),
(0, '运营人员查看智能体工具目录', 'OPERATOR', 'GET', '/api/agent/tools/**', 'AI_RUNTIME', 'VIEW', 'ALLOW', 134, 1, '运营人员可查看 Agent 工具目录，用于排查工具是否启用、风险等级和下游服务映射。', NOW(), NOW()),
(0, '运营人员调用智能体模型入口', 'OPERATOR', 'POST', '/api/agent/models/chat', 'AI_RUNTIME', 'EXECUTE', 'ALLOW', 134, 1, '运营人员可调用 Agent Runtime 辅助事故排查、运行分析和治理建议生成。', NOW(), NOW()),
(0, '运营人员管理智能体会话', 'OPERATOR', 'ANY', '/api/agent/sessions/**', 'AI_RUNTIME', NULL, 'ALLOW', 134, 1, '运营人员可创建、查看和取消排障类 Agent 会话与运行，用于事故分析和运行诊断。', NOW(), NOW()),
(0, '审计员查看模型路由', 'AUDITOR', 'GET', '/api/agent/models/routes', 'AI_RUNTIME', 'VIEW', 'ALLOW', 111, 1, '审计员可只读查看 Agent Runtime 模型路由，用于复核 AI 调用链路与模型选择。', NOW(), NOW()),
(0, '审计员查看智能体工具目录', 'AUDITOR', 'GET', '/api/agent/tools/**', 'AI_RUNTIME', 'VIEW', 'ALLOW', 111, 1, '审计员可只读查看 Agent 工具目录，用于复核工具风险、审批要求和下游服务映射。', NOW(), NOW()),
(0, '审计员查看智能体会话', 'AUDITOR', 'GET', '/api/agent/sessions/**', 'AI_RUNTIME', 'VIEW', 'ALLOW', 112, 1, '审计员可只读查看 Agent 会话、工具绑定和运行状态，用于复核 AI 辅助治理过程。', NOW(), NOW()),
(0, '租户管理员调用智能体模型入口', 'TENANT_ADMINISTRATOR', 'POST', '/api/agent/models/chat', 'AI_RUNTIME', 'EXECUTE', 'ALLOW', 155, 1, '租户管理员可调用 Agent Runtime 辅助租户内治理配置、权限分析和任务排障。', NOW(), NOW()),
(0, '租户管理员查看智能体工具目录', 'TENANT_ADMINISTRATOR', 'GET', '/api/agent/tools/**', 'AI_RUNTIME', 'VIEW', 'ALLOW', 155, 1, '租户管理员可查看本租户可用 Agent 工具目录，用于治理能力配置和排障。', NOW(), NOW()),
(0, '租户管理员管理智能体会话', 'TENANT_ADMINISTRATOR', 'ANY', '/api/agent/sessions/**', 'AI_RUNTIME', NULL, 'ALLOW', 155, 1, '租户管理员可管理本租户 Agent 会话，用于租户级治理问答、权限分析和任务排障。', NOW(), NOW());

UPDATE permission_route_policy
SET resource_type = 'AI_RUNTIME',
    action = CASE
        WHEN action IS NOT NULL THEN action
        WHEN http_method = 'GET' THEN 'VIEW'
        WHEN http_method = 'POST' THEN 'EXECUTE'
        ELSE action
    END
WHERE path_pattern LIKE '/api/agent/%';
