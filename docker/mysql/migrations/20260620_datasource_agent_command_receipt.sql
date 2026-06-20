-- ---------------------------------------------------------------------------
-- datasource-management: Agent data-sync.execute command receipt
-- ---------------------------------------------------------------------------
-- 背景：
-- 1. task-management 已经持有 DataSync worker command outbox，用于记录“准备调用 data-sync”的本地命令事实；
-- 2. datasource-management 仍然需要自己的 receipt，用于记录“下游已经接收命令并创建/入队同步任务”的副作用事实；
-- 3. 如果只有上游 outbox，没有下游 receipt，HTTP 超时后上游重试可能在 datasource 侧创建重复同步任务；
-- 4. command_id 与 idempotency_key 均设置唯一键，保证跨服务重试和人工重放不会重复越过副作用边界。
--
-- 安全边界：
-- 1. 本表只保存低敏控制面元数据、任务引用和状态；
-- 2. 不保存工具参数正文、SQL、连接串、用户名、密码、样本数据、prompt、模型输出或内部 endpoint；
-- 3. message 只能写入低敏结果摘要，不能写异常堆栈或原始业务数据；
-- 4. name/description 不进入本表，避免把 Agent 自然语言上下文复制到跨服务 receipt 台账。
-- ---------------------------------------------------------------------------

USE datasmart_govern;

CREATE TABLE IF NOT EXISTS sync_agent_command_receipt (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '数据库自增主键，仅用于表内排序、分页和运维定位',
    receipt_id VARCHAR(180) NOT NULL COMMENT 'datasource-management 本地 receipt ID；当前由 command_id 派生，区别于 task-management outbox receipt',
    command_id VARCHAR(128) NOT NULL COMMENT 'Agent Runtime 稳定命令 ID；同一 command 在 datasource 侧只能创建或复用一条同步任务落地记录',
    idempotency_key VARCHAR(255) NOT NULL COMMENT '跨服务幂等键；HTTP 超时、Kafka 重放或 dispatcher 重试时必须复用该值',
    agent_session_id VARCHAR(128) DEFAULT NULL COMMENT 'Agent 会话 ID，用于低敏 timeline 与排障关联',
    agent_run_id VARCHAR(128) DEFAULT NULL COMMENT 'Agent run ID；与 datasource sync_execution.id 不是同一个 ID 空间',
    audit_id VARCHAR(128) DEFAULT NULL COMMENT 'Agent 工具审计 ID，用于关联审批、readiness 和运行时事件',
    tool_code VARCHAR(128) NOT NULL COMMENT '工具编码，当前只接受 data-sync.execute',
    tenant_id BIGINT NOT NULL COMMENT '租户边界快照',
    project_id BIGINT DEFAULT NULL COMMENT '项目边界快照；任务最终 project 仍以同步模板为准',
    workspace_id BIGINT DEFAULT NULL COMMENT '工作空间边界快照；任务最终 workspace 仍以同步模板为准',
    actor_id VARCHAR(128) DEFAULT NULL COMMENT '原始发起人 ID，保留字符串形式以兼容未来统一账号体系',
    trace_id VARCHAR(128) DEFAULT NULL COMMENT '链路追踪 ID',
    template_id BIGINT DEFAULT NULL COMMENT '历史兼容模板 ID；sync_template_id 为空时可作为 datasource 同步模板 ID 使用',
    sync_template_id BIGINT DEFAULT NULL COMMENT 'data-sync 同步模板 ID；新链路优先使用该字段',
    resolved_template_id BIGINT NOT NULL COMMENT 'datasource-management 实际采用的同步模板 ID',
    sync_task_id BIGINT DEFAULT NULL COMMENT 'datasource-management 创建或复用的同步任务 ID',
    sync_execution_id BIGINT DEFAULT NULL COMMENT '当前阶段通常为空；若后续内部入口升级为立即运行，则保存 execution ID',
    status VARCHAR(32) NOT NULL COMMENT 'RECEIVED/QUEUED 等 receipt 状态；RECEIVED 表示幂等键已抢占，QUEUED 表示任务已入队',
    downstream_state VARCHAR(64) NOT NULL COMMENT '同步任务当前低敏状态快照，例如 QUEUED',
    side_effect_started TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否已经越过 datasource 侧副作用边界，例如创建 sync_task',
    side_effect_executed TINYINT(1) NOT NULL DEFAULT 0 COMMENT '副作用是否已完成到可继续调度的程度；当前表示已创建并入队',
    duplicate TINYINT(1) NOT NULL DEFAULT 0 COMMENT '最近一次响应是否为 datasource 侧幂等重复命中',
    message VARCHAR(512) DEFAULT NULL COMMENT '低敏结果说明；不得保存 SQL、连接串、凭据、样本数据、prompt、模型输出或工具实参正文',
    create_time DATETIME NOT NULL COMMENT '记录创建时间',
    update_time DATETIME NOT NULL COMMENT '记录更新时间',
    UNIQUE KEY uk_sync_agent_command_receipt_id (receipt_id),
    UNIQUE KEY uk_sync_agent_command_id (command_id),
    UNIQUE KEY uk_sync_agent_command_idempotency (idempotency_key),
    INDEX idx_sync_agent_command_scope_time (tenant_id, project_id, workspace_id, update_time),
    INDEX idx_sync_agent_command_task (sync_task_id, sync_execution_id),
    INDEX idx_sync_agent_command_run (agent_run_id, agent_session_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='datasource-management Agent 命令接收 receipt 表';
