-- DataSmart Govern - data-sync OBJECT_LIST 对象级执行账本
--
-- 该迁移用于 MySQL 兼容环境。PostgreSQL 是目标系统库，但当前本地 E2E 和部分服务仍保留 MySQL 初始化路径，
-- 因此新表需要在 MySQL 侧同步创建，避免旧兼容环境运行 OBJECT_LIST fan-out 时缺少对象级执行记录。
CREATE TABLE IF NOT EXISTS data_sync_object_execution (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '对象级执行记录主键',
    tenant_id BIGINT NOT NULL DEFAULT 0 COMMENT '租户 ID，冗余自父 execution，用于租户级历史查询、清理和审计',
    project_id BIGINT COMMENT '项目 ID，冗余自父 execution，用于项目级运行明细、失败率统计和权限过滤',
    workspace_id BIGINT COMMENT '工作空间 ID，冗余自父 execution，用于空间级运行证据和运营看板',
    sync_task_id BIGINT NOT NULL COMMENT '同步任务 ID',
    execution_id BIGINT NOT NULL COMMENT '父级 data_sync_execution ID',
    template_id BIGINT COMMENT '同步模板 ID',
    object_ordinal INT NOT NULL COMMENT '对象在 objectMappingConfig.mappings 中的顺序号，从 0 开始',
    source_schema_name VARCHAR(128) COMMENT '源端 schema 或命名空间，内部恢复和排障使用',
    source_object_name VARCHAR(256) COMMENT '源端对象名，内部恢复和排障使用，普通 API/日志/指标/receipt 不应直接暴露',
    target_schema_name VARCHAR(128) COMMENT '目标端 schema 或命名空间，内部恢复和排障使用',
    target_object_name VARCHAR(256) COMMENT '目标端对象名，内部恢复和排障使用，普通 API/日志/指标/receipt 不应直接暴露',
    object_state VARCHAR(32) NOT NULL DEFAULT 'PENDING' COMMENT '对象级状态：PENDING、RUNNING、RETRYING、SUCCEEDED、FAILED、SKIPPED',
    attempt_count INT NOT NULL DEFAULT 0 COMMENT '当前对象已经尝试执行的次数',
    max_attempt_count INT NOT NULL DEFAULT 3 COMMENT '当前对象允许的最大尝试次数，创建时从模板 retryPolicy 解析并落表',
    records_read BIGINT NOT NULL DEFAULT 0 COMMENT '当前对象累计读取记录数',
    records_written BIGINT NOT NULL DEFAULT 0 COMMENT '当前对象累计写入记录数',
    failed_record_count BIGINT NOT NULL DEFAULT 0 COMMENT '当前对象失败记录数或失败对象计数',
    last_error_type VARCHAR(128) COMMENT '最近一次失败类型，只保存低敏分类',
    last_error_code VARCHAR(128) COMMENT '最近一次失败码，只保存低敏错误码',
    last_error_message VARCHAR(1000) COMMENT '最近一次低敏错误摘要，禁止保存连接串、凭据、SQL、字段值、样本行或完整异常堆栈',
    started_at DATETIME COMMENT '当前对象首次开始执行时间',
    finished_at DATETIME COMMENT '当前对象终态时间',
    payload_policy VARCHAR(256) NOT NULL COMMENT '载荷安全策略说明',
    create_time DATETIME NOT NULL COMMENT '创建时间',
    update_time DATETIME NOT NULL COMMENT '更新时间',
    UNIQUE KEY uk_data_sync_object_execution_ordinal (execution_id, object_ordinal),
    INDEX idx_data_sync_object_execution_parent_state (execution_id, object_state, object_ordinal),
    INDEX idx_data_sync_object_execution_task_state (sync_task_id, object_state, create_time),
    INDEX idx_data_sync_object_execution_scope_state (tenant_id, project_id, workspace_id, object_state, update_time),
    INDEX idx_data_sync_object_execution_retry (object_state, attempt_count, max_attempt_count, update_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='data-sync OBJECT_LIST 对象级执行账本';
