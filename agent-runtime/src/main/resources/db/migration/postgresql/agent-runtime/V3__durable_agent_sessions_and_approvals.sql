-- Agent 会话聚合主表。
-- tenant_id + project_id + actor_id 构成用户历史的核心数据范围；agent_id 表示实际执行主体。
-- workspace_id 仅保留为兼容旧数据的可空列，新业务隔离以租户和项目为准。
CREATE TABLE IF NOT EXISTS agent_session (
    id BIGSERIAL PRIMARY KEY,
    session_id VARCHAR(96) NOT NULL UNIQUE,
    agent_id VARCHAR(128) NOT NULL,
    tenant_id BIGINT NOT NULL,
    project_id BIGINT NOT NULL,
    workspace_id BIGINT,
    actor_id VARCHAR(128) NOT NULL,
    actor_role VARCHAR(64),
    actor_type VARCHAR(64),
    authorized_project_roles TEXT,
    channel VARCHAR(64) NOT NULL,
    objective TEXT NOT NULL,
    isolation_level VARCHAR(32) NOT NULL,
    workspace_key VARCHAR(512) NOT NULL,
    state VARCHAR(32) NOT NULL,
    pinned BOOLEAN NOT NULL DEFAULT FALSE,
    archived_at TIMESTAMP,
    last_message_at TIMESTAMP NOT NULL,
    create_time TIMESTAMP NOT NULL,
    update_time TIMESTAMP NOT NULL
);

-- 支撑“当前用户的会话历史”：先显示置顶项，再按最近更新时间倒序。
CREATE INDEX IF NOT EXISTS idx_agent_session_owner_history
    ON agent_session (tenant_id, project_id, actor_id, pinned DESC, update_time DESC);
-- 支撑活跃/归档两个列表分区，避免随着历史增长而全表扫描。
CREATE INDEX IF NOT EXISTS idx_agent_session_archive
    ON agent_session (tenant_id, project_id, actor_id, archived_at, update_time DESC);

-- Agent 双主体模型中的委托快照。
-- 一次会话只能有一个当前委托；它把 Agent 可做的事情限制在用户本来权限的更小子集内，绝不用于提权。
-- tool_codes/actions/resource_scopes 使用 JSONB，是因为内容随工具协议演进，但执行时必须逐项解析并校验。
CREATE TABLE IF NOT EXISTS agent_delegation (
    id BIGSERIAL PRIMARY KEY,
    delegation_id VARCHAR(96) NOT NULL UNIQUE,
    session_id VARCHAR(96) NOT NULL UNIQUE REFERENCES agent_session(session_id) ON DELETE CASCADE,
    agent_id VARCHAR(128) NOT NULL,
    user_actor_id VARCHAR(128) NOT NULL,
    tenant_id BIGINT NOT NULL,
    project_id BIGINT NOT NULL,
    tool_codes JSONB NOT NULL DEFAULT '[]'::jsonb,
    actions JSONB NOT NULL DEFAULT '[]'::jsonb,
    resource_scopes JSONB NOT NULL DEFAULT '[]'::jsonb,
    status VARCHAR(32) NOT NULL,
    issued_at TIMESTAMP NOT NULL,
    expires_at TIMESTAMP,
    revoked_at TIMESTAMP,
    update_time TIMESTAMP NOT NULL
);

-- 支撑按用户和项目查找仍有效或已撤销/过期的委托证据。
CREATE INDEX IF NOT EXISTS idx_agent_delegation_scope
    ON agent_delegation (tenant_id, project_id, user_actor_id, status, expires_at);

-- 会话工具绑定快照，描述本次会话规划时可见的工具及其风险属性。
-- allowed_actions 只是能力声明；真正调用仍需通过委托、策略中心、审批事实和下游业务权限四重校验。
CREATE TABLE IF NOT EXISTS agent_session_tool_binding (
    id BIGSERIAL PRIMARY KEY,
    binding_id VARCHAR(96) NOT NULL UNIQUE,
    session_id VARCHAR(96) NOT NULL REFERENCES agent_session(session_id) ON DELETE CASCADE,
    tool_code VARCHAR(160) NOT NULL,
    tool_type VARCHAR(64) NOT NULL,
    display_name VARCHAR(255),
    target_service VARCHAR(160),
    target_endpoint VARCHAR(512),
    target_resource_id BIGINT,
    read_only BOOLEAN NOT NULL,
    risk_level VARCHAR(64),
    execution_mode VARCHAR(64),
    requires_approval BOOLEAN NOT NULL,
    idempotent BOOLEAN NOT NULL,
    status VARCHAR(32) NOT NULL,
    allowed_actions JSONB NOT NULL DEFAULT '[]'::jsonb,
    create_time TIMESTAMP NOT NULL
);
-- 按会话恢复工具列表，并保持创建时的稳定展示顺序。
CREATE INDEX IF NOT EXISTS idx_agent_tool_binding_session ON agent_session_tool_binding (session_id, create_time);

