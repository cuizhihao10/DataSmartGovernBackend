-- DataSmart Govern - data-sync 单表分片账本字段扩展
-- 设计说明：
-- 1. V3 创建的 data_sync_object_execution 最初用于 OBJECT_LIST 多对象执行账本；
-- 2. 本迁移把同一张账本扩展为“对象/分片工作单元”通用事实表；
-- 3. work_unit_type 区分 OBJECT 与 PARTITION_SHARD，避免同一张源表被拆成多个分片时运维视图语义不清；
-- 4. shard_or_partition 只保存低敏分片编号，例如 id-range-0000，不保存真实 where 边界值；
-- 5. partition_strategy 与 partition_field 用于受控诊断和恢复，不应作为 Prometheus 高基数标签使用。

ALTER TABLE data_sync_object_execution
    ADD COLUMN IF NOT EXISTS work_unit_type VARCHAR(32) NOT NULL DEFAULT 'OBJECT';

ALTER TABLE data_sync_object_execution
    ADD COLUMN IF NOT EXISTS shard_or_partition VARCHAR(128);

ALTER TABLE data_sync_object_execution
    ADD COLUMN IF NOT EXISTS partition_strategy VARCHAR(64);

ALTER TABLE data_sync_object_execution
    ADD COLUMN IF NOT EXISTS partition_field VARCHAR(128);

COMMENT ON COLUMN data_sync_object_execution.work_unit_type IS
    '工作单元类型：OBJECT 表示 OBJECT_LIST 中的一张表或逻辑对象；PARTITION_SHARD 表示单表大数据量按 partitionConfig 拆出的分片';
COMMENT ON COLUMN data_sync_object_execution.shard_or_partition IS
    '低敏分片或分区标识，例如 id-range-0000；禁止保存真实边界值、where 条件、SQL 或样本数据';
COMMENT ON COLUMN data_sync_object_execution.partition_strategy IS
    '分片策略低敏分类，当前主要为 ID_RANGE；后续 HASH_BUCKET、TIME_WINDOW 等策略也应先经过合同解析再写入';
COMMENT ON COLUMN data_sync_object_execution.partition_field IS
    '分片字段名，例如 id 或 customer_id；字段名必须经过安全标识符校验，不应进入公开日志、指标或普通 receipt';

CREATE INDEX IF NOT EXISTS idx_data_sync_object_execution_work_unit_state
    ON data_sync_object_execution (execution_id, work_unit_type, object_state, object_ordinal);

CREATE INDEX IF NOT EXISTS idx_data_sync_object_execution_shard_retry
    ON data_sync_object_execution (work_unit_type, object_state, shard_or_partition, update_time);
