/**
 * @Author : Cui
 * @Date: 2026/06/29 13:20
 * @Description DataSmart Govern Backend - SyncBatchRunOnceDispatchServiceTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.datasync.config.DataSyncDatasourceRunOnceProperties;
import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExecutionCheckpointRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExecutionCompleteRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExecutionFailRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncRecoveryPlanWorkerResult;
import com.czh.datasmart.govern.datasync.controller.dto.SyncWorkerExecutionPlanView;
import com.czh.datasmart.govern.datasync.entity.SyncCheckpoint;
import com.czh.datasmart.govern.datasync.entity.SyncExecution;
import com.czh.datasmart.govern.datasync.entity.SyncTask;
import com.czh.datasmart.govern.datasync.entity.SyncTaskDefinition;
import com.czh.datasmart.govern.datasync.integration.datasource.runonce.DatasourceRunOnceClient;
import com.czh.datasmart.govern.datasync.integration.datasource.runonce.DatasourceRunOnceRequest;
import com.czh.datasmart.govern.datasync.integration.datasource.runonce.DatasourceRunOnceResponse;
import com.czh.datasmart.govern.datasync.mapper.SyncCheckpointMapper;
import com.czh.datasmart.govern.datasync.support.SyncExecutionState;
import com.czh.datasmart.govern.datasync.support.SyncTriggerType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * run-once 派发服务测试。
 *
 * <p>本测试关注 data-sync 控制面如何把 bridgePlan 派发给 datasource-management，并根据结果回写自身状态机。
 * 它不测试 JDBC reader/writer，因为真实读写已经由 datasource-management 的 run-once 服务负责；
 * 这里要证明的是：data-sync 不会越权拼 SQL、不泄露 checkpoint，也不会在不完整能力下把 execution 卡在 RUNNING。</p>
 */
class SyncBatchRunOnceDispatchServiceTest {

    /**
     * FULL 单批且源端已结束时，data-sync 应调用远端 run-once，然后回写 complete。
     */
    @Test
    void fullRunOnceShouldCallDatasourceAndCompleteExecutionWhenSourceExhausted() {
        FakeDatasourceRunOnceClient client = new FakeDatasourceRunOnceClient(completeResponse());
        SyncExecutionLifecycleSupport lifecycleSupport = mock(SyncExecutionLifecycleSupport.class);
        DataSyncTaskManagementReceiptPublisher receiptPublisher = mock(DataSyncTaskManagementReceiptPublisher.class);
        SyncBatchRunOnceDispatchService service = service(client, lifecycleSupport, properties(true), receiptPublisher);

        SyncExecution execution = execution("FULL");
        SyncBatchRunOnceDispatchResult result = service.dispatchRunOnce(execution, task(), definition("FULL", directMapping()),
                workerPlan("FULL", "READY_TO_RUN", List.of()), actor());

        assertThat(result.dispatched()).isTrue();
        assertThat(result.completed()).isTrue();
        assertThat(result.failed()).isFalse();
        assertThat(result.dispatchStatus()).isEqualTo("DISPATCHED_AND_COMPLETED");
        assertThat(client.calls()).isEqualTo(1);
        assertThat(client.capturedRequest().getCheckpointValue()).isNull();
        assertThat(client.capturedRequest().getSelectedColumns()).containsExactly("id", "name");
        assertThat(client.capturedRequest().getExecutionPlan().getReadPlan().getReadStrategy()).isEqualTo("FULL_OBJECT_SCAN");
        assertThat(client.capturedRequest().getExecutionPlan().getReadPlan().getFilterConditions()).isEmpty();
        assertThat(client.capturedRequest().getExecutionPlan().getCheckpointPlan().getCheckpointType())
                .isEqualTo("NONE_OR_FINAL_WATERMARK");

        ArgumentCaptor<SyncExecutionCompleteRequest> completeCaptor =
                ArgumentCaptor.forClass(SyncExecutionCompleteRequest.class);
        verify(lifecycleSupport).completeExecution(eq(task()), eq(execution), completeCaptor.capture(), any(SyncActorContext.class));
        assertThat(completeCaptor.getValue().getExecutorId()).isEqualTo("worker-1");
        assertThat(completeCaptor.getValue().getRecordsRead()).isEqualTo(12L);
        assertThat(completeCaptor.getValue().getRecordsWritten()).isEqualTo(10L);
        verify(receiptPublisher).publishComplete(eq(task()), eq(execution), any(SyncActorContext.class), any(DatasourceRunOnceResponse.class));
        verify(lifecycleSupport, never()).failExecution(any(), any(), any(), any());
    }