-- 同一会话中的多轮 Agent 运行记录。
-- next_actions 和 variables 是编排快照，状态与关键时间单独成列，便于监控和故障检索。
CREATE TABLE IF NOT EXISTS agent_run (
    id BIGSERIAL PRIMARY KEY,
    run_id VARCHAR(96) NOT NULL UNIQUE,
    session_id VARCHAR(96) NOT NULL REFERENCES agent_session(session_id) ON DELETE CASCADE,
    state VARCHAR(32) NOT NULL,
    workload_type VARCHAR(128),
    user_input_preview TEXT,
    dry_run BOOLEAN NOT NULL,
    require_human_approval BOOLEAN NOT NULL,
    next_actions JSONB NOT NULL DEFAULT '[]'::jsonb,
    variables JSONB NOT NULL DEFAULT '{}'::jsonb,
    message TEXT,
    create_time TIMESTAMP NOT NULL,
    update_time TIMESTAMP NOT NULL,
    finish_time TIMESTAMP
);
-- 支撑按会话倒序查看运行历史和快速定位最近一次运行。
CREATE INDEX IF NOT EXISTS idx_agent_run_session ON agent_run (session_id, create_time DESC);

-- 用户、助手和系统消息的持久上下文。
-- run_id 可空，因为用户消息可以先进入会话，随后才形成具体运行；删除会话时由外键级联清理。
CREATE TABLE IF NOT EXISTS agent_conversation_message (
    id BIGSERIAL PRIMARY KEY,
    message_id VARCHAR(96) NOT NULL UNIQUE,
    session_id VARCHAR(96) NOT NULL REFERENCES agent_session(session_id) ON DELETE CASCADE,
    run_id VARCHAR(96),
    role VARCHAR(24) NOT NULL,
    content TEXT NOT NULL,
    create_time TIMESTAMP NOT NULL
);
-- 继续历史会话时按发生顺序恢复消息，避免只依赖易失的进程内内存。
CREATE INDEX IF NOT EXISTS idx_agent_message_session ON agent_conversation_message (session_id, create_time);

-- Agent Runtime 保存的用户交互确认记录。
-- 该表证明用户确认了某个具体提案和载荷摘要，但不等于业务授权；执行时还要向权限中心核对审批事实。
-- confirmation_id 唯一约束为网络重试提供幂等性，expires_at 防止一次确认被永久复用。
CREATE TABLE IF NOT EXISTS agent_tool_action_approval_confirmation (
    id BIGSERIAL PRIMARY KEY,
    confirmation_id VARCHAR(160) NOT NULL UNIQUE,
    proposal_id VARCHAR(160),
    client_request_id VARCHAR(160),
    payload_reference VARCHAR(512),
    run_id VARCHAR(96),
    payload_key VARCHAR(160),
    tenant_id VARCHAR(64) NOT NULL,
    project_id VARCHAR(64) NOT NULL,
    actor_id VARCHAR(128) NOT NULL,
    confirming_actor_id VARCHAR(128),
    tool_name VARCHAR(160) NOT NULL,
    graph_id VARCHAR(160),
    contract_id VARCHAR(160),
    policy_version VARCHAR(160),
    payload_policy VARCHAR(160),
    payload_body_available BOOLEAN NOT NULL,
    payload_size_bytes INTEGER NOT NULL,
    payload_metadata_digest VARCHAR(256),
    accepted_payload_evidence JSONB NOT NULL DEFAULT '[]'::jsonb,
    confirmed BOOLEAN NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    confirmed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE
);
-- 支撑执行前按租户、项目、运行和工具快速定位尚未过期的确认记录。
CREATE INDEX IF NOT EXISTS idx_agent_approval_confirmation_scope
    ON agent_tool_action_approval_confirmation (tenant_id, project_id, run_id, tool_name, expires_at);
