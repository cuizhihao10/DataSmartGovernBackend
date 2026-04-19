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
 * 这个对象描述的是“模板定义本身”，而不是某一次运行实例。
 * 因此这里不会出现任务状态、执行编号、运行统计等字段。
 *
 * 从产品建模角度看，模板请求应该重点表达：
 * - 数据从哪里来、写到哪里去；
 * - 采用什么同步模式；
 * - 采用什么写入策略；
 * - 用什么字段做增量与冲突判定；
 * - 字段映射、过滤、分区、重试、超时等策略如何配置。
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
     * 对关系型数据库来说通常就是表名或视图名。
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
     * 例如 FULL、INCREMENTAL_TIME、INCREMENTAL_ID、BACKFILL。
     */
    @NotBlank(message = "syncMode 不能为空")
    private String syncMode;

    /**
     * 写入策略。
     * 如果调用方不传，服务层会自动采用更保守的默认值 APPEND。
     * 这里不强制前端必填，是为了兼容当前仓库已有接口调用方式，
     * 但从产品设计角度，更推荐调用方显式传值。
     */
    private String writeStrategy;

    /**
     * 主键字段。
     * 该字段会影响幂等写入、去重、冲突判定和 upsert 策略。
     */
    private String primaryKeyField;

    /**
     * 增量字段。
     * 时间增量和 ID 增量模式下通常需要该字段。
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
     * 分片或分区配置 JSON。
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
