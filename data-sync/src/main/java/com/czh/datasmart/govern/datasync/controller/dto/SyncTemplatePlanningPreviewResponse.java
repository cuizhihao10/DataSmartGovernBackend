/**
 * @Author : Cui
 * @Date: 2026/06/29 02:40
 * @Description DataSmart Govern Backend - SyncTemplatePlanningPreviewResponse.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller.dto;

import java.util.List;

/**
 * 同步模板规划预览响应。
 *
 * <p>规划预览用于回答“这个模板距离创建任务或真实执行还有哪些明显缺口”。它不是数据预览，
 * 不读取源端数据、不返回样本、不执行 SQL、不访问目标端，也不暴露字段映射、过滤条件、分区配置的原文。</p>
 *
 * <p>为什么要独立 DTO：</p>
 * <p>1. `validateTemplate` 更像布尔式校验，失败直接抛异常；preview 更适合给用户、Agent 或运营台返回可解释清单；</p>
 * <p>2. 商业化产品里，用户需要知道“能不能进入任务草稿”“是否建议执行前人工复核”“缺哪个配置块”；</p>
 * <p>3. 这里所有字段都是低敏摘要，便于前端展示、Agent 规划和审计留痕。</p>
 *
 * @param templateId 模板 ID。
 * @param tenantId 模板所属租户 ID。
 * @param projectId 模板所属项目 ID。
 * @param workspaceId 模板所属工作空间 ID。
 * @param sourceDatasourceId 源数据源 ID，只是平台内部引用，不包含连接信息。
 * @param targetDatasourceId 目标数据源 ID，只是平台内部引用，不包含连接信息。
 * @param sourceConnectorType 源端连接器类型低敏枚举，例如 MYSQL。
 * @param targetConnectorType 目标端连接器类型低敏枚举，例如 POSTGRESQL。
 * @param syncMode 同步模式，例如 FULL、INCREMENTAL_TIME、CDC_STREAMING。
 * @param sourceObjectDeclared 是否已经声明源端对象名称；不返回对象名正文，避免普通预览接口扩大业务元数据暴露面。
 * @param targetObjectDeclared 是否已经声明目标端对象名称；缺失时真实 worker 无法形成读写计划。
 * @param writeStrategy 归一化后的写入策略，例如 APPEND、UPSERT、OVERWRITE；为空模板会按 APPEND 兼容处理。
 * @param writeStrategyRequiresConflictKey 当前写入策略是否要求 primaryKeyField。
 * @param primaryKeyDeclared 是否声明主键或冲突字段；不返回字段名正文。
 * @param incrementalFieldDeclared 是否声明增量字段；INCREMENTAL_TIME/INCREMENTAL_ID 必须声明。
 * @param previewStatus 预览状态：READY、NEEDS_REVIEW、BLOCKED。
 * @param canProceedToTaskDraft 是否建议允许进入任务草稿阶段；BLOCKED 时为 false。
 * @param executionPrecheckReady 是否已经达到执行前预检的推荐状态；NEEDS_REVIEW/BLOCKED 时为 false。
 * @param connectorCompatibilitySupported 连接器组合与 syncMode 是否通过能力矩阵。
 * @param checkpointRequired 当前连接器组合和同步模式是否建议 checkpoint。
 * @param fieldMappingDeclared 是否已声明字段映射配置；不返回配置原文。
 * @param filterDeclared 是否已声明过滤/增量边界配置；不返回配置原文。
 * @param partitionDeclared 是否已声明分区/并行配置；不返回配置原文。
 * @param retryPolicyDeclared 是否已声明重试策略；不返回配置原文。
 * @param timeoutPolicyDeclared 是否已声明超时策略；不返回配置原文。
 * @param issueCodes 低敏问题码。
 * @param recommendedActions 推荐动作，不包含 SQL、样本、连接串或凭据。
 * @param performanceNotes 性能提示。
 * @param safetyNotes 安全与治理提示。
 * @param payloadPolicy 低敏载荷策略说明。
 */
public record SyncTemplatePlanningPreviewResponse(
        Long templateId,
        Long tenantId,
        Long projectId,
        Long workspaceId,
        Long sourceDatasourceId,
        Long targetDatasourceId,
        String sourceConnectorType,
        String targetConnectorType,
        String syncMode,
        boolean sourceObjectDeclared,
        boolean targetObjectDeclared,
        String writeStrategy,
        boolean writeStrategyRequiresConflictKey,
        boolean primaryKeyDeclared,
        boolean incrementalFieldDeclared,
        String previewStatus,
        boolean canProceedToTaskDraft,
        boolean executionPrecheckReady,
        boolean connectorCompatibilitySupported,
        boolean checkpointRequired,
        boolean fieldMappingDeclared,
        boolean filterDeclared,
        boolean partitionDeclared,
        boolean retryPolicyDeclared,
        boolean timeoutPolicyDeclared,
        List<String> issueCodes,
        List<String> recommendedActions,
        List<String> performanceNotes,
        List<String> safetyNotes,
        String payloadPolicy
) {
}
