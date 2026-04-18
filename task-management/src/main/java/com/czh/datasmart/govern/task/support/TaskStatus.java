package com.czh.datasmart.govern.task.support;

/**
 * 任务状态常量。
 * <p>
 * 当前先使用显式常量而不是引入复杂状态机框架，
 * 是因为现阶段更重要的是先把业务边界和学习路径表达清楚。
 * 当状态数量和迁移规则进一步复杂后，再考虑升级实现方式。
 */
public final class TaskStatus {

    /**
     * 已创建但未开始执行。
     */
    public static final String PENDING = "PENDING";

    /**
     * 正在执行。
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
     * 被取消。
     */
    public static final String CANCELLED = "CANCELLED";

    private TaskStatus() {
    }
}
