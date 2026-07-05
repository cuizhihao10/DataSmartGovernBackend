/**
 * @Author : Cui
 * @Date: 2026/07/05 23:50
 * @Description DataSmart Govern Backend - SyncTemplateExecutionPrecheckResponse.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller.dto;

import java.util.List;

/**
 * 同步模板执行前预检查响应。
 *
 * <p>预检查与预览不同：预览主要服务配置页面和 Agent 规划，允许“可先保存草稿但暂不能执行”的状态；
 * 预检查则服务真实执行入口，必须更保守地回答“现在能不能入队并触发 runner”。因此这里会把
 * 当前最小 runner 不支持的范围、checkpoint handoff 未完成、字段映射不可运行等问题标记为执行阻断。</p>
 *
 * <p>低敏原则：该响应只返回布尔事实、数量、状态和 issueCodes，不返回字段映射 JSON、对象映射 JSON、
 * 自定义 SQL、过滤条件、连接串、凭据、样本数据或 checkpoint 原始值。</p>
 *
 * @param templateId 模板 ID。
 * @param tenantId 租户 ID。
 * @param projectId 项目 ID。
 * @param workspaceId 工作空间 ID。
 * @param syncMode 同步模式。
 * @param syncScopeType 同步范围类型。
 * @param precheckStatus 预检查状态：READY_TO_EXECUTE、REQUIRES_APPROVAL、NOT_SUPPORTED_BY_CURRENT_RUNNER、BLOCKED。
 * @param canCreateTaskDraft 是否仍可创建任务草稿；高级范围可能可建草稿但不可执行。
 * @param canStartExecution 是否允许直接入队执行。
 * @param connectorFactsComplete 连接器低敏事实是否完整。
 * @param connectorCompatibilitySupported 连接器组合与同步模式是否通过能力矩阵。
 * @param scopeContractValid 范围配置是否通过安全和必填契约。
 * @param fieldMappingDeclared 是否声明字段映射。
 * @param fieldMappingRunnableByMinimalBridge 字段映射是否能被当前最小 JDBC bridge 直接执行。
 * @param objectMappingDeclared 是否声明对象映射配置。
 * @param customSqlDeclared 是否声明自定义 SQL 配置。
 * @param customSqlSafetyPassed 自定义 SQL 场景下是否通过只读安全校验；非自定义 SQL 时为 true。
 * @param approvalRequired 是否需要审批或人工确认。
 * @param executableByCurrentRunner 当前 runner 是否具备执行该模板所需能力。
 * @param checkpointRequired 能力矩阵是否要求 checkpoint。
 * @param checkpointHandoffSupported 当前 data-sync -> datasource-management 桥是否支持该 checkpoint handoff。
 * @param issueCodes 低敏问题码。
 * @param recommendedActions 推荐动作。
 * @param performanceNotes 性能提示。
 * @param safetyNotes 安全治理提示。
 * @param payloadPolicy 低敏载荷策略说明。
 */
public record SyncTemplateExecutionPrecheckResponse(
        Long templateId,
        Long tenantId,
        Long projectId,
        Long workspaceId,
        String syncMode,
        String transferChannel,
        String referenceRuntime,
        String syncScopeType,
        String precheckStatus,
        boolean canCreateTaskDraft,
        boolean canStartExecution,
        boolean connectorFactsComplete,
        boolean connectorCompatibilitySupported,
        boolean scopeContractValid,
        boolean fieldMappingDeclared,
        boolean fieldMappingRunnableByMinimalBridge,
        boolean objectMappingDeclared,
        boolean customSqlDeclared,
        boolean customSqlSafetyPassed,
        boolean approvalRequired,
        boolean executableByCurrentRunner,
        boolean checkpointRequired,
        boolean checkpointHandoffSupported,
        List<String> issueCodes,
        List<String> recommendedActions,
        List<String> performanceNotes,
        List<String> safetyNotes,
        String payloadPolicy
) {
}
