-- data-sync 任务管理生命周期状态扩展
--
-- 背景：
-- 1. 任务定义状态和单次 execution 状态必须分层建模。
--    定时任务的主状态应长期保持 SCHEDULED，单次执行结果由 data_sync_execution 保存；
--    下线、回收站、彻底删除、手工结束则属于任务定义管理面。
-- 2. PostgreSQL 使用 CHECK 约束保护状态枚举边界。Java enum 新增状态后，如果不同步更新约束，
--    服务层即使编译通过，运行时也会因为数据库拒绝新状态而失败。
-- 3. 当前“彻底删除”采用逻辑 DELETED，而不是物理删除行。这样可以保留 execution、checkpoint、
--    error sample、object ledger 和审计证据的历史归属，满足商用产品的追溯和合规需求。

ALTER TABLE data_sync_task DROP CONSTRAINT IF EXISTS ck_data_sync_task_state;

ALTER TABLE data_sync_task
    ADD CONSTRAINT ck_data_sync_task_state CHECK (current_state IN (
        'DRAFT',
        'CONFIGURED',
        'PENDING_APPROVAL',
        'SCHEDULED',
        'QUEUED',
        'RUNNING',
        'PAUSED',
        'RETRYING',
        'PARTIALLY_SUCCEEDED',
        'SUCCEEDED',
        'FAILED',
        'VALIDATION_FAILED',
        'BLOCKED_BY_DEPENDENCY',
        'AWAITING_OPERATOR_ACTION',
        'DEGRADED',
        'CANCELLED',
        'MANUALLY_TERMINATED',
        'OFFLINE',
        'RECYCLED',
        'ARCHIVED',
        'DELETED'
    ));

COMMENT ON COLUMN data_sync_task.current_state IS
    '任务主状态。DRAFT=编辑中，CONFIGURED=已配置待手工执行，SCHEDULED=等待调度，QUEUED/RUNNING/RETRYING=执行窗口，SUCCEEDED/FAILED/PARTIALLY_SUCCEEDED=非定时任务最近运行结论，MANUALLY_TERMINATED=手工结束，OFFLINE=已下线，RECYCLED=回收站，DELETED=逻辑彻底删除';

ALTER TABLE data_sync_execution DROP CONSTRAINT IF EXISTS ck_data_sync_execution_state;

ALTER TABLE data_sync_execution
    ADD CONSTRAINT ck_data_sync_execution_state CHECK (execution_state IN (
        'QUEUED',
        'RUNNING',
        'PAUSED',
        'RETRYING',
        'PARTIALLY_SUCCEEDED',
        'SUCCEEDED',
        'FAILED',
        'CANCELLED',
        'MANUALLY_TERMINATED',
        'SKIPPED'
    ));

COMMENT ON COLUMN data_sync_execution.execution_state IS
    '执行状态。execution 只描述某一次运行：QUEUED/RUNNING/RETRYING=可执行窗口，PAUSED/CANCELLED/MANUALLY_TERMINATED=控制面停止信号，SUCCEEDED/FAILED/PARTIALLY_SUCCEEDED=终态结果，SKIPPED=按策略跳过';

CREATE INDEX IF NOT EXISTS idx_data_sync_task_recycle_bin
    ON data_sync_task (tenant_id, project_id, current_state, update_time)
    WHERE current_state IN ('OFFLINE', 'RECYCLED', 'DELETED');
