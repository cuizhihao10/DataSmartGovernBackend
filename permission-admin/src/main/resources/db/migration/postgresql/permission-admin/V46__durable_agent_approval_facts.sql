-- 权限控制面独立保存的 Agent 工具审批事实。
-- 它与 Agent Runtime 的用户确认分开持久化，避免执行服务既生成授权又自行证明授权。
-- user/session/run/command/tool 的组合保留完整审计上下文，expires_at 限制批准的可复用时间窗口。
-- reason_codes 和 evidence_codes 使用 JSONB 承载可扩展的机器可读理由，但租户、项目和主体必须是独立列。
CREATE TABLE IF NOT EXISTS agent_tool_action_approval_fact (
    id BIGSERIAL PRIMARY KEY,
    approval_fact_id VARCHAR(160) NOT NULL UNIQUE,
    tenant_id BIGINT NOT NULL,
    project_id BIGINT NOT NULL,
    actor_id VARCHAR(128) NOT NULL,
    session_id VARCHAR(96) NOT NULL,
    run_id VARCHAR(96) NOT NULL,
    command_id VARCHAR(160) NOT NULL,
    tool_code VARCHAR(160) NOT NULL,
    policy_version VARCHAR(160),
    status VARCHAR(32) NOT NULL,
    expires_at TIMESTAMP,
    approved_by_actor_id VARCHAR(128),
    reason_codes JSONB NOT NULL DEFAULT '[]'::jsonb,
    evidence_codes JSONB NOT NULL DEFAULT '[]'::jsonb,
    create_time TIMESTAMP NOT NULL,
    update_time TIMESTAMP NOT NULL
);

-- 工具执行前按完整安全范围查找事实，避免只凭 approval_fact_id 跨会话复用。
CREATE INDEX IF NOT EXISTS idx_agent_approval_fact_scope
    ON agent_tool_action_approval_fact
    (tenant_id, project_id, actor_id, session_id, run_id, command_id, tool_code, status);
-- 支撑后台清理过期事实以及运维检查仍处于有效状态的授权。
CREATE INDEX IF NOT EXISTS idx_agent_approval_fact_expiry
    ON agent_tool_action_approval_fact (status, expires_at);

-- Gateway 路由策略是第一道入口门禁，仅允许服务账号角色提交登记请求。
-- permission-admin 控制器之后还会校验来源服务白名单和内部共享凭据，因此该 ALLOW 不代表无条件写入。
INSERT INTO permission_route_policy
(tenant_id, policy_name, role_code, http_method, path_pattern, resource_type, action, effect, priority, enabled,
 description, create_time, update_time)
VALUES
(0, 'Trusted service registers Agent approval facts', 'SERVICE_ACCOUNT', 'POST',
 '/api/permission/agent/tool-action-approvals/facts', 'AGENT_TOOL_APPROVAL_FACT', 'REGISTER', 'ALLOW', 1100, TRUE,
 'Gateway role policy is only the first gate; permission-admin also requires an allowlisted source service and shared internal credential.',
 CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT DO NOTHING;
