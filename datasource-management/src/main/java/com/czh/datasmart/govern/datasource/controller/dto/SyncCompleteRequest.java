package com.czh.datasmart.govern.datasource.controller.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * @Author : Cui
 * @Date: 2026/4/18 23:18
 * @Description DataSmart Govern Backend - SyncCompleteRequest.java
 * @Version:1.0.0
 *
 * 执行完成请求。
 */
@Data
public class SyncCompleteRequest {

    @NotNull(message = "actorId 不能为空")
    private Long actorId;

    @NotBlank(message = "actorRole 不能为空")
    private String actorRole;

    /**
     * 完成回写动作所属租户。
     */
    @NotNull(message = "actorTenantId 不能为空")
    private Long actorTenantId;

    @NotNull(message = "executionId 不能为空")
    private Long executionId;

    @NotNull(message = "recordsRead 不能为空")
    @Min(value = 0, message = "recordsRead 不能小于 0")
    private Long recordsRead;

    @NotNull(message = "recordsWritten 不能为空")
    @Min(value = 0, message = "recordsWritten 不能小于 0")
    private Long recordsWritten;

    @NotNull(message = "failedRecordCount 不能为空")
    @Min(value = 0, message = "failedRecordCount 不能小于 0")
    private Long failedRecordCount;

    private String summary;
}
