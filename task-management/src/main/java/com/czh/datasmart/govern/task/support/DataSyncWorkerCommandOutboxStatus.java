/**
 * @Author : Cui
 * @Date: 2026/06/20 16:40
 * @Description DataSmart Govern Backend - DataSyncWorkerCommandOutboxStatus.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.support;

/**
 * DataSync worker command outbox 状态。
 *
 * <p>这个枚举服务于 task-management 到 datasource-management/data-sync 的执行边界。
 * 它描述的不是 task 主表状态，也不是 data-sync 内部 SyncTask 状态，而是一条“任务中心准备投递给
 * data-sync worker 的命令”在本地 outbox 中的状态。</p>
 *
 * <p>为什么单独建 outbox 状态：</p>
 * <p>1. task 状态表达的是用户可见任务生命周期，例如 PENDING、RUNNING、SUCCESS、FAILED；</p>
 * <p>2. data-sync 状态表达的是同步任务内部生命周期，例如 QUEUED、RUNNING、SUCCEEDED；</p>
 * <p>3. outbox 状态表达的是跨服务命令投递和回执生命周期，例如是否已经开始投递、是否收到下游 receipt；</p>
 * <p>4. 三者如果混在一个字段里，后续排障时很难判断到底是队列没认领、命令没投递，还是下游执行失败。</p>
 */
public enum DataSyncWorkerCommandOutboxStatus {

    /**
     * 命令已经在 task-management 本地持久化，但尚未开始调用 data-sync。
     */
    PENDING,

    /**
     * 命令正在投递或已经发起本轮下游调用。
     */
    DISPATCHING,

    /**
     * 下游返回了可恢复失败，后续可以按 nextRetryAt 重新投递。
     */
    DEFERRED,

    /**
     * 下游已接受命令并返回成功 receipt。
     */
    SUCCEEDED,

    /**
     * 命令因为不可恢复错误失败，例如契约不兼容、响应缺失关键字段或本地状态冲突。
     */
    FAILED,

    /**
     * 命令达到最大重试或被运维策略阻断，后续需要人工处理。
     */
    DEAD_LETTER,

    /**
     * 命令已经由运维、平台管理员或受控补偿工具人工关闭。
     *
     * <p>为什么不直接复用 FAILED：</p>
     * <p>1. FAILED 表示系统判定命令本身不可恢复，例如 payload 契约损坏或 toolCode 不支持；</p>
     * <p>2. DEAD_LETTER 表示系统停止自动重试，等待人工确认；</p>
     * <p>3. CLOSED 表示人工已经完成处置，并明确要求普通 dispatcher 不再重放该命令。</p>
     *
     * <p>这个状态让运维台能够区分“还需要人看”的死信和“已经确认结束”的死信，
     * 避免长期告警一直停留在 DEAD_LETTER 队列里。</p>
     */
    CLOSED;

    /**
     * 判断状态是否已经是终态。
     *
     * @return true 表示不应再被普通 dispatcher 自动覆盖为 DISPATCHING。
     */
    public boolean terminal() {
        return this == SUCCEEDED || this == FAILED || this == DEAD_LETTER || this == CLOSED;
    }
}
