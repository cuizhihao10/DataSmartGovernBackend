/**
 * @Author : Cui
 * @Date: 2026/07/28 02:38
 * @Description DataSmart Govern Backend - SyncTaskExecutionPrecheckResponse.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller.dto;

import java.util.List;

/**
 * User-facing execution precheck result addressed by the synchronization task ID.
 *
 * <p>The one-to-one sync definition remains an internal persisted task definition.
 * Product clients and Agent tools should not need its identifier, nor the retired
 * workspace compatibility column, to inspect whether a task can run.</p>
 */
public record SyncTaskExecutionPrecheckResponse(
        Long taskId,
        Long tenantId,
        Long projectId,
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

    /**
     * Projects the internal definition-oriented precheck into the stable task contract.
     */
    public static SyncTaskExecutionPrecheckResponse from(
            Long taskId,
            SyncTaskDefinitionExecutionPrecheckResponse source) {
        return new SyncTaskExecutionPrecheckResponse(
                taskId,
                source.tenantId(),
                source.projectId(),
                source.syncMode(),
                source.transferChannel(),
                source.referenceRuntime(),
                source.syncScopeType(),
                source.precheckStatus(),
                source.canCreateTaskDraft(),
                source.canStartExecution(),
                source.connectorFactsComplete(),
                source.connectorCompatibilitySupported(),
                source.scopeContractValid(),
                source.fieldMappingDeclared(),
                source.fieldMappingRunnableByMinimalBridge(),
                source.objectMappingDeclared(),
                source.customSqlDeclared(),
                source.customSqlSafetyPassed(),
                source.approvalRequired(),
                source.executableByCurrentRunner(),
                source.checkpointRequired(),
                source.checkpointHandoffSupported(),
                source.issueCodes(),
                source.recommendedActions(),
                source.performanceNotes(),
                source.safetyNotes(),
                source.payloadPolicy());
    }
}
