/**
 * @Author : Cui
 * @Date: 2026/07/07 00:19
 * @Description DataSmart Govern Backend - SyncObjectListSelectiveRetryControlPlaneE2ETest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.datasync.config.DataSyncDatasourceRunOnceProperties;
import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExecutionCompleteRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExecutionPartialSuccessRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncObjectRetryRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncObjectRetryResult;
import com.czh.datasmart.govern.datasync.controller.dto.SyncWorkerExecutionPlanView;
import com.czh.datasmart.govern.datasync.entity.SyncExecution;
import com.czh.datasmart.govern.datasync.entity.SyncObjectExecution;
import com.czh.datasmart.govern.datasync.entity.SyncTask;
import com.czh.datasmart.govern.datasync.entity.SyncTemplate;
import com.czh.datasmart.govern.datasync.integration.datasource.runonce.DatasourceRunOnceClient;
import com.czh.datasmart.govern.datasync.integration.datasource.runonce.DatasourceRunOnceRequest;
import com.czh.datasmart.govern.datasync.integration.datasource.runonce.DatasourceRunOnceResponse;
import com.czh.datasmart.govern.datasync.mapper.SyncExecutionMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncObjectExecutionMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncTaskMapper;
import com.czh.datasmart.govern.datasync.support.SyncExecutionState;
import com.czh.datasmart.govern.datasync.support.SyncObjectExecutionState;
import com.czh.datasmart.govern.datasync.support.SyncTaskState;
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
 * OBJECT_LIST 对象级账本与失败对象选择性重试的控制面 E2E 测试。
 *
 * <p>这个测试覆盖的是 DataX-style “一个大任务拆成多个对象级执行单元”的恢复语义：
 * 一个对象成功后应该写入对象账本并在后续恢复时跳过；另一个对象失败后，运营侧可以选择性重试失败对象，
 * worker 再次进入 fan-out 时只重跑失败对象，不应该把已经成功的对象再写一遍。</p>
 *
 * <p>为什么这一步很关键：如果只有父 execution 的 SUCCEEDED/FAILED 状态，平台只能整单重跑或整单失败；
 * 但真实商业化数据同步工具必须支持“部分成功、失败分片/对象重传、成功分片不重复写入”。这也是用户前面提到的
 * DataX Job/Task 思路在当前项目里的最小落地形态。</p>
 */
