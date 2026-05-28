/**
 * @Author : Cui
 * @Date: 2026/05/08 09:10
 * @Description DataSmart Govern Backend - SyncExecutionStartRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 同步执行开始请求。
 *
 * <p>该请求由执行器或后续 task-management worker 调用，用于把 QUEUED execution 推进到 RUNNING。
 */
@Data
public class SyncExecutionStartRequest {

    @NotBlank(message = "执行器 ID 不能为空")
    private String executorId;

    /**
     * 幂等键。当前先进入审计摘要，后续会落独立幂等表或唯一键。
     */
    private String idempotencyKey;
}
