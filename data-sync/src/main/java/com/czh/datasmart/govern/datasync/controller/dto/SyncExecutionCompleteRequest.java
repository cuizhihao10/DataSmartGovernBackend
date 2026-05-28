/**
 * @Author : Cui
 * @Date: 2026/05/08 09:10
 * @Description DataSmart Govern Backend - SyncExecutionCompleteRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 同步执行成功完成请求。
 */
@Data
public class SyncExecutionCompleteRequest {

    @NotBlank(message = "执行器 ID 不能为空")
    private String executorId;

    private Long recordsRead;
    private Long recordsWritten;
    private String checkpointRef;
    private String idempotencyKey;
}
