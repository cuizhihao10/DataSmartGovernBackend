/*
 * data-sync task scheduler schema extension.
 *
 * 这份迁移只为 data_sync_task 增加“任务级自动调度”所需的持久化游标。
 * 注意：不要修改已经发布的 V1 baseline，避免 Flyway checksum 在长期本地卷或客户环境中不一致。
 */

ALTER TABLE data_sync_task
    ADD COLUMN IF NOT EXISTS schedule_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS next_fire_time TIMESTAMP WITHOUT TIME ZONE,
    ADD COLUMN IF NOT EXISTS last_fire_time TIMESTAMP WITHOUT TIME ZONE,
    ADD COLUMN IF NOT EXISTS schedule_misfire_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS schedule_dispatch_count BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS schedule_version BIGINT NOT NULL DEFAULT 0;

COMMENT ON COLUMN data_sync_task.schedule_enabled IS '是否启用任务级自动调度；只有 true 且 current_state=SCHEDULED 的任务才会被 task scheduler 扫描';
COMMENT ON COLUMN data_sync_task.next_fire_time IS '下一次计划触发时间，task scheduler 依据该字段扫描到期任务';
COMMENT ON COLUMN data_sync_task.last_fire_time IS '最近一次成功派发 execution 的计划触发时间，不等同于 worker started_at';
COMMENT ON COLUMN data_sync_task.schedule_misfire_count IS '调度错过次数，包括 misfirePolicy=SKIP、服务停机或并发冲突导致的跳过窗口';
COMMENT ON COLUMN data_sync_task.schedule_dispatch_count IS '调度器已成功创建 SCHEDULED execution 的累计次数';
COMMENT ON COLUMN data_sync_task.schedule_version IS '调度乐观锁版本，多实例扫描时用于原子抢占并避免重复触发';

CREATE INDEX IF NOT EXISTS idx_data_sync_task_schedule_due
    ON data_sync_task (schedule_enabled, current_state, next_fire_time, id);

CREATE INDEX IF NOT EXISTS idx_data_sync_task_schedule_version
    ON data_sync_task (id, schedule_version, next_fire_time);
