/**
 * @Author : Cui
 * @Date: 2026/07/08 15:21
 * @Description DataSmart Govern Backend - SyncTaskWizardStepValidationRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller.dto;

import lombok.Data;

/**
 * 同步任务创建向导单步校验请求。
 *
 * <p>前端在“下一步”或“保存并进入下一步”时可以把当前步骤已收集的结构化字段提交给该接口，让后端给出阻断项和警告项。
 * 它不是最终创建模板/任务的接口，也不会写入源端或目标端数据；它只做低敏、快速、可解释的控制面校验。</p>
 *
 * <p>为什么仍保留若干 JSON 字段：当前后端模板表仍以 JSON 文本保存对象映射、字段映射、过滤条件、SQL 配置等复杂结构。
 * 但新的前端不应该直接让用户编辑这些 JSON，而是通过选择、搜索、勾选、编辑映射名称、填写 where 条件等 UI 操作生成这些字段。
 * 这个请求体只是承接前端生成后的结构化结果，不代表 UI 继续展示 JSON 文本框。</p>
 */
@Data
public class SyncTaskWizardStepValidationRequest {

    /**
     * 当前步骤编码，例如 SOURCE_TARGET、OBJECT_MAPPING、FIELD_SQL、PRECHECK。
     */
    private String stepCode;

    /**
     * 用户第一步选择的五类传输模式之一。
     */
    private String syncMode;

    /**
     * 源端数据源 ID；前端应只从 usagePurpose=SOURCE 的数据源列表中选择。
     */
    private Long sourceDatasourceId;

    /**
     * 目标端数据源 ID；前端应只从 usagePurpose=TARGET 的数据源列表中选择。
     */
    private Long targetDatasourceId;

    /**
     * 同步范围类型，例如 SINGLE_OBJECT、OBJECT_LIST、SCHEMA_FULL、DATABASE_FULL、CUSTOM_SQL_QUERY。
     */
    private String syncScopeType;

    private String sourceSchemaName;
    private String sourceObjectName;
    private String targetSchemaName;
    private String targetObjectName;

    /**
     * 前端通过元数据发现、搜索、勾选、排除、改名等交互生成的对象映射配置。
     */
    private String objectMappingConfig;

    /**
     * 前端通过字段勾选、同名映射、类型兼容提示、别名映射等交互生成的字段映射配置。
     */
    private String fieldMappingConfig;

    /**
     * where 条件配置。过滤条件可以在 UI 中保持“可编辑表达式”，但后端仍应在预检查阶段做安全约束。
     */
    private String filterConfig;

    /**
     * SQL 自定义传输配置。该字段只应在 CUSTOM_SQL_QUERY 模式下出现，并由后端进行只读 SQL、语法和对象存在性预检查。
     */
    private String customSqlConfig;

    /**
     * 用户可见写入策略，只允许 INSERT 或 UPDATE。
     */
    private String writeStrategy;

    /**
     * 定时全量/定时批量的调度配置。FULL/CUSTOM_SQL_QUERY/CDC_STREAMING 创建阶段不允许通过该字段伪装成定时任务。
     */
    private String scheduleConfig;
}