class SyncObjectListSelectiveRetryControlPlaneE2ETest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 验证两表 OBJECT_LIST 的部分成功与失败对象选择性重试闭环。
     *
     * <p>第一次运行：orders 成功，customers 失败，父 execution 进入 PARTIALLY_SUCCEEDED。
     * 选择性重试：只把 customers 对象账本从 FAILED 重置为 PENDING，orders 继续保持 SUCCEEDED。
     * 第二次运行：fan-out 应跳过 orders，只调用 datasource run-once 处理 customers，最终父 execution complete。</p>
     */
    @Test
    void objectListShouldRetryOnlyFailedObjectAndSkipSucceededObject() {
        FakeDatasourceRunOnceClient datasourceClient = new FakeDatasourceRunOnceClient(
                completeResponse(3L, 3L),
                failedResponse(),
                completeResponse(4L, 4L)
        );
        ObjectExecutionMapperFixture objectMapperFixture = objectExecutionMapperFixture();
        SyncExecutionLifecycleSupport lifecycleSupport = mock(SyncExecutionLifecycleSupport.class);
        DataSyncTaskManagementReceiptPublisher receiptPublisher = mock(DataSyncTaskManagementReceiptPublisher.class);
        SyncOfflineRunnerDispatchService dispatchService =
                dispatchService(datasourceClient, objectMapperFixture.mapper(), lifecycleSupport, receiptPublisher);
        SyncObjectExecutionOperationSupport retrySupport = retrySupport(objectMapperFixture.mapper());
        SyncExecution execution = execution(SyncExecutionState.RUNNING);
        SyncTask task = task("RUNNING");
        SyncTemplate template = objectListTemplate();

        SyncOfflineRunnerDispatchResult firstResult =
                dispatchService.dispatchOffline(execution, task, template, workerPlan(), actor());

        assertThat(firstResult.dispatched()).isTrue();
        assertThat(firstResult.completed()).isFalse();
        assertThat(firstResult.failed()).isFalse();
        assertThat(firstResult.dispatchStatus()).isEqualTo("OBJECT_LIST_OBJECT_LEVEL_FAN_OUT_PARTIALLY_SUCCEEDED");
        assertThat(firstResult.remoteRunStatus()).isEqualTo("OBJECT_LIST_PARTIALLY_SUCCEEDED_RETRY_FAILED_OBJECTS");
        assertThat(datasourceClient.sourceObjectLocators()).containsExactly("ods.orders", "ods.customers");
        assertThat(objectMapperFixture.rows()).hasSize(2);
        assertObjectState(objectMapperFixture.rows(), 0, SyncObjectExecutionState.SUCCEEDED, 3L, 3L, 1);
        assertObjectState(objectMapperFixture.rows(), 1, SyncObjectExecutionState.FAILED, 0L, 0L, 1);

        ArgumentCaptor<SyncExecutionPartialSuccessRequest> partialCaptor =
                ArgumentCaptor.forClass(SyncExecutionPartialSuccessRequest.class);
        verify(lifecycleSupport).partiallySucceedExecution(eq(task), eq(execution),
                partialCaptor.capture(), any(SyncActorContext.class));
        assertThat(partialCaptor.getValue().getRecordsRead()).isEqualTo(3L);
        assertThat(partialCaptor.getValue().getRecordsWritten()).isEqualTo(3L);
        assertThat(partialCaptor.getValue().getFailedRecordCount()).isEqualTo(1L);
        verify(receiptPublisher).publishPartiallySucceeded(eq(task), eq(execution),
                any(SyncActorContext.class), any(DatasourceRunOnceResponse.class), any());

        execution.setExecutionState(SyncExecutionState.PARTIALLY_SUCCEEDED.name());
        task.setCurrentState(SyncTaskState.PARTIALLY_SUCCEEDED.name());
        SyncObjectRetryRequest retryRequest = new SyncObjectRetryRequest();
        /*
         * objectOrdinal 使用 objectMappingConfig.mappings 的数组下标，因此 customers 是第 2 个配置项但 ordinal=1。
         * 这里显式按 ordinal 选择失败对象，可以验证运营侧不会误把已经成功的 orders 重新置为 PENDING。
         */
        retryRequest.setObjectOrdinals(List.of(1));
        retryRequest.setRetryAttemptBudget(3);
        retryRequest.setResetAttemptCount(true);
        retryRequest.setReason("重试 customers 失败对象，验证成功对象不会重复执行");

        SyncObjectRetryResult retryResult = retrySupport.retryFailedObjects(task, execution, retryRequest, actor());

        assertThat(retryResult.retryObjectCount()).isEqualTo(1);
        assertThat(retryResult.executionState()).isEqualTo(SyncExecutionState.QUEUED.name());
        assertThat(retryResult.taskState()).isEqualTo(SyncTaskState.RETRYING.name());
        assertObjectState(objectMapperFixture.rows(), 0, SyncObjectExecutionState.SUCCEEDED, 3L, 3L, 1);
        assertObjectState(objectMapperFixture.rows(), 1, SyncObjectExecutionState.PENDING, 0L, 0L, 0);

        execution.setExecutionState(SyncExecutionState.RUNNING.name());
        task.setCurrentState(SyncTaskState.RETRYING.name());
        SyncOfflineRunnerDispatchResult retryDispatchResult =
                dispatchService.dispatchOffline(execution, task, template, workerPlan(), actor());

        assertThat(retryDispatchResult.dispatched()).isTrue();
        assertThat(retryDispatchResult.completed()).isTrue();
        assertThat(retryDispatchResult.failed()).isFalse();
        assertThat(retryDispatchResult.dispatchStatus()).isEqualTo("OBJECT_LIST_OBJECT_LEVEL_FAN_OUT_COMPLETED");
        assertThat(datasourceClient.sourceObjectLocators())
                .containsExactly("ods.orders", "ods.customers", "ods.customers");
        assertObjectState(objectMapperFixture.rows(), 0, SyncObjectExecutionState.SUCCEEDED, 3L, 3L, 1);
        assertObjectState(objectMapperFixture.rows(), 1, SyncObjectExecutionState.SUCCEEDED, 4L, 4L, 1);

        ArgumentCaptor<SyncExecutionCompleteRequest> completeCaptor =
                ArgumentCaptor.forClass(SyncExecutionCompleteRequest.class);
        verify(lifecycleSupport).completeExecution(eq(task), eq(execution),
                completeCaptor.capture(), any(SyncActorContext.class));
        assertThat(completeCaptor.getValue().getRecordsRead()).isEqualTo(7L);
        assertThat(completeCaptor.getValue().getRecordsWritten()).isEqualTo(7L);
        verify(receiptPublisher).publishComplete(eq(task), eq(execution),
                any(SyncActorContext.class), any(DatasourceRunOnceResponse.class));
        verify(lifecycleSupport, never()).failExecution(any(), any(), any(), any());
    }

    private SyncOfflineRunnerDispatchService dispatchService(FakeDatasourceRunOnceClient datasourceClient,
                                                            SyncObjectExecutionMapper objectExecutionMapper,
                                                            SyncExecutionLifecycleSupport lifecycleSupport,
                                                            DataSyncTaskManagementReceiptPublisher receiptPublisher) {
        SyncBatchRunnerBridgePlanSupport bridgePlanSupport = bridgePlanSupport();
        SyncBatchRunOnceDispatchService runOnceDispatchService = new SyncBatchRunOnceDispatchService(
                bridgePlanSupport,
                datasourceClient,
                runOnceProperties(),
                lifecycleSupport,
                receiptPublisher,
                mock(SyncExecutionLogSupport.class));
        SyncObjectListFanOutDispatchService objectListFanOutDispatchService = new SyncObjectListFanOutDispatchService(
                new SyncObjectMappingExecutionContractSupport(objectMapper),
                new SyncObjectExecutionLifecycleSupport(objectExecutionMapper),
                bridgePlanSupport,
                runOnceDispatchService,
                lifecycleSupport,
                receiptPublisher,
                mock(SyncExecutionLogSupport.class),
                objectMapper);
        SyncPartitionShardFanOutDispatchService partitionShardFanOutDispatchService =
                new SyncPartitionShardFanOutDispatchService(
                        new SyncPartitionShardExecutionContractSupport(objectMapper),
                        new SyncObjectExecutionLifecycleSupport(objectExecutionMapper),
                        bridgePlanSupport,
                        runOnceDispatchService,
                        null,
                        lifecycleSupport,
                        receiptPublisher,
                        mock(SyncExecutionLogSupport.class));
        return new SyncOfflineRunnerDispatchService(
                bridgePlanSupport,
                runOnceDispatchService,
                new SyncOfflineRunnerAdapterRegistry(List.of()),
                objectListFanOutDispatchService,
                partitionShardFanOutDispatchService,
                lifecycleSupport,
                receiptPublisher);
    }

    private SyncObjectExecutionOperationSupport retrySupport(SyncObjectExecutionMapper objectExecutionMapper) {
        SyncExecutionMapper executionMapper = mock(SyncExecutionMapper.class);
        SyncTaskMapper taskMapper = mock(SyncTaskMapper.class);
        SyncAuditSupport auditSupport = mock(SyncAuditSupport.class);
        when(executionMapper.requeueTerminalObjectLevelRetry(eq(88L), any())).thenReturn(1);
        when(taskMapper.markLifecycleState(eq(11L), eq(SyncTaskState.RETRYING.name()),
                eq(SyncTriggerType.MANUAL.name()), eq(88L))).thenReturn(1);
        return new SyncObjectExecutionOperationSupport(
                objectExecutionMapper,
                executionMapper,
                taskMapper,
                new SyncQuerySupport(),
                auditSupport);
    }

    private SyncBatchRunnerBridgePlanSupport bridgePlanSupport() {
        return new SyncBatchRunnerBridgePlanSupport(
                new SyncFieldMappingExecutionContractSupport(objectMapper),
                new SyncFilterExecutionContractSupport(objectMapper),
                new SyncTemplateScopeContractSupport(objectMapper),
                new SyncOfflineRunnerContractSupport());
    }

    private DataSyncDatasourceRunOnceProperties runOnceProperties() {
        DataSyncDatasourceRunOnceProperties properties = new DataSyncDatasourceRunOnceProperties();
        properties.setEnabled(true);
        properties.setDefaultFetchSize(2);
        properties.setDefaultWriteBatchSize(2);
        properties.setDefaultCommitIntervalRecords(2);
        properties.setMaxRunOnceBatches(5);
        return properties;
    }

    private ObjectExecutionMapperFixture objectExecutionMapperFixture() {
        SyncObjectExecutionMapper mapper = mock(SyncObjectExecutionMapper.class);
        List<SyncObjectExecution> rows = new ArrayList<>();
        when(mapper.selectByExecutionId(eq(88L))).thenAnswer(invocation -> new ArrayList<>(rows));
        when(mapper.insert(any(SyncObjectExecution.class))).thenAnswer(invocation -> {
            SyncObjectExecution row = invocation.getArgument(0);
            row.setId((long) rows.size() + 1L);
            rows.add(row);
            return 1;
        });
        when(mapper.updateById(any(SyncObjectExecution.class))).thenReturn(1);
        return new ObjectExecutionMapperFixture(mapper, rows);
    }

    private void assertObjectState(List<SyncObjectExecution> rows,
                                   int objectOrdinal,
                                   SyncObjectExecutionState expectedState,
                                   Long expectedRecordsRead,
                                   Long expectedRecordsWritten,
                                   Integer expectedAttemptCount) {
        assertThat(rows)
                .filteredOn(row -> row.getObjectOrdinal() == objectOrdinal)
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.getObjectState()).isEqualTo(expectedState.name());
                    assertThat(row.getRecordsRead()).isEqualTo(expectedRecordsRead);
                    assertThat(row.getRecordsWritten()).isEqualTo(expectedRecordsWritten);
                    assertThat(row.getAttemptCount()).isEqualTo(expectedAttemptCount);
                });
    }

    private SyncExecution execution(SyncExecutionState state) {
        SyncExecution execution = new SyncExecution();
        execution.setId(88L);
        execution.setTenantId(7L);
        execution.setProjectId(101L);
        execution.setWorkspaceId(301L);
        execution.setSyncTaskId(11L);
        execution.setExecutionNo(3L);
        execution.setExecutionState(state.name());
        execution.setTriggerType(SyncTriggerType.MANUAL.name());
        execution.setExecutorId("worker-1");
        execution.setLeaseExpireTime(LocalDateTime.now().plusMinutes(2));
        execution.setTriggeredBy(1001L);
        return execution;
    }

    private SyncTask task(String currentState) {
        SyncTask task = new SyncTask();
        task.setId(11L);
        task.setTenantId(7L);
        task.setProjectId(101L);
        task.setWorkspaceId(301L);
        task.setTemplateId(22L);
        task.setCurrentState(currentState);
        return task;
    }

    private SyncTemplate objectListTemplate() {
        SyncTemplate template = new SyncTemplate();
        template.setId(22L);
        template.setTenantId(7L);
        template.setProjectId(101L);
        template.setWorkspaceId(301L);
        template.setSourceDatasourceId(10001L);
        template.setTargetDatasourceId(10002L);
        template.setSourceConnectorType("MYSQL");
        template.setTargetConnectorType("POSTGRESQL");
        template.setSourceSchemaName("ods");
        template.setTargetSchemaName("dwd");
        template.setSyncMode("FULL");
        template.setSyncScopeType("OBJECT_LIST");
        template.setWriteStrategy("UPSERT");
        template.setPrimaryKeyField("id");
        template.setFieldMappingConfig("""
                [
                  {"sourceField":"id","targetField":"id"},
                  {"sourceField":"name","targetField":"name"}
                ]
                """);
        template.setObjectMappingConfig("""
                {
                  "mappings": [
                    {"sourceObject": "orders", "targetObject": "dwd_orders"},
                    {"sourceObject": "customers", "targetObject": "dwd_customers"}
                  ]
                }
                """);
        template.setEnabled(true);
        return template;
    }

    private SyncWorkerExecutionPlanView workerPlan() {
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
                "FULL",
                "OFFLINE",
                "DATAX_STYLE_OFFLINE_READER_WRITER_RUNNER",
                "SINGLE_OBJECT",
                true,
                false,
                false,
                2,
                false,
                true,
                true,
                true,
                "UPSERT",
                false,
                true,
                false,
                true,
                "SNAPSHOT_BOUNDED",
                false,
                "SEGMENT_RETRY",
                true,
                false,
                false,
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
        return new SyncActorContext(7L, 1001L, "SERVICE_ACCOUNT", "trace-object-list-retry-e2e",
                "PROJECT", "project_id IN ${actorProjectIds}", List.of(101L), false);
    }

    private DatasourceRunOnceResponse completeResponse(Long recordsRead, Long recordsWritten) {
        DatasourceRunOnceResponse response = baseResponse(recordsRead, recordsWritten);
        response.setRunStatus("SOURCE_EXHAUSTED_COMPLETE_REQUIRED");
        response.setEndOfSource(true);
        response.setCompleteCallbackRecommended(true);
        response.setProgressCallbackRecommended(false);
        response.setFailed(false);
        response.setFailCallbackRecommended(false);
        return response;
    }

    private DatasourceRunOnceResponse failedResponse() {
        DatasourceRunOnceResponse response = baseResponse(0L, 0L);
        response.setRunStatus("REMOTE_OBJECT_BATCH_FAILED");
        response.setTotalFailedRecordCount(1L);
        response.setBatchFailedRecordCount(1L);
        response.setEndOfSource(true);
        response.setCompleteCallbackRecommended(false);
        response.setProgressCallbackRecommended(false);
        response.setFailed(true);
        response.setFailCallbackRecommended(true);
        return response;
    }

    private DatasourceRunOnceResponse baseResponse(Long recordsRead, Long recordsWritten) {
        DatasourceRunOnceResponse response = new DatasourceRunOnceResponse();
        response.setTaskId(11L);
        response.setExecutionId(88L);
        response.setBatchRecordsRead(recordsRead);
        response.setBatchRecordsWritten(recordsWritten);
        response.setBatchFailedRecordCount(0L);
        response.setTotalRecordsRead(recordsRead);
        response.setTotalRecordsWritten(recordsWritten);
        response.setTotalFailedRecordCount(0L);
        response.setCheckpointCandidateProduced(false);
        response.setPayloadPolicy("LOW_SENSITIVE_RUN_ONCE_RESULT_NO_ROWS_NO_SQL_NO_CREDENTIALS_NO_CHECKPOINT_VALUE");
        return response;
    }

    /**
     * datasource-management run-once 的内存替身。
     *
     * <p>它按调用顺序返回预设结果，同时记录每次真正被派发的源对象定位。第二次 worker 重入后，
     * 如果 fan-out 错误地重跑了已经成功的 orders，这里的对象定位序列会立即暴露问题。</p>
     */
    private static class FakeDatasourceRunOnceClient implements DatasourceRunOnceClient {

        private final List<DatasourceRunOnceResponse> responses;
        private final List<String> sourceObjectLocators = new ArrayList<>();
        private int calls;

        private FakeDatasourceRunOnceClient(DatasourceRunOnceResponse... responses) {
            this.responses = Arrays.asList(responses);
        }

        @Override
        public DatasourceRunOnceResponse runOnce(DatasourceRunOnceRequest request, SyncActorContext actorContext) {
            calls++;
            sourceObjectLocators.add(request.getExecutionPlan().getReadPlan().getObjectLocator());
            int responseIndex = Math.min(calls - 1, responses.size() - 1);
            return responses.get(responseIndex);
        }

        private List<String> sourceObjectLocators() {
            return sourceObjectLocators;
        }
    }

    /**
     * 对象账本 mapper 夹具。
     *
     * <p>这里不用真实数据库，是为了让测试聚焦业务状态流转。Mockito 返回的是同一批对象引用，
     * 因此 lifecycle support 对 row 的状态修改会自然反映到 rows 列表中，足以模拟对象账本的核心行为。</p>
     */
    private record ObjectExecutionMapperFixture(SyncObjectExecutionMapper mapper, List<SyncObjectExecution> rows) {
    }
}
