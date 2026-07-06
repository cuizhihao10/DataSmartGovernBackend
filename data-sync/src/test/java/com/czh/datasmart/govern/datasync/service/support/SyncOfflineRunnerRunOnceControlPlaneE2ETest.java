/**
 * @Author : Cui
 * @Date: 2026/07/07 00:12
 * @Description DataSmart Govern Backend - SyncOfflineRunnerRunOnceControlPlaneE2ETest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.datasync.config.DataSyncDatasourceRunOnceProperties;
import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExecutionCompleteRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncWorkerExecutionPlanView;
import com.czh.datasmart.govern.datasync.entity.SyncExecution;
import com.czh.datasmart.govern.datasync.entity.SyncTask;
import com.czh.datasmart.govern.datasync.entity.SyncTemplate;
import com.czh.datasmart.govern.datasync.integration.datasource.runonce.DatasourceRunOnceClient;
import com.czh.datasmart.govern.datasync.integration.datasource.runonce.DatasourceRunOnceRequest;
import com.czh.datasmart.govern.datasync.integration.datasource.runonce.DatasourceRunOnceResponse;
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
import static org.mockito.Mockito.verify;

/**
 * data-sync 离线 Runner 到 datasource-management run-once 的控制面闭环测试。
 *
 * <p>这个测试刻意放在 {@code service.support} 包内，而不是通过 Controller 或真实 HTTP 启动 Spring 容器，
 * 是因为本阶段要守住的是“控制面业务链路”的确定性：worker 已经 claim 到 execution 后，data-sync 是否能
 * 正确生成离线 Runner 合同、选择最小 run-once 路径、把模板中的表定位/字段映射/过滤条件转换为内部执行请求、
 * 按 datasource-management 的批次响应推进 offset，并最终回写 complete 与发布 task-management 回执。</p>
 *
 * <p>它和 datasource-management 模块里的 JDBC E2E 是互补关系：</p>
 * <p>1. datasource-management JDBC E2E 证明 Java Reader/Writer 真的能读写测试库；</p>
 * <p>2. 本测试证明 data-sync 控制面真的能把任务计划派发给执行面，并根据执行面响应关闭状态机；</p>
 * <p>3. 两者组合起来，才接近“从配置计划到真实数据搬运再到任务终态”的商业化闭环证据。</p>
 *
 * <p>安全边界说明：测试中的 {@link FakeDatasourceRunOnceClient} 只模拟 datasource-management internal API，
 * 不返回 SQL、连接串、凭据、样本行或 checkpoint 原始值。断言也重点确认这些敏感内容不会出现在跨服务请求中，
 * 因为真实生产环境的普通日志、指标、回执和 API 响应都不应该暴露这些数据。</p>
 */
