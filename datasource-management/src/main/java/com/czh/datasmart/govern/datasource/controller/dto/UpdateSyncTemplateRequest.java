package com.czh.datasmart.govern.datasource.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * @Author : Cui
 * @Date: 2026/4/18 23:18
 * @Description DataSmart Govern Backend - UpdateSyncTemplateRequest.java
 * @Version:1.0.0
 *
 * 更新同步模板请求。
 */
@Data
public class UpdateSyncTemplateRequest {

    @NotBlank(message = "模板名称不能为空")
    private String name;

    private String description;

    @NotNull(message = "sourceDatasourceId 不能为空")
    private Long sourceDatasourceId;

    private String sourceSchemaName;

    @NotBlank(message = "sourceObjectName 不能为空")
    private String sourceObjectName;

    @NotNull(message = "targetDatasourceId 不能为空")
    private Long targetDatasourceId;

    private String targetSchemaName;

    @NotBlank(message = "targetObjectName 不能为空")
    private String targetObjectName;

    @NotBlank(message = "syncMode 不能为空")
    private String syncMode;

    private String primaryKeyField;

    private String incrementalField;

    private String fieldMappingConfig;

    private String filterConfig;

    private String partitionConfig;

    private String retryPolicy;

    private String timeoutPolicy;

    @NotNull(message = "enabled 不能为空")
    private Boolean enabled;

    @NotNull(message = "updatedBy 不能为空")
    private Long updatedBy;
}
