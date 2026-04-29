package com.czh.datasmart.govern.task.support;

/**
 * @Author : Cui
 * @Date: 2026/04/27 01:05
 * @Description DataSmart Govern Backend - TaskExecutionRunState.java
 * @Version:1.0.0
 *
 * 任务执行记录状态。
 *
 * <p>TaskStatus 描述任务主表生命周期，TaskExecutionRunState 描述某一次执行尝试的生命周期。
 * 两者不要混用：
 * - 一个 Task 可能只有一个当前状态；
 * - 但一个 Task 可以有多条 Run，每条 Run 都有自己的成功、失败、超时结果。
 */
public final class TaskExecutionRunState {

    /**
     * 执行器已认领并正在执行。
     */
    public static final String RUNNING = "RUNNING";

    /**
     * 本次执行成功。
     */
    public static final String SUCCESS = "SUCCESS";

    /**
     * 本次执行失败。
     */
    public static final String FAILED = "FAILED";

    /**
     * 执行器心跳超时，被系统恢复流程判定为超时。
     */
    public static final String TIMEOUT = "TIMEOUT";

    /**
     * 本次执行被执行器主动延迟。
     *
     * <p>DEFERRED 是“执行尝试级别”的状态，不等同于任务整体失败。
     * 典型场景是执行器已经从 task-management 认领任务，但在真正访问下游系统前发现本实例并发配额、
     * 租户配额、数据源配额或外部依赖限流已经触顶，于是主动结束当前 run，并把任务主表放回延迟队列。
     *
     * <p>把 run 标记为 DEFERRED 的价值是：运营人员能清楚看到“这一次认领没有真正失败，
     * 而是系统为了保护资源做了背压退避”。如果只把 run 记为 FAILED，会让容量不足和业务异常混在一起，
     * 后续失败率、告警和重试分析都会失真。
     */
    public static final String DEFERRED = "DEFERRED";

    /**
     * 本次执行被取消。
     */
    public static final String CANCELLED = "CANCELLED";

    private TaskExecutionRunState() {
    }
}
