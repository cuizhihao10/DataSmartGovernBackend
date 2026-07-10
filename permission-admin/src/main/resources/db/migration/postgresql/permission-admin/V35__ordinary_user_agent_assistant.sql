-- Open the user-facing intelligent assistant while keeping runtime governance/admin endpoints restricted.
SET search_path TO permission_admin, public;

INSERT INTO permission_role_menu_binding
(tenant_id, role_code, menu_code, enabled, binding_source, note, create_time, update_time)
VALUES
(0, 'ORDINARY_USER', 'agent-runtime', TRUE, 'BOOTSTRAP',
 '普通用户可使用本人已加入项目范围内的智能助手；数据源凭据仍只能通过数据源管理安全表单提交。',
 CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (tenant_id, role_code, menu_code) DO UPDATE
SET enabled = EXCLUDED.enabled,
    note = EXCLUDED.note,
    update_time = CURRENT_TIMESTAMP;

INSERT INTO permission_route_policy
(tenant_id, policy_name, role_code, http_method, path_pattern, resource_type, action, effect, priority, enabled,
 description, create_time, update_time)
VALUES
(0, '普通用户生成本人项目 Agent 计划', 'ORDINARY_USER', 'POST', '/api/agent/plans', 'AI_RUNTIME', 'EXECUTE',
 'ALLOW', 930, TRUE, 'Python Runtime 从 gateway 可信身份重建 tenant/project/actor；请求体不能扩大数据范围。',
 CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '普通用户查看本人 Agent 工具计划', 'ORDINARY_USER', 'GET',
 '/api/agent/sessions/*/runs/*/tool-executions', 'AI_RUNTIME', 'VIEW', 'ALLOW', 930, TRUE,
 '普通用户前端只读取本人本项目 Run 的节点状态；确认执行接口仍会再次校验 session 发起人。',
 CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '普通用户确认执行本人 Agent 计划', 'ORDINARY_USER', 'POST',
 '/api/agent/sessions/*/runs/*/confirm-and-execute', 'AI_RUNTIME', 'EXECUTE', 'ALLOW', 940, TRUE,
 '一次确认只作用于当前 Run；Java 服务端校验 tenant/project/actor 与 session 发起人完全一致。',
 CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '普通用户查看本人 Agent 工具结果', 'ORDINARY_USER', 'GET',
 '/api/agent/sessions/*/runs/*/tool-executions/results', 'AI_RUNTIME', 'VIEW', 'ALLOW', 930, TRUE,
 '只返回受控工具输出与审计摘要，不返回数据库凭据、SQL 正文或样本数据。',
 CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '项目负责人生成项目 Agent 计划', 'PROJECT_OWNER', 'POST', '/api/agent/plans', 'AI_RUNTIME', 'EXECUTE',
 'ALLOW', 940, TRUE, '项目负责人可在本人授权项目中使用智能助手。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '项目负责人查看 Agent 工具计划', 'PROJECT_OWNER', 'GET',
 '/api/agent/sessions/*/runs/*/tool-executions', 'AI_RUNTIME', 'VIEW', 'ALLOW', 940, TRUE,
 '项目负责人可查看本人发起 Run 的工具节点。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '项目负责人确认执行本人 Agent 计划', 'PROJECT_OWNER', 'POST',
 '/api/agent/sessions/*/runs/*/confirm-and-execute', 'AI_RUNTIME', 'EXECUTE', 'ALLOW', 950, TRUE,
 '确认执行仍限定为 Run 发起人，项目负责人身份不会自动接管其他成员会话。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '项目负责人查看 Agent 工具结果', 'PROJECT_OWNER', 'GET',
 '/api/agent/sessions/*/runs/*/tool-executions/results', 'AI_RUNTIME', 'VIEW', 'ALLOW', 940, TRUE,
 '项目负责人读取本人 Run 的低敏工具结果。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT DO NOTHING;

INSERT INTO permission_data_scope_policy
(tenant_id, role_code, resource_type, scope_level, scope_expression, approval_required, enabled, description,
 create_time, update_time)
VALUES
(0, 'ORDINARY_USER', 'AI_RUNTIME', 'SELF', 'actor_id = ${actorId} AND project_id IN ${actorProjectIds}', FALSE, TRUE,
 '普通用户只能访问本人在已授权项目中发起的 Agent 会话、Run、工具计划和结果。',
 CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, 'PROJECT_OWNER', 'AI_RUNTIME', 'SELF', 'actor_id = ${actorId} AND project_id IN ${actorProjectIds}', FALSE, TRUE,
 '项目负责人使用智能助手时仍以本人会话为边界；项目级治理审计通过独立管理员接口完成。',
 CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (tenant_id, role_code, resource_type) DO UPDATE
SET scope_level = EXCLUDED.scope_level,
    scope_expression = EXCLUDED.scope_expression,
    approval_required = EXCLUDED.approval_required,
    enabled = EXCLUDED.enabled,
    description = EXCLUDED.description,
    update_time = CURRENT_TIMESTAMP;