    /**
     * 增量模式需要 checkpoint 原始值安全交接；该机制尚未实现时，必须在本地阻断，不能调用真实读写。
     */
    @Test
    void incrementalModeShouldFailBeforeRemoteCallUntilCheckpointHandoffExists() {
        FakeDatasourceRunOnceClient client = new FakeDatasourceRunOnceClient(completeResponse());
        SyncExecutionLifecycleSupport lifecycleSupport = mock(SyncExecutionLifecycleSupport.class);
        DataSyncTaskManagementReceiptPublisher receiptPublisher = mock(DataSyncTaskManagementReceiptPublisher.class);
        SyncBatchRunOnceDispatchService service = service(client, lifecycleSupport, properties(true), receiptPublisher);
        SyncExecution execution = execution("INCREMENTAL_TIME");

        SyncBatchRunOnceDispatchResult result = service.dispatchRunOnce(execution, task(),
                definition("INCREMENTAL_TIME", directMapping()), workerPlan("INCREMENTAL_TIME", "READY_TO_RUN", List.of()), actor());

        assertThat(result.dispatched()).isFalse();
        assertThat(result.failed()).isTrue();
        assertThat(result.issueCodes()).contains("CHECKPOINT_HANDOFF_NOT_IMPLEMENTED");
        assertThat(client.calls()).isZero();
        assertFail(lifecycleSupport, execution, "CHECKPOINT_HANDOFF_NOT_IMPLEMENTED");
        verify(receiptPublisher).publishFailed(eq(task()), eq(execution), any(SyncActorContext.class),
                eq("CHECKPOINT_HANDOFF_NOT_IMPLEMENTED"), any());
    }

    /**
     * 恢复执行引用的持久 checkpoint 通过完整范围校验后，才允许进入内部 run-once 请求。
     *
     * <p>原值只存在于捕获的内部 DTO 中；对外结果和日志仍只暴露类型与恢复状态。</p>
     */
    @Test
    void checkpointReplayShouldHandoffPersistedValueAfterScopeValidation() {
        FakeDatasourceRunOnceClient client = new FakeDatasourceRunOnceClient(completeResponse());
        SyncExecutionLifecycleSupport lifecycleSupport = mock(SyncExecutionLifecycleSupport.class);
        SyncBatchRunOnceDispatchService service = service(client, lifecycleSupport, properties(true),
                mock(DataSyncTaskManagementReceiptPublisher.class));
        SyncCheckpointMapper checkpointMapper = mock(SyncCheckpointMapper.class);
        service.setCheckpointMapper(checkpointMapper);
        SyncExecution execution = execution("INCREMENTAL_ID");
        execution.setTriggerType(SyncTriggerType.REPLAY.name());
        SyncTask task = task();
        SyncTaskDefinition definition = definition("INCREMENTAL_ID", directMapping());
        SyncWorkerExecutionPlanView workerPlan = workerPlan("INCREMENTAL_ID", "READY_TO_RUN", List.of());
        SyncBatchRunnerBridgePlan bridgePlan = new SyncBatchRunnerBridgePlanSupport(
                new SyncFieldMappingExecutionContractSupport(new ObjectMapper()))
                .buildPlan(execution, task, definition, workerPlan);
        SyncCheckpoint checkpoint = checkpoint(9001L, 77L, "ID_FIELD", "4200");
        when(checkpointMapper.selectById(9001L)).thenReturn(checkpoint);

        SyncBatchRunOnceDispatchResult result = service.dispatchPreparedRunOnce(
                bridgePlan, execution, task, actor(), checkpointRecoveryPlan(9001L));

        assertThat(result.completed()).isTrue();
        assertThat(client.capturedRequest().getCheckpointValue()).isEqualTo("4200");
        assertThat(client.capturedRequest().getExecutionPlan().getCheckpointPlan().getCheckpointType())
                .isEqualTo("ID_FIELD");
        assertThat(client.capturedRequest().getExecutionPlan().getCheckpointPlan().getInitialCheckpointPolicy())
                .isEqualTo("RESUME_FROM_PERSISTED_CHECKPOINT");
        assertThat(client.capturedRequest().getExecutionPlan().getCheckpointPlan().getResumeRequired()).isTrue();
        assertThat(result.toString()).doesNotContain("4200");
    }

