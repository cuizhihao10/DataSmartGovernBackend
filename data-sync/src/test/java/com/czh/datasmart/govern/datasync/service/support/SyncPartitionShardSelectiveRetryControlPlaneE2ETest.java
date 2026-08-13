/**
 * @Author : Cui
 * @Date: 2026/07/08 00:06
 * @Description DataSmart Govern Backend - SyncPartitionShardSelectiveRetryControlPlaneE2ETest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.datasync.config.DataSyncDatasourceRunOnceProperties;
import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExecutionCompleteRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExecutionFailRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExecutionPartialSuccessRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncObjectRetryRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncObjectRetryResult;
import com.czh.datasmart.govern.datasync.controller.dto.SyncWorkerExecutionPlanView;
import com.czh.datasmart.govern.datasync.entity.SyncExecution;
import com.czh.datasmart.govern.datasync.entity.SyncObjectExecution;
import com.czh.datasmart.govern.datasync.entity.SyncTask;
import com.czh.datasmart.govern.datasync.entity.SyncTaskDefinition;
import com.czh.datasmart.govern.datasync.integration.datasource.partition.DatasourcePartitionRangeProbeClient;
import com.czh.datasmart.govern.datasync.integration.datasource.partition.DatasourcePartitionRangeProbeResponse;
import com.czh.datasmart.govern.datasync.integration.datasource.partition.DatasourcePartitionRangeProbeTransportUnavailableException;
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
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 单表 ID_RANGE 分片失败选择性重试控制面 E2E 测试。
 *
 * <p>这个测试覆盖的是用户关心的“大数据量离线任务是否会拆成多个小任务；某个小任务失败后，是否不会回退整个大任务，
 * 而是后续只重传失败分片”的闭环。测试不连接真实数据库，而是用 fake datasource-management run-once client
 * 模拟两个分片：第一个分片成功，第二个分片第一次失败、重试后成功。</p>
 *
 * <p>由于生产实现使用有界并发，本测试的 fake client 不依赖调用顺序，而是按 {@code shardOrPartition} 返回结果。
 * 这样即使两个分片并发调度，测试仍能稳定验证业务语义：成功分片只执行一次，失败分片重置后只执行失败分片。</p>
 */
