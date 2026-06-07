-- ---------------------------------------------------------------------------
-- task-management：Agent 异步工具命令消费 Inbox
-- ---------------------------------------------------------------------------
-- 背景：
-- 1. agent-runtime 已经能够为 ASYNC_TASK 生成稳定 commandId 和 idempotencyKey；
-- 2. Kafka、网络重试、消费者重启、死信重放通常采用至少一次投递语义；
-- 3. 如果 task-management 收到消息后直接 INSERT task，同一 command 可能创建多个重复任务；
-- 4. 因此先使用 Inbox 唯一索引占用命令身份，再在同一个本地事务中创建任务并回写 task_id。
--
-- 安全边界：
-- 1. 表中只保存 payload_reference、argument_names、sensitive_argument_names；
-- 2. 不保存原始工具参数、密钥、SQL、文件内容或样本数据；
-- 3. worker 后续必须通过受控 resolver 按 payload_reference 读取载荷并再次校验权限。
-- ---------------------------------------------------------------------------

USE datasmart_govern;

CREATE TABLE IF NOT EXISTS agent_async_task_command_inbox (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'Agent 异步工具命令 Inbox 主键，仅用于表内更新和运维定位',
    command_id VARCHAR(128) NOT NULL COMMENT 'Agent Runtime 稳定生成的命令 ID；同一 session/run/audit 多次规划必须一致',
    idempotency_key VARCHAR(255) NOT NULL COMMENT '跨服务幂等键；Kafka 重投、消费者重启和人工重放必须复用相同值',
    schema_version VARCHAR(80) NOT NULL COMMENT '命令协议版本；消费侧只接受显式支持版本，避免升级后静默误执行',
    command_type VARCHAR(80) NOT NULL COMMENT '命令类型，例如 AGENT_TOOL_ASYNC_TASK_REQUESTED、AGENT_TOOL_ACTION_CONTROLLED_COMMAND',
    audit_id VARCHAR(128) NOT NULL COMMENT 'Agent 工具执行审计 ID；用于回查参数快照和回写工具状态',
    session_id VARCHAR(128) NOT NULL COMMENT 'Agent 会话 ID',
    run_id VARCHAR(128) NOT NULL COMMENT 'Agent Run ID',
    tool_code VARCHAR(128) NOT NULL COMMENT '工具编码，例如 data-sync.execute、quality.scan.start',
    target_service VARCHAR(128) NOT NULL COMMENT '工具最终面向的业务模块',
    target_endpoint VARCHAR(300) DEFAULT NULL COMMENT '工具目录声明的目标端点模板；历史 async-task 必填，新工具动作受控命令禁止携带，由后续执行器重新解析',
    tenant_id BIGINT NOT NULL COMMENT '租户隔离边界；异步自动化动作禁止缺失',
    project_id BIGINT NOT NULL COMMENT '项目隔离边界；用于项目级队列、公平性、权限和成本归集',
    workspace_id BIGINT DEFAULT NULL COMMENT 'Agent 工作空间隔离边界；历史 async-task 必填，新工具动作受控命令可为空并在 payload store/执行器阶段补强',
    actor_id VARCHAR(128) NOT NULL COMMENT '原始发起者，可以是用户、服务账号或 Agent 身份',
    trace_id VARCHAR(128) NOT NULL COMMENT '全链路追踪 ID',
    payload_reference VARCHAR(512) NOT NULL COMMENT '受控载荷引用；只允许保存安全引用，不保存原始工具参数值',
    argument_names JSON NOT NULL COMMENT '参数名 JSON 快照，不包含参数值',
    sensitive_argument_names JSON NOT NULL COMMENT '敏感参数名 JSON 快照，用于执行前脱敏、审批和审计复核',
    consume_state VARCHAR(32) NOT NULL COMMENT 'Inbox 消费状态：PROCESSING、TASK_CREATED',
    task_id BIGINT COMMENT '命令成功转换得到的 task.id',
    first_seen_time DATETIME NOT NULL COMMENT '首次收到命令时间',
    last_seen_time DATETIME NOT NULL COMMENT '最近收到命令时间；重复投递只刷新该字段',
    create_time DATETIME NOT NULL COMMENT '记录创建时间',
    update_time DATETIME NOT NULL COMMENT '记录更新时间',
    UNIQUE KEY uk_agent_async_task_command_id (command_id),
    UNIQUE KEY uk_agent_async_task_idempotency_key (idempotency_key),
    INDEX idx_agent_async_task_audit (audit_id),
    INDEX idx_agent_async_task_run (session_id, run_id, consume_state),
    INDEX idx_agent_async_task_scope_state (tenant_id, project_id, workspace_id, consume_state, update_time),
    INDEX idx_agent_async_task_task (task_id),
    INDEX idx_agent_async_task_state_time (consume_state, update_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Agent 异步工具命令消费 Inbox；用于跨服务去重和任务创建回执';
