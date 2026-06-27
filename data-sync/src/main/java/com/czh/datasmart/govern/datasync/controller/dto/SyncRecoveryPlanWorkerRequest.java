/**
 * @Author : Cui
 * @Date: 2026/06/27 16:20
 * @Description DataSmart Govern Backend - SyncRecoveryPlanWorkerRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * worker 读取或消费同步恢复计划的请求。
 *
 * <p>该请求只承载机器协议必需字段，不允许携带 SQL、连接串、样本数据、凭据、用户 prompt、
 * 模型输出或任意业务 payload。恢复计划的详细来源坐标已经由控制面持久化在
 * {@code data_sync_execution_recovery_plan} 表中，worker 这里只需要声明“我是哪个执行器”。
 *
 * <p>为什么只要求 executorId：
 * 1. 当前 execution 的租约持有人已经写入 {@code data_sync_execution.executor_id}；
 * 2. 服务端会用 executorId 与 execution.executorId 做二次校验，防止其它 worker 猜 executionId 后读取恢复计划；
 * 3. 具体恢复窗口、checkpoint 和分区由服务端白名单返回，避免 worker 请求体伪造恢复范围。
 */
@Data
public class SyncRecoveryPlanWorkerRequest {

    /**
     * 执行器实例 ID。
     *
     * <p>该字段必须与当前 RUNNING execution 的 executorId 完全一致。它不是普通用户 ID，
     * 而是 worker 在 claim 阶段写入的机器实例标识，用于证明当前调用方确实持有执行租约。
     */
    @NotBlank(message = "执行器 ID 不能为空")
    private String executorId;

    /**
     * 幂等键。
     *
     * <p>当前 claim/consume 的核心幂等由恢复计划状态机保证，因此该字段暂时不参与数据库幂等表。
     * 仍然保留它，是为了未来把 worker SDK 的网络重试、请求追踪和统一幂等审计接入同一套协议。
     */
    private String idempotencyKey;
}