    /**
     * checkpoint 即使主键存在，只要来源 execution 不匹配也必须 fail-closed，防止跨执行窃取水位。
     */
    @Test
    void checkpointReplayShouldFailBeforeRemoteCallWhenCheckpointScopeDoesNotMatch() {
        FakeDatasourceRunOnceClient client = new FakeDatasourceRunOnceClient(completeResponse());
        SyncExecutionLifecycleSupport lifecycleSupport = mock(SyncExecutionLifecycleSupport.class);
        SyncBatchRunOnceDispatchService service = service(client, lifecycleSupport, properties(true),
                mock(DataSyncTaskManagementReceiptPublisher.class));
        SyncCheckpointMapper checkpointMapper = mock(SyncCheckpointMapper.class);
        service.setCheckpointMapper(checkpointMapper);
        SyncExecution execution = execution("INCREMENTAL_ID");
        execution.setTriggerType(SyncTriggerType.REPLAY.name());
        SyncTask task = task();
        SyncTaskDefinition definition = definition("INCREMENTAL_ID", directMapping());
        SyncBatchRunnerBridgePlan bridgePlan = new SyncBatchRunnerBridgePlanSupport(
                new SyncFieldMappingExecutionContractSupport(new ObjectMapper()))
                .buildPlan(execution, task, definition,
                        workerPlan("INCREMENTAL_ID", "READY_TO_RUN", List.of()));
        when(checkpointMapper.selectById(9001L)).thenReturn(checkpoint(9001L, 76L, "ID_FIELD", "4200"));

        SyncBatchRunOnceDispatchResult result = service.dispatchPreparedRunOnce(
                bridgePlan, execution, task, actor(), checkpointRecoveryPlan(9001L));

        assertThat(result.failed()).isTrue();
        assertThat(result.issueCodes()).contains("RECOVERY_CHECKPOINT_SCOPE_MISMATCH");
        assertThat(client.calls()).isZero();
        assertFail(lifecycleSupport, execution, "RECOVERY_CHECKPOINT_SCOPE_MISMATCH");
    }

    /**
     * 增量恢复的每一批都必须先保存新水位，再用它读取下一批。
     *
     * <p>本测试同时覆盖最终批：即使远端已经建议 complete，最终候选水位也要先写入 checkpoint，
     * 否则下一次周期执行仍可能从旧位置开始。</p>
     */
    @Test
    void checkpointRecoveryShouldPersistCandidateAndUseItForNextBatch() {
        DatasourceRunOnceResponse firstBatch = checkpointResponse(moreBatchesResponse(), "4300");
        DatasourceRunOnceResponse finalBatch = checkpointResponse(completeResponse(14L, 12L), "4400");
        FakeDatasourceRunOnceClient client = new FakeDatasourceRunOnceClient(firstBatch, finalBatch);
        SyncExecutionLifecycleSupport lifecycleSupport = mock(SyncExecutionLifecycleSupport.class);
        SyncBatchRunOnceDispatchService service = service(client, lifecycleSupport, properties(true),
                mock(DataSyncTaskManagementReceiptPublisher.class));
        SyncCheckpointMapper checkpointMapper = mock(SyncCheckpointMapper.class);
        service.setCheckpointMapper(checkpointMapper);
        SyncExecution execution = execution("INCREMENTAL_ID");
        execution.setTriggerType(SyncTriggerType.REPLAY.name());
        SyncTask task = task();
        SyncBatchRunnerBridgePlan bridgePlan = new SyncBatchRunnerBridgePlanSupport(
                new SyncFieldMappingExecutionContractSupport(new ObjectMapper()))
                .buildPlan(execution, task, definition("INCREMENTAL_ID", directMapping()),
                        workerPlan("INCREMENTAL_ID", "READY_TO_RUN", List.of()));
        when(checkpointMapper.selectById(9001L)).thenReturn(checkpoint(9001L, 77L, "ID_FIELD", "4200"));

        SyncBatchRunOnceDispatchResult result = service.dispatchPreparedRunOnce(
                bridgePlan, execution, task, actor(), checkpointRecoveryPlan(9001L));

        assertThat(result.completed()).isTrue();
        assertThat(client.calls()).isEqualTo(2);
        assertThat(client.checkpointValueSnapshots()).containsExactly("4200", "4300");
        ArgumentCaptor<SyncExecutionCheckpointRequest> checkpointCaptor =
                ArgumentCaptor.forClass(SyncExecutionCheckpointRequest.class);
        verify(lifecycleSupport, times(2)).writeCheckpoint(
                eq(task), eq(execution), checkpointCaptor.capture(), any(SyncActorContext.class));
        assertThat(checkpointCaptor.getAllValues())
                .extracting(SyncExecutionCheckpointRequest::getCheckpointValue)
                .containsExactly("4300", "4400");
        assertThat(checkpointCaptor.getAllValues())
                .extracting(SyncExecutionCheckpointRequest::getCheckpointType)
                .containsOnly("ID_FIELD");
        assertThat(checkpointCaptor.getAllValues())
                .extracting(SyncExecutionCheckpointRequest::getIdempotencyKey)
                .allSatisfy(key -> assertThat(key)
                        .startsWith("run-once-checkpoint:88:")
                        .doesNotContain("4300", "4400"));
        assertThat(result.toString()).doesNotContain("4200", "4300", "4400");
    }

