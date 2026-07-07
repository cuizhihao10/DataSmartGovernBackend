-- data-sync 任务管理生命周期兼容迁移
--
-- MySQL 兼容库当前没有像 PostgreSQL 一样强制 CHECK 状态枚举，但字段注释仍是运维和学习的重要信息源。
-- 本迁移更新 data_sync_task/data_sync_execution 的状态说明，使本地 MySQL 兼容环境和 PostgreSQL 目标架构语义一致。

ALTER TABLE data_sync_task
    MODIFY current_state VARCHAR(64) NOT NULL COMMENT
        '任务主状态：DRAFT 编辑中、CONFIGURED 已配置、PENDING_APPROVAL 待审批、SCHEDULED 等待调度、QUEUED/RUNNING/RETRYING 执行窗口、SUCCEEDED/FAILED/PARTIALLY_SUCCEEDED 最近结论、MANUALLY_TERMINATED 手工结束、OFFLINE 已下线、RECYCLED 回收站、DELETED 逻辑彻底删除';

ALTER TABLE data_sync_execution
    MODIFY execution_state VARCHAR(64) NOT NULL COMMENT
        '执行状态：QUEUED、RUNNING、PAUSED、RETRYING、PARTIALLY_SUCCEEDED、SUCCEEDED、FAILED、CANCELLED、MANUALLY_TERMINATED、SKIPPED';

-- 不额外创建回收站索引：既有 idx_data_sync_task_project_state 已覆盖
-- (tenant_id, project_id, current_state, update_time)，足以支撑 current_state=RECYCLED/OFFLINE/DELETED 的兼容查询。
