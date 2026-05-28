/**
 * @Author : Cui
 * @Date: 2026/05/08 09:10
 * @Description DataSmart Govern Backend - SyncExecutionCheckpointRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 同步 checkpoint 写入请求。
 */
@Data
public class SyncExecutionCheckpointRequest {

    @NotBlank(message = "执行器 ID 不能为空")
    private String executorId;

    @NotBlank(message = "checkpoint 类型不能为空")
    private String checkpointType;

    @NotBlank(message = "checkpoint 值不能为空")
    private String checkpointValue;

    private String shardOrPartition;
    private Long recordsRead;
    private Long recordsWritten;
    private String idempotencyKey;
}
