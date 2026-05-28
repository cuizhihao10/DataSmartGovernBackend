-- DataSmart Govern - Agent Runtime 工具执行入口权限策略
--
-- 背景说明：
-- 1. Agent Runtime 已具备工具目录、工具绑定、工具审计计划和人工审批入口；
-- 2. 本迁移补充第一版“执行已规划工具”的路由权限；
-- 3. 执行入口只允许服务层执行 PLANNED 状态工具，WAITING_APPROVAL 工具仍会被拒绝；
-- 4. datasource.metadata.read 是低风险只读工具，但仍走 AI_RUNTIME + EXECUTE，便于统一审计和限流。

INSERT IGNORE INTO permission_route_policy
(tenant_id, policy_name, role_code, http_method, path_pattern, resource_type, action, effect, priority, enabled, description, create_time, update_time)
VALUES
(0, '普通用户执行智能体已规划工具', 'ORDINARY_USER', 'PATCH', '/api/agent/sessions/*/runs/*/tool-executions/*/execute', 'AI_RUNTIME', 'EXECUTE', 'ALLOW', 120, 1, '普通用户可执行已规划且无需审批的 Agent 工具；真实工具适配器仍会做二次校验。', NOW(), NOW()),
(0, '项目负责人执行智能体已规划工具', 'PROJECT_OWNER', 'PATCH', '/api/agent/sessions/*/runs/*/tool-executions/*/execute', 'AI_RUNTIME', 'EXECUTE', 'ALLOW', 131, 1, '项目负责人可执行负责项目内已规划 Agent 工具，适合元数据读取、治理建议生成等受控能力。', NOW(), NOW()),
(0, '运营人员执行智能体已规划工具', 'OPERATOR', 'PATCH', '/api/agent/sessions/*/runs/*/tool-executions/*/execute', 'AI_RUNTIME', 'EXECUTE', 'ALLOW', 136, 1, '运营人员可执行排障和运行诊断场景下的已规划 Agent 工具。', NOW(), NOW()),
(0, '租户管理员执行智能体已规划工具', 'TENANT_ADMINISTRATOR', 'PATCH', '/api/agent/sessions/*/runs/*/tool-executions/*/execute', 'AI_RUNTIME', 'EXECUTE', 'ALLOW', 157, 1, '租户管理员可执行本租户范围内已规划 Agent 工具，用于治理配置、排障和审计辅助。', NOW(), NOW());
