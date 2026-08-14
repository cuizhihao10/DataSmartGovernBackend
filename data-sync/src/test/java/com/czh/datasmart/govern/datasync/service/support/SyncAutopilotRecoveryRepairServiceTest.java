/**
 * @Author : Cui
 * @Date: 2026/08/14
 * @Description DataSmart Govern Backend - SyncAutopilotRecoveryRepairServiceTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.czh.datasmart.govern.common.context.PlatformAuthorizedProjectRole;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import com.czh.datasmart.govern.datasync.controller.dto.SyncObjectRetryResult;
import com.czh.datasmart.govern.datasync.entity.SyncAutopilotRecoveryCase;
import com.czh.datasmart.govern.datasync.entity.SyncExecution;
import com.czh.datasmart.govern.datasync.entity.SyncTask;
import com.czh.datasmart.govern.datasync.entity.SyncTaskDefinition;
import com.czh.datasmart.govern.datasync.mapper.SyncAutopilotRecoveryCaseMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncCheckpointMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncExecutionMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncExecutionPolicyMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncExecutionPolicySnapshotMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncObjectExecutionMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncTaskDefinitionMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncTaskMapper;
import com.czh.datasmart.govern.datasync.support.SyncAutopilotExecutionMode;
import com.czh.datasmart.govern.datasync.support.SyncAutopilotRecoveryAction;
import com.czh.datasmart.govern.datasync.support.SyncAutopilotRiskLevel;
import com.czh.datasmart.govern.datasync.support.SyncAuditActionType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 验证受治理修复只有在持久授权、元数据和预检同时成立时才产生副作用。 */
class SyncAutopilotRecoveryRepairServiceTest {

    private static final String POLICY = """
            {
              "policyId":"policy-1","tenantId":10,"projectId":20,"taskId":31,
              "state":"ACTIVE","userId":"501","actorId":"501",
              "agentId":"RECOVERY_AGENT","delegationId":"delegation-1",
              "expiresAt":"2099-01-01T00:00:00Z","maxRecoveryCycles":5,
              "maxTotalDurationMinutes":120,"maxAutomaticRiskLevel":"LOW",
              "allowedRecoveryActions":["REPAIR_FIELD_MAPPING"],
              "requireApprovalFor":["CHANGE_SCHEMA","CHANGE_CREDENTIAL"]
            }
            """;
    private static final String ERROR_FINGERPRINT = "a".repeat(64);
    private static final Map<String, Object> PARAMETERS = Map.of(
            "repairMode", "METADATA_PROVEN_SAFE");
    private static final String ACTION_FINGERPRINT = SyncAutopilotDigestSupport.sha256(String.join("|",
            "event-1", ERROR_FINGERPRINT, "41", "REPAIR_FIELD_MAPPING",
            "repairMode=METADATA_PROVEN_SAFE"));
    private static final String AUTHORIZATION_DIGEST = SyncAutopilotDigestSupport.sha256("policy-1");

    /** 元数据修复和完整预检都通过后，才保存新映射并重排失败对象。 */
    @Test
    void shouldPersistMetadataProvenFieldMappingAndRequeueFailedObjects() {
        Fixture fixture = fixture();
        String repaired = "[{\"sourceField\":\"ID\",\"targetField\":\"ID\",\"syncEnabled\":true}]";
        when(fixture.metadataPrecheckSupport().repairFieldMappings(any(), any())).thenReturn(
                new SyncTaskDefinitionMetadataAwarePrecheckSupport.MetadataFieldMappingRepairResult(
                        repaired, 1, List.of()));
        when(fixture.metadataPrecheckSupport().evaluate(any(), any(), eq(true))).thenReturn(
                new SyncTaskDefinitionMetadataAwarePrecheckSupport.MetadataAwarePrecheckResult(
                        List.of(), List.of(), List.of()));
        when(fixture.definitionMapper().update(any(), any(UpdateWrapper.class))).thenReturn(1);
        when(fixture.objectRetrySupport().retryFailedObjects(any(), any(), any(), any())).thenReturn(
                new SyncObjectRetryResult(31L, 41L, 1, "QUEUED", "RETRYING", List.of(), "queued"));

        SyncAutopilotRecoveryRepairReceiptView result = fixture.service().apply(command(), principal(), actor());

        assertThat(result.applied()).isTrue();
        assertThat(result.executionId()).isEqualTo(41L);
        assertThat(result.reasonCode()).isEqualTo("AUTOPILOT_GOVERNED_REPAIR_APPLIED_AND_REQUEUED");
        ArgumentCaptor<UpdateWrapper<SyncTaskDefinition>> updateCaptor =
                ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(fixture.definitionMapper()).update(isNull(), updateCaptor.capture());
        /*
         * data_sync_task_definition 的主键列名是 task_id，而实体属性为了和其它聚合统一仍叫 id。
         * 字符串 UpdateWrapper 不会读取 @TableId 做属性到列名转换，因此这里直接检查最终 SQL 片段，
         * 防止单元测试只 mock mapper 返回值、却把一个真实数据库必然拒绝的 WHERE id = ? 放过去。
         */
        assertThat(updateCaptor.getValue().getSqlSegment())
                .contains("task_id")
                .doesNotMatch("(?i)(^|.*\\W)id\\s*=.*");
        verify(fixture.definitionMapper(), never()).updateById(any(SyncTaskDefinition.class));
        verify(fixture.dataScopeSupport()).validateProjectManageable(
                eq(10L), eq(20L), isNull(), any(), eq("Autopilot 受治理恢复动作"));
        verify(fixture.idempotencySupport()).markSucceeded(
                eq(10L), eq("AUTOPILOT_RECOVERY_REPAIR"), eq("case:81"),
                eq("event-1:repair-apply"), any());
        verify(fixture.auditSupport()).saveAudit(
                eq(10L), eq(31L), eq(41L), eq(SyncAuditActionType.AUTOPILOT_GOVERNED_REPAIR),
                any(), org.mockito.ArgumentMatchers.contains("action=REPAIR_FIELD_MAPPING"));
    }

