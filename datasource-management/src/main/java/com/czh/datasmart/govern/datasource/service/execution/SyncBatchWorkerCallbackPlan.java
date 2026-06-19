/**
 * @Author : Cui
 * @Date: 2026/06/20 03:22
 * @Description DataSmart Govern Backend - SyncBatchWorkerCallbackPlan.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.service.execution;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * worker 回调计划。
 *
 * <p>真实批处理 worker 不是“拿到 SQL 后跑完就结束”。
 * 它必须持续向 Java 控制面回写心跳、进度、checkpoint、完成或失败结果，
 * 这样队列、租约恢复、运维告警和审计才能判断任务是否仍然健康。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SyncBatchWorkerCallbackPlan {

    /**
     * 执行器实例 ID。
     */
    private String executorId;

    /**
     * 租约过期时间。
     */
    private LocalDateTime leaseExpireAt;

    /**
     * 是否需要心跳。
     */
    private Boolean heartbeatRequired;

    /**
     * 必须回调的动作。
     * 例如 heartbeat、progress、complete、fail。
     */
    private List<String> requiredCallbacks;

    /**
     * 幂等范围说明。
     */
    private String idempotencyScope;

    /**
     * 超时时间。
     */
    private Integer timeoutSeconds;

    /**
     * 最大重试次数。
     */
    private Integer maxRetryCount;
}