    /** 远端只返回“产生了水位”却缺少原值时，必须在第二次远程调用前终止。 */
    @Test
    void checkpointRecoveryShouldFailClosedWhenCandidateValueIsMissing() {
        DatasourceRunOnceResponse incomplete = checkpointResponse(moreBatchesResponse(), null);
        FakeDatasourceRunOnceClient client = new FakeDatasourceRunOnceClient(incomplete, completeResponse());
        SyncExecutionLifecycleSupport lifecycleSupport = mock(SyncExecutionLifecycleSupport.class);
        SyncBatchRunOnceDispatchService service = service(client, lifecycleSupport, properties(true),
                mock(DataSyncTaskManagementReceiptPublisher.class));
        SyncCheckpointMapper checkpointMapper = mock(SyncCheckpointMapper.class);
        service.setCheckpointMapper(checkpointMapper);
        SyncExecution execution = execution("INCREMENTAL_ID");
        execution.setTriggerType(SyncTriggerType.REPLAY.name());
        SyncTask task = task();
        SyncBatchRunnerBridgePlan bridgePlan = new SyncBatchRunnerBridgePlanSupport(
                new SyncFieldMappingExecutionContractSupport(new ObjectMapper()))
                .buildPlan(execution, task, definition("INCREMENTAL_ID", directMapping()),
                        workerPlan("INCREMENTAL_ID", "READY_TO_RUN", List.of()));
        when(checkpointMapper.selectById(9001L)).thenReturn(checkpoint(9001L, 77L, "ID_FIELD", "4200"));

        SyncBatchRunOnceDispatchResult result = service.dispatchPreparedRunOnce(
                bridgePlan, execution, task, actor(), checkpointRecoveryPlan(9001L));

        assertThat(result.failed()).isTrue();
        assertThat(client.calls()).isEqualTo(1);
        verify(lifecycleSupport, never()).writeCheckpoint(any(), any(), any(), any());
        assertFail(lifecycleSupport, execution, "CHECKPOINT_VALUE_NOT_RETURNED");
    }

