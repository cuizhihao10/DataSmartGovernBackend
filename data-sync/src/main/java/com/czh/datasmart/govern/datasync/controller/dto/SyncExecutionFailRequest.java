/**
 * @Author : Cui
 * @Date: 2026/05/08 09:10
 * @Description DataSmart Govern Backend - SyncExecutionFailRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 同步执行失败请求。
 *
 * <p>失败回调会同时更新 execution 终态、任务终态，并写入一条错误样本，便于运营人员排障。
 */
@Data
public class SyncExecutionFailRequest {

    @NotBlank(message = "执行器 ID 不能为空")
    private String executorId;

    @NotBlank(message = "错误类型不能为空")
    private String errorType;

    private String errorCode;
    private String errorMessage;
    private String sourceRecordKey;
    private String targetRecordKey;
    private String samplePayload;
    private Boolean retryable;
    private String idempotencyKey;
}