class SyncOfflineRunnerRunOnceControlPlaneE2ETest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 验证 FULL 单对象离线同步的最小控制面闭环。
     *
     * <p>业务场景：用户创建了一个从 MySQL 源表 {@code ods.customer} 到 PostgreSQL 目标表
     * {@code dwd.customer_clean} 的离线全量同步任务，只同步 {@code region='EAST'} 的记录，
     * 并把源字段 {@code customer_name} 写入目标字段 {@code name}。worker claim 任务后，
     * data-sync 应该走最小 DataX-style run-once 路径，而不是误判为需要审批、专用 Runner 或 CDC 通道。</p>
     *
     * <p>执行语义：fake datasource 第一次返回“本批写入成功但还有后续批次”，第二次返回“源端已读完，可以 complete”。
     * data-sync 需要把第一批的累计计数作为第二次请求的 {@code previousRecordsRead/previousRecordsWritten}，
     * 这就是当前 run-once 多批次闭环中最小的可恢复进度交接方式。</p>
     */
    @Test
    void fullSingleObjectOfflineRunnerShouldDispatchRunOnceLoopAndCompleteExecution() {
        FakeDatasourceRunOnceClient datasourceClient = new FakeDatasourceRunOnceClient(
                moreRemainResponse(2L, 2L),
                completeResponse(3L, 3L)
        );
        SyncExecutionLifecycleSupport lifecycleSupport = mock(SyncExecutionLifecycleSupport.class);
        DataSyncTaskManagementReceiptPublisher receiptPublisher = mock(DataSyncTaskManagementReceiptPublisher.class);
        SyncOfflineRunnerDispatchService service = service(datasourceClient, lifecycleSupport, receiptPublisher);
        SyncExecution execution = execution();
        SyncTask task = task();
        SyncTemplate template = fullCustomerTemplate();

        SyncOfflineRunnerDispatchResult result =
                service.dispatchOffline(execution, task, template, workerPlan(), actor());

        assertThat(result.dispatched()).isTrue();
        assertThat(result.completed()).isTrue();
        assertThat(result.failed()).isFalse();
        assertThat(result.dispatchStatus()).isEqualTo("DISPATCHED_AND_COMPLETED");
        assertThat(result.remoteRunStatus()).isEqualTo("SOURCE_EXHAUSTED_COMPLETE_REQUIRED");
        assertThat(result.runnerContractStatus()).isEqualTo("MINIMAL_BRIDGE_END_TO_END_SUPPORTED");
        assertThat(result.issueCodes()).isEmpty();

        assertThat(datasourceClient.calls()).isEqualTo(2);
        assertThat(datasourceClient.snapshots())
                .extracting(RunOnceRequestSnapshot::previousRecordsRead)
                .containsExactly(0L, 2L);
        assertThat(datasourceClient.snapshots())
                .extracting(RunOnceRequestSnapshot::previousRecordsWritten)
                .containsExactly(0L, 2L);

        RunOnceRequestSnapshot firstRequest = datasourceClient.snapshots().get(0);
        assertThat(firstRequest.taskId()).isEqualTo(task.getId());
        assertThat(firstRequest.executionId()).isEqualTo(execution.getId());
        assertThat(firstRequest.executionBoundary())
                .isEqualTo("DATA_SYNC_TO_DATASOURCE_RUN_ONCE_NO_RAW_SQL_NO_CREDENTIALS");
        assertThat(firstRequest.sourceObjectLocator()).isEqualTo("ods.customer");
        assertThat(firstRequest.targetObjectLocator()).isEqualTo("dwd.customer_clean");
        assertThat(firstRequest.readStrategy()).isEqualTo("FULL_OBJECT_SCAN");
        assertThat(firstRequest.writeStrategy()).isEqualTo("UPSERT");
        assertThat(firstRequest.conflictPolicy()).isEqualTo("UPDATE_ON_CONFLICT");
        assertThat(firstRequest.selectedColumns()).containsExactly("id", "customer_name", "amount", "region");
        assertThat(firstRequest.writeColumns()).containsExactly("id", "name", "amount", "region");
        assertThat(firstRequest.primaryKeyColumns()).containsExactly("id");
        assertThat(firstRequest.checkpointValue()).isNull();
        assertThat(firstRequest.checkpointType()).isEqualTo("NONE_OR_FINAL_WATERMARK");
        assertThat(firstRequest.checkpointValueVisibility())
                .isEqualTo("WORKER_INTERNAL_AND_SYNC_CHECKPOINT_TABLE_ONLY");
        assertThat(firstRequest.readCapabilities()).containsExactly("JDBC_BATCH_READ");
        assertThat(firstRequest.writeCapabilities()).containsExactly("JDBC_BATCH_WRITE", "IDEMPOTENT_CONFLICT_WRITE");
        assertThat(firstRequest.idempotencyScope()).isEqualTo("task:11:execution:88");
        assertThat(firstRequest.requiredCallbacks()).containsExactly("COMPLETE_OR_FAIL");
        assertThat(firstRequest.filters()).singleElement().satisfies(filter -> {
            assertThat(filter.column()).isEqualTo("region");
            assertThat(filter.operator()).isEqualTo("EQ");
            assertThat(filter.value()).isEqualTo("EAST");
            assertThat(filter.valueRequired()).isTrue();
        });

        ArgumentCaptor<SyncExecutionCompleteRequest> completeCaptor =
                ArgumentCaptor.forClass(SyncExecutionCompleteRequest.class);
        verify(lifecycleSupport).completeExecution(eq(task), eq(execution),
                completeCaptor.capture(), any(SyncActorContext.class));
        assertThat(completeCaptor.getValue().getExecutorId()).isEqualTo("worker-1");
        assertThat(completeCaptor.getValue().getRecordsRead()).isEqualTo(3L);
        assertThat(completeCaptor.getValue().getRecordsWritten()).isEqualTo(3L);
        assertThat(completeCaptor.getValue().getCheckpointRef()).isNull();
        assertThat(completeCaptor.getValue().getIdempotencyKey())
                .isEqualTo("datasource-run-once-complete-88");

        ArgumentCaptor<DatasourceRunOnceResponse> receiptResponseCaptor =
                ArgumentCaptor.forClass(DatasourceRunOnceResponse.class);
        verify(receiptPublisher).publishComplete(eq(task), eq(execution),
                any(SyncActorContext.class), receiptResponseCaptor.capture());
        assertThat(receiptResponseCaptor.getValue().getTotalRecordsRead()).isEqualTo(3L);
        assertThat(receiptResponseCaptor.getValue().getTotalRecordsWritten()).isEqualTo(3L);
        assertThat(receiptResponseCaptor.getValue().getPayloadPolicy())
                .isEqualTo("LOW_SENSITIVE_RUN_ONCE_REMOTE_RESULT_NO_ROWS_NO_SQL_NO_CREDENTIALS_NO_CHECKPOINT_VALUE");
        verify(lifecycleSupport, never()).failExecution(any(), any(), any(), any());
        verify(receiptPublisher, never()).publishFailed(any(), any(), any(), any(), any());
    }

    private SyncOfflineRunnerDispatchService service(FakeDatasourceRunOnceClient datasourceClient,
                                                     SyncExecutionLifecycleSupport lifecycleSupport,
                                                     DataSyncTaskManagementReceiptPublisher receiptPublisher) {
        SyncBatchRunnerBridgePlanSupport bridgePlanSupport = bridgePlanSupport();
        SyncBatchRunOnceDispatchService runOnceDispatchService = new SyncBatchRunOnceDispatchService(
                bridgePlanSupport,
                datasourceClient,
                runOnceProperties(),
                lifecycleSupport,
                receiptPublisher);
        /*
         * 本测试聚焦 SINGLE_OBJECT 最小 run-once 闭环，不验证 OBJECT_LIST fan-out。
         * 因此这里用 mock 固定让 object-list 门面返回 false，避免把对象级账本夹具也拉入本测试。
         * OBJECT_LIST 的账本、部分成功和选择性重试已经由专门测试覆盖。
         */
        SyncObjectListFanOutDispatchService objectListFanOutDispatchService =
                mock(SyncObjectListFanOutDispatchService.class);
        SyncPartitionShardFanOutDispatchService partitionShardFanOutDispatchService =
                mock(SyncPartitionShardFanOutDispatchService.class);
        return new SyncOfflineRunnerDispatchService(
                bridgePlanSupport,
                runOnceDispatchService,
                new SyncOfflineRunnerAdapterRegistry(List.of()),
                objectListFanOutDispatchService,
                partitionShardFanOutDispatchService,
                lifecycleSupport,
                receiptPublisher);
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

    private SyncExecution execution() {
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
        execution.setRecordsRead(0L);
        execution.setRecordsWritten(0L);
        execution.setFailedRecordCount(0L);
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

    private SyncTemplate fullCustomerTemplate() {
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
        template.setSourceObjectName("customer");
        template.setTargetSchemaName("dwd");
        template.setTargetObjectName("customer_clean");
        template.setSyncMode("FULL");
        template.setSyncScopeType("SINGLE_OBJECT");
        /*
         * 使用 UPSERT 而不是 APPEND，是为了体现生产同步任务更推荐幂等写入。
         * APPEND 在远端部分提交后重试可能产生重复数据，而 UPSERT 可以依赖主键做冲突更新。
         */
        template.setWriteStrategy("UPSERT");
        template.setPrimaryKeyField("id");
        template.setIncrementalField("updated_at");
        template.setFieldMappingConfig("""
                [
                  {"sourceField":"id","targetField":"id"},
                  {"sourceField":"customer_name","targetField":"name"},
                  {"sourceField":"amount","targetField":"amount"},
                  {"sourceField":"region","targetField":"region"}
                ]
                """);
        /*
         * filterConfig 对应用户在任务配置阶段填写的 where 条件。
         * data-sync 只把它解析为结构化条件交给 datasource-management，真实 SQL 拼接和 PreparedStatement
         * 参数绑定由执行面根据具体数据库方言处理，避免控制面持有 raw SQL。
         */
        template.setFilterConfig("""
                {
                  "conditions": [
                    {"field":"region","operator":"=","value":"EAST"}
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
        return new SyncActorContext(7L, 1001L, "SERVICE_ACCOUNT", "trace-control-plane-e2e",
                "PROJECT", "project_id IN ${actorProjectIds}", List.of(101L), false);
    }

    private DatasourceRunOnceResponse moreRemainResponse(Long totalRecordsRead, Long totalRecordsWritten) {
        DatasourceRunOnceResponse response = response(totalRecordsRead, totalRecordsWritten);
        response.setRunStatus("BATCH_WRITTEN_MORE_REMAIN");
        response.setEndOfSource(false);
        response.setProgressCallbackRecommended(true);
        response.setCompleteCallbackRecommended(false);
        return response;
    }

    private DatasourceRunOnceResponse completeResponse(Long totalRecordsRead, Long totalRecordsWritten) {
        DatasourceRunOnceResponse response = response(totalRecordsRead, totalRecordsWritten);
        response.setRunStatus("SOURCE_EXHAUSTED_COMPLETE_REQUIRED");
        response.setEndOfSource(true);
        response.setProgressCallbackRecommended(false);
        response.setCompleteCallbackRecommended(true);
        return response;
    }

    private DatasourceRunOnceResponse response(Long totalRecordsRead, Long totalRecordsWritten) {
        DatasourceRunOnceResponse response = new DatasourceRunOnceResponse();
        response.setTaskId(11L);
        response.setExecutionId(88L);
        response.setBatchRecordsRead(totalRecordsRead);
        response.setBatchRecordsWritten(totalRecordsWritten);
        response.setBatchFailedRecordCount(0L);
        response.setTotalRecordsRead(totalRecordsRead);
        response.setTotalRecordsWritten(totalRecordsWritten);
        response.setTotalFailedRecordCount(0L);
        response.setFailed(false);
        response.setFailCallbackRecommended(false);
        response.setCheckpointCandidateProduced(false);
        response.setPayloadPolicy("LOW_SENSITIVE_RUN_ONCE_RESULT_NO_ROWS_NO_SQL_NO_CREDENTIALS_NO_CHECKPOINT_VALUE");
        return response;
    }

    /**
     * datasource-management internal run-once client 的测试替身。
     *
     * <p>真实环境中这里会是 HTTP 客户端：data-sync 通过 JSON 合同调用 datasource-management 的
     * {@code /internal/sync-batch-runs/run-once}。测试中使用 fake client 的好处是可以精确观察每一次请求，
     * 同时不用启动网络端口、Docker、真实数据库或 Spring 容器。</p>
     */
    private static class FakeDatasourceRunOnceClient implements DatasourceRunOnceClient {

        private final List<DatasourceRunOnceResponse> responses;
        private final List<RunOnceRequestSnapshot> snapshots = new ArrayList<>();
        private int calls;

        private FakeDatasourceRunOnceClient(DatasourceRunOnceResponse... responses) {
            this.responses = Arrays.asList(responses);
        }

        @Override
        public DatasourceRunOnceResponse runOnce(DatasourceRunOnceRequest request, SyncActorContext actorContext) {
            calls++;
            snapshots.add(RunOnceRequestSnapshot.from(request));
            int responseIndex = Math.min(calls - 1, responses.size() - 1);
            return responses.get(responseIndex);
        }

        private int calls() {
            return calls;
        }

        private List<RunOnceRequestSnapshot> snapshots() {
            return snapshots;
        }
    }

    /**
     * 对 run-once 请求做不可变快照，避免测试误读同一个 request 对象在多批次循环中的后续变更。
     */
    private record RunOnceRequestSnapshot(
            Long taskId,
            Long executionId,
            Long previousRecordsRead,
            Long previousRecordsWritten,
            Long previousFailedRecordCount,
            Object checkpointValue,
            List<String> selectedColumns,
            List<String> writeColumns,
            List<String> primaryKeyColumns,
            String executionBoundary,
            String sourceObjectLocator,
            String targetObjectLocator,
            String readStrategy,
            String writeStrategy,
            String conflictPolicy,
            List<FilterSnapshot> filters,
            List<String> readCapabilities,
            List<String> writeCapabilities,
            String checkpointType,
            String checkpointValueVisibility,
            String idempotencyScope,
            List<String> requiredCallbacks
    ) {
        private static RunOnceRequestSnapshot from(DatasourceRunOnceRequest request) {
            DatasourceRunOnceRequest.ExecutionPlan plan = request.getExecutionPlan();
            DatasourceRunOnceRequest.ReadPlan readPlan = plan.getReadPlan();
            DatasourceRunOnceRequest.WritePlan writePlan = plan.getWritePlan();
            DatasourceRunOnceRequest.CheckpointPlan checkpointPlan = plan.getCheckpointPlan();
            DatasourceRunOnceRequest.RuntimeControlPlan runtimeControlPlan = plan.getRuntimeControlPlan();
            return new RunOnceRequestSnapshot(
                    plan.getTaskId(),
                    plan.getExecutionId(),
                    request.getPreviousRecordsRead(),
                    request.getPreviousRecordsWritten(),
                    request.getPreviousFailedRecordCount(),
                    request.getCheckpointValue(),
                    List.copyOf(request.getSelectedColumns()),
                    List.copyOf(request.getWriteColumns()),
                    List.copyOf(request.getPrimaryKeyColumns()),
                    plan.getExecutionBoundary(),
                    readPlan.getObjectLocator(),
                    writePlan.getObjectLocator(),
                    readPlan.getReadStrategy(),
                    writePlan.getWriteStrategy(),
                    writePlan.getConflictPolicy(),
                    readPlan.getFilterConditions().stream().map(FilterSnapshot::from).toList(),
                    List.copyOf(readPlan.getRequiredWorkerCapabilities()),
                    List.copyOf(writePlan.getRequiredWorkerCapabilities()),
                    checkpointPlan.getCheckpointType(),
                    checkpointPlan.getCheckpointValueVisibility(),
                    runtimeControlPlan.getIdempotencyScope(),
                    List.copyOf(runtimeControlPlan.getRequiredCallbacks())
            );
        }
    }

    /**
     * 过滤条件快照只保留结构化字段、操作符和值，不持有 SQL 字符串。
     */
    private record FilterSnapshot(String column, String operator, Object value, Boolean valueRequired) {
        private static FilterSnapshot from(DatasourceRunOnceRequest.ReadFilterCondition condition) {
            return new FilterSnapshot(
                    condition.getColumn(),
                    condition.getOperator(),
                    condition.getValue(),
                    condition.getValueRequired());
        }
    }
}