    /** 元数据无法给出唯一安全修复时，服务返回未应用结论且不更新定义、不重试。 */
    @Test
    void shouldNotMutateDefinitionWhenFieldMappingRepairIsNotDeterministic() {
        Fixture fixture = fixture();
        when(fixture.metadataPrecheckSupport().repairFieldMappings(any(), any())).thenReturn(
                new SyncTaskDefinitionMetadataAwarePrecheckSupport.MetadataFieldMappingRepairResult(
                        "[]", 0, List.of("AUTOPILOT_SOURCE_FIELD_REPAIR_NOT_DETERMINISTIC")));

        SyncAutopilotRecoveryRepairReceiptView result = fixture.service().apply(command(), principal(), actor());

        assertThat(result.applied()).isFalse();
        assertThat(result.reasonCode()).isEqualTo("AUTOPILOT_FIELD_MAPPING_REPAIR_NOT_DETERMINISTIC");
        assertThat(result.issueCodes()).contains("AUTOPILOT_SOURCE_FIELD_REPAIR_NOT_DETERMINISTIC");
        assertThat(result.caseState()).isEqualTo("ATTENTION_REQUIRED");
        assertThat(result.replanQueued()).isTrue();
        assertThat(result.replanEventId()).isEqualTo("autopilot-trigger:next");
        assertThat(result.nextCycle()).isEqualTo(2);
        verify(fixture.triggerPublisher()).publishRepairNotApplied(
                any(), any(), any(),
                eq("AUTOPILOT_FIELD_MAPPING_REPAIR_NOT_DETERMINISTIC"),
                eq(List.of("AUTOPILOT_SOURCE_FIELD_REPAIR_NOT_DETERMINISTIC")));
        verify(fixture.definitionMapper(), never()).update(any(), any());
        verify(fixture.objectRetrySupport(), never()).retryFailedObjects(any(), any(), any(), any());
    }

    /** 授权主体与 Header 主体不一致时，即使内部令牌链路可信也不能产生任何修复副作用。 */
    @Test
    void shouldRejectRepairWhenDualPrincipalDoesNotMatchPersistedAuthorization() {
        Fixture fixture = fixture();
        SyncAutopilotRecoveryPrincipalContext forged = new SyncAutopilotRecoveryPrincipalContext(
                "501", "PROJECT_OWNER", "RECOVERY_AGENT", "delegation-forged", "trace-1");

        assertThatThrownBy(() -> fixture.service().apply(command(), forged, actor()))
                .isInstanceOf(PlatformBusinessException.class)
                .hasMessageContaining("principal");

        verify(fixture.definitionMapper(), never()).update(any(), any());
        verify(fixture.objectRetrySupport(), never()).retryFailedObjects(any(), any(), any(), any());
    }

