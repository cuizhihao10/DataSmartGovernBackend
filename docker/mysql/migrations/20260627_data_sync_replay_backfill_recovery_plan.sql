-- data-sync：replay/backfill 恢复计划表
--
-- 背景：
-- 1. data_sync_execution 负责记录“某一次运行”的通用事实，例如状态、触发方式、计数和执行器租约；
-- 2. replay/backfill 还需要额外表达“从哪次执行/哪个 checkpoint 回放”或“补哪个窗口/分区”；
-- 3. 如果把这些语义塞进 execution.error_summary 或 checkpoint_ref，会导致 worker 契约、审计证据和运营查询都变得含糊。
--
-- 设计边界：
-- 1. 本表只保存低敏控制面字段，不保存 SQL、连接串、密码、token、prompt、样本数据、模型输出或完整工具参数；
-- 2. execution_id 唯一，表示一条恢复计划驱动一条新 execution；
-- 3. 当前阶段 plan_state 先写 CREATED，后续真实 worker SDK 接入后再推进 CLAIMED/CONSUMED/CANCELLED 等状态。

CREATE TABLE IF NOT EXISTS data_sync_execution_recovery_plan (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '同步执行恢复计划主键；replay/backfill 不把恢复窗口塞进 execution 摘要，而是由本表保存低敏控制面契约',
    tenant_id BIGINT NOT NULL DEFAULT 0 COMMENT '租户 ID，冗余自同步任务，用于租户隔离、恢复计划清理和租户级审计',
    project_id BIGINT COMMENT '项目 ID，冗余自同步任务，用于 PROJECT 数据范围、项目级补数审计和运营看板',
    workspace_id BIGINT COMMENT '工作空间 ID，冗余自同步任务，用于空间级恢复计划筛选',
    sync_task_id BIGINT NOT NULL COMMENT '所属同步任务 ID；一个任务可产生多条 replay/backfill 恢复计划',
    execution_id BIGINT NOT NULL COMMENT '本恢复计划驱动的新 executionId；worker 认领 execution 后按该字段读取计划',
    recovery_type VARCHAR(32) NOT NULL COMMENT '恢复类型：REPLAY 表示从历史执行或 checkpoint 回放；BACKFILL 表示按窗口或分区补数',
    source_execution_id BIGINT COMMENT 'replay 来源 executionId；backfill 可为空或仅作为审计锚点',
    source_checkpoint_id BIGINT COMMENT 'replay 来源 checkpointId；为空表示从来源 execution 起点回放或由 worker 自行选择安全起点',
    window_start VARCHAR(128) COMMENT 'backfill 窗口开始边界；保持低敏字符串以兼容时间戳、offset 时间、目录日期等连接器差异',
    window_end VARCHAR(128) COMMENT 'backfill 窗口结束边界；不保存 SQL where 片段或样本数据',
    shard_or_partition VARCHAR(256) COMMENT 'backfill 分片或分区选择器，例如日期分区、Kafka partition、文件目录或业务哈希桶',
    reason VARCHAR(500) COMMENT '低敏操作原因；禁止保存 SQL、连接串、密码、token、prompt、样本数据或完整工具参数',
    plan_state VARCHAR(32) NOT NULL DEFAULT 'CREATED' COMMENT '计划状态：CREATED、CLAIMED、CONSUMED、CANCELLED；当前阶段先写 CREATED，后续 worker 消费时推进',
    create_time DATETIME NOT NULL COMMENT '创建时间',
    update_time DATETIME NOT NULL COMMENT '更新时间',
    UNIQUE KEY uk_data_sync_recovery_execution (execution_id),
    INDEX idx_data_sync_recovery_task (sync_task_id, create_time),
    INDEX idx_data_sync_recovery_source (source_execution_id, source_checkpoint_id),
    INDEX idx_data_sync_recovery_project (tenant_id, project_id, recovery_type, create_time),
    INDEX idx_data_sync_recovery_state (plan_state, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='数据同步 replay/backfill 恢复计划表';
