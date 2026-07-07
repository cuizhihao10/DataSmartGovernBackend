-- data-sync：任务级定时调度字段兼容迁移
--
-- 说明：
-- 1. PostgreSQL 是 data-sync 的目标系统库，但 MySQL 初始化脚本仍服务于历史本地环境和迁移期兼容；
-- 2. 本迁移为 data_sync_task 增加自动调度所需的游标、计数和乐观锁字段；
-- 3. 这些字段只保存低敏调度事实，不保存 SQL、字段映射、连接串、密码、token 或源端样本数据。

SET @schema_name = DATABASE();

SET @add_schedule_enabled = (
    SELECT IF(
        EXISTS (
            SELECT 1 FROM information_schema.columns
            WHERE table_schema = @schema_name
              AND table_name = 'data_sync_task'
              AND column_name = 'schedule_enabled'
        ),
        'SELECT 1',
        'ALTER TABLE data_sync_task ADD COLUMN schedule_enabled TINYINT(1) NOT NULL DEFAULT 0 COMMENT ''是否启用任务级自动调度；只有 true 且 current_state=SCHEDULED 的任务才会被 task scheduler 扫描'' AFTER schedule_config'
    )
);
PREPARE stmt FROM @add_schedule_enabled;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @add_next_fire_time = (
    SELECT IF(
        EXISTS (
            SELECT 1 FROM information_schema.columns
            WHERE table_schema = @schema_name
              AND table_name = 'data_sync_task'
              AND column_name = 'next_fire_time'
        ),
        'SELECT 1',
        'ALTER TABLE data_sync_task ADD COLUMN next_fire_time DATETIME COMMENT ''下一次计划触发时间，task scheduler 依据该字段扫描到期任务'' AFTER schedule_enabled'
    )
);
PREPARE stmt FROM @add_next_fire_time;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @add_last_fire_time = (
    SELECT IF(
        EXISTS (
            SELECT 1 FROM information_schema.columns
            WHERE table_schema = @schema_name
              AND table_name = 'data_sync_task'
              AND column_name = 'last_fire_time'
        ),
        'SELECT 1',
        'ALTER TABLE data_sync_task ADD COLUMN last_fire_time DATETIME COMMENT ''最近一次成功派发 execution 的计划触发时间，不等同于 worker started_at'' AFTER next_fire_time'
    )
);
PREPARE stmt FROM @add_last_fire_time;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @add_schedule_misfire_count = (
    SELECT IF(
        EXISTS (
            SELECT 1 FROM information_schema.columns
            WHERE table_schema = @schema_name
              AND table_name = 'data_sync_task'
              AND column_name = 'schedule_misfire_count'
        ),
        'SELECT 1',
        'ALTER TABLE data_sync_task ADD COLUMN schedule_misfire_count INT NOT NULL DEFAULT 0 COMMENT ''调度错过次数，包括 skip、服务停机或并发冲突导致的跳过窗口'' AFTER last_fire_time'
    )
);
PREPARE stmt FROM @add_schedule_misfire_count;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @add_schedule_dispatch_count = (
    SELECT IF(
        EXISTS (
            SELECT 1 FROM information_schema.columns
            WHERE table_schema = @schema_name
              AND table_name = 'data_sync_task'
              AND column_name = 'schedule_dispatch_count'
        ),
        'SELECT 1',
        'ALTER TABLE data_sync_task ADD COLUMN schedule_dispatch_count BIGINT NOT NULL DEFAULT 0 COMMENT ''调度器已成功创建 SCHEDULED execution 的累计次数'' AFTER schedule_misfire_count'
    )
);
PREPARE stmt FROM @add_schedule_dispatch_count;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @add_schedule_version = (
    SELECT IF(
        EXISTS (
            SELECT 1 FROM information_schema.columns
            WHERE table_schema = @schema_name
              AND table_name = 'data_sync_task'
              AND column_name = 'schedule_version'
        ),
        'SELECT 1',
        'ALTER TABLE data_sync_task ADD COLUMN schedule_version BIGINT NOT NULL DEFAULT 0 COMMENT ''调度乐观锁版本，多实例扫描时用于原子抢占并避免重复触发'' AFTER schedule_dispatch_count'
    )
);
PREPARE stmt FROM @add_schedule_version;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @add_idx_schedule_due = (
    SELECT IF(
        EXISTS (
            SELECT 1 FROM information_schema.statistics
            WHERE table_schema = @schema_name
              AND table_name = 'data_sync_task'
              AND index_name = 'idx_data_sync_task_schedule_due'
        ),
        'SELECT 1',
        'ALTER TABLE data_sync_task ADD INDEX idx_data_sync_task_schedule_due (schedule_enabled, current_state, next_fire_time, id)'
    )
);
PREPARE stmt FROM @add_idx_schedule_due;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @add_idx_schedule_version = (
    SELECT IF(
        EXISTS (
            SELECT 1 FROM information_schema.statistics
            WHERE table_schema = @schema_name
              AND table_name = 'data_sync_task'
              AND index_name = 'idx_data_sync_task_schedule_version'
        ),
        'SELECT 1',
        'ALTER TABLE data_sync_task ADD INDEX idx_data_sync_task_schedule_version (id, schedule_version, next_fire_time)'
    )
);
PREPARE stmt FROM @add_idx_schedule_version;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
