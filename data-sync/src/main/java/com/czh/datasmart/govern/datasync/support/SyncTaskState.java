/**
 * @Author : Cui
 * @Date: 2026/05/07 21:26
 * @Description DataSmart Govern Backend - SyncTaskState.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.support;

/**
 * 同步任务主状态。
 *
 * <p>这里的状态参考 data-sync PRD 中的状态机，不直接复用 task-management 的 TaskStatus。
 * 原因是 data-sync 的任务不仅是通用调度任务，还包含审批、配置完整性、checkpoint、部分成功、回放补数等数据移动专属语义。
 *
 * <p>后续接入 task-management 时，data-sync 可以把自身任务转化为平台任务，但不能把领域状态完全丢给通用任务中心。
 */
public enum SyncTaskState {
    DRAFT,
    CONFIGURED,
    PENDING_APPROVAL,
    SCHEDULED,
    QUEUED,
    RUNNING,
    PAUSED,
    RETRYING,
    PARTIALLY_SUCCEEDED,
    SUCCEEDED,
    FAILED,
    /**
     * 需要人工介入。
     *
     * <p>这个状态用于区别普通 FAILED：
     * FAILED 只说明一次执行失败，而 AWAITING_OPERATOR_ACTION 表示系统已经多次退避、恢复或重试仍无法安全推进，
     * 继续自动执行可能浪费资源或扩大故障影响，因此需要运营人员检查数据源连通性、目标端容量、字段映射、租户配额或连接器版本。
     */
    AWAITING_OPERATOR_ACTION,
    CANCELLED,
    ARCHIVED
}
