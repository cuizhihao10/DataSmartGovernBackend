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

    /**
     * 失败发生前已经读取的记录数。
     *
     * <p>普通单对象失败可以为空，由生命周期组件沿用 execution 已有计数；
     * OBJECT_LIST 这类父任务汇总失败时会传入聚合值，避免父 execution 只记录“失败样本 +1”而丢失已成功对象的读写计数。</p>
     */
    private Long recordsRead;

    /**
     * 失败发生前已经写入的记录数。
     */
    private Long recordsWritten;

    /**
     * 聚合失败记录数或失败对象数。
     *
     * <p>对象级 fan-out 场景下，该字段通常表示失败对象数量；行级错误样本场景下可以表示失败行数。
     * 当前 DTO 允许为空是为了保持旧回调兼容。</p>
     */
    private Long failedRecordCount;

    private Boolean retryable;
    private String idempotencyKey;
}
