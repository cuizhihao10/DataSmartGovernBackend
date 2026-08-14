package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.datasync.controller.dto.SyncExecutionDiagnosisResponse;
import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import com.czh.datasmart.govern.datasync.entity.SyncExecution;
import com.czh.datasmart.govern.datasync.entity.SyncExecutionPolicySnapshot;
import com.czh.datasmart.govern.datasync.entity.SyncObjectExecution;
import com.czh.datasmart.govern.datasync.entity.SyncTask;
import com.czh.datasmart.govern.datasync.entity.SyncTaskDefinition;
import com.czh.datasmart.govern.datasync.mapper.SyncErrorSampleMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncExecutionMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncExecutionPolicySnapshotMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncIncidentRecordMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncObjectExecutionMapper;
import com.czh.datasmart.govern.datasync.integration.datasource.DatasourceCapabilitySnapshotClient;
import com.czh.datasmart.govern.datasync.integration.datasource.DatasourceCapabilitySnapshotView;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 同步失败确定性诊断测试。
 *
 * <p>测试重点不是错误文案，而是数据库约束错误能否稳定进入正确治理分支，避免把结构性问题当成瞬态故障盲目重试。</p>
 */
class SyncAgentExecutionDiagnosisSupportTest {

    /** 字段不存在时应先尝试元数据证明型映射修复，再考虑需要 DDL 的人工预览。 */
    @Test
    void schemaMismatchShouldRecommendGovernedFieldMappingRepair() {
        SyncExecutionDiagnosisResponse response = diagnose("42703", "COLUMN_NOT_FOUND");

        assertThat(response.rootCauseCodes()).contains("SCHEMA_COLUMN_MISMATCH");
        assertThat(response.recommendedRepairActions())
                .startsWith("RETRY_FAILED_OBJECTS_AFTER_ROOT_CAUSE_FIXED", "REPAIR_FIELD_MAPPING")
                .contains("PREVIEW_TARGET_ADD_NULLABLE_COLUMN_OR_REPAIR_FIELD_MAPPING");
    }

    /** NOT NULL 错误允许尝试安全映射/默认值省略，但不能直接建议删除约束。 */
    @Test
    void notNullViolationShouldTrySafeMappingBeforePrivilegedSchemaChange() {
        SyncExecutionDiagnosisResponse response = diagnose("23502", "NOT_NULL_VIOLATION");

        assertThat(response.rootCauseCodes()).contains("TARGET_NOT_NULL_VIOLATION");
        assertThat(response.recommendedRepairActions())
                .contains("REPAIR_FIELD_MAPPING", "PREVIEW_TARGET_DROP_NOT_NULL_OR_FIX_SOURCE_VALUE");
    }

    /** 外键错误必须单独分类，并退出到父子依赖或源引用治理，不得伪装成普通 retry。 */
    @Test
    void foreignKeyViolationShouldRequireDependencyOrSourceReferenceReview() {
        SyncExecutionDiagnosisResponse response = diagnose("23503", "FOREIGN_KEY_VIOLATION");

        assertThat(response.rootCauseCodes()).contains("TARGET_FOREIGN_KEY_VIOLATION");
        assertThat(response.recommendedRepairActions())
                .contains("REVIEW_PARENT_DEPENDENCY_OR_REPAIR_SOURCE_REFERENCE")
                .doesNotContain("REPAIR_FIELD_MAPPING");
    }

    /** 诊断包必须把上次成功策略差异、连接器版本/限界和统一证据元数据交给 Recovery。 */
    @Test
    void diagnosisShouldExposePolicyConnectorAndEvidenceFacts() {
        SyncExecutionDiagnosisResponse response = diagnose("42703", "COLUMN_NOT_FOUND");

        assertThat(response.runtimeMetrics().failedObjectCount()).isEqualTo(1);
        assertThat(response.executionPolicyComparison().comparisonStatus()).isEqualTo("COMPARISON_AVAILABLE");
        assertThat(response.executionPolicyComparison().changedFields()).contains("resolvedChannel");
        assertThat(response.connectorRuntimeSummaries()).hasSize(2).allSatisfy(summary -> {
            assertThat(summary.connectorRuntimeVersion()).isEqualTo("42.7.12");
            assertThat(summary.runtimeLimitStatus()).isEqualTo("EXECUTION_POLICY_SNAPSHOT_AVAILABLE");
            assertThat(summary.capacityStatus()).isEqualTo("POLICY_GOVERNED_NO_HARD_CONNECTOR_CAPACITY_DECLARED");
        });
        assertThat(response.evidenceRecords()).isNotEmpty().allSatisfy(evidence -> {
            assertThat(evidence.sourceRef()).isNotBlank();
            assertThat(evidence.retrievedAt()).isNotBlank();
            assertThat(evidence.confidence()).isBetween(0.0d, 1.0d);
            assertThat(evidence.confidenceBasis()).isNotBlank();
        });
    }

