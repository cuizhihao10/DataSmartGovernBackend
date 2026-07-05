/**
 * @Author : Cui
 * @Date: 2026/07/05 14:52
 * @Description DataSmart Govern Backend - SyncOfflineRunnerAdapter.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

/**
 * 专用 DataX-style 离线 Runner 适配器 SPI。
 *
 * <p>当前项目已经把离线作业拆成“控制面合同”和“执行面 Runner”两层。本接口就是执行面 Runner 的最小接入点：
 * data-sync 调度门面先生成 {@link SyncOfflineRunnerJobContract}，再询问注册的 adapter 谁能承接该合同。
 * 这样后续无论真实实现是 DataX、Flink batch、Spark、Airbyte-like worker、自研 Java runner，还是远程任务服务，
 * 都不需要改 worker loop 和任务 claim 状态机，只需要实现本接口。</p>
 *
 * <p>adapter 的边界非常重要：</p>
 * <p>1. {@link #supports(SyncOfflineRunnerJobContract)} 只能基于低敏合同判断能力，不应读取连接凭据或 SQL 正文；</p>
 * <p>2. {@link #dispatch(SyncOfflineRunnerExecutionRequest)} 可以把合同提交给真实执行器，但必须继续遵守低敏报告策略；</p>
 * <p>3. 如果 adapter 只是异步提交作业，应返回 {@code dispatched=true, completed=false, failed=false}，最终状态由回调闭环；</p>
 * <p>4. 如果 adapter 在提交前发现不可执行，应返回低敏问题码，或抛出异常由调度门面统一 fail-closed。</p>
 */
public interface SyncOfflineRunnerAdapter {

    /**
     * adapter 编码。
     *
     * <p>该编码会进入低敏调度结果，用于运维诊断和后续容量统计。它应该稳定、低基数，例如
     * {@code DATAX_STANDALONE_RUNNER} 或 {@code FLINK_BATCH_RUNNER}，不要拼接租户、表名、SQL 标识或机器名。</p>
     *
     * @return adapter 编码。
     */
    String adapterCode();

    /**
     * 判断当前 adapter 是否能承接指定离线 Runner 合同。
     *
     * @param contract 低敏 Runner 合同。
     * @return true 表示本 adapter 愿意接收该合同。
     */
    boolean supports(SyncOfflineRunnerJobContract contract);

    /**
     * 派发或执行离线 Runner 合同。
     *
     * @param request 专用 Runner 执行请求，包含 bridge plan、合同、任务、模板、execution 和操作者上下文。
     * @return 低敏 adapter 派发结果。
     */
    SyncOfflineRunnerAdapterResult dispatch(SyncOfflineRunnerExecutionRequest request);
}
