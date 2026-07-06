-- data-sync：脏数据修复重放 selector 兼容迁移
--
-- 说明：
-- 1. 当前目标数据库正在从 MySQL 迁移到 PostgreSQL，但本地兼容环境和历史数据卷仍可能使用 MySQL；
-- 2. 该迁移为既有 data_sync_execution_recovery_plan 补充 error_sample_selector；
-- 3. 字段只保存低敏 selector，不保存原始坏行、SQL、连接串、密码、token、where 条件或完整样本 payload。

SET @schema_name = DATABASE();

SET @add_error_sample_selector = (
    SELECT IF(
        EXISTS (
            SELECT 1
            FROM information_schema.columns
            WHERE table_schema = @schema_name
              AND table_name = 'data_sync_execution_recovery_plan'
              AND column_name = 'error_sample_selector'
        ),
        'SELECT 1',
        'ALTER TABLE data_sync_execution_recovery_plan ADD COLUMN error_sample_selector TEXT COMMENT ''脏数据修复重放 selector，只保存错误样本 ID、数量、来源 execution 和修复策略摘要，不保存原始坏行、SQL 或凭据'' AFTER shard_or_partition'
    )
);
PREPARE stmt FROM @add_error_sample_selector;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
