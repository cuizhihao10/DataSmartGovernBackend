package com.czh.datasmart.govern.task.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * @Author : Cui
 * @Date: 2026/4/18 22:12
 * @Description DataSmart Govern Backend - TaskFailRequest.java
 * @Version:1.0.0
 *
 * 标记任务失败时的请求体。
 * 失败信息非常关键，因为它直接决定后续是否重试、如何排障以及是否需要人工介入。
 */
@Data
public class TaskFailRequest {

    /**
     * 当前执行 run ID。
     *
     * <p>失败回调必须绑定本次 run，避免已经失去租约的旧 worker 迟到上报失败，
     * 把新 worker 正在处理的任务错误标记为 FAILED。
     */
    @NotNull(message = "执行 runId 不能为空")
    private Long runId;

    /**
     * 执行器实例 ID。
     *
     * <p>只有当前租约持有者才能把任务标记失败，这是执行器协议最核心的安全边界之一。
     */
    @NotBlank(message = "执行器 ID 不能为空")
    private String executorId;

    /**
     * 幂等键。
     *
     * <p>失败回调也可能因网络超时被重复提交。要求调用方提供幂等键，
     * 可以减少重复日志、重复告警和重复失败补偿带来的运维噪音。
     */
    @NotBlank(message = "幂等键不能为空")
    private String idempotencyKey;

    /**
     * 失败原因说明。
     * 当前要求非空，目的是强制每一次失败都留下最基本的故障上下文。
     */
    @NotBlank(message = "失败原因不能为空")
    private String errorMessage;
}
