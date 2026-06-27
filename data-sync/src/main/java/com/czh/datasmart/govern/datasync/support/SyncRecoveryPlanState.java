/**
 * @Author : Cui
 * @Date: 2026/06/27 16:20
 * @Description DataSmart Govern Backend - SyncRecoveryPlanState.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.support;

/**
 * 同步恢复计划状态。
 *
 * <p>恢复计划是 replay/backfill execution 的“执行契约”，它不是普通任务状态，也不是 worker 本地状态。
 * 控制台创建 replay/backfill 时会先创建新的 {@code SyncExecution}，再创建一条与 executionId 绑定的恢复计划。
 * worker 认领 execution 后，需要通过恢复计划接口读取“从哪个 checkpoint 回放、补哪个窗口、按哪个分区执行”等低敏坐标。
 *
 * <p>状态拆分的设计目的：
 * 1. 避免 worker 重复读取计划时无法区分“从未读取”和“已经读取但本地重试”；
 * 2. 避免计划被消费前后没有审计证据，导致事故复盘无法回答 worker 是否真的接收过恢复指令；
 * 3. 为后续增加 CANCELLED、EXPIRED、SUPERSEDED 等治理状态预留稳定枚举边界。
 */
public enum SyncRecoveryPlanState {

    /**
     * 控制面已经创建计划，但 worker 尚未读取。
     *
     * <p>这是 replay/backfill API 写入恢复计划后的初始状态。此时 execution 通常处于 QUEUED，
     * 等待 worker 通过租约协议认领。CREATED 状态不能直接视为“正在补数”，因为它只说明控制面已经准备好契约。
     */
    CREATED,

    /**
     * worker 已经读取并锁定计划。
     *
     * <p>CLAIMED 表示 worker 已经知道自己要执行的是 replay/backfill 恢复任务，并拿到了低敏恢复坐标。
     * 这个状态是执行前准备完成的证据，但还不代表 worker 已经把计划应用到本地读取器或 checkpoint 策略中。
     */
    CLAIMED,

    /**
     * worker 已经把计划作为执行输入消费。
     *
     * <p>CONSUMED 表示 worker 已完成恢复计划加载，后续应进入普通 checkpoint、complete、fail 回调链路。
     * 计划一旦 CONSUMED，就不再允许回退到 CREATED，避免同一个补数窗口被重复启动。
     */
    CONSUMED,

    /**
     * 计划已取消。
     *
     * <p>当前版本暂不开放取消恢复计划接口，但保留该状态是为了后续支持控制台停止待执行补数、替换计划、
     * 或在 worker 认领前撤销高风险 replay/backfill。
     */
    CANCELLED
}