    /**
     * 字段重命名需要 transform 层；当前最小 JDBC bridge 不能把 sourceField 改名为 targetField 后直接写入。
     */
    @Test
    void fieldRenameShouldBePassedToDatasourceRuntimeForRowKeyAlignment() {
        FakeDatasourceRunOnceClient client = new FakeDatasourceRunOnceClient(completeResponse());
        SyncExecutionLifecycleSupport lifecycleSupport = mock(SyncExecutionLifecycleSupport.class);
        DataSyncTaskManagementReceiptPublisher receiptPublisher = mock(DataSyncTaskManagementReceiptPublisher.class);
        SyncBatchRunOnceDispatchService service = service(client, lifecycleSupport, properties(true), receiptPublisher);
        SyncExecution execution = execution("FULL");

        SyncBatchRunOnceDispatchResult result = service.dispatchRunOnce(execution, task(),
                definition("FULL", renameMapping()), workerPlan("FULL", "READY_TO_RUN", List.of()), actor());

        assertThat(result.dispatched()).isTrue();
        assertThat(result.completed()).isTrue();
        assertThat(client.calls()).isEqualTo(1);
        assertThat(client.capturedRequest().getSelectedColumns()).containsExactly("customer_id");
        assertThat(client.capturedRequest().getWriteColumns()).containsExactly("id");
        verify(lifecycleSupport).completeExecution(eq(task()), eq(execution), any(), any(SyncActorContext.class));
        verify(lifecycleSupport, never()).failExecution(any(), any(), any(), any());
    }

    @Test
    void safeFilterConfigShouldBeSentAsStructuredInternalConditions() {
        FakeDatasourceRunOnceClient client = new FakeDatasourceRunOnceClient(completeResponse());
        SyncExecutionLifecycleSupport lifecycleSupport = mock(SyncExecutionLifecycleSupport.class);
        DataSyncTaskManagementReceiptPublisher receiptPublisher = mock(DataSyncTaskManagementReceiptPublisher.class);
        SyncBatchRunOnceDispatchService service = service(client, lifecycleSupport, properties(true), receiptPublisher);
        SyncTaskDefinition definition = definition("FULL", directMapping());
        definition.setFilterConfig("""
                {
                  "conditions": [
                    {"field":"status","operator":"=","value":"ACTIVE"}
                  ]
                }
                """);

        SyncBatchRunOnceDispatchResult result = service.dispatchRunOnce(execution("FULL"), task(),
                definition, workerPlan("FULL", "READY_TO_RUN", List.of()), actor());

        assertThat(result.dispatched()).isTrue();
        assertThat(client.capturedRequest().getExecutionPlan().getReadPlan().getFilterConditions()).hasSize(1);
        assertThat(client.capturedRequest().getExecutionPlan().getReadPlan().getFilterConditions().get(0).getColumn())
                .isEqualTo("status");
        assertThat(client.capturedRequest().getExecutionPlan().getReadPlan().getFilterConditions().get(0).getOperator())
                .isEqualTo("EQ");
        assertThat(client.capturedRequest().getExecutionPlan().getReadPlan().getFilterConditions().get(0).getValue())
                .isEqualTo("ACTIVE");
    }

    /**
     * 远端提示“本批成功但仍有后续批次”时，当前阶段必须 fail-closed。
     *
     * <p>原因是 data-sync 外层多批循环、心跳续租、checkpoint 安全保存和退避重试还没有完整实现。
     * 如果此时把 execution 留在 RUNNING，会让用户和运营侧误以为任务仍在执行，最终形成不可解释的悬挂状态。</p>
     */
    @Test
    void moreBatchesShouldContinueUntilSourceExhausted() {
        FakeDatasourceRunOnceClient client = new FakeDatasourceRunOnceClient(moreBatchesResponse(), completeResponse(14L, 12L));
        SyncExecutionLifecycleSupport lifecycleSupport = mock(SyncExecutionLifecycleSupport.class);
        DataSyncTaskManagementReceiptPublisher receiptPublisher = mock(DataSyncTaskManagementReceiptPublisher.class);
        SyncBatchRunOnceDispatchService service = service(client, lifecycleSupport, properties(true), receiptPublisher);
        SyncExecution execution = execution("FULL");

        SyncBatchRunOnceDispatchResult result = service.dispatchRunOnce(execution, task(), definition("FULL", directMapping()),
                workerPlan("FULL", "READY_TO_RUN", List.of()), actor());

        assertThat(result.dispatched()).isTrue();
        assertThat(result.completed()).isTrue();
        assertThat(result.failed()).isFalse();
        assertThat(result.remoteRunStatus()).isEqualTo("SOURCE_EXHAUSTED_COMPLETE_REQUIRED");
        assertThat(client.calls()).isEqualTo(2);
        assertThat(client.previousRecordsReadSnapshots()).containsExactly(10L, 12L);
        verify(lifecycleSupport).completeExecution(eq(task()), eq(execution), any(), any(SyncActorContext.class));
        verify(lifecycleSupport, never()).failExecution(any(), any(), any(), any());
    }

