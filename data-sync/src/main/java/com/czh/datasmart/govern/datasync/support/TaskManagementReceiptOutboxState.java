/**
 * @Author : Cui
 * @Date: 2026/06/29 19:34
 * @Description DataSmart Govern Backend - TaskManagementReceiptOutboxState.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.support;

/**
 * data-sync 投递 task-management receipt outbox 状态。
 *
 * <p>这个枚举描述的是“跨服务投影投递”的生命周期，不是 data-sync execution 的生命周期。
 * execution 可以已经 SUCCEEDED/FAILED，而 receipt outbox 仍然处于 RETRY_WAIT；二者不能混为一谈。
 * 这样设计是为了保证业务事实先在 data-sync 闭合，再通过 outbox 可靠补偿到 task-management 视图。</p>
 */
public enum TaskManagementReceiptOutboxState {

    /** 已入库但尚未开始投递，通常是新创建记录或等待第一轮调度。 */
    PENDING,

    /** 某个实例已经抢到投递权，正在调用 task-management。 */
    DELIVERING,

    /** 最近一次投递失败，但还没达到最大尝试次数，等待 nextRetryAt 后继续。 */
    RETRY_WAIT,

    /** task-management 已确认接收，receipt 投影闭环完成。 */
    DELIVERED,

    /** 达到最大投递次数或出现不可恢复问题，等待运维介入。 */
    DEAD_LETTER
}
