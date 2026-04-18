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
 * RUNNING -> PAUSED -> RUNNING
 * PENDING / RUNNING / PAUSED -> CANCELLED
 * FAILED / CANCELLED -> PENDING（通过 retry 重新进入下一轮）
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
