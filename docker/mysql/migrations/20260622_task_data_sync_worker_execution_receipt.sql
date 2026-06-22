-- ---------------------------------------------------------------------------
-- task-management: DataSync worker execution receipt projection
-- ---------------------------------------------------------------------------
-- 背景：
-- 1. task_data_sync_worker_command_outbox 只能证明 task-management 已把命令投递给 datasource-management；
-- 2. 商业化 DataSync 任务还必须能追踪下游 Runner 的真实执行进度、检查点、完成和失败；
-- 3. 本表保存低敏执行事实，给管理台、任务时间线、告警、审计和后续补偿策略使用。
--
-- 安全边界：
-- 1. 不保存 SQL、连接串、工具参数正文、样本数据、失败行、prompt、模型输出或内部 endpoint；
-- 2. 不保存 checkpoint 原始值，只保存 checkpoint_type 与 checkpoint_value_visibility；
-- 3. error_summary 与 warning_summary 只允许写入服务端脱敏后的摘要，普通 API 视图默认仍不返回正文；
-- 4. receipt_id 是幂等键，同一个下游事件重放时必须复用该值。
-- ---------------------------------------------------------------------------

USE datasmart_govern;

CREATE TABLE IF NOT EXISTS task_data_sync_worker_execution_receipt (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '数据库自增主键，仅用于表内排序、分页和运维定位',
    receipt_id VARCHAR(220) NOT NULL COMMENT '执行回执幂等 ID；同一个下游事件重试回写必须复用该值',
    command_id VARCHAR(128) NOT NULL COMMENT 'Agent Runtime command ID；用于从工具命令维度串联端到端历史',
    outbox_id VARCHAR(300) NOT NULL COMMENT 'task-management 本地 DataSync worker outbox ID',
    task_id BIGINT NOT NULL COMMENT 'task-management 任务 ID',
    agent_run_id VARCHAR(128) DEFAULT NULL COMMENT 'Agent Runtime run ID',
    agent_session_id VARCHAR(128) DEFAULT NULL COMMENT 'Agent 会话 ID',
    audit_id VARCHAR(128) DEFAULT NULL COMMENT 'Agent 工具审计 ID',
    tenant_id BIGINT DEFAULT NULL COMMENT '租户边界',
    project_id BIGINT DEFAULT NULL COMMENT '项目边界',
    workspace_id BIGINT DEFAULT NULL COMMENT '工作空间边界',
    sync_task_id BIGINT NOT NULL COMMENT 'datasource-management 内部同步任务 ID',
    sync_execution_id BIGINT NOT NULL COMMENT 'datasource-management 内部同步执行 ID',
    event_type VARCHAR(32) NOT NULL COMMENT '执行事件类型：PROGRESS/CHECKPOINT/COMPLETE/FAILED',
    event_time DATETIME(3) NOT NULL COMMENT '下游事件发生时间；缺省时使用 task-management 接收时间',
    executor_id VARCHAR(128) DEFAULT NULL COMMENT '下游 Runner/worker 执行器 ID，用于排障定位',
    source_service VARCHAR(128) NOT NULL DEFAULT 'datasource-management' COMMENT '回执来源服务',
    batch_records_read BIGINT NOT NULL DEFAULT 0 COMMENT '本批读取记录数',
    batch_records_written BIGINT NOT NULL DEFAULT 0 COMMENT '本批写入记录数',
    batch_failed_record_count BIGINT NOT NULL DEFAULT 0 COMMENT '本批失败记录数',
    total_records_read BIGINT NOT NULL DEFAULT 0 COMMENT '累计读取记录数',
    total_records_written BIGINT NOT NULL DEFAULT 0 COMMENT '累计写入记录数',
    total_failed_record_count BIGINT NOT NULL DEFAULT 0 COMMENT '累计失败记录数',
    progress_percent INT DEFAULT NULL COMMENT '进度百分比；流式、CDC 或未知总量任务可为空',
    end_of_source TINYINT(1) NOT NULL DEFAULT 0 COMMENT '下游是否已经到达源数据末尾',
    completed TINYINT(1) NOT NULL DEFAULT 0 COMMENT '下游是否报告完成',
    failed TINYINT(1) NOT NULL DEFAULT 0 COMMENT '下游是否报告失败',
    progress_reported TINYINT(1) NOT NULL DEFAULT 0 COMMENT '下游是否已向 datasource 本地任务状态报告进度',
    checkpoint_persisted TINYINT(1) NOT NULL DEFAULT 0 COMMENT '下游是否已持久化检查点',
    checkpoint_type VARCHAR(80) DEFAULT NULL COMMENT '检查点类型，例如 PRIMARY_KEY/UPDATED_AT/OFFSET/PARTITION',
    checkpoint_value_visibility VARCHAR(160) DEFAULT NULL COMMENT '检查点值可见性策略；禁止保存 checkpoint 原始值',
    error_summary VARCHAR(500) DEFAULT NULL COMMENT '服务端脱敏后的低敏错误摘要；普通 API 视图默认不返回正文',
    warning_count INT NOT NULL DEFAULT 0 COMMENT 'warning 数量',
    warning_summary VARCHAR(1000) DEFAULT NULL COMMENT '服务端脱敏后的 warning 摘要；普通 API 视图默认不返回正文',
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '记录创建时间',
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '记录更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_task_datasync_exec_receipt_id (receipt_id),
    KEY idx_task_datasync_exec_command_time (command_id, event_time, id),
    KEY idx_task_datasync_exec_sync_ref (sync_task_id, sync_execution_id, event_time),
    KEY idx_task_datasync_exec_task_time (task_id, event_time),
    KEY idx_task_datasync_exec_scope_time (tenant_id, project_id, workspace_id, event_time),
    KEY idx_task_datasync_exec_event_time (event_type, event_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='task-management 保存 datasource-management Runner 低敏执行回执投影';
