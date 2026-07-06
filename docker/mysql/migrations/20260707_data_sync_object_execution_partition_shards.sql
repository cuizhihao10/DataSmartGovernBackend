-- DataSmart Govern - data-sync 单表分片账本字段扩展（MySQL 兼容迁移）
-- 说明：
-- 1. PostgreSQL 是目标系统库，但本地兼容环境和部分历史服务仍会使用 MySQL 初始化脚本；
-- 2. 该迁移为 data_sync_object_execution 增加分片工作单元字段；
-- 3. 分片边界值不落表，只落低敏 shard_or_partition，例如 id-range-0000；
-- 4. 如果执行环境已经手工添加过这些字段，请不要重复执行本迁移。

ALTER TABLE data_sync_object_execution
    ADD COLUMN work_unit_type VARCHAR(32) NOT NULL DEFAULT 'OBJECT'
        COMMENT '工作单元类型：OBJECT 表示多对象任务中的对象；PARTITION_SHARD 表示单表大数据量分片'
        AFTER object_ordinal,
    ADD COLUMN shard_or_partition VARCHAR(128)
        COMMENT '低敏分片或分区标识，例如 id-range-0000；禁止保存真实边界值、where 条件、SQL 或样本数据'
        AFTER work_unit_type,
    ADD COLUMN partition_strategy VARCHAR(64)
        COMMENT '分片策略低敏分类，当前主要为 ID_RANGE'
        AFTER shard_or_partition,
    ADD COLUMN partition_field VARCHAR(128)
        COMMENT '分片字段名，例如 id 或 customer_id；字段名必须经过安全标识符校验'
        AFTER partition_strategy;

CREATE INDEX idx_data_sync_object_execution_work_unit_state
    ON data_sync_object_execution (execution_id, work_unit_type, object_state, object_ordinal);

CREATE INDEX idx_data_sync_object_execution_shard_retry
    ON data_sync_object_execution (work_unit_type, object_state, shard_or_partition, update_time);
