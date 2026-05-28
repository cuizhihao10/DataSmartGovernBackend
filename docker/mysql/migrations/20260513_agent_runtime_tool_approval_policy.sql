-- DataSmart Govern - Agent Runtime 高风险工具人工决策权限策略
--
-- 背景说明：
-- 1. Agent Runtime 已能在 Run 创建时识别 HIGH/CRITICAL 或 requiresApproval=true 的工具计划；
-- 2. 这些工具计划会进入 WAITING_APPROVAL，必须由具备治理职责的角色确认或拒绝；
-- 3. 本迁移只补充“人工决策入口”的路由权限，不代表工具可以绕过后续二次权限校验直接执行；
-- 4. 审计员保持只读，普通用户当前不默认拥有 APPROVE，避免把高风险工具决策下放过早。

INSERT IGNORE INTO permission_route_policy
(tenant_id, policy_name, role_code, http_method, path_pattern, resource_type, action, effect, priority, enabled, description, create_time, update_time)
VALUES
(0, '项目负责人确认智能体高风险工具计划', 'PROJECT_OWNER', 'PATCH', '/api/agent/sessions/*/runs/*/tool-executions/*/approve', 'AI_RUNTIME', 'APPROVE', 'ALLOW', 130, 1, '项目负责人可确认负责项目内 Agent Run 的高风险工具计划；确认后仍需工具适配器执行二次权限和项目范围校验。', NOW(), NOW()),
(0, '项目负责人拒绝智能体高风险工具计划', 'PROJECT_OWNER', 'PATCH', '/api/agent/sessions/*/runs/*/tool-executions/*/reject', 'AI_RUNTIME', 'APPROVE', 'ALLOW', 130, 1, '项目负责人可拒绝负责项目内 Agent Run 的高风险工具计划，拒绝后工具计划进入 SKIPPED。', NOW(), NOW()),
(0, '运营人员确认智能体高风险工具计划', 'OPERATOR', 'PATCH', '/api/agent/sessions/*/runs/*/tool-executions/*/approve', 'AI_RUNTIME', 'APPROVE', 'ALLOW', 135, 1, '运营人员可在排障、恢复或平台治理场景中确认高风险工具计划；该能力应结合审计记录使用。', NOW(), NOW()),
(0, '运营人员拒绝智能体高风险工具计划', 'OPERATOR', 'PATCH', '/api/agent/sessions/*/runs/*/tool-executions/*/reject', 'AI_RUNTIME', 'APPROVE', 'ALLOW', 135, 1, '运营人员可拒绝不安全或不合规的高风险工具计划，避免模型继续推进危险动作。', NOW(), NOW()),
(0, '租户管理员确认智能体高风险工具计划', 'TENANT_ADMINISTRATOR', 'PATCH', '/api/agent/sessions/*/runs/*/tool-executions/*/approve', 'AI_RUNTIME', 'APPROVE', 'ALLOW', 156, 1, '租户管理员可确认本租户范围内 Agent 高风险工具计划，用于租户级治理和应急处理。', NOW(), NOW()),
(0, '租户管理员拒绝智能体高风险工具计划', 'TENANT_ADMINISTRATOR', 'PATCH', '/api/agent/sessions/*/runs/*/tool-executions/*/reject', 'AI_RUNTIME', 'APPROVE', 'ALLOW', 156, 1, '租户管理员可拒绝本租户范围内 Agent 高风险工具计划，保证租户安全策略优先于模型规划。', NOW(), NOW());
