/**
 * @Author : Cui
 * @Date: 2026/05/08 21:52
 * @Description DataSmart Govern Backend - SyncExecutionHeartbeatRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 同步执行心跳请求。
 */
@Data
public class SyncExecutionHeartbeatRequest {

    @NotBlank(message = "执行器 ID 不能为空")
    private String executorId;

    private Long recordsRead;
    private Long recordsWritten;
    private Long leaseSeconds;

    /**
     * 幂等键。
     *
     * <p>同一次心跳重试应复用同一个键；新的心跳周期应使用新的键。
     * 当前字段可选，但生产执行器建议必传，便于服务端识别网络重试导致的重复心跳。
     */
    private String idempotencyKey;
}