    /** 并发修改字段映射或撤销授权时，条件更新必须失败并回滚，不能用旧整行覆盖管理员的新事实。 */
    @Test
    void shouldRejectConcurrentDefinitionChangeBeforeRequeue() {
        Fixture fixture = fixture();
        when(fixture.metadataPrecheckSupport().repairFieldMappings(any(), any())).thenReturn(
                new SyncTaskDefinitionMetadataAwarePrecheckSupport.MetadataFieldMappingRepairResult(
                        "[{\"sourceField\":\"ID\",\"targetField\":\"ID\"}]", 1, List.of()));
        when(fixture.metadataPrecheckSupport().evaluate(any(), any(), eq(true))).thenReturn(
                new SyncTaskDefinitionMetadataAwarePrecheckSupport.MetadataAwarePrecheckResult(
                        List.of(), List.of(), List.of()));
        when(fixture.definitionMapper().update(any(), any(UpdateWrapper.class))).thenReturn(0);

        assertThatThrownBy(() -> fixture.service().apply(command(), principal(), actor()))
                .isInstanceOf(PlatformBusinessException.class)
                .hasMessageContaining("could not be persisted");

        verify(fixture.objectRetrySupport(), never()).retryFailedObjects(any(), any(), any(), any());
    }

    /** 创建满足三层授权和资源范围要求的服务夹具。 */
    private Fixture fixture() {
        SyncAutopilotRecoveryCaseMapper caseMapper = mock(SyncAutopilotRecoveryCaseMapper.class);
        SyncTaskMapper taskMapper = mock(SyncTaskMapper.class);
        SyncTaskDefinitionMapper definitionMapper = mock(SyncTaskDefinitionMapper.class);
        SyncExecutionMapper executionMapper = mock(SyncExecutionMapper.class);
        SyncObjectExecutionMapper objectMapper = mock(SyncObjectExecutionMapper.class);
        SyncCheckpointMapper checkpointMapper = mock(SyncCheckpointMapper.class);
        SyncExecutionPolicyMapper policyMapper = mock(SyncExecutionPolicyMapper.class);
        SyncExecutionPolicySnapshotMapper snapshotMapper = mock(SyncExecutionPolicySnapshotMapper.class);
        SyncCallbackIdempotencySupport idempotencySupport = mock(SyncCallbackIdempotencySupport.class);
        SyncObjectExecutionOperationSupport objectRetrySupport = mock(SyncObjectExecutionOperationSupport.class);
        SyncTaskRecoveryOperationSupport recoveryOperationSupport = mock(SyncTaskRecoveryOperationSupport.class);
        SyncTaskDefinitionMetadataAwarePrecheckSupport metadataPrecheckSupport =
                mock(SyncTaskDefinitionMetadataAwarePrecheckSupport.class);
        SyncAuditSupport auditSupport = mock(SyncAuditSupport.class);
        SyncAutopilotRecoveryTriggerPublisher triggerPublisher =
                mock(SyncAutopilotRecoveryTriggerPublisher.class);
        when(triggerPublisher.publishRepairNotApplied(any(), any(), any(), any(), any())).thenReturn(
                new SyncAutopilotRecoveryRepairReplanResult(
                        true, "autopilot-trigger:next", 2));
        SyncAutopilotRecoveryPolicyEvaluator evaluator = new SyncAutopilotRecoveryPolicyEvaluator();
        SyncDataScopeSupport dataScopeSupport = mock(SyncDataScopeSupport.class);

        LocalDateTime deadline = LocalDateTime.now(ZoneOffset.UTC).plusMinutes(30);
        SyncAutopilotRecoveryPolicyDecision policyDecision = evaluator.evaluate(
                POLICY,
                new SyncAutopilotRecoveryEvaluationRequest(
                        SyncAutopilotExecutionMode.AUTOPILOT, 10L, 20L, 31L, 1, deadline,
                        ERROR_FINGERPRINT, 0, SyncAutopilotRecoveryAction.REPAIR_FIELD_MAPPING,
                        SyncAutopilotRiskLevel.LOW, ACTION_FINGERPRINT, "event-1:repair-apply",
                        100, true, false, LocalDateTime.now(ZoneOffset.UTC)));
        SyncAutopilotRecoveryCase recoveryCase = new SyncAutopilotRecoveryCase();
        recoveryCase.setCaseId(81L);
        recoveryCase.setTenantId(10L);
        recoveryCase.setProjectId(20L);
        recoveryCase.setSyncTaskId(31L);
        recoveryCase.setRootExecutionId(40L);
        recoveryCase.setCurrentExecutionId(41L);
        recoveryCase.setAuthorizationDigest(AUTHORIZATION_DIGEST);
        recoveryCase.setPolicyDigest(policyDecision.policyDigest());
        recoveryCase.setCaseState("AUTO_APPROVED");
        recoveryCase.setCycle(1);
        recoveryCase.setMaxCycles(5);
        recoveryCase.setDeadlineAt(deadline);
        recoveryCase.setLastErrorFingerprint(ERROR_FINGERPRINT);
        recoveryCase.setRepeatedErrorCount(0);
        recoveryCase.setRecoveryAction("REPAIR_FIELD_MAPPING");
        recoveryCase.setRiskLevel("LOW");
        recoveryCase.setRepairFingerprint(ACTION_FINGERPRINT);
        recoveryCase.setVersion(0L);
        when(caseMapper.selectByCaseId(81L)).thenReturn(recoveryCase);

        SyncTask task = new SyncTask();
        task.setId(31L);
        task.setTenantId(10L);
        task.setProjectId(20L);
        when(taskMapper.selectById(31L)).thenReturn(task);
        SyncTaskDefinition definition = new SyncTaskDefinition();
        definition.setId(31L);
        definition.setTenantId(10L);
        definition.setProjectId(20L);
        definition.setAutopilotPolicy(POLICY);
        definition.setFieldMappingConfig("[]");
        when(definitionMapper.selectById(31L)).thenReturn(definition);
        SyncExecution execution = new SyncExecution();
        execution.setId(41L);
        execution.setTenantId(10L);
        execution.setProjectId(20L);
        execution.setSyncTaskId(31L);
        execution.setExecutionState("FAILED");
        when(executionMapper.selectById(41L)).thenReturn(execution);

        SyncAutopilotRecoveryRepairService service = new SyncAutopilotRecoveryRepairService(
                caseMapper, taskMapper, definitionMapper, executionMapper, objectMapper, checkpointMapper,
                policyMapper, snapshotMapper, evaluator, dataScopeSupport, idempotencySupport, objectRetrySupport,
                recoveryOperationSupport, metadataPrecheckSupport, auditSupport, triggerPublisher,
                new ObjectMapper());
        return new Fixture(service, definitionMapper, idempotencySupport,
                objectRetrySupport, metadataPrecheckSupport, auditSupport, dataScopeSupport, triggerPublisher,
                policyDecision.policyDigest());
    }

