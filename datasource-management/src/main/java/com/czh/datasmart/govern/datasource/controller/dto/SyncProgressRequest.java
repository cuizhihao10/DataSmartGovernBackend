package com.czh.datasmart.govern.datasource.controller.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * @Author : Cui
 * @Date: 2026/4/18 23:18
 * @Description DataSmart Govern Backend - SyncProgressRequest.java
 * @Version:1.0.0
 *
 * 执行进度上报请求。
 * 未来真正的执行器会使用这一类接口把运行期指标持续回写到控制面。
 */
@Data
public class SyncProgressRequest {

    @NotNull(message = "actorId 不能为空")
    private Long actorId;

    @NotBlank(message = "actorRole 不能为空")
    private String actorRole;

    /**
     * 当前执行回写动作所属租户。
     * 对执行器类接口来说，租户上下文不仅用于权限，也用于后续做租户级审计和限流。
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

    private String checkpointType;

    private String checkpointValue;

    private String shardOrPartition;

    private String errorSummary;
}