    @Test
    void remoteFailureShouldPersistLowSensitiveErrorSummary() {
        DatasourceRunOnceResponse response = new DatasourceRunOnceResponse();
        response.setRunStatus("RUNNER_FAILED");
        response.setFailed(true);
        response.setFailCallbackRecommended(true);
        response.setTotalRecordsRead(0L);
        response.setTotalRecordsWritten(0L);
        response.setTotalFailedRecordCount(1L);
        response.setErrorSummary("执行阶段异常：IllegalArgumentException - 目标对象定位缺少 schema\n请检查映射");
        SyncExecutionLifecycleSupport lifecycleSupport = mock(SyncExecutionLifecycleSupport.class);
        SyncBatchRunOnceDispatchService service = service(
                new FakeDatasourceRunOnceClient(response),
                lifecycleSupport,
                properties(true),
                mock(DataSyncTaskManagementReceiptPublisher.class));
        SyncExecution execution = execution("FULL");

        SyncBatchRunOnceDispatchResult result = service.dispatchRunOnce(
                execution, task(), definition("FULL", directMapping()),
                workerPlan("FULL", "READY_TO_RUN", List.of()), actor());

        assertThat(result.failed()).isTrue();
        ArgumentCaptor<SyncExecutionFailRequest> failCaptor = ArgumentCaptor.forClass(SyncExecutionFailRequest.class);
        verify(lifecycleSupport).failExecution(eq(task()), eq(execution), failCaptor.capture(), any(SyncActorContext.class));
        assertThat(failCaptor.getValue().getErrorCode()).isEqualTo("RUNNER_FAILED");
        assertThat(failCaptor.getValue().getErrorMessage())
                .contains("目标对象定位缺少 schema 请检查映射")
                .doesNotContain("\n");
    }

    private SyncBatchRunOnceDispatchService service(FakeDatasourceRunOnceClient client,
                                                   SyncExecutionLifecycleSupport lifecycleSupport,
                                                   DataSyncDatasourceRunOnceProperties properties,
                                                   DataSyncTaskManagementReceiptPublisher receiptPublisher) {
        return new SyncBatchRunOnceDispatchService(
                new SyncBatchRunnerBridgePlanSupport(new SyncFieldMappingExecutionContractSupport(new ObjectMapper())),
                client,
                properties,
                lifecycleSupport,
                receiptPublisher,
                mock(SyncExecutionLogSupport.class));
    }

    private DataSyncDatasourceRunOnceProperties properties(boolean enabled) {
        DataSyncDatasourceRunOnceProperties properties = new DataSyncDatasourceRunOnceProperties();
        properties.setEnabled(enabled);
        return properties;
    }

    private void assertFail(SyncExecutionLifecycleSupport lifecycleSupport,
                            SyncExecution execution,
                            String expectedErrorCode) {
        ArgumentCaptor<SyncExecutionFailRequest> failCaptor = ArgumentCaptor.forClass(SyncExecutionFailRequest.class);
        verify(lifecycleSupport).failExecution(eq(task()), eq(execution), failCaptor.capture(), any(SyncActorContext.class));
        assertThat(failCaptor.getValue().getErrorCode()).isEqualTo(expectedErrorCode);
        assertThat(failCaptor.getValue().getSamplePayload()).isNull();
        assertThat(failCaptor.getValue().getSourceRecordKey()).isNull();
        assertThat(failCaptor.getValue().getTargetRecordKey()).isNull();
    }

    private DatasourceRunOnceResponse completeResponse() {
        return completeResponse(12L, 10L);
    }

