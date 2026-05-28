/**
 * @Author : Cui
 * @Date: 2026/05/28 18:00
 * @Description DataSmart Govern Backend - AgentToolExecutionEventOutboxStatus.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.event.outbox;

/**
 * Agent 工具执行事件 outbox 记录状态。
 *
 * <p>这些状态不是工具本身的业务状态，而是“事件发布任务”的生命周期状态。
 * 例如工具业务状态可能是 SUCCEEDED，但 outbox 状态仍可能是 PENDING，表示成功事件已经被写入事件箱，
 * 但还没有被后台投递器发布到 Kafka 或审计中心。</p>
 */
public enum AgentToolExecutionEventOutboxStatus {

    /**
     * 待投递。
     *
     * <p>事件已经写入 outbox，可以被后续 dispatcher 拉取并发布到 Kafka、WebSocket、审计中心或持久化投影。</p>
     */
    PENDING,

    /**
     * 投递中。
     *
     * <p>后续 dispatcher 领取任务后会先标记为该状态，用于避免多个 worker 同时投递同一条事件。
     * 当前内存版主要是为后续持久化实现保留状态语义。</p>
     */
    PUBLISHING,

    /**
     * 已投递。
     *
     * <p>表示下游发布已经成功确认。生产环境通常还需要记录 broker offset、审计落库 ID 或 WebSocket 批次号。</p>
     */
    PUBLISHED,

    /**
     * 投递失败但可重试。
     *
     * <p>网络抖动、Kafka broker 临时不可用、下游限流等场景应该进入该状态，并通过 nextRetryAt 控制下次重试时间。</p>
     */
    FAILED,

    /**
     * 被阻断，需要人工或修复后再处理。
     *
     * <p>例如 payload 超过安全上限、序列化异常、事件字段缺少关键标识等，不应盲目自动重试，否则可能制造事件风暴。</p>
     */
    BLOCKED,

    /**
     * 已人工忽略。
     *
     * <p>IGNORED 表示运维或平台管理员明确判断该事件不再需要自动投递，也不需要继续占用补偿队列。
     * 它与 PUBLISHED 不同：PUBLISHED 表示事件已经成功送达下游；IGNORED 表示事件没有送达，但经过人工判断后被归档。
     * 真实生产环境中，该状态必须保留操作者、原因和审计记录，避免关键治理事件被静默吞掉。</p>
     */
    IGNORED
}
