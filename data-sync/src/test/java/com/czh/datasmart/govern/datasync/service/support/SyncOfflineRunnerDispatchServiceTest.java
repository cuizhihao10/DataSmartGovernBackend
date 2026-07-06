/**
 * @Author : Cui
 * @Date: 2026/07/05 14:42
 * @Description DataSmart Govern Backend - SyncOfflineRunnerDispatchServiceTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExecutionCompleteRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExecutionFailRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExecutionPartialSuccessRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncWorkerExecutionPlanView;
import com.czh.datasmart.govern.datasync.entity.SyncExecution;
import com.czh.datasmart.govern.datasync.entity.SyncObjectExecution;
import com.czh.datasmart.govern.datasync.entity.SyncTask;
import com.czh.datasmart.govern.datasync.entity.SyncTemplate;
import com.czh.datasmart.govern.datasync.integration.datasource.runonce.DatasourceRunOnceResponse;
import com.czh.datasmart.govern.datasync.mapper.SyncObjectExecutionMapper;
import com.czh.datasmart.govern.datasync.support.SyncExecutionState;
import com.czh.datasmart.govern.datasync.support.SyncTriggerType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.ArrayList;
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
 * 离线 Runner 调度门面测试。
 *
 * <p>本测试验证“合同驱动执行循环”的核心分支：</p>
 * <p>1. 合同确认 FULL 单对象可由最小 bridge 端到端执行时，门面才委托 run-once；</p>
 * <p>2. 定时批量虽然属于 OFFLINE，但需要 checkpoint handoff，当前阶段必须提前 fail-closed；</p>
 * <p>3. 自定义 SQL 即使已经建模，也必须先审批，且任何结果都不能泄露 SQL 正文或 statementRef 值。</p>
 */
class SyncOfflineRunnerDispatchServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void fullSingleObjectContractShouldDelegateToRunOnce() {
        SyncBatchRunOnceDispatchService runOnceDispatchService = mock(SyncBatchRunOnceDispatchService.class);
        SyncExecutionLifecycleSupport lifecycleSupport = mock(SyncExecutionLifecycleSupport.class);
        DataSyncTaskManagementReceiptPublisher receiptPublisher = mock(DataSyncTaskManagementReceiptPublisher.class);
        SyncOfflineRunnerDispatchService service = service(runOnceDispatchService, lifecycleSupport, receiptPublisher);
        SyncExecution execution = execution("FULL");
        SyncTask task = task();
        SyncTemplate template = template("FULL");
        SyncWorkerExecutionPlanView workerPlan = workerPlan("FULL", false, false, false);
        when(runOnceDispatchService.dispatchPreparedRunOnce(any(SyncBatchRunnerBridgePlan.class),
                eq(execution), eq(task), any(SyncActorContext.class)))
                .thenReturn(new SyncBatchRunOnceDispatchResult(true, true, false,
                        "DISPATCHED_AND_COMPLETED", 88L, "SOURCE_EXHAUSTED_COMPLETE_REQUIRED",
                        List.of(), SyncBatchRunOnceDispatchResult.PAYLOAD_POLICY));

        SyncOfflineRunnerDispatchResult result =
                service.dispatchOffline(execution, task, template, workerPlan, actor());

