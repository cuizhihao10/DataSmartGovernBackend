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
 * <p>规划预览用于回答“这份模板距离创建任务草稿或进入执行前预检查还差什么”。它不是数据预览：
 * 不读取源端样本、不连接目标端、不执行 SQL、不返回字段映射、过滤条件、分区配置、自定义 SQL 或连接凭据原文。</p>
 *
 * <p>这个 DTO 刻意暴露较多布尔字段，是为了让前端、Agent 和运维台可以分别做不同层面的判断：</p>
 * <p>1. 表单页关心哪些配置块缺失；</p>
 * <p>2. Agent 关心下一步该补字段映射、补对象映射、补审批，还是改同步模式；</p>
 * <p>3. 运维台关心当前模板是否会被最小 run-once bridge 执行，还是只能作为高级同步草稿保存。</p>
 *
 * @param templateId 模板 ID。
 * @param tenantId 租户 ID。
 * @param projectId 项目 ID。
 * @param workspaceId 工作空间 ID。
 * @param sourceDatasourceId 源数据源 ID，只是平台内部引用，不包含连接信息。
 * @param targetDatasourceId 目标数据源 ID，只是平台内部引用，不包含连接信息。
 * @param sourceConnectorType 源端连接器类型低敏枚举，例如 MYSQL、POSTGRESQL。
 * @param targetConnectorType 目标端连接器类型低敏枚举。
 * @param syncMode 同步模式。用户可新建的一级传输模式只包括 FULL、SCHEDULED_FULL、SCHEDULED_BATCH、
 *                 CUSTOM_SQL_QUERY、CDC_STREAMING。
 * @param syncScopeType 同步范围类型，例如 SINGLE_OBJECT、OBJECT_LIST、DATABASE_FULL。
 * @param singleObjectScope 是否单对象同步。
 * @param multiObjectScope 是否多对象、整 schema 或整库同步。
 * @param customSqlScope 是否自定义 SQL 查询同步。
 * @param selectedObjectCount objectMappingConfig 中显式声明的对象映射数量；不返回对象名原文。
 * @param objectMappingDeclared 是否声明了对象映射配置原文。
 * @param customSqlDeclared 是否声明了自定义 SQL 配置原文；不表示 SQL 一定安全，安全性由 issueCodes 表达。
 * @param requiresApproval 当前范围或写入策略是否天然需要审批，例如整库迁移、自定义 SQL、覆盖写入。
 * @param executableByMinimalBridge 当前模板是否能被现有 data-sync -> datasource-management run-once 最小桥直接执行。
 * @param sourceObjectDeclared 是否声明源端对象名；只返回声明状态，不返回对象名。
 * @param targetObjectDeclared 是否声明目标端对象名；只返回声明状态，不返回对象名。
 * @param writeStrategy 归一化写入策略，例如 APPEND、UPSERT、OVERWRITE。
 * @param writeStrategyRequiresConflictKey 写入策略是否要求 primaryKeyField。
 * @param primaryKeyDeclared 是否声明主键或冲突字段。
 * @param incrementalFieldDeclared 是否声明增量字段。
 * @param previewStatus READY、NEEDS_REVIEW、BLOCKED。
 * @param canProceedToTaskDraft 是否建议允许进入任务草稿阶段。
 * @param executionPrecheckReady 是否适合进入执行前预检查；高级范围即使可保存，也可能为 false。
 * @param connectorCompatibilitySupported 连接器组合与同步模式是否通过能力矩阵。
 * @param checkpointRequired 当前连接器组合和同步模式是否建议或要求 checkpoint。
 * @param fieldMappingDeclared 是否声明字段映射配置。
 * @param filterDeclared 是否声明过滤或增量边界配置。
 * @param partitionDeclared 是否声明分区或并行配置。
 * @param retryPolicyDeclared 是否声明重试策略。
 * @param timeoutPolicyDeclared 是否声明超时策略。
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
        String transferChannel,
        String referenceRuntime,
        String syncScopeType,
        boolean singleObjectScope,
        boolean multiObjectScope,
        boolean customSqlScope,
        int selectedObjectCount,
        boolean objectMappingDeclared,
        boolean customSqlDeclared,
        boolean requiresApproval,
        boolean executableByMinimalBridge,
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
