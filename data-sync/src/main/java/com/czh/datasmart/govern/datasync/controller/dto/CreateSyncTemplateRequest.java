/**
 * @Author : Cui
 * @Date: 2026/05/07 21:28
 * @Description DataSmart Govern Backend - CreateSyncTemplateRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 创建同步模板请求。
 *
 * <p>该请求只保存同步配置，不直接触发执行。
 * 后续真实产品中，模板创建后通常还会经历校验、预览、审批、生成任务、调度等环节。
 */
@Data
public class CreateSyncTemplateRequest {

    /**
     * 租户 ID。普通用户场景下服务端会优先使用 gateway 透传租户，平台管理员或服务账号才允许显式指定。
     */
    private Long tenantId;

    /**
     * 项目 ID。
     *
     * <p>用于租户内部二级隔离。项目负责人、项目级同步看板、项目级配额和成本统计都会依赖该字段。
     */
    private Long projectId;

    /**
     * 工作空间 ID。
     *
     * <p>用于多团队协作空间和空间级筛选。当前可为空，后续如果前端已经有 workspace 上下文，应优先传入。
     */
    private Long workspaceId;

    @NotBlank(message = "同步模板名称不能为空")
    private String name;

    private String description;

    @NotNull(message = "源数据源 ID 不能为空")
    private Long sourceDatasourceId;

    @NotNull(message = "目标数据源 ID 不能为空")
    private Long targetDatasourceId;

    /**
     * 源端连接器类型，例如 MYSQL、POSTGRESQL、KAFKA、OBJECT_STORAGE。
     *
     * <p>这个字段是低敏控制面信息，只表达“源端属于哪类系统”，不表达真实 host、port、database、topic、
     * bucket、文件路径、账号或密钥。当前它是可选字段：旧调用方只传 datasourceId 时仍可创建模板；
     * 新调用方或 Agent 规划如果能提供该字段，服务端会结合连接器能力矩阵做更强的同步模式预检。</p>
     */
    private String sourceConnectorType;

    /**
     * 目标端连接器类型。
     *
     * <p>必须与 sourceConnectorType 同时提供或同时缺省。只传其中一个会导致模板校验无法判断完整的数据流向，
     * 服务端会 fail-closed 拒绝，避免把“半个能力事实”误当成可执行配置。</p>
     */
    private String targetConnectorType;

    /**
     * 同步模式编码，例如 FULL、INCREMENTAL_TIME、CDC_STREAMING。
     */
    @NotBlank(message = "同步模式不能为空")
    private String syncMode;

    private String fieldMappingConfig;
    private String filterConfig;
    private String partitionConfig;
    private String retryPolicy;
    private String timeoutPolicy;
}