    private DatasourceRunOnceResponse completeResponse(Long totalRecordsRead, Long totalRecordsWritten) {
        DatasourceRunOnceResponse response = new DatasourceRunOnceResponse();
        response.setTaskId(11L);
        response.setExecutionId(88L);
        response.setRunStatus("SOURCE_EXHAUSTED_COMPLETE_REQUIRED");
        response.setBatchRecordsRead(2L);
        response.setBatchRecordsWritten(2L);
        response.setTotalRecordsRead(totalRecordsRead);
        response.setTotalRecordsWritten(totalRecordsWritten);
        response.setTotalFailedRecordCount(1L);
        response.setEndOfSource(true);
        response.setFailed(false);
        response.setCompleteCallbackRecommended(true);
        response.setFailCallbackRecommended(false);
        response.setProgressCallbackRecommended(false);
        response.setCheckpointCandidateProduced(false);
        response.setPayloadPolicy("LOW_SENSITIVE_RUN_ONCE_RESULT_NO_ROWS_NO_SQL_NO_CREDENTIALS_NO_CHECKPOINT_VALUE");
        return response;
    }

    /** 构造符合 internal checkpoint 交接合同的测试响应。 */
    private DatasourceRunOnceResponse checkpointResponse(DatasourceRunOnceResponse response, Object checkpointValue) {
        response.setCheckpointCallbackRecommended(true);
        response.setCheckpointCandidateProduced(true);
        response.setCheckpointCandidateValue(checkpointValue);
        response.setCheckpointHandoffMode("INTERNAL_RESPONSE_PERSIST_BEFORE_NEXT_BATCH");
        response.setCheckpointType("ID_WATERMARK");
        response.setCheckpointValueVisibility("WORKER_INTERNAL_AND_SYNC_CHECKPOINT_TABLE_ONLY");
        return response;
    }

    private DatasourceRunOnceResponse moreBatchesResponse() {
        DatasourceRunOnceResponse response = completeResponse();
        response.setRunStatus("BATCH_WRITTEN_MORE_REMAIN");
        response.setEndOfSource(false);
        response.setCompleteCallbackRecommended(false);
        response.setProgressCallbackRecommended(true);
        return response;
    }

    private SyncExecution execution(String syncMode) {
        SyncExecution execution = new SyncExecution();
        execution.setId(88L);
        execution.setTenantId(7L);
        execution.setProjectId(101L);
        execution.setWorkspaceId(301L);
        execution.setSyncTaskId(11L);
        execution.setExecutionNo(3L);
        execution.setExecutionState(SyncExecutionState.RUNNING.name());
        execution.setTriggerType(SyncTriggerType.MANUAL.name());
        execution.setExecutorId("worker-1");
        execution.setLeaseExpireTime(LocalDateTime.now().plusMinutes(2));
        execution.setRecordsRead(10L);
        execution.setRecordsWritten(8L);
        execution.setFailedRecordCount(1L);
        execution.setTriggeredBy(1001L);
        return execution;
    }

    private SyncTask task() {
        SyncTask task = new SyncTask();
        task.setId(11L);
        task.setTenantId(7L);
        task.setProjectId(101L);
        task.setWorkspaceId(301L);
        task.setCurrentState("RUNNING");
        return task;
    }

    private SyncTaskDefinition definition(String syncMode, String fieldMapping) {
        SyncTaskDefinition definition = new SyncTaskDefinition();
        definition.setId(22L);
        definition.setTenantId(7L);
        definition.setProjectId(101L);
        definition.setWorkspaceId(301L);
        definition.setSourceDatasourceId(10001L);
        definition.setTargetDatasourceId(10002L);
        definition.setSourceSchemaName("ods");
        definition.setSourceObjectName("customer");
        definition.setTargetSchemaName("dwd");
        definition.setTargetObjectName("customer");
        definition.setSourceConnectorType("MYSQL");
        definition.setTargetConnectorType("POSTGRESQL");
        definition.setSyncMode(syncMode);
        definition.setWriteStrategy("APPEND");
        definition.setPrimaryKeyField("id");
        definition.setIncrementalField("updated_at");
        definition.setFieldMappingConfig(fieldMapping);
        definition.setEnabled(true);
        return definition;
    }