    private SyncExecutionDiagnosisResponse diagnose(String errorCode, String errorType) {
        SyncExecutionMapper executionMapper = mock(SyncExecutionMapper.class);
        SyncObjectExecutionMapper objectMapper = mock(SyncObjectExecutionMapper.class);
        SyncErrorSampleMapper sampleMapper = mock(SyncErrorSampleMapper.class);
        SyncIncidentRecordMapper incidentMapper = mock(SyncIncidentRecordMapper.class);
        SyncExecutionPolicySnapshotMapper policyMapper = mock(SyncExecutionPolicySnapshotMapper.class);
        DatasourceCapabilitySnapshotClient capabilityClient = mock(DatasourceCapabilitySnapshotClient.class);
        SyncExecution execution = execution();
        when(executionMapper.selectById(88L)).thenReturn(execution);
        when(executionMapper.selectOne(any())).thenReturn(successfulExecution());
        when(objectMapper.selectByExecutionId(88L)).thenReturn(List.of(failedObject(errorCode, errorType)));
        when(sampleMapper.selectList(any())).thenReturn(List.of());
        when(incidentMapper.selectList(any())).thenReturn(List.of());
        when(policyMapper.selectOne(any())).thenReturn(currentPolicy(), previousPolicy());
        when(capabilityClient.getSnapshot(any(), any())).thenReturn(capabilitySnapshot());
        SyncAgentExecutionDiagnosisSupport support = new SyncAgentExecutionDiagnosisSupport(
                executionMapper, objectMapper, sampleMapper, incidentMapper, policyMapper, capabilityClient);
        return support.diagnose(task(), definition(), 88L,
                new SyncActorContext(7L, 99L, "SERVICE_ACCOUNT", "trace-diagnosis"));
    }

    private SyncTask task() {
        SyncTask task = new SyncTask();
        task.setId(11L);
        task.setTenantId(7L);
        task.setProjectId(101L);
        task.setLastExecutionId(88L);
        task.setCurrentState("FAILED");
        return task;
    }

    private SyncTaskDefinition definition() {
        SyncTaskDefinition definition = new SyncTaskDefinition();
        definition.setSyncMode("FULL");
        definition.setWriteStrategy("MERGE");
        definition.setSourceConnectorType("MYSQL");
        definition.setTargetConnectorType("POSTGRESQL");
        definition.setSourceDatasourceId(10001L);
        definition.setTargetDatasourceId(10002L);
        return definition;
    }

    private SyncExecution execution() {
        SyncExecution execution = new SyncExecution();
        execution.setId(88L);
        execution.setSyncTaskId(11L);
        execution.setExecutionState("FAILED");
        execution.setFailedRecordCount(1L);
        return execution;
    }

    private SyncExecution successfulExecution() {
        SyncExecution execution = new SyncExecution();
        execution.setId(77L);
        execution.setSyncTaskId(11L);
        execution.setExecutionState("SUCCEEDED");
        return execution;
    }

    private SyncExecutionPolicySnapshot currentPolicy() {
        SyncExecutionPolicySnapshot snapshot = policy(88L);
        snapshot.setResolvedChannel(2);
        snapshot.setTimeoutSeconds(120);
        return snapshot;
    }

    private SyncExecutionPolicySnapshot previousPolicy() {
        SyncExecutionPolicySnapshot snapshot = policy(77L);
        snapshot.setResolvedChannel(4);
        snapshot.setTimeoutSeconds(60);
        return snapshot;
    }

    private SyncExecutionPolicySnapshot policy(Long executionId) {
        SyncExecutionPolicySnapshot snapshot = new SyncExecutionPolicySnapshot();
        snapshot.setExecutionId(executionId);
        snapshot.setSyncTaskId(11L);
        snapshot.setReadBatchSize(100);
        snapshot.setWriteBatchSize(100);
        snapshot.setUpdateTime(LocalDateTime.now().minusMinutes(1));
        return snapshot;
    }

    private DatasourceCapabilitySnapshotView capabilitySnapshot() {
        DatasourceCapabilitySnapshotView snapshot = new DatasourceCapabilitySnapshotView();
        snapshot.setSnapshotVersion("datasmart.datasource.capability-snapshot.v1");
        snapshot.setConnectorRuntimeVersion("42.7.12");
        snapshot.setConnectorRuntimeVersionSource("PACKAGE_IMPLEMENTATION_VERSION");
        snapshot.setConnectorType("POSTGRESQL");
        snapshot.setConnectorFamily("RELATIONAL_JDBC");
        snapshot.setImplementationStage("PRODUCTION_READY");
        snapshot.setHealthStatus("CONNECTION_VERIFIED");
        snapshot.setCanRead(true);
        snapshot.setCanWrite(true);
        snapshot.setSupportsSchemaDiscovery(true);
        snapshot.setSupportsFieldMapping(true);
        snapshot.setSupportsCheckpointResume(true);
        snapshot.setSupportsPartitionParallelism(true);
        snapshot.setIssueCodes(List.of());
        snapshot.setGeneratedAt(LocalDateTime.now().minusMinutes(1));
        return snapshot;
    }

    private SyncObjectExecution failedObject(String errorCode, String errorType) {
        SyncObjectExecution object = new SyncObjectExecution();
        object.setId(501L);
        object.setObjectState("FAILED");
        object.setLastErrorCode(errorCode);
        object.setLastErrorType(errorType);
        object.setLastErrorMessage("目标约束校验失败");
        return object;
    }
}