        assertThat(result.dispatched()).isTrue();
        assertThat(result.completed()).isTrue();
        assertThat(result.failed()).isFalse();
        assertThat(result.runnerContractStatus()).isEqualTo("MINIMAL_BRIDGE_END_TO_END_SUPPORTED");
        verify(runOnceDispatchService).dispatchPreparedRunOnce(any(SyncBatchRunnerBridgePlan.class),
                eq(execution), eq(task), any(SyncActorContext.class));
        verify(lifecycleSupport, never()).failExecution(any(), any(), any(), any());
    }

    @Test
    void scheduledBatchShouldFailBeforeRunOnceUntilCheckpointHandoffExists() {
        SyncBatchRunOnceDispatchService runOnceDispatchService = mock(SyncBatchRunOnceDispatchService.class);
        SyncExecutionLifecycleSupport lifecycleSupport = mock(SyncExecutionLifecycleSupport.class);
        DataSyncTaskManagementReceiptPublisher receiptPublisher = mock(DataSyncTaskManagementReceiptPublisher.class);
        SyncOfflineRunnerDispatchService service = service(runOnceDispatchService, lifecycleSupport, receiptPublisher);
        SyncExecution execution = execution("SCHEDULED_BATCH");
        SyncTask task = task();
        task.setScheduleConfig("{\"cron\":\"0 0 * * * ?\"}");
        SyncTemplate template = template("SCHEDULED_BATCH");
        SyncWorkerExecutionPlanView workerPlan = workerPlan("SCHEDULED_BATCH", true, false, false);

        SyncOfflineRunnerDispatchResult result =
                service.dispatchOffline(execution, task, template, workerPlan, actor());

        assertThat(result.dispatched()).isFalse();
        assertThat(result.failed()).isTrue();
        assertThat(result.dispatchStatus()).isEqualTo("CHECKPOINT_HANDOFF_REQUIRED_BEFORE_RUN_ONCE");
        assertThat(result.issueCodes()).contains("OFFLINE_RUNNER_CHECKPOINT_HANDOFF_REQUIRED");
        verify(runOnceDispatchService, never()).dispatchPreparedRunOnce(any(), any(), any(), any());
        assertFailRequest(lifecycleSupport, "OFFLINE_RUNNER_CHECKPOINT_HANDOFF_REQUIRED");
        verify(receiptPublisher).publishFailed(eq(task), eq(execution), any(SyncActorContext.class),
                eq("OFFLINE_RUNNER_CHECKPOINT_HANDOFF_REQUIRED"), any());
    }

    @Test
    void scheduledBatchShouldUseDedicatedAdapterWhenRunnerIsRegistered() {
        SyncBatchRunOnceDispatchService runOnceDispatchService = mock(SyncBatchRunOnceDispatchService.class);
        SyncExecutionLifecycleSupport lifecycleSupport = mock(SyncExecutionLifecycleSupport.class);
        DataSyncTaskManagementReceiptPublisher receiptPublisher = mock(DataSyncTaskManagementReceiptPublisher.class);
        List<SyncOfflineRunnerExecutionRequest> capturedRequests = new ArrayList<>();
        SyncOfflineRunnerAdapter adapter = new SyncOfflineRunnerAdapter() {
            @Override
            public String adapterCode() {
                return "TEST_DATAX_STYLE_RUNNER";
            }

            @Override
            public boolean supports(SyncOfflineRunnerJobContract contract) {
                return contract != null
                        && contract.offlineChannel()
                        && contract.checkpointRequired()
                        && contract.dedicatedOfflineRunnerRequired();
            }

            @Override
            public SyncOfflineRunnerAdapterResult dispatch(SyncOfflineRunnerExecutionRequest request) {
                capturedRequests.add(request);
                return new SyncOfflineRunnerAdapterResult(
                        true,
                        false,
                        false,
                        "DEDICATED_RUNNER_DISPATCHED",
                        request.execution().getId(),
                        "QUEUED",
                        adapterCode(),
                        List.of("DEDICATED_RUNNER_ACCEPTED"),
                        SyncOfflineRunnerAdapterResult.PAYLOAD_POLICY
                );
            }
        };
        SyncOfflineRunnerDispatchService service = service(runOnceDispatchService, lifecycleSupport,
                receiptPublisher, List.of(adapter));
        SyncExecution execution = execution("SCHEDULED_BATCH");
        SyncTask task = task();
        task.setScheduleConfig("{\"cron\":\"0 0 * * * ?\"}");
        SyncTemplate template = template("SCHEDULED_BATCH");
        SyncWorkerExecutionPlanView workerPlan = workerPlan("SCHEDULED_BATCH", true, false, false);

        SyncOfflineRunnerDispatchResult result =
                service.dispatchOffline(execution, task, template, workerPlan, actor());

        assertThat(result.dispatched()).isTrue();
        assertThat(result.completed()).isFalse();
        assertThat(result.failed()).isFalse();
        assertThat(result.dispatchStatus()).isEqualTo("DEDICATED_RUNNER_DISPATCHED");
        assertThat(result.remoteRunStatus()).isEqualTo("QUEUED");
        assertThat(result.issueCodes()).contains("DEDICATED_RUNNER_ACCEPTED", "TEST_DATAX_STYLE_RUNNER");
        assertThat(capturedRequests).hasSize(1);
        assertThat(capturedRequests.get(0).runnerContract().checkpointRequired()).isTrue();
        assertThat(capturedRequests.get(0).bridgePlan().getOfflineRunnerContract()).isNotNull();
        verify(runOnceDispatchService, never()).dispatchPreparedRunOnce(any(), any(), any(), any());
        verify(lifecycleSupport, never()).failExecution(any(), any(), any(), any());
        verify(receiptPublisher, never()).publishFailed(any(), any(), any(), any(), any());
    }

    @Test
    void objectListFullModeShouldFanOutObjectsSeriallyAndCompleteOnce() {
        SyncBatchRunOnceDispatchService runOnceDispatchService = mock(SyncBatchRunOnceDispatchService.class);
        SyncExecutionLifecycleSupport lifecycleSupport = mock(SyncExecutionLifecycleSupport.class);
        DataSyncTaskManagementReceiptPublisher receiptPublisher = mock(DataSyncTaskManagementReceiptPublisher.class);
        SyncOfflineRunnerDispatchService service = service(runOnceDispatchService, lifecycleSupport, receiptPublisher);
        SyncExecution execution = execution("FULL");
        SyncTask task = task();
        SyncTemplate template = objectListTemplate("FULL");
        SyncWorkerExecutionPlanView workerPlan = workerPlan("FULL", false, false, false);
        when(runOnceDispatchService.executePreparedRunOnceRemoteOnly(any(SyncBatchRunnerBridgePlan.class),
                any(SyncExecution.class), eq(task), any(SyncActorContext.class)))
                .thenReturn(remoteComplete(3L, 3L), remoteComplete(4L, 4L));

        SyncOfflineRunnerDispatchResult result =
                service.dispatchOffline(execution, task, template, workerPlan, actor());

        assertThat(result.dispatched()).isTrue();
        assertThat(result.completed()).isTrue();
        assertThat(result.failed()).isFalse();
        assertThat(result.dispatchStatus()).isEqualTo("OBJECT_LIST_OBJECT_LEVEL_FAN_OUT_COMPLETED");
        assertThat(result.remoteRunStatus()).isEqualTo("OBJECT_LIST_ALL_OBJECTS_COMPLETED");

        ArgumentCaptor<SyncBatchRunnerBridgePlan> bridgePlanCaptor =
                ArgumentCaptor.forClass(SyncBatchRunnerBridgePlan.class);
        verify(runOnceDispatchService, times(2)).executePreparedRunOnceRemoteOnly(
                bridgePlanCaptor.capture(), any(SyncExecution.class), eq(task), any(SyncActorContext.class));
        assertThat(bridgePlanCaptor.getAllValues())
                .extracting(SyncBatchRunnerBridgePlan::getSourceObjectLocator)
                .containsExactly("ods.orders", "ods.customers");
        assertThat(bridgePlanCaptor.getAllValues())
                .extracting(SyncBatchRunnerBridgePlan::getTargetObjectLocator)
                .containsExactly("dwd.dwd_orders", "dwd.dwd_customers");

        ArgumentCaptor<SyncExecutionCompleteRequest> completeCaptor =
                ArgumentCaptor.forClass(SyncExecutionCompleteRequest.class);
        verify(lifecycleSupport).completeExecution(eq(task), eq(execution),
                completeCaptor.capture(), any(SyncActorContext.class));
        assertThat(completeCaptor.getValue().getRecordsRead()).isEqualTo(7L);
        assertThat(completeCaptor.getValue().getRecordsWritten()).isEqualTo(7L);
        verify(receiptPublisher).publishComplete(eq(task), eq(execution),
                any(SyncActorContext.class), any(DatasourceRunOnceResponse.class));
        verify(lifecycleSupport, never()).failExecution(any(), any(), any(), any());
        verify(lifecycleSupport, never()).partiallySucceedExecution(any(), any(), any(), any());
    }

    @Test
    void objectListShouldRetryFailedObjectBeforeMovingToNextObject() {
        SyncBatchRunOnceDispatchService runOnceDispatchService = mock(SyncBatchRunOnceDispatchService.class);
        SyncExecutionLifecycleSupport lifecycleSupport = mock(SyncExecutionLifecycleSupport.class);
        DataSyncTaskManagementReceiptPublisher receiptPublisher = mock(DataSyncTaskManagementReceiptPublisher.class);
        SyncOfflineRunnerDispatchService service = service(runOnceDispatchService, lifecycleSupport, receiptPublisher);
        SyncExecution execution = execution("FULL");
        SyncTask task = task();
        SyncTemplate template = objectListTemplate("FULL");
        template.setRetryPolicy("{\"maxObjectRetries\":1}");
        SyncWorkerExecutionPlanView workerPlan = workerPlan("FULL", false, false, false);
        when(runOnceDispatchService.executePreparedRunOnceRemoteOnly(any(SyncBatchRunnerBridgePlan.class),
                any(SyncExecution.class), eq(task), any(SyncActorContext.class)))
                .thenReturn(
                        remoteFailed("REMOTE_OBJECT_TRANSIENT_FAILED", true),
                        remoteComplete(3L, 3L),
                        remoteComplete(4L, 4L)
                );

        SyncOfflineRunnerDispatchResult result =
                service.dispatchOffline(execution, task, template, workerPlan, actor());

        assertThat(result.dispatched()).isTrue();
        assertThat(result.completed()).isTrue();
        assertThat(result.failed()).isFalse();
        assertThat(result.issueCodes()).contains("OBJECT_LIST_CHILD_RETRYING", "OBJECT_LIST_CHILD_COMPLETED");
        verify(runOnceDispatchService, times(3)).executePreparedRunOnceRemoteOnly(
                any(SyncBatchRunnerBridgePlan.class), any(SyncExecution.class), eq(task), any(SyncActorContext.class));
        ArgumentCaptor<SyncExecutionCompleteRequest> completeCaptor =
                ArgumentCaptor.forClass(SyncExecutionCompleteRequest.class);
        verify(lifecycleSupport).completeExecution(eq(task), eq(execution),
                completeCaptor.capture(), any(SyncActorContext.class));
        assertThat(completeCaptor.getValue().getRecordsRead()).isEqualTo(7L);
        verify(lifecycleSupport, never()).failExecution(any(), any(), any(), any());
        verify(lifecycleSupport, never()).partiallySucceedExecution(any(), any(), any(), any());
    }

    @Test
    void objectListShouldContinueAfterFinalObjectFailureAndMarkParentPartiallySucceeded() {
        SyncBatchRunOnceDispatchService runOnceDispatchService = mock(SyncBatchRunOnceDispatchService.class);
        SyncExecutionLifecycleSupport lifecycleSupport = mock(SyncExecutionLifecycleSupport.class);
        DataSyncTaskManagementReceiptPublisher receiptPublisher = mock(DataSyncTaskManagementReceiptPublisher.class);
        SyncOfflineRunnerDispatchService service = service(runOnceDispatchService, lifecycleSupport, receiptPublisher);
        SyncExecution execution = execution("FULL");
        SyncTask task = task();
        SyncTemplate template = objectListTemplate("FULL");
        SyncWorkerExecutionPlanView workerPlan = workerPlan("FULL", false, false, false);
        when(runOnceDispatchService.executePreparedRunOnceRemoteOnly(any(SyncBatchRunnerBridgePlan.class),
                any(SyncExecution.class), eq(task), any(SyncActorContext.class)))
                .thenReturn(
                        remoteFailed("REMOTE_OBJECT_BATCH_FAILED", false),
                        remoteComplete(4L, 4L)
                );

        SyncOfflineRunnerDispatchResult result =
                service.dispatchOffline(execution, task, template, workerPlan, actor());

        assertThat(result.dispatched()).isTrue();
        assertThat(result.completed()).isFalse();
        assertThat(result.failed()).isFalse();
        assertThat(result.dispatchStatus()).isEqualTo("OBJECT_LIST_OBJECT_LEVEL_FAN_OUT_PARTIALLY_SUCCEEDED");
        assertThat(result.remoteRunStatus()).isEqualTo("OBJECT_LIST_PARTIALLY_SUCCEEDED_RETRY_FAILED_OBJECTS");
        assertThat(result.issueCodes()).contains("OBJECT_LIST_PARTIALLY_SUCCEEDED", "REMOTE_OBJECT_BATCH_FAILED");
        verify(runOnceDispatchService, times(2)).executePreparedRunOnceRemoteOnly(
                any(SyncBatchRunnerBridgePlan.class), any(SyncExecution.class), eq(task), any(SyncActorContext.class));
        ArgumentCaptor<SyncExecutionPartialSuccessRequest> partialCaptor =
                ArgumentCaptor.forClass(SyncExecutionPartialSuccessRequest.class);
        verify(lifecycleSupport).partiallySucceedExecution(eq(task), eq(execution),
                partialCaptor.capture(), any(SyncActorContext.class));
        assertThat(partialCaptor.getValue().getRecordsRead()).isEqualTo(4L);
        assertThat(partialCaptor.getValue().getRecordsWritten()).isEqualTo(4L);
        assertThat(partialCaptor.getValue().getFailedRecordCount()).isEqualTo(1L);
        verify(receiptPublisher).publishPartiallySucceeded(eq(task), eq(execution),
                any(SyncActorContext.class), any(DatasourceRunOnceResponse.class), any());
        verify(lifecycleSupport, never()).completeExecution(any(), any(), any(), any());
        verify(lifecycleSupport, never()).failExecution(any(), any(), any(), any());
    }

    @Test
    void customSqlShouldFailForApprovalWithoutLeakingSqlText() {
        SyncBatchRunOnceDispatchService runOnceDispatchService = mock(SyncBatchRunOnceDispatchService.class);
        SyncExecutionLifecycleSupport lifecycleSupport = mock(SyncExecutionLifecycleSupport.class);
        DataSyncTaskManagementReceiptPublisher receiptPublisher = mock(DataSyncTaskManagementReceiptPublisher.class);
        SyncOfflineRunnerDispatchService service = service(runOnceDispatchService, lifecycleSupport, receiptPublisher);
        SyncExecution execution = execution("CUSTOM_SQL_QUERY");
        SyncTask task = task();
        SyncTemplate template = template("CUSTOM_SQL_QUERY");
        template.setSyncScopeType("CUSTOM_SQL_QUERY");
        template.setCustomSqlConfig("""
                {
                  "statementRef": "managed-sql.customer-active",
                  "sql": "select id, name from customer where status = :status"
                }
                """);
        SyncWorkerExecutionPlanView workerPlan = workerPlan("CUSTOM_SQL_QUERY", true, true, true);

        SyncOfflineRunnerDispatchResult result =
                service.dispatchOffline(execution, task, template, workerPlan, actor());

        assertThat(result.dispatched()).isFalse();
        assertThat(result.failed()).isTrue();
        assertThat(result.dispatchStatus()).isEqualTo("WAITING_APPROVAL_BEFORE_RUNNER_DISPATCH");
        assertThat(result.issueCodes()).contains("OFFLINE_RUNNER_APPROVAL_REQUIRED");
        assertThat(result.toString())
                .doesNotContain("select id")
                .doesNotContain("customer-active")
                .doesNotContain("status = :status");
        verify(runOnceDispatchService, never()).dispatchPreparedRunOnce(any(), any(), any(), any());
        assertFailRequest(lifecycleSupport, "OFFLINE_RUNNER_APPROVAL_REQUIRED");
    }

    private SyncOfflineRunnerDispatchService service(SyncBatchRunOnceDispatchService runOnceDispatchService,
                                                     SyncExecutionLifecycleSupport lifecycleSupport,
                                                     DataSyncTaskManagementReceiptPublisher receiptPublisher) {
        return service(runOnceDispatchService, lifecycleSupport, receiptPublisher, List.of());
    }

    private SyncOfflineRunnerDispatchService service(SyncBatchRunOnceDispatchService runOnceDispatchService,
                                                     SyncExecutionLifecycleSupport lifecycleSupport,
                                                     DataSyncTaskManagementReceiptPublisher receiptPublisher,
                                                     List<SyncOfflineRunnerAdapter> adapters) {
        SyncBatchRunnerBridgePlanSupport bridgePlanSupport = new SyncBatchRunnerBridgePlanSupport(
                new SyncFieldMappingExecutionContractSupport(objectMapper),
                new SyncFilterExecutionContractSupport(objectMapper),
                new SyncTemplateScopeContractSupport(objectMapper),
                new SyncOfflineRunnerContractSupport());
        SyncOfflineRunnerAdapterRegistry runnerAdapterRegistry = new SyncOfflineRunnerAdapterRegistry(adapters);
        SyncObjectListFanOutDispatchService objectListFanOutDispatchService = new SyncObjectListFanOutDispatchService(
                new SyncObjectMappingExecutionContractSupport(objectMapper),
                objectExecutionLifecycleSupport(),
                bridgePlanSupport,
                runOnceDispatchService,
                lifecycleSupport,
                receiptPublisher,
                objectMapper);
        return new SyncOfflineRunnerDispatchService(bridgePlanSupport, runOnceDispatchService,
                runnerAdapterRegistry, objectListFanOutDispatchService, lifecycleSupport, receiptPublisher);
    }

    private SyncObjectExecutionLifecycleSupport objectExecutionLifecycleSupport() {
        SyncObjectExecutionMapper mapper = mock(SyncObjectExecutionMapper.class);
        List<SyncObjectExecution> rows = new ArrayList<>();
        when(mapper.selectByExecutionId(any())).thenAnswer(invocation -> new ArrayList<>(rows));
        when(mapper.insert(any(SyncObjectExecution.class))).thenAnswer(invocation -> {
            SyncObjectExecution row = invocation.getArgument(0);
            row.setId((long) rows.size() + 1L);
            rows.add(row);
            return 1;
        });
        when(mapper.updateById(any(SyncObjectExecution.class))).thenReturn(1);
        return new SyncObjectExecutionLifecycleSupport(mapper);
    }

    private void assertFailRequest(SyncExecutionLifecycleSupport lifecycleSupport, String expectedCode) {
        ArgumentCaptor<SyncExecutionFailRequest> failCaptor = ArgumentCaptor.forClass(SyncExecutionFailRequest.class);
        verify(lifecycleSupport).failExecution(any(SyncTask.class), any(SyncExecution.class),
                failCaptor.capture(), any(SyncActorContext.class));
        assertThat(failCaptor.getValue().getErrorCode()).isEqualTo(expectedCode);
        assertThat(failCaptor.getValue().getSourceRecordKey()).isNull();
        assertThat(failCaptor.getValue().getTargetRecordKey()).isNull();
        assertThat(failCaptor.getValue().getSamplePayload()).isNull();
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
        execution.setTriggeredBy(1001L);
        return execution;
    }

    private SyncTask task() {
        SyncTask task = new SyncTask();
        task.setId(11L);
        task.setTenantId(7L);
        task.setProjectId(101L);
        task.setWorkspaceId(301L);
        task.setTemplateId(22L);
        task.setCurrentState("RUNNING");
        return task;
    }

    private SyncTemplate template(String syncMode) {
        SyncTemplate template = new SyncTemplate();
        template.setId(22L);
        template.setTenantId(7L);
        template.setProjectId(101L);
        template.setWorkspaceId(301L);
        template.setSourceDatasourceId(10001L);
        template.setTargetDatasourceId(10002L);
        template.setSourceSchemaName("ods");
        template.setSourceObjectName("customer");
        template.setTargetSchemaName("dwd");
        template.setTargetObjectName("customer");
        template.setSourceConnectorType("MYSQL");
        template.setTargetConnectorType("POSTGRESQL");
        template.setSyncMode(syncMode);
        template.setSyncScopeType("SINGLE_OBJECT");
        template.setWriteStrategy("APPEND");
        template.setPrimaryKeyField("id");
        template.setIncrementalField("updated_at");
        template.setFieldMappingConfig("""
                [
                  {"sourceField":"id","targetField":"id"},
                  {"sourceField":"name","targetField":"name"}
                ]
                """);
        template.setEnabled(true);
        return template;
    }

    private SyncTemplate objectListTemplate(String syncMode) {
        SyncTemplate template = template(syncMode);
        template.setSyncScopeType("OBJECT_LIST");
        template.setSourceObjectName(null);
        template.setTargetObjectName(null);
        template.setObjectMappingConfig("""
                {
                  "mappings": [
                    {"sourceObject": "orders", "targetObject": "dwd_orders"},
                    {"sourceObject": "customers", "targetObject": "dwd_customers"}
                  ]
                }
                """);
        return template;
    }

    private SyncBatchRunOnceRemoteExecutionResult remoteComplete(Long recordsRead, Long recordsWritten) {
        return new SyncBatchRunOnceRemoteExecutionResult(
                true,
                true,
                false,
                "DISPATCHED_AND_COMPLETED",
                88L,
                "SOURCE_EXHAUSTED_COMPLETE_REQUIRED",
                recordsRead,
                recordsWritten,
                0L,
                null,
                null,
                null,
                false,
                List.of(),
                SyncBatchRunOnceRemoteExecutionResult.PAYLOAD_POLICY
        );
    }

    private SyncBatchRunOnceRemoteExecutionResult remoteFailed(String errorCode, boolean retryable) {
        return new SyncBatchRunOnceRemoteExecutionResult(
                true,
                false,
                true,
                "DISPATCHED_AND_FAILED_BY_REMOTE_RESULT",
                88L,
                "REMOTE_FAILED",
                0L,
                0L,
                0L,
                "CONNECTOR_RUNTIME_RUN_ONCE_FAILED",
                errorCode,
                "datasource-management run-once 报告子对象失败",
                retryable,
                List.of(errorCode),
                SyncBatchRunOnceRemoteExecutionResult.PAYLOAD_POLICY
        );
    }

    private SyncWorkerExecutionPlanView workerPlan(String syncMode,
                                                   boolean checkpointRequired,
                                                   boolean customSqlScope,
                                                   boolean requiresApproval) {
        return new SyncWorkerExecutionPlanView(
                true,
                "READY_TO_RUN",
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
                22L,
                10001L,
                10002L,
                "MYSQL",
                "POSTGRESQL",
                syncMode,
                "OFFLINE",
                "DATAX_STYLE_OFFLINE_READER_WRITER_RUNNER",
                customSqlScope ? "CUSTOM_SQL_QUERY" : "SINGLE_OBJECT",
                !customSqlScope,
                false,
                customSqlScope,
                1,
                requiresApproval,
                !customSqlScope,
                true,
                true,
                "APPEND",
                false,
                true,
                !"FULL".equals(syncMode),
                true,
                "SNAPSHOT_BOUNDED",
                checkpointRequired,
                "SEGMENT_RETRY",
                true,
                false,
                customSqlScope,
                false,
                false,
                false,
                false,
                List.of(),
                List.of("CLAIM_ALREADY_MARKED_RUNNING_DO_NOT_CALL_START"),
                List.of(),
                List.of(),
                "LOW_SENSITIVE_WORKER_PLAN_METADATA_ONLY");
    }

    private SyncActorContext actor() {
        return new SyncActorContext(7L, 1001L, "SERVICE_ACCOUNT", "trace-offline-runner-dispatch",
                "PROJECT", "project_id IN ${actorProjectIds}", List.of(101L), false);
    }
}
