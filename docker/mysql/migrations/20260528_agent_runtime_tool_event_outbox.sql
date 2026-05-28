-- DataSmart Govern Backend - agent-runtime 工具执行事件 outbox 表
--
-- 设计目的：
-- 1. 解决“业务状态已经提交，但 Kafka/WebSocket/审计中心事件投递失败”的双写可靠性问题。
-- 2. 让工具状态事件先与业务事实一起落在本地事务边界内，再由后台 dispatcher 异步投递到下游。
-- 3. 为 Python AI Runtime 的事件驱动 loop、Gateway WebSocket replay、审计中心对账和人工补偿提供统一事实源。
--
-- 当前 Java 代码仍使用内存版 outbox store；该表先作为生产持久化目标落地。
-- 后续接入 MyBatis/JDBC 时，应把工具审计状态更新与本表 INSERT 放入同一个 MySQL 事务。

CREATE TABLE IF NOT EXISTS agent_tool_execution_event_outbox (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '数据库自增主键，仅用于表内排序和运维定位，不作为跨服务事件 ID',
    outbox_id VARCHAR(300) NOT NULL COMMENT 'outbox 记录唯一 ID，建议由 event_id 派生，保证重复写入可幂等去重',
    event_id VARCHAR(255) NOT NULL COMMENT '工具状态事件 ID，对应 AgentToolExecutionStateChangedEvent.eventId',
    event_type VARCHAR(128) NOT NULL COMMENT '事件类型，例如 agent.tool_execution.state_changed',
    schema_version VARCHAR(80) NOT NULL COMMENT '事件契约版本，用于消费者兼容多版本 payload',
    source VARCHAR(80) NOT NULL COMMENT '事件来源服务，例如 agent-runtime、tool-runner、sandbox-executor',
    partition_key VARCHAR(160) DEFAULT NULL COMMENT '建议投递到 Kafka 时使用的分区键，通常为 runId/sessionId/auditId',
    tenant_id BIGINT DEFAULT NULL COMMENT '租户 ID，用于多租户隔离和审计过滤',
    project_id BIGINT DEFAULT NULL COMMENT '项目 ID，用于项目级数据范围过滤',
    workspace_id BIGINT DEFAULT NULL COMMENT 'Agent 工作空间 ID，用于后续 workspace 隔离和产物追踪',
    actor_id VARCHAR(128) DEFAULT NULL COMMENT '触发该 Agent Run 或工具计划的用户/服务账号 ID',
    session_id VARCHAR(128) DEFAULT NULL COMMENT 'Agent 会话 ID',
    run_id VARCHAR(128) DEFAULT NULL COMMENT 'Agent Run ID，前端详情页和 Python replay 主要按该字段查询',
    audit_id VARCHAR(128) NOT NULL COMMENT '工具执行审计 ID，对应一次工具计划/审批/执行状态链路',
    tool_code VARCHAR(128) DEFAULT NULL COMMENT '工具编码，例如 datasource.metadata.read、task.draft.persist',
    current_state VARCHAR(64) DEFAULT NULL COMMENT '工具业务状态，例如 PLANNED、WAITING_APPROVAL、EXECUTING、SUCCEEDED、FAILED',
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING' COMMENT 'outbox 投递状态：PENDING/PUBLISHING/PUBLISHED/FAILED/BLOCKED/IGNORED',
    attempt_count INT NOT NULL DEFAULT 0 COMMENT '投递尝试次数，dispatcher 每次领取或发送时递增',
    payload_json JSON NOT NULL COMMENT '待投递事件 payload；应使用脱敏后的工具状态事件，不应写入完整工具入参或原始输出',
    payload_size_bytes INT NOT NULL DEFAULT 0 COMMENT 'payload UTF-8 字节数，用于发现异常大事件',
    payload_truncated TINYINT(1) NOT NULL DEFAULT 0 COMMENT 'payload 是否因超过安全上限而被截断或阻断',
    occurred_at DATETIME(3) NOT NULL COMMENT '业务事件发生时间，来自工具状态事件 occurredAt',
    next_retry_at DATETIME(3) DEFAULT NULL COMMENT '下一次允许重试投递的时间',
    published_at DATETIME(3) DEFAULT NULL COMMENT '投递成功时间',
    last_error VARCHAR(1024) DEFAULT NULL COMMENT '最近一次投递失败或阻断原因',
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'outbox 记录创建时间',
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT 'outbox 记录更新时间；PUBLISHING 状态下也作为轻量领取时间，用于 dispatcher 崩溃后的 stale 恢复',
    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_tool_event_outbox_outbox_id (outbox_id),
    UNIQUE KEY uk_agent_tool_event_outbox_event_id (event_id),
    KEY idx_agent_tool_event_outbox_status_retry (status, next_retry_at, id),
    KEY idx_agent_tool_event_outbox_status_update (status, update_time, id),
    KEY idx_agent_tool_event_outbox_run_status (run_id, status, id),
    KEY idx_agent_tool_event_outbox_audit (audit_id),
    KEY idx_agent_tool_event_outbox_scope_time (tenant_id, project_id, occurred_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='agent-runtime 工具执行状态事件 outbox 表';
