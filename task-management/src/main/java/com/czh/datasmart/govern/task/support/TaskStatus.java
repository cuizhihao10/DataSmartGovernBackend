package com.czh.datasmart.govern.task.support;

/**
 * @Author : Cui
 * @Date: 2026/4/18 22:12
 * @Description DataSmart Govern Backend - TaskStatus.java
 * @Version:1.0.0
 *
 * 任务状态常量定义。
 * 当前先采用简单常量类，而不是立刻引入完整状态机框架，
 * 原因是现阶段更需要让状态流转规则足够直观、可读、便于学习。
 *
 * 当前生命周期主线大致如下：
 * PENDING -> RUNNING -> SUCCESS / FAILED
 * RUNNING -> DEFERRED -> RUNNING（执行器主动退避后延迟回队列）
 * RUNNING -> DEAD_LETTER（连续退避超过上限，需要人工关注）
 * RUNNING -> PAUSED -> RUNNING
 * PENDING / RUNNING / PAUSED -> CANCELLED
 * FAILED / CANCELLED / DEAD_LETTER -> PENDING（通过受控 retry 重新进入下一轮）
 *
 * 随着任务中心朝商业化产品演进，后续状态会继续扩展为：
 * DRAFT、CONFIGURED、PENDING_APPROVAL、SCHEDULED、QUEUED、RETRYING、PARTIAL_SUCCESS、ARCHIVED 等。
 * 当前代码先保留轻量常量模式，把最核心的人工运维动作和日志闭环补起来。
 */
public final class TaskStatus {

    /**
     * 已登记、待调度、尚未开始执行。
     */
    public static final String PENDING = "PENDING";

    /**
     * 正在执行中。
     */
    public static final String RUNNING = "RUNNING";

    /**
     * 已暂停，可恢复。
     */
    public static final String PAUSED = "PAUSED";

    /**
     * 重试准备中。
     *
     * <p>当前第一版强制重试最终仍会回到 PENDING，等待后续调度器或执行器启动。
     * 这里先保留 RETRYING 常量，是为了给后续“重试排队中、重试中、选择性重跑、断点续跑”等更细状态预留语义。
     */
    public static final String RETRYING = "RETRYING";

    /**
     * 延迟回队列。
     *
     * <p>DEFERRED 表示任务已经被执行器认领过，但执行器因为资源不足、并发配额、下游限流等原因，
     * 主动放弃本次执行并要求稍后再认领。
     *
     * <p>它与 FAILED 的区别非常重要：
     * - FAILED 表示本次执行确实失败，需要进入失败分析、重试或人工关注；
     * - DEFERRED 表示系统在做背压保护，任务本身还没有真正执行失败。
     */
    public static final String DEFERRED = "DEFERRED";

    /**
     * 死信/人工关注状态。
     *
     * <p>DEAD_LETTER 表示任务没有被继续放回自动认领队列。
     * 当前最典型的进入原因是连续多次 DEFERRED：执行器反复认领后又因为容量不足退避，
     * 说明系统容量、租户配额、数据源健康度或任务本身的调度策略存在持续问题。
     *
     * <p>与 FAILED 的差异：
     * - FAILED 通常代表任务执行逻辑或外部依赖失败；
     * - DEAD_LETTER 代表系统已经停止自动推进，需要运营人员查看原因后决定扩容、降级、调整配额或强制重试。
     */
    public static final String DEAD_LETTER = "DEAD_LETTER";

    /**
     * 已成功完成。
     */
    public static final String SUCCESS = "SUCCESS";

    /**
     * 执行失败。
     */
    public static final String FAILED = "FAILED";

    /**
     * 被主动取消。
     */
    public static final String CANCELLED = "CANCELLED";

    private TaskStatus() {
    }
}
