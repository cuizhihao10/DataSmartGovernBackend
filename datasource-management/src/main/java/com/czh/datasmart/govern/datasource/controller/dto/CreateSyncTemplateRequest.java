package com.czh.datasmart.govern.datasource.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * @Author : Cui
 * @Date: 2026/4/18 23:18
 * @Description DataSmart Govern Backend - CreateSyncTemplateRequest.java
 * @Version:1.0.0
 *
 * 创建同步模板请求。
 * 这个请求对象关注的是“模板定义”本身，因此不包含任务运行期的状态、执行编号等字段。
 */
@Data
public class CreateSyncTemplateRequest {

    /**
     * 租户标识。
     */
    @NotNull(message = "tenantId 不能为空")
    private Long tenantId;

    /**
     * 模板名称。
     */
    @NotBlank(message = "模板名称不能为空")
    private String name;

    /**
     * 模板业务说明。
     */
    private String description;

    /**
     * 源数据源 ID。
     */
    @NotNull(message = "sourceDatasourceId 不能为空")
    private Long sourceDatasourceId;

    /**
     * 源端 schema 名称。
     */
    private String sourceSchemaName;

    /**
     * 源端对象名称。
     * 对关系型数据库来说通常是表或视图名。
     */
    @NotBlank(message = "sourceObjectName 不能为空")
    private String sourceObjectName;

    /**
     * 目标数据源 ID。
     */
    @NotNull(message = "targetDatasourceId 不能为空")
    private Long targetDatasourceId;

    /**
     * 目标端 schema 名称。
     */
    private String targetSchemaName;

    /**
     * 目标端对象名称。
     */
    @NotBlank(message = "targetObjectName 不能为空")
    private String targetObjectName;

    /**
     * 同步模式。
     */
    @NotBlank(message = "syncMode 不能为空")
    private String syncMode;

    /**
     * 主键字段。
     */
    private String primaryKeyField;

    /**
     * 增量字段。
     */
    private String incrementalField;

    /**
     * 字段映射配置 JSON。
     */
    private String fieldMappingConfig;

    /**
     * 过滤条件配置 JSON。
     */
    private String filterConfig;

    /**
     * 分片/分区配置 JSON。
     */
    private String partitionConfig;

    /**
     * 重试策略 JSON。
     */
    private String retryPolicy;

    /**
     * 超时策略 JSON。
     */
    private String timeoutPolicy;

    /**
     * 是否启用模板。
     */
    private Boolean enabled;

    /**
     * 创建人。
     */
    @NotNull(message = "createdBy 不能为空")
    private Long createdBy;
}
