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
 * 更新场景和创建场景的本质区别在于：
 * 1. 创建关注“是否补齐必要定义”。
 * 2. 更新关注“修改后是否会影响已有任务和后续执行语义”。
 *
 * 因此这里虽然字段与创建请求高度相似，但在业务上仍建议保留独立 DTO，
 * 便于后续继续扩展差异化校验，例如：
 * - 修改模板时是否需要版本号；
 * - 是否允许修改目标端对象；
 * - 是否需要审批后生效。
 */
@Data
public class UpdateSyncTemplateRequest {

    /**
     * 模板名称。
     */
    @NotBlank(message = "模板名称不能为空")
    private String name;

    /**
     * 模板说明。
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
     */
    @NotBlank(message = "sourceObjectName 不能为空")
    private String sourceObjectName;

    /**
     * 目标数据源 ID。
     */
    @NotNull(message = "targetDatasourceId 不能为空")
    private Long targetDatasourceId;

    /**
     * 目标 schema 名称。
     */
    private String targetSchemaName;

    /**
     * 目标对象名称。
     */
    @NotBlank(message = "targetObjectName 不能为空")
    private String targetObjectName;

    /**
     * 同步模式。
     */
    @NotBlank(message = "syncMode 不能为空")
    private String syncMode;

    /**
     * 写入策略。
     * 与创建接口一样，服务层会对空值兜底成 APPEND。
     */
    private String writeStrategy;

    /**
     * 主键字段。
     */
    private String primaryKeyField;

    /**
     * 增量字段。
     */
    private String incrementalField;

    /**
     * 字段映射配置。
     */
    private String fieldMappingConfig;

    /**
     * 过滤条件配置。
     */
    private String filterConfig;

    /**
     * 分片或分区配置。
     */
    private String partitionConfig;

    /**
     * 重试策略配置。
     */
    private String retryPolicy;

    /**
     * 超时策略配置。
     */
    private String timeoutPolicy;

    /**
     * 是否启用模板。
     */
    @NotNull(message = "enabled 不能为空")
    private Boolean enabled;

    /**
     * 更新人。
     */
    @NotNull(message = "updatedBy 不能为空")
    private Long updatedBy;
}
