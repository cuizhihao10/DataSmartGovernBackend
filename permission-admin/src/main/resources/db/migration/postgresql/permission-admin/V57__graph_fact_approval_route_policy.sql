-- permission-admin：业务图事实审批控制面路由。
--
-- 图事实审批只登记低敏事实和指纹，真正的图写入由 Kafka consumer 在审批回查通过后执行。
-- 这里把登记/评估路由明确纳入服务账号和审计角色边界，避免只依赖代码注释。

SET search_path TO permission_admin, public;

INSERT INTO permission_route_policy
(tenant_id, policy_name, role_code, http_method, path_pattern, resource_type, action, effect, priority, enabled, description, create_time, update_time)
VALUES
(0, '服务账号登记图事实审批', 'SERVICE_ACCOUNT', 'POST', '/api/permission/agent/graph-facts/approvals', 'GRAPH_FACTS', 'APPROVE', 'ALLOW', 830, TRUE, '受信 Agent/审批服务可登记图事实候选或审批结果；服务身份和最终决定权限仍由控制器校验。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '服务账号评估图事实审批', 'SERVICE_ACCOUNT', 'POST', '/api/permission/agent/graph-facts/evaluate', 'GRAPH_FACTS', 'EVALUATE', 'ALLOW', 830, TRUE, '受控图摄取 worker 可回查当前图事实审批绑定。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '审计员查看图事实审批', 'AUDITOR', 'POST', '/api/permission/agent/graph-facts/evaluate', 'GRAPH_FACTS', 'EVALUATE', 'ALLOW', 120, TRUE, '审计员可复核图事实审批判定，但不直接获得事实正文或图写权限。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT DO NOTHING;
