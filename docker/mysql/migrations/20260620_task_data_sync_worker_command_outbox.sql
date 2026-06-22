-- ---------------------------------------------------------------------------
-- task-management: DataSync worker command outbox
-- ---------------------------------------------------------------------------
-- 背景：
-- 1. datasource-management 已具备 data-sync 单批 Runner，但 task-management 不能直接把跨服务调用当成普通 HTTP；
-- 2. 任务中心需要先持久化“准备调用 data-sync”的本地命令事实，再记录下游 receipt；
-- 3. 该表是后续 dispatcher、补偿器、worker receipt、租约续期、暂停/取消和跨模块排障的基础。
--
-- 安全边界：
-- 1. payload_json 只保存低敏 ID、任务边界和控制字段；
-- 2. 不保存 SQL、连接串、密码、样本数据、工具参数正文、模型输出或用户 prompt；
-- 3. receipt 只保存 sync_task_id、sync_execution_id、状态和低敏错误摘要；
-- 4. 真正的幂等裁决依赖 command_id、idempotency_key、outbox_id 和 receipt_id 唯一键。
-- ---------------------------------------------------------------------------

USE datasmart_govern;

CREATE TABLE IF NOT EXISTS task_data_sync_worker_command_outbox (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '数据库自增主键，仅用于表内排序、分页和运维定位',
    outbox_id VARCHAR(300) NOT NULL COMMENT 'task-management 本地 outbox 记录唯一 ID，建议由 command_id 派生',
    command_id VARCHAR(128) NOT NULL COMMENT 'Agent Runtime 稳定命令 ID；同一 command 只能对应一条 data-sync worker outbox',
    idempotency_key VARCHAR(255) NOT NULL COMMENT '跨服务幂等键；也会传给 data-sync 内部入口',
    task_id BIGINT NOT NULL COMMENT 'task-management 任务 ID',
    agent_run_id VARCHAR(128) NOT NULL COMMENT 'Agent Runtime run ID；与 task_execution_run 不是同一 ID 空间',
    agent_session_id VARCHAR(128) DEFAULT NULL COMMENT 'Agent 会话 ID，用于 timeline 和排障关联',
    audit_id VARCHAR(128) DEFAULT NULL COMMENT 'Agent 工具审计 ID',
    tool_code VARCHAR(128) NOT NULL COMMENT '工具编码，例如 data-sync.execute',
    target_service VARCHAR(128) NOT NULL COMMENT '目标服务，当前为 data-sync',
    operation VARCHAR(80) NOT NULL COMMENT '命令操作类型，例如 DATA_SYNC_EXECUTE',
    tenant_id BIGINT DEFAULT NULL COMMENT '租户边界',
    project_id BIGINT DEFAULT NULL COMMENT '项目边界',
    workspace_id BIGINT DEFAULT NULL COMMENT '工作空间边界',
    actor_id VARCHAR(128) DEFAULT NULL COMMENT '原始发起者',
    trace_id VARCHAR(128) DEFAULT NULL COMMENT '链路追踪 ID',
    template_id BIGINT DEFAULT NULL COMMENT '历史任务模板 ID，可为空',
    sync_template_id BIGINT DEFAULT NULL COMMENT 'data-sync 模板 ID，可为空但 template_id/sync_template_id 至少一个应存在',
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/DISPATCHING/DEFERRED/SUCCEEDED/FAILED/DEAD_LETTER/CLOSED',
    attempt_count INT NOT NULL DEFAULT 0 COMMENT '投递尝试次数',
    payload_json JSON NOT NULL COMMENT '低敏命令 payload；不应包含工具参数正文、SQL、凭据或样本数据',
    payload_size_bytes INT NOT NULL DEFAULT 0 COMMENT 'payload UTF-8 字节数',
    payload_truncated TINYINT(1) NOT NULL DEFAULT 0 COMMENT 'payload 是否被截断；正常情况下应为 false，超限应阻断而不是截断执行',
    next_retry_at DATETIME(3) DEFAULT NULL COMMENT '下一次允许重试时间',
    dispatched_at DATETIME(3) DEFAULT NULL COMMENT '最近一次开始投递时间',
    receipt_id VARCHAR(180) DEFAULT NULL COMMENT '下游 worker receipt ID；成功或失败 receipt 都应稳定可重放',
    sync_task_id BIGINT DEFAULT NULL COMMENT 'data-sync 返回的同步任务 ID',
    sync_execution_id BIGINT DEFAULT NULL COMMENT 'data-sync 返回的同步 execution ID',
    side_effect_started TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否已经越过下游调用副作用边界',
    side_effect_executed TINYINT(1) NOT NULL DEFAULT 0 COMMENT '下游是否确认副作用已被接受或执行',
    last_error VARCHAR(1024) DEFAULT NULL COMMENT '最近一次低敏错误摘要',
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '记录创建时间',
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '记录更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_task_datasync_worker_outbox_id (outbox_id),
    UNIQUE KEY uk_task_datasync_worker_command_id (command_id),
    UNIQUE KEY uk_task_datasync_worker_idempotency_key (idempotency_key),
    UNIQUE KEY uk_task_datasync_worker_receipt_id (receipt_id),
    KEY idx_task_datasync_worker_status_retry (status, next_retry_at, id),
    KEY idx_task_datasync_worker_task_status (task_id, status, id),
    KEY idx_task_datasync_worker_scope_time (tenant_id, project_id, workspace_id, update_time),
    KEY idx_task_datasync_worker_sync_ref (sync_task_id, sync_execution_id),
    KEY idx_task_datasync_worker_agent_run (agent_run_id, agent_session_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='task-management 到 data-sync worker 的命令 outbox 与 receipt 表';