class SyncPartitionShardSelectiveRetryControlPlaneE2ETest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 证明分片前的 AUTO_SPLIT_PK 传输故障可以进入正常的受治理恢复循环。
     *
     * <p>第一次探测在分片边界存在之前失败。派发器仍必须持久化一个 FAILED 范围探测工作单元，并将
     * 父失败标记为可重试。既有的失败对象重试服务随后会重新入队同一个 execution。第二次 worker
     * 领取时探测成功，临时标记被协调删除，创建真实分片行，父 execution 完成且不会生成重复的账本行。</p>
     */
    @Test
    void autoSplitProbeTransportFailureShouldRequeueAndReconcileIntoSuccessfulShardLedger() {
        AtomicInteger probeCalls = new AtomicInteger();
        DatasourcePartitionRangeProbeClient rangeProbeClient = (request, actorContext) -> {
            if (probeCalls.incrementAndGet() == 1) {
                throw new DatasourcePartitionRangeProbeTransportUnavailableException(request.getDatasourceId());
            }
            DatasourcePartitionRangeProbeResponse response = new DatasourcePartitionRangeProbeResponse();
            response.setProbeStatus("RANGE_PROBED");
            response.setNumericRange(true);
            response.setMinValue(1L);
            response.setMaxValue(10L);
            response.setRowCount(10L);
            response.setWarnings(List.of());
            return response;
        };
        FakeDatasourceRunOnceClient datasourceClient = new FakeDatasourceRunOnceClient();
        ObjectExecutionMapperFixture objectFixture = objectExecutionMapperFixture();
        SyncExecutionLifecycleSupport lifecycleSupport = mock(SyncExecutionLifecycleSupport.class);
        DataSyncTaskManagementReceiptPublisher receiptPublisher = mock(DataSyncTaskManagementReceiptPublisher.class);
        SyncOfflineRunnerDispatchService dispatchService = dispatchService(
                datasourceClient, objectFixture.mapper(), lifecycleSupport, receiptPublisher, rangeProbeClient);
        SyncObjectExecutionOperationSupport retrySupport = retrySupport(objectFixture.mapper());
        SyncExecution execution = execution(SyncExecutionState.RUNNING);
        SyncTask task = task(SyncTaskState.RUNNING.name());
        SyncTaskDefinition definition = autoSplitSingleObjectDefinition();

        SyncOfflineRunnerDispatchResult failedResult =
                dispatchService.dispatchOffline(execution, task, definition, workerPlan(), actor());

        assertThat(failedResult.failed()).isTrue();
        assertThat(failedResult.issueCodes())
                .contains("DATASOURCE_PARTITION_RANGE_PROBE_TRANSPORT_UNAVAILABLE");
        assertThat(objectFixture.rows()).singleElement().satisfies(row -> {
            assertThat(row.getWorkUnitType())
                    .isEqualTo(SyncObjectExecutionLifecycleSupport.WORK_UNIT_TYPE_PARTITION_RANGE_PROBE);
            assertThat(row.getObjectState()).isEqualTo(SyncObjectExecutionState.FAILED.name());
            assertThat(row.getLastErrorCode())
                    .isEqualTo("DATASOURCE_PARTITION_RANGE_PROBE_TRANSPORT_UNAVAILABLE");
        });
        ArgumentCaptor<SyncExecutionFailRequest> failRequest =
                ArgumentCaptor.forClass(SyncExecutionFailRequest.class);
        verify(lifecycleSupport).failExecution(eq(task), eq(execution), failRequest.capture(), eq(actor()));
        assertThat(failRequest.getValue().getRetryable()).isTrue();

        execution.setExecutionState(SyncExecutionState.FAILED.name());
        task.setCurrentState(SyncTaskState.FAILED.name());
        SyncObjectRetryResult retry = retrySupport.retryFailedObjects(task, execution, null, actor());
        assertThat(retry.retryObjectCount()).isEqualTo(1);
        assertThat(objectFixture.rows()).singleElement()
                .extracting(SyncObjectExecution::getObjectState)
                .isEqualTo(SyncObjectExecutionState.PENDING.name());

        execution.setExecutionState(SyncExecutionState.RUNNING.name());
        execution.setLeaseExpireTime(execution.getLeaseExpireTime().plusMinutes(5));
        task.setCurrentState(SyncTaskState.RETRYING.name());
        SyncOfflineRunnerDispatchResult recovered =
                dispatchService.dispatchOffline(execution, task, definition, workerPlan(), actor());

        assertThat(recovered.completed()).isTrue();
        assertThat(recovered.failed()).isFalse();
        assertThat(probeCalls).hasValue(2);
        assertThat(objectFixture.rows()).hasSize(1);
        assertThat(objectFixture.rows()).allSatisfy(row -> {
            assertThat(row.getWorkUnitType())
                    .isNotEqualTo(SyncObjectExecutionLifecycleSupport.WORK_UNIT_TYPE_PARTITION_RANGE_PROBE);
            assertThat(row.getObjectState()).isEqualTo(SyncObjectExecutionState.SUCCEEDED.name());
            assertThat(row.getObjectOrdinal()).isZero();
        });
        verify(lifecycleSupport).completeExecution(eq(task), eq(execution), any(), eq(actor()));
    }

    /** 业务或 HTTP 拒绝不是瞬态传输中断，必须排除在 Autopilot 重试之外。 */
    @Test
    void autoSplitProbeBusinessFailureShouldNotCreateRetryableWorkUnit() {
        DatasourcePartitionRangeProbeClient rejectedProbe = (request, actorContext) -> {
            throw new IllegalStateException("controlled range-probe contract rejection");
        };
        ObjectExecutionMapperFixture objectFixture = objectExecutionMapperFixture();
        SyncExecutionLifecycleSupport lifecycleSupport = mock(SyncExecutionLifecycleSupport.class);
        SyncOfflineRunnerDispatchService dispatchService = dispatchService(
                new FakeDatasourceRunOnceClient(),
                objectFixture.mapper(),
                lifecycleSupport,
                mock(DataSyncTaskManagementReceiptPublisher.class),
                rejectedProbe);
        SyncExecution execution = execution(SyncExecutionState.RUNNING);
        SyncTask task = task(SyncTaskState.RUNNING.name());

        SyncOfflineRunnerDispatchResult result = dispatchService.dispatchOffline(
                execution, task, autoSplitSingleObjectDefinition(), workerPlan(), actor());

        assertThat(result.failed()).isTrue();
        assertThat(result.issueCodes()).contains("PARTITION_SHARD_CONTRACT_BLOCKED");
        assertThat(objectFixture.rows()).isEmpty();
        ArgumentCaptor<SyncExecutionFailRequest> failure =
                ArgumentCaptor.forClass(SyncExecutionFailRequest.class);
        verify(lifecycleSupport).failExecution(eq(task), eq(execution), failure.capture(), eq(actor()));
        assertThat(failure.getValue().getRetryable()).isFalse();
        assertThat(failure.getValue().getErrorCode()).isEqualTo("PARTITION_SHARD_CONTRACT_BLOCKED");
    }

    @Test
    void partitionShardShouldRetryOnlyFailedShardAndSkipSucceededShard() {
        FakeDatasourceRunOnceClient datasourceClient = new FakeDatasourceRunOnceClient();
        ObjectExecutionMapperFixture objectMapperFixture = objectExecutionMapperFixture();
        SyncExecutionLifecycleSupport lifecycleSupport = mock(SyncExecutionLifecycleSupport.class);
        DataSyncTaskManagementReceiptPublisher receiptPublisher = mock(DataSyncTaskManagementReceiptPublisher.class);
        SyncOfflineRunnerDispatchService dispatchService =
                dispatchService(datasourceClient, objectMapperFixture.mapper(), lifecycleSupport, receiptPublisher);
        SyncObjectExecutionOperationSupport retrySupport = retrySupport(objectMapperFixture.mapper());
        SyncExecution execution = execution(SyncExecutionState.RUNNING);
        SyncTask task = task("RUNNING");
        SyncTaskDefinition definition = partitionedSingleObjectDefinition();

        SyncOfflineRunnerDispatchResult firstResult =
                dispatchService.dispatchOffline(execution, task, definition, workerPlan(), actor());

        assertThat(firstResult.dispatched()).isTrue();
        assertThat(firstResult.completed()).isFalse();
        assertThat(firstResult.failed()).isFalse();
        assertThat(firstResult.dispatchStatus()).isEqualTo("PARTITION_SHARD_FAN_OUT_PARTIALLY_SUCCEEDED");
        assertThat(firstResult.remoteRunStatus()).isEqualTo("PARTITION_SHARD_PARTIALLY_SUCCEEDED_RETRY_FAILED_SHARDS");
        assertThat(datasourceClient.callCount("id-range-0000")).isEqualTo(1);
        assertThat(datasourceClient.callCount("id-range-0001")).isEqualTo(1);
        assertShardState(objectMapperFixture.rows(), 0, SyncObjectExecutionState.SUCCEEDED, "id-range-0000", 5L, 5L, 1);
        assertShardState(objectMapperFixture.rows(), 1, SyncObjectExecutionState.FAILED, "id-range-0001", 0L, 0L, 1);
        assertThat(objectMapperFixture.rows())
                .allSatisfy(row -> assertThat(row.getWorkUnitType())
                        .isEqualTo(SyncObjectExecutionLifecycleSupport.WORK_UNIT_TYPE_PARTITION_SHARD));

        DatasourceRunOnceRequest firstShardRequest = datasourceClient.firstRequestFor("id-range-0000");
        assertThat(firstShardRequest.getExecutionPlan().getReadPlan().getPartitionConfigured()).isTrue();
        assertThat(firstShardRequest.getExecutionPlan().getReadPlan().getRequiredWorkerCapabilities())
                .contains("PARTITION_AWARE_READ");
        assertThat(firstShardRequest.getExecutionPlan().getReadPlan().getFilterConditions())
                .extracting(DatasourceRunOnceRequest.ReadFilterCondition::getColumn)
                .containsExactly("status", "id", "id");
        assertThat(firstShardRequest.getExecutionPlan().getReadPlan().getFilterConditions())
                .extracting(DatasourceRunOnceRequest.ReadFilterCondition::getOperator)
                .containsExactly("EQ", "GTE", "LT");

        ArgumentCaptor<SyncExecutionPartialSuccessRequest> partialCaptor =
                ArgumentCaptor.forClass(SyncExecutionPartialSuccessRequest.class);
        verify(lifecycleSupport).partiallySucceedExecution(eq(task), eq(execution),
                partialCaptor.capture(), any(SyncActorContext.class));
        assertThat(partialCaptor.getValue().getRecordsRead()).isEqualTo(5L);
        assertThat(partialCaptor.getValue().getRecordsWritten()).isEqualTo(5L);
        assertThat(partialCaptor.getValue().getFailedRecordCount()).isEqualTo(1L);

        execution.setExecutionState(SyncExecutionState.PARTIALLY_SUCCEEDED.name());
        task.setCurrentState(SyncTaskState.PARTIALLY_SUCCEEDED.name());
        SyncObjectRetryRequest retryRequest = new SyncObjectRetryRequest();
        retryRequest.setObjectOrdinals(List.of(1));
        retryRequest.setRetryAttemptBudget(3);
        retryRequest.setResetAttemptCount(true);
        retryRequest.setReason("重试第二个 ID_RANGE 分片，验证成功分片不会重跑");

        SyncObjectRetryResult retryResult = retrySupport.retryFailedObjects(task, execution, retryRequest, actor());

        assertThat(retryResult.retryObjectCount()).isEqualTo(1);
        assertThat(retryResult.executionState()).isEqualTo(SyncExecutionState.QUEUED.name());
        assertShardState(objectMapperFixture.rows(), 0, SyncObjectExecutionState.SUCCEEDED, "id-range-0000", 5L, 5L, 1);
        assertShardState(objectMapperFixture.rows(), 1, SyncObjectExecutionState.PENDING, "id-range-0001", 0L, 0L, 0);

        execution.setExecutionState(SyncExecutionState.RUNNING.name());
        task.setCurrentState(SyncTaskState.RETRYING.name());
        SyncOfflineRunnerDispatchResult retryDispatchResult =
                dispatchService.dispatchOffline(execution, task, definition, workerPlan(), actor());

        assertThat(retryDispatchResult.dispatched()).isTrue();
        assertThat(retryDispatchResult.completed()).isTrue();
        assertThat(retryDispatchResult.failed()).isFalse();
        assertThat(retryDispatchResult.dispatchStatus()).isEqualTo("PARTITION_SHARD_FAN_OUT_COMPLETED");
        assertThat(datasourceClient.callCount("id-range-0000")).isEqualTo(1);
        assertThat(datasourceClient.callCount("id-range-0001")).isEqualTo(2);
        assertShardState(objectMapperFixture.rows(), 0, SyncObjectExecutionState.SUCCEEDED, "id-range-0000", 5L, 5L, 1);
        assertShardState(objectMapperFixture.rows(), 1, SyncObjectExecutionState.SUCCEEDED, "id-range-0001", 6L, 6L, 1);

        ArgumentCaptor<SyncExecutionCompleteRequest> completeCaptor =
                ArgumentCaptor.forClass(SyncExecutionCompleteRequest.class);
        verify(lifecycleSupport).completeExecution(eq(task), eq(execution),
                completeCaptor.capture(), any(SyncActorContext.class));
        assertThat(completeCaptor.getValue().getRecordsRead()).isEqualTo(11L);
        assertThat(completeCaptor.getValue().getRecordsWritten()).isEqualTo(11L);
        verify(lifecycleSupport, never()).failExecution(any(), any(), any(), any());
    }

    /**
     * 验证同一父 execution 经过选择性重试后，再次失败不会复用上一运行代次的 FAIL 幂等键。
     *
     * <p>真实问题发生在“第一次所有分片失败 → 用户修复后选择性重试 → 第二次仍存在脏数据”的场景。
     * 选择性重试为了复用分片账本不会创建新 executionId；如果第二次 FAIL 仍使用第一次的幂等键，
     * 生命周期层会把它识别成旧回调重放，父 execution 将长期残留在 RUNNING。本测试让两个分片在两轮
     * 执行中都稳定失败，并模拟 worker 重新 claim 时刷新 leaseExpireTime，断言两次终态键必须不同。</p>
     */
    @Test
    void partitionShardRetryGenerationShouldUseDistinctFailureIdempotencyKey() {
        DatasourceRunOnceClient datasourceClient = (request, actorContext) -> failedResponse();
        ObjectExecutionMapperFixture objectMapperFixture = objectExecutionMapperFixture();
        SyncExecutionLifecycleSupport lifecycleSupport = mock(SyncExecutionLifecycleSupport.class);
        DataSyncTaskManagementReceiptPublisher receiptPublisher = mock(DataSyncTaskManagementReceiptPublisher.class);
        SyncOfflineRunnerDispatchService dispatchService =
                dispatchService(datasourceClient, objectMapperFixture.mapper(), lifecycleSupport, receiptPublisher);
        SyncObjectExecutionOperationSupport retrySupport = retrySupport(objectMapperFixture.mapper());
        SyncExecution execution = execution(SyncExecutionState.RUNNING);
        SyncTask task = task(SyncTaskState.RUNNING.name());
        SyncTaskDefinition definition = partitionedSingleObjectDefinition();

        SyncOfflineRunnerDispatchResult firstResult =
                dispatchService.dispatchOffline(execution, task, definition, workerPlan(), actor());
        assertThat(firstResult.failed()).isTrue();
        assertThat(objectMapperFixture.rows())
                .allSatisfy(row -> assertThat(row.getObjectState()).isEqualTo(SyncObjectExecutionState.FAILED.name()));

        execution.setExecutionState(SyncExecutionState.FAILED.name());
        task.setCurrentState(SyncTaskState.FAILED.name());
        SyncObjectRetryRequest retryRequest = new SyncObjectRetryRequest();
        retryRequest.setRetryAttemptBudget(1);
        retryRequest.setResetAttemptCount(true);
        retryRequest.setReason("retry failed partition shards in a new worker lease generation");
        retrySupport.retryFailedObjects(task, execution, retryRequest, actor());

        execution.setExecutionState(SyncExecutionState.RUNNING.name());
        execution.setLeaseExpireTime(execution.getLeaseExpireTime().plusMinutes(5));
        task.setCurrentState(SyncTaskState.RETRYING.name());
        SyncOfflineRunnerDispatchResult retryResult =
                dispatchService.dispatchOffline(execution, task, definition, workerPlan(), actor());
        assertThat(retryResult.failed()).isTrue();

        ArgumentCaptor<SyncExecutionFailRequest> failCaptor =
                ArgumentCaptor.forClass(SyncExecutionFailRequest.class);
        verify(lifecycleSupport, times(2)).failExecution(eq(task), eq(execution),
                failCaptor.capture(), any(SyncActorContext.class));
        assertThat(failCaptor.getAllValues())
                .extracting(SyncExecutionFailRequest::getIdempotencyKey)
                .doesNotHaveDuplicates();
    }

    private SyncOfflineRunnerDispatchService dispatchService(DatasourceRunOnceClient datasourceClient,
                                                             SyncObjectExecutionMapper objectExecutionMapper,
                                                             SyncExecutionLifecycleSupport lifecycleSupport,
                                                             DataSyncTaskManagementReceiptPublisher receiptPublisher) {
        return dispatchService(datasourceClient, objectExecutionMapper, lifecycleSupport, receiptPublisher, null);
    }

    /** 构建生产派发链，并可选地接入 AUTO_SPLIT_PK 范围探测边界。 */
    private SyncOfflineRunnerDispatchService dispatchService(DatasourceRunOnceClient datasourceClient,
                                                             SyncObjectExecutionMapper objectExecutionMapper,
                                                             SyncExecutionLifecycleSupport lifecycleSupport,
                                                             DataSyncTaskManagementReceiptPublisher receiptPublisher,
                                                             DatasourcePartitionRangeProbeClient rangeProbeClient) {
        SyncBatchRunnerBridgePlanSupport bridgePlanSupport = bridgePlanSupport();
        SyncBatchRunOnceDispatchService runOnceDispatchService = new SyncBatchRunOnceDispatchService(
                bridgePlanSupport,
                datasourceClient,
                runOnceProperties(),
                lifecycleSupport,
                receiptPublisher,
                mock(SyncExecutionLogSupport.class));
        SyncObjectListFanOutDispatchService objectListFanOutDispatchService =
                mock(SyncObjectListFanOutDispatchService.class);
        SyncPartitionShardFanOutDispatchService partitionShardFanOutDispatchService =
                new SyncPartitionShardFanOutDispatchService(
                        new SyncPartitionShardExecutionContractSupport(objectMapper),
                        new SyncObjectExecutionLifecycleSupport(objectExecutionMapper),
                        bridgePlanSupport,
                        runOnceDispatchService,
                        rangeProbeClient,
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
                auditSupport,
                mock(SyncCallbackIdempotencySupport.class),
                new com.fasterxml.jackson.databind.ObjectMapper());
    }

    private SyncBatchRunnerBridgePlanSupport bridgePlanSupport() {
        return new SyncBatchRunnerBridgePlanSupport(
                new SyncFieldMappingExecutionContractSupport(objectMapper),
                new SyncFilterExecutionContractSupport(objectMapper),
                new SyncTaskDefinitionScopeContractSupport(objectMapper),
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
        List<SyncObjectExecution> rows = Collections.synchronizedList(new ArrayList<>());
        when(mapper.selectByExecutionId(eq(88L))).thenAnswer(invocation -> new ArrayList<>(rows));
        when(mapper.insert(any(SyncObjectExecution.class))).thenAnswer(invocation -> {
            SyncObjectExecution row = invocation.getArgument(0);
            row.setId((long) rows.size() + 1L);
            rows.add(row);
            return 1;
        });
        when(mapper.updateById(any(SyncObjectExecution.class))).thenReturn(1);
        when(mapper.deleteById(any(Long.class))).thenAnswer(invocation -> {
            Long id = invocation.getArgument(0);
            return rows.removeIf(row -> id.equals(row.getId())) ? 1 : 0;
        });
        return new ObjectExecutionMapperFixture(mapper, rows);
    }

    private void assertShardState(List<SyncObjectExecution> rows,
                                  int objectOrdinal,
                                  SyncObjectExecutionState expectedState,
                                  String expectedShard,
                                  Long expectedRecordsRead,
                                  Long expectedRecordsWritten,
                                  Integer expectedAttemptCount) {
        assertThat(rows)
                .filteredOn(row -> row.getObjectOrdinal() == objectOrdinal)
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.getObjectState()).isEqualTo(expectedState.name());
                    assertThat(row.getShardOrPartition()).isEqualTo(expectedShard);
                    assertThat(row.getPartitionStrategy()).isEqualTo("ID_RANGE");
                    assertThat(row.getPartitionField()).isEqualTo("id");
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
        task.setCurrentState(currentState);
        return task;
    }

    private SyncTaskDefinition partitionedSingleObjectDefinition() {
        SyncTaskDefinition definition = new SyncTaskDefinition();
        definition.setId(22L);
        definition.setTenantId(7L);
        definition.setProjectId(101L);
        definition.setWorkspaceId(301L);
        definition.setSourceDatasourceId(10001L);
        definition.setTargetDatasourceId(10002L);
        definition.setSourceConnectorType("MYSQL");
        definition.setTargetConnectorType("POSTGRESQL");
        definition.setSourceSchemaName("ods");
        definition.setSourceObjectName("huge_customer");
        definition.setTargetSchemaName("dwd");
        definition.setTargetObjectName("huge_customer");
        definition.setSyncMode("FULL");
        definition.setSyncScopeType("SINGLE_OBJECT");
        definition.setWriteStrategy("UPSERT");
        definition.setPrimaryKeyField("id");
        definition.setFieldMappingConfig("""
                [
                  {"sourceField":"id","targetField":"id"},
                  {"sourceField":"name","targetField":"name"},
                  {"sourceField":"status","targetField":"status"}
                ]
                """);
        definition.setFilterConfig("""
                [
                  {"field":"status","operator":"=","value":"ACTIVE"}
                ]
                """);
        definition.setPartitionConfig("""
                {
                  "strategy": "ID_RANGE",
                  "partitionField": "id",
                  "maxParallelism": 2,
                  "maxShardAttempts": 1,
                  "ranges": [
                    {"startInclusive": 1, "endExclusive": 100},
                    {"startInclusive": 100, "endExclusive": 200}
                  ]
                }
                """);
        definition.setEnabled(true);
        return definition;
    }

    /** 复用有效的单对象定义，同时允许平台依据 splitPk 推导范围。 */
    private SyncTaskDefinition autoSplitSingleObjectDefinition() {
        SyncTaskDefinition definition = partitionedSingleObjectDefinition();
        definition.setPartitionConfig("""
                {
                  "strategy": "AUTO_SPLIT_PK",
                  "splitPk": "id",
                  "maxParallelism": 2,
                  "maxShardAttempts": 3
                }
                """);
        return definition;
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
                1,
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
                true,
                true,
                false,
                false,
                List.of(),
                List.of("CLAIM_ALREADY_MARKED_RUNNING_DO_NOT_CALL_START"),
                List.of("PARTITION_PARALLELISM_DECLARED"),
                List.of(),
                "LOW_SENSITIVE_WORKER_PLAN_METADATA_ONLY");
    }

    private SyncActorContext actor() {
        return new SyncActorContext(7L, 1001L, "SERVICE_ACCOUNT", "trace-partition-shard-retry-e2e",
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
        response.setRunStatus("REMOTE_PARTITION_SHARD_FAILED");
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

    private class FakeDatasourceRunOnceClient implements DatasourceRunOnceClient {

        private final ConcurrentHashMap<String, AtomicInteger> callCounts = new ConcurrentHashMap<>();
        private final CopyOnWriteArrayList<DatasourceRunOnceRequest> requests = new CopyOnWriteArrayList<>();

        @Override
        public DatasourceRunOnceResponse runOnce(DatasourceRunOnceRequest request, SyncActorContext actorContext) {
            requests.add(request);
            String shard = request.getShardOrPartition();
            int attempt = callCounts.computeIfAbsent(shard, ignored -> new AtomicInteger()).incrementAndGet();
            if ("id-range-0000".equals(shard)) {
                return completeResponse(5L, 5L);
            }
            if ("id-range-0001".equals(shard) && attempt == 1) {
                return failedResponse();
            }
            return completeResponse(6L, 6L);
        }

        private int callCount(String shardOrPartition) {
            AtomicInteger count = callCounts.get(shardOrPartition);
            return count == null ? 0 : count.get();
        }

        private DatasourceRunOnceRequest firstRequestFor(String shardOrPartition) {
            return requests.stream()
                    .filter(request -> shardOrPartition.equals(request.getShardOrPartition()))
                    .findFirst()
                    .orElseThrow();
        }
    }

    private record ObjectExecutionMapperFixture(SyncObjectExecutionMapper mapper, List<SyncObjectExecution> rows) {
    }
}
