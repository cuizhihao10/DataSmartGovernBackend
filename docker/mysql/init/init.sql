-- ---------------------------------------------------------------------------
-- DataSmart Govern 初始化脚本
-- ---------------------------------------------------------------------------
-- 这份脚本承担两个职责：
-- 1. 初始化本地开发环境依赖的基础数据库；
-- 2. 初始化当前已经落地模块的核心业务表。
--
-- 当前阶段仍然采用集中式初始化脚本，是为了让学习和调试成本更低：
-- 你可以从一个文件里看到任务、数据源、数据同步、数据质量这几个领域的第一版表结构。
-- ---------------------------------------------------------------------------

CREATE DATABASE IF NOT EXISTS nacos
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

GRANT ALL PRIVILEGES ON nacos.* TO 'root'@'%' IDENTIFIED BY 'password';
FLUSH PRIVILEGES;

CREATE DATABASE IF NOT EXISTS datasmart_govern
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

USE datasmart_govern;

-- ---------------------------------------------------------------------------
-- task-management：任务管理模块
-- ---------------------------------------------------------------------------
-- task 表是任务领域的聚合根，承载任务定义、当前状态、重试和结果摘要。
CREATE TABLE IF NOT EXISTS task (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '任务主键',
    name VARCHAR(255) NOT NULL COMMENT '任务名称',
    description TEXT COMMENT '任务说明，便于运营和学习理解',
    type VARCHAR(50) NOT NULL COMMENT '任务类型，用于分类、路由或策略绑定',
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT '当前主状态',
    params TEXT COMMENT '任务输入参数，通常为 JSON',
    progress INT DEFAULT 0 COMMENT '执行进度百分比，范围 0 到 100',
    checkpoint TEXT COMMENT '断点续跑或重试所需的检查点信息',
    priority VARCHAR(10) DEFAULT 'MEDIUM' COMMENT '调度优先级',
    retry_count INT NOT NULL DEFAULT 0 COMMENT '当前已发生的重试次数',
    max_retry_count INT NOT NULL DEFAULT 3 COMMENT '允许的最大重试次数',
    create_time DATETIME NOT NULL COMMENT '创建时间',
    update_time DATETIME NOT NULL COMMENT '最后更新时间',
    start_time DATETIME COMMENT '开始执行时间',
    end_time DATETIME COMMENT '执行结束时间',
    result TEXT COMMENT '执行结果或失败摘要',
    INDEX idx_task_status (status),
    INDEX idx_task_type (type),
    INDEX idx_task_create_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='任务聚合主表';

-- task_execution_log 是追加式任务日志表，用于追踪状态变更和人工处理动作。
CREATE TABLE IF NOT EXISTS task_execution_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '日志主键',
    task_id BIGINT NOT NULL COMMENT '关联任务 ID',
    action VARCHAR(32) NOT NULL COMMENT '执行动作，例如 CREATE、RETRY',
    from_status VARCHAR(20) COMMENT '变更前状态',
    to_status VARCHAR(20) COMMENT '变更后状态',
    message VARCHAR(255) NOT NULL COMMENT '面向运营的摘要说明',
    operator VARCHAR(64) DEFAULT 'system' COMMENT '操作者或动作来源',
    details TEXT COMMENT '详细上下文信息',
    create_time DATETIME NOT NULL COMMENT '日志创建时间',
    INDEX idx_task_execution_log_task_time (task_id, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='任务执行日志表';

-- ---------------------------------------------------------------------------
-- datasource-management：数据源管理模块
-- ---------------------------------------------------------------------------
-- datasource_config 保存的是“如何连接外部系统”，而不是外部业务数据本身。
-- 这是治理平台的基础注册中心，后续元数据采集、同步、质量检测都依赖它。
CREATE TABLE IF NOT EXISTS datasource_config (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '数据源主键',
    name VARCHAR(128) NOT NULL COMMENT '唯一数据源名称',
    type VARCHAR(32) NOT NULL COMMENT '数据源类型，例如 MYSQL、POSTGRESQL',
    jdbc_url VARCHAR(512) NOT NULL COMMENT '连接地址',
    username VARCHAR(128) NOT NULL COMMENT '连接用户名',
    password VARCHAR(256) NOT NULL COMMENT '连接密码，当前阶段先明文保存，后续应升级为密钥引用',
    driver_class_name VARCHAR(256) NOT NULL COMMENT 'JDBC 驱动类名',
    description VARCHAR(512) COMMENT '便于人理解的数据源描述',
    status VARCHAR(32) NOT NULL COMMENT '生命周期状态',
    last_test_status VARCHAR(32) DEFAULT 'UNKNOWN' COMMENT '最近一次连接测试状态',
    last_test_message VARCHAR(512) COMMENT '最近一次连接测试说明',
    last_test_time DATETIME COMMENT '最近一次连接测试时间',
    create_time DATETIME NOT NULL COMMENT '创建时间',
    update_time DATETIME NOT NULL COMMENT '最后更新时间',
    UNIQUE KEY uk_datasource_name (name),
    INDEX idx_datasource_type (type),
    INDEX idx_datasource_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='数据源配置表';

-- sync_template 是“可重复使用的数据同步配置模板”。
-- 它位于数据源注册和具体同步任务之间，用来固化同步模式、字段映射、过滤条件、重试和超时策略。
CREATE TABLE IF NOT EXISTS sync_template (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '同步模板主键',
    tenant_id BIGINT NOT NULL COMMENT '租户 ID，预留给后续多租户场景',
    name VARCHAR(128) NOT NULL COMMENT '模板名称',
    description VARCHAR(512) COMMENT '模板说明',
    source_datasource_id BIGINT NOT NULL COMMENT '源端数据源 ID',
    source_schema_name VARCHAR(128) COMMENT '源端 schema 名称',
    source_object_name VARCHAR(128) NOT NULL COMMENT '源端对象名称，例如表名或视图名',
    target_datasource_id BIGINT NOT NULL COMMENT '目标端数据源 ID',
    target_schema_name VARCHAR(128) COMMENT '目标端 schema 名称',
    target_object_name VARCHAR(128) NOT NULL COMMENT '目标端对象名称',
    sync_mode VARCHAR(64) NOT NULL COMMENT '同步模式，例如 FULL、CDC、BACKFILL',
    primary_key_field VARCHAR(128) COMMENT '主键字段，用于去重、幂等和回放策略',
    incremental_field VARCHAR(128) COMMENT '增量字段，用于时间增量或 ID 增量模式',
    field_mapping_config TEXT COMMENT '字段映射配置 JSON',
    filter_config TEXT COMMENT '过滤条件配置 JSON',
    partition_config TEXT COMMENT '分片或分区配置 JSON',
    retry_policy TEXT COMMENT '重试策略 JSON',
    timeout_policy TEXT COMMENT '超时策略 JSON',
    enabled TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否启用模板',
    created_by BIGINT NOT NULL COMMENT '创建人 ID',
    updated_by BIGINT COMMENT '更新人 ID',
    create_time DATETIME NOT NULL COMMENT '创建时间',
    update_time DATETIME NOT NULL COMMENT '更新时间',
    UNIQUE KEY uk_sync_template_tenant_name (tenant_id, name),
    INDEX idx_sync_template_source (tenant_id, source_datasource_id),
    INDEX idx_sync_template_target (tenant_id, target_datasource_id),
    INDEX idx_sync_template_enabled (tenant_id, enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='数据同步模板表';

-- sync_task 表示一个可运营、可审批、可调度的具体同步任务。
-- 它和模板不同，任务更偏向“谁负责、当前状态如何、何时运行、是否需要人工介入”。
CREATE TABLE IF NOT EXISTS sync_task (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '同步任务主键',
    tenant_id BIGINT NOT NULL COMMENT '租户 ID',
    template_id BIGINT NOT NULL COMMENT '关联同步模板 ID',
    name VARCHAR(128) NOT NULL COMMENT '任务名称',
    description VARCHAR(512) COMMENT '任务说明',
    current_state VARCHAR(64) NOT NULL COMMENT '当前主状态',
    approval_state VARCHAR(64) NOT NULL COMMENT '审批状态',
    priority VARCHAR(32) NOT NULL COMMENT '优先级',
    run_mode VARCHAR(64) NOT NULL COMMENT '执行模式',
    trigger_type VARCHAR(64) NOT NULL COMMENT '触发类型',
    schedule_config TEXT COMMENT '调度配置 JSON',
    owner_id BIGINT NOT NULL COMMENT '负责人 ID',
    last_execution_id BIGINT COMMENT '最近一次执行记录 ID',
    next_run_at DATETIME COMMENT '下一次计划运行时间',
    enabled TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否启用任务',
    operator_attention_required TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否需要人工关注',
    timeout_seconds INT NOT NULL COMMENT '任务超时秒数',
    max_retry_count INT NOT NULL DEFAULT 3 COMMENT '最大重试次数',
    retry_count INT NOT NULL DEFAULT 0 COMMENT '当前已重试次数',
    latest_error_summary VARCHAR(1024) COMMENT '最近一次错误摘要',
    incident_note VARCHAR(1024) COMMENT '人工处置说明或事件备注',
    created_by BIGINT NOT NULL COMMENT '创建人 ID',
    updated_by BIGINT COMMENT '更新人 ID',
    create_time DATETIME NOT NULL COMMENT '创建时间',
    update_time DATETIME NOT NULL COMMENT '更新时间',
    UNIQUE KEY uk_sync_task_tenant_name (tenant_id, name),
    INDEX idx_sync_task_state (tenant_id, current_state),
    INDEX idx_sync_task_owner (tenant_id, owner_id),
    INDEX idx_sync_task_approval (tenant_id, approval_state),
    INDEX idx_sync_task_next_run (tenant_id, next_run_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='数据同步任务表';

-- sync_execution 记录任务的每一次实际执行。
-- 一个任务可以执行很多次，所以执行历史必须独立存表，不能直接覆盖任务本身。
CREATE TABLE IF NOT EXISTS sync_execution (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '执行记录主键',
    sync_task_id BIGINT NOT NULL COMMENT '关联同步任务 ID',
    execution_no BIGINT NOT NULL COMMENT '同一任务下的第几次执行',
    state VARCHAR(64) NOT NULL COMMENT '执行状态',
    started_at DATETIME COMMENT '开始执行时间',
    finished_at DATETIME COMMENT '结束时间',
    checkpoint_ref VARCHAR(256) COMMENT '当前检查点摘要或引用',
    records_read BIGINT DEFAULT 0 COMMENT '已读取记录数',
    records_written BIGINT DEFAULT 0 COMMENT '已写入记录数',
    failed_record_count BIGINT DEFAULT 0 COMMENT '失败记录数',
    error_summary TEXT COMMENT '错误摘要',
    triggered_by BIGINT COMMENT '触发人 ID',
    trigger_reason VARCHAR(512) COMMENT '触发原因说明',
    create_time DATETIME NOT NULL COMMENT '创建时间',
    update_time DATETIME NOT NULL COMMENT '更新时间',
    INDEX idx_sync_execution_task_no (sync_task_id, execution_no),
    INDEX idx_sync_execution_state (sync_task_id, state),
    INDEX idx_sync_execution_started_at (started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='数据同步执行记录表';

-- sync_checkpoint 是断点续跑、分区恢复和回放能力的基础。
CREATE TABLE IF NOT EXISTS sync_checkpoint (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '检查点主键',
    execution_id BIGINT NOT NULL COMMENT '关联执行记录 ID',
    checkpoint_type VARCHAR(64) NOT NULL COMMENT '检查点类型，例如时间水位、主键范围、offset',
    checkpoint_value TEXT NOT NULL COMMENT '检查点值',
    shard_or_partition VARCHAR(128) COMMENT '分片或分区标识',
    update_time DATETIME NOT NULL COMMENT '最后更新时间',
    INDEX idx_sync_checkpoint_execution_partition (execution_id, shard_or_partition)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='数据同步检查点表';

-- sync_audit_record 承载数据同步领域的治理审计轨迹。
-- 它记录的是“谁在什么时候做了什么”，而不只是普通技术日志。
CREATE TABLE IF NOT EXISTS sync_audit_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '审计记录主键',
    tenant_id BIGINT NOT NULL COMMENT '租户 ID',
    sync_task_id BIGINT COMMENT '关联同步任务 ID',
    execution_id BIGINT COMMENT '关联执行记录 ID',
    action_type VARCHAR(64) NOT NULL COMMENT '动作类型',
    actor_id BIGINT NOT NULL COMMENT '操作者 ID',
    actor_role VARCHAR(64) NOT NULL COMMENT '操作者角色',
    action_payload TEXT COMMENT '动作载荷内容',
    create_time DATETIME NOT NULL COMMENT '创建时间',
    INDEX idx_sync_audit_tenant_action (tenant_id, action_type),
    INDEX idx_sync_audit_task_time (sync_task_id, create_time),
    INDEX idx_sync_audit_execution_time (execution_id, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='数据同步审计记录表';

-- ---------------------------------------------------------------------------
-- data-quality：数据质量模块
-- ---------------------------------------------------------------------------
-- quality_rule 用来定义“什么样的数据才算合格”。
CREATE TABLE IF NOT EXISTS quality_rule (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '规则主键',
    name VARCHAR(128) NOT NULL COMMENT '唯一规则名称',
    rule_type VARCHAR(32) NOT NULL COMMENT '规则分类',
    target_object VARCHAR(256) NOT NULL COMMENT '被检测对象，例如表、字段或指标',
    comparison_operator VARCHAR(16) NOT NULL COMMENT '比较运算符，例如 GT、LTE',
    expected_value DECIMAL(20,4) NOT NULL COMMENT '期望值或阈值',
    severity VARCHAR(16) NOT NULL COMMENT '业务严重级别',
    description VARCHAR(512) COMMENT '规则说明',
    status VARCHAR(32) NOT NULL COMMENT '生命周期状态',
    create_time DATETIME NOT NULL COMMENT '创建时间',
    update_time DATETIME NOT NULL COMMENT '最后更新时间',
    UNIQUE KEY uk_quality_rule_name (name),
    INDEX idx_quality_rule_type (rule_type),
    INDEX idx_quality_rule_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='质量规则表';

-- quality_check_report 保存一次具体检测的结果快照。
-- 即使规则后续被修改，历史报告仍应能独立解释当时为何通过或失败。
CREATE TABLE IF NOT EXISTS quality_check_report (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '报告主键',
    rule_id BIGINT NOT NULL COMMENT '关联规则 ID',
    rule_name VARCHAR(128) NOT NULL COMMENT '规则名称快照',
    target_object VARCHAR(256) NOT NULL COMMENT '检测对象快照',
    measured_value DECIMAL(20,4) NOT NULL COMMENT '实际观测值',
    expected_value DECIMAL(20,4) NOT NULL COMMENT '期望值快照',
    comparison_operator VARCHAR(16) NOT NULL COMMENT '比较运算符快照',
    check_status VARCHAR(16) NOT NULL COMMENT '检测结果，PASSED 或 FAILED',
    sample_size INT NOT NULL COMMENT '参与检测的样本量',
    exception_count INT NOT NULL COMMENT '异常记录数量',
    summary VARCHAR(1024) NOT NULL COMMENT '结果摘要',
    notes VARCHAR(1024) COMMENT '补充说明',
    create_time DATETIME NOT NULL COMMENT '创建时间',
    INDEX idx_quality_report_rule_id (rule_id),
    INDEX idx_quality_report_status (check_status),
    INDEX idx_quality_report_create_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='质量检测报告表';
