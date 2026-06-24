-- ---------------------------------------------------------------------------
-- agent-runtime：Agent ASYNC_TASK 命令 outbox
-- ---------------------------------------------------------------------------
-- 背景：
-- 1. ASYNC_TASK 工具不能长期占用同步 HTTP 线程；
-- 2. task-management 已具备 command Inbox，可以把 command 转换为可恢复任务；
-- 3. agent-runtime 仍需要本地 outbox，避免“工具审计状态已提交但 command 投递失败”的双写问题；
-- 4. 当前 Java 代码先提供 memory outbox，该表是生产 MySQL store 的目标结构。
--
-- 安全边界：
-- 1. payload_json 只能保存命令 envelope、payload_reference 和参数名快照；
-- 2. 不应保存原始工具参数、连接密钥、SQL、文件内容或样本数据；
-- 3. task-management 消费侧仍会用 commandId/idempotencyKey 再次去重。
-- ---------------------------------------------------------------------------

USE datasmart_govern;

CREATE TABLE IF NOT EXISTS agent_async_task_command_outbox (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '数据库自增主键，仅用于表内排序和运维定位',
    outbox_id VARCHAR(300) NOT NULL COMMENT 'outbox 记录唯一 ID，建议由 command_id 派生',
    command_id VARCHAR(128) NOT NULL COMMENT 'Agent Runtime 稳定生成的命令 ID',
    idempotency_key VARCHAR(255) NOT NULL COMMENT '跨服务幂等键，task-management Inbox 会再次据此去重',
    schema_version VARCHAR(80) NOT NULL COMMENT '命令协议版本',
    command_type VARCHAR(80) NOT NULL COMMENT '命令类型，例如 AGENT_TOOL_ASYNC_TASK_REQUESTED、AGENT_TOOL_ACTION_CONTROLLED_COMMAND',
    partition_key VARCHAR(160) DEFAULT NULL COMMENT '建议投递分区键，通常为 run_id',
    command_topic VARCHAR(160) DEFAULT NULL COMMENT '建议 Kafka topic',
    consumer_service VARCHAR(128) DEFAULT NULL COMMENT '建议消费者服务，默认 task-management',
    session_id VARCHAR(128) NOT NULL COMMENT 'Agent 会话 ID',
    run_id VARCHAR(128) NOT NULL COMMENT 'Agent Run ID',
    audit_id VARCHAR(128) NOT NULL COMMENT '工具执行审计 ID',
    tool_code VARCHAR(128) NOT NULL COMMENT '工具编码',
    target_service VARCHAR(128) NOT NULL COMMENT '工具最终目标服务',
    target_endpoint VARCHAR(300) DEFAULT NULL COMMENT '目标端点模板；新工具动作受控命令不携带 endpoint，避免外部输入变成内部任意调用路径',
    tenant_id BIGINT DEFAULT NULL COMMENT '租户边界',
    project_id BIGINT DEFAULT NULL COMMENT '项目边界',
    workspace_id BIGINT DEFAULT NULL COMMENT '工作空间边界',
    actor_id VARCHAR(128) DEFAULT NULL COMMENT '原始发起者',
    trace_id VARCHAR(128) DEFAULT NULL COMMENT '链路追踪 ID',
    payload_reference VARCHAR(512) NOT NULL COMMENT '受控载荷引用，例如 agent-tool-audit://session/run/audit/plan-arguments',
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING' COMMENT '投递状态：PENDING/PUBLISHING/PUBLISHED/FAILED/BLOCKED/DEAD_LETTER/IGNORED',
    attempt_count INT NOT NULL DEFAULT 0 COMMENT '投递尝试次数',
    payload_json JSON NOT NULL COMMENT '待投递命令 envelope；不应包含原始敏感参数值',
    payload_size_bytes INT NOT NULL DEFAULT 0 COMMENT 'payload UTF-8 字节数',
    payload_truncated TINYINT(1) NOT NULL DEFAULT 0 COMMENT 'payload 是否因超过安全上限被阻断或截断',
    next_retry_at DATETIME(3) DEFAULT NULL COMMENT '下一次允许重试时间',
    published_at DATETIME(3) DEFAULT NULL COMMENT '投递成功时间',
    last_error VARCHAR(1024) DEFAULT NULL COMMENT '最近一次失败或阻断原因',
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'outbox 记录创建时间',
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT 'outbox 记录更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_async_command_outbox_id (outbox_id),
    UNIQUE KEY uk_agent_async_command_id (command_id),
    UNIQUE KEY uk_agent_async_command_idempotency_key (idempotency_key),
    KEY idx_agent_async_command_status_retry (status, next_retry_at, id),
    KEY idx_agent_async_command_run_status (run_id, status, id),
    KEY idx_agent_async_command_audit (audit_id),
    KEY idx_agent_async_command_scope_time (tenant_id, project_id, workspace_id, update_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='agent-runtime ASYNC_TASK 命令 outbox 表';