    private SyncAutopilotRecoveryRepairCommand command() {
        return new SyncAutopilotRecoveryRepairCommand(
                81L, 0L, 10L, 20L, 31L, 41L, 1,
                AUTHORIZATION_DIGEST, currentPolicyDigest(), SyncAutopilotRecoveryAction.REPAIR_FIELD_MAPPING,
                ACTION_FINGERPRINT, "event-1:repair-apply", PARAMETERS);
    }

    private String currentPolicyDigest() {
        return new SyncAutopilotRecoveryPolicyEvaluator().evaluate(
                POLICY,
                new SyncAutopilotRecoveryEvaluationRequest(
                        SyncAutopilotExecutionMode.AUTOPILOT, 10L, 20L, 31L, 1,
                        LocalDateTime.now(ZoneOffset.UTC).plusMinutes(30), ERROR_FINGERPRINT, 0,
                        SyncAutopilotRecoveryAction.REPAIR_FIELD_MAPPING, SyncAutopilotRiskLevel.LOW,
                        ACTION_FINGERPRINT, "event-1:repair-apply", 100, true, false,
                        LocalDateTime.now(ZoneOffset.UTC))).policyDigest();
    }

    private SyncAutopilotRecoveryPrincipalContext principal() {
        return new SyncAutopilotRecoveryPrincipalContext(
                "501", "PROJECT_OWNER", "RECOVERY_AGENT", "delegation-1", "trace-1");
    }

    /** 构造与 Agent Runtime 可信 Header 等价的项目管理上下文。 */
    private SyncActorContext actor() {
        return new SyncActorContext(
                10L, 20L, null, 501L, "PROJECT_OWNER", "trace-1",
                "PROJECT", null, List.of(20L),
                List.of(new PlatformAuthorizedProjectRole(20L, "OWNER")), false, null);
    }

    private record Fixture(
            SyncAutopilotRecoveryRepairService service,
            SyncTaskDefinitionMapper definitionMapper,
            SyncCallbackIdempotencySupport idempotencySupport,
            SyncObjectExecutionOperationSupport objectRetrySupport,
            SyncTaskDefinitionMetadataAwarePrecheckSupport metadataPrecheckSupport,
            SyncAuditSupport auditSupport,
            SyncDataScopeSupport dataScopeSupport,
            SyncAutopilotRecoveryTriggerPublisher triggerPublisher,
            String policyDigest) {
    }
}
