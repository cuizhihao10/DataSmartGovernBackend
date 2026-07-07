/**
 * @Author : Cui
 * @Date: 2026/05/07 21:38
 * @Description DataSmart Govern Backend - SyncExecutionState.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.support;

/**
 * 同步执行记录状态。
 *
 * <p>任务状态和执行状态要分开理解：
 * 1. SyncTask 表示“这个同步任务作为运营对象当前处于什么阶段”；
 * 2. SyncExecution 表示“某一次真实运行当前执行到哪里”。
 *
 * <p>同一个任务可以有多次执行记录，例如第一次失败、第二次重试成功、第三次做历史补数。
 * 如果只在任务主表保存状态，就无法支撑执行历史、失败样本、checkpoint 回放和事故复盘。
 */
public enum SyncExecutionState {
    QUEUED,
    RUNNING,
    PAUSED,
    RETRYING,
    PARTIALLY_SUCCEEDED,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    /**
     * 手工结束。
     *
     * <p>execution 级 MANUALLY_TERMINATED 用于记录“某一次真实运行被人工终止”。
     * 它不同于任务主状态：任务主状态回答任务定义后续是否还能调度，execution 状态回答这一次运行为何停止。
     * worker 心跳和写入型回调看到该状态时必须停止，不允许继续写 checkpoint、complete 或 fail。</p>
     */
    MANUALLY_TERMINATED,
    /**
     * 已跳过。
     *
     * <p>SKIPPED 预留给调度 misfire、依赖未满足、容量窗口冲突或对象级 fan-out 汇总时的“本次不执行”记录。
     * 它可以让执行历史区分“失败”和“按策略跳过”，避免调度看板把有意跳过误判为运行故障。</p>
     */
    SKIPPED
}
