/**
 * @Author : Cui
 * @Date: 2026/06/29 03:18
 * @Description DataSmart Govern Backend - SyncWorkerExecutionPlanView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 同步执行器工作计划视图。
 *
 * <p>该 DTO 是 worker claim 后看到的低敏执行计划摘要。它不是内部 SQL 计划，不包含字段映射原文、
 * 对象映射原文、自定义 SQL、过滤条件、连接串、账号、密码、样本数据或 checkpoint 原始值。</p>
 *
 * <p>worker 只能根据这里的状态决定“继续执行、fail、等待修复”，不能自行猜测缺失配置。
 * 如果 planStatus 为 BLOCKED，worker 必须停止读写，并调用 fail/defer 类回调。</p>
 */
public record SyncWorkerExecutionPlanView(
        boolean available,
        String planStatus,
        Long tenantId,
        Long projectId,
        Long workspaceId,
        Long syncTaskId,
        Long executionId,
        Long executionNo,
        String executionState,
        String triggerType,
        String executorId,
        LocalDateTime leaseExpireTime,
        Long templateId,
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
        boolean requiresApproval,
        boolean executableByMinimalBridge,
        boolean sourceObjectDeclared,
        boolean targetObjectDeclared,
        String writeStrategy,
        boolean writeStrategyRequiresConflictKey,
        boolean primaryKeyDeclared,
        boolean incrementalFieldDeclared,
        boolean connectorCompatibilitySupported,
        String consistencyGoal,
        boolean checkpointRequired,
        String retryPattern,
        boolean fieldMappingDeclared,
        boolean objectMappingDeclared,
        boolean customSqlDeclared,
        boolean filterDeclared,
        boolean partitionDeclared,
        boolean retryPolicyDeclared,
        boolean timeoutPolicyDeclared,
        List<String> issueCodes,
        List<String> workerActions,
        List<String> performanceNotes,
        List<String> safetyNotes,
        String payloadPolicy
) {
}