    private SyncWorkerExecutionPlanView workerPlan(String syncMode, String planStatus, List<String> issueCodes) {
        return new SyncWorkerExecutionPlanView(
                true,
                planStatus,
                7L,
                101L,
                301L,
                11L,
                88L,
                3L,
                SyncExecutionState.RUNNING.name(),
                SyncTriggerType.MANUAL.name(),
                "worker-1",
                LocalDateTime.now().plusMinutes(2),
                10001L,
                10002L,
                "MYSQL",
                "POSTGRESQL",
                syncMode,
                "OFFLINE",
                "DATAX_STYLE_OFFLINE_READER_WRITER_RUNNER",
                "SINGLE_OBJECT",
                true,
                false,
                false,
                1,
                false,
                true,
                true,
                true,
                "APPEND",
                false,
                true,
                "INCREMENTAL_TIME".equals(syncMode),
                true,
                "SNAPSHOT_BOUNDED",
                !"FULL".equals(syncMode),
                "SEGMENT_RETRY",
                true,
                false,
                false,
                false,
                false,
                false,
                false,
                issueCodes,
                List.of("CLAIM_ALREADY_MARKED_RUNNING_DO_NOT_CALL_START"),
                List.of(),
                List.of(),
                "LOW_SENSITIVE_WORKER_PLAN_METADATA_ONLY");
    }

    private SyncActorContext actor() {
        return new SyncActorContext(7L, 1001L, "SERVICE_ACCOUNT", "trace-run-once-dispatch",
                "PROJECT", "project_id IN ${actorProjectIds}", List.of(101L), false);
    }

    private SyncCheckpoint checkpoint(Long id, Long sourceExecutionId, String type, String value) {
        SyncCheckpoint checkpoint = new SyncCheckpoint();
        checkpoint.setId(id);
        checkpoint.setTenantId(7L);
        checkpoint.setProjectId(101L);
        checkpoint.setWorkspaceId(301L);
        checkpoint.setSyncTaskId(11L);
        checkpoint.setExecutionId(sourceExecutionId);
        checkpoint.setCheckpointType(type);
        checkpoint.setCheckpointValue(value);
        checkpoint.setCheckpointTime(LocalDateTime.now().minusMinutes(1));
        return checkpoint;
    }

    private SyncRecoveryPlanWorkerResult checkpointRecoveryPlan(Long checkpointId) {
        return new SyncRecoveryPlanWorkerResult(
                true, 7L, 101L, 301L, 11L, 88L, 7002L,
                "REPLAY", 77L, checkpointId, null, null, null, null,
                "AUTOPILOT_PREAUTHORIZED_CHECKPOINT_RESUME", "CONSUMED", "恢复计划已消费");
    }

    private String directMapping() {
        return """
                [
                  {"sourceField":"id","targetField":"id"},
                  {"sourceField":"name","targetField":"name"}
                ]
                """;
    }

    private String renameMapping() {
        return """
                [{"sourceField":"customer_id","targetField":"id"}]
                """;
    }

    /**
     * 测试专用假客户端。
     *
     * <p>它只捕获 data-sync 即将发往 datasource-management 的请求，用于验证请求没有携带 checkpoint 原始值，
     * 并验证阻断场景不会触发远端调用。</p>
     */
    private static class FakeDatasourceRunOnceClient implements DatasourceRunOnceClient {

        private final List<DatasourceRunOnceResponse> responses;
        private int calls;
        private DatasourceRunOnceRequest capturedRequest;
        private final List<DatasourceRunOnceRequest> capturedRequests = new ArrayList<>();
        private final List<Long> previousRecordsReadSnapshots = new ArrayList<>();
        private final List<Object> checkpointValueSnapshots = new ArrayList<>();

        private FakeDatasourceRunOnceClient(DatasourceRunOnceResponse... responses) {
            this.responses = Arrays.asList(responses);
        }

        @Override
        public DatasourceRunOnceResponse runOnce(DatasourceRunOnceRequest request, SyncActorContext actorContext) {
            calls++;
            capturedRequest = request;
            capturedRequests.add(request);
            previousRecordsReadSnapshots.add(request.getPreviousRecordsRead());
            checkpointValueSnapshots.add(request.getCheckpointValue());
            int responseIndex = Math.min(calls - 1, responses.size() - 1);
            return responses.get(responseIndex);
        }

        private int calls() {
            return calls;
        }

        private DatasourceRunOnceRequest capturedRequest() {
            return capturedRequest;
        }

        private List<DatasourceRunOnceRequest> capturedRequests() {
            return capturedRequests;
        }

        private List<Long> previousRecordsReadSnapshots() {
            return previousRecordsReadSnapshots;
        }

        private List<Object> checkpointValueSnapshots() {
            return checkpointValueSnapshots;
        }
    }
}
