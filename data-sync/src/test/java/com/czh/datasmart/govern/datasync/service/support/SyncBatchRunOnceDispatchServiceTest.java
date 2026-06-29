/**
 * @Author : Cui
 * @Date: 2026/06/29 13:20
 * @Description DataSmart Govern Backend - SyncBatchRunOnceDispatchServiceTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.datasync.config.DataSyncDatasourceRunOnceProperties;
import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExecutionCompleteRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExecutionFailRequest;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

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
        SyncBatchRunOnceDispatchService service = service(client, lifecycleSupport, properties(true));

        SyncExecution execution = execution("FULL");
        SyncBatchRunOnceDispatchResult result = service.dispatchRunOnce(execution, task(), template("FULL", directMapping()),
                workerPlan("FULL", "READY_TO_RUN", List.of()), actor());

        assertThat(result.dispatched()).isTrue();
        assertThat(result.completed()).isTrue();
        assertThat(result.failed()).isFalse();
        assertThat(result.dispatchStatus()).isEqualTo("DISPATCHED_AND_COMPLETED");
        assertThat(client.calls()).isEqualTo(1);
        assertThat(client.capturedRequest().getCheckpointValue()).isNull();
        assertThat(client.capturedRequest().getSelectedColumns()).containsExactly("id", "name");
        assertThat(client.capturedRequest().getExecutionPlan().getReadPlan().getReadStrategy()).isEqualTo("FULL_OBJECT_SCAN");
        assertThat(client.capturedRequest().getExecutionPlan().getCheckpointPlan().getCheckpointType())
                .isEqualTo("NONE_OR_FINAL_WATERMARK");

        ArgumentCaptor<SyncExecutionCompleteRequest> completeCaptor =
                ArgumentCaptor.forClass(SyncExecutionCompleteRequest.class);
        verify(lifecycleSupport).completeExecution(eq(task()), eq(execution), completeCaptor.capture(), any(SyncActorContext.class));
        assertThat(completeCaptor.getValue().getExecutorId()).isEqualTo("worker-1");
        assertThat(completeCaptor.getValue().getRecordsRead()).isEqualTo(12L);
        assertThat(completeCaptor.getValue().getRecordsWritten()).isEqualTo(10L);
        verify(lifecycleSupport, never()).failExecution(any(), any(), any(), any());
    }

    /**
     * 增量模式需要 checkpoint 原始值安全交接；该机制尚未实现时，必须在本地阻断，不能调用真实读写。
     */
    @Test
    void incrementalModeShouldFailBeforeRemoteCallUntilCheckpointHandoffExists() {
        FakeDatasourceRunOnceClient client = new FakeDatasourceRunOnceClient(completeResponse());
        SyncExecutionLifecycleSupport lifecycleSupport = mock(SyncExecutionLifecycleSupport.class);
        SyncBatchRunOnceDispatchService service = service(client, lifecycleSupport, properties(true));
        SyncExecution execution = execution("INCREMENTAL_TIME");

        SyncBatchRunOnceDispatchResult result = service.dispatchRunOnce(execution, task(),
                template("INCREMENTAL_TIME", directMapping()), workerPlan("INCREMENTAL_TIME", "READY_TO_RUN", List.of()), actor());

        assertThat(result.dispatched()).isFalse();
        assertThat(result.failed()).isTrue();
        assertThat(result.issueCodes()).contains("CHECKPOINT_HANDOFF_NOT_IMPLEMENTED");
        assertThat(client.calls()).isZero();
        assertFail(lifecycleSupport, execution, "CHECKPOINT_HANDOFF_NOT_IMPLEMENTED");
    }

    /**
     * 字段重命名需要 transform 层；当前最小 JDBC bridge 不能把 sourceField 改名为 targetField 后直接写入。
     */
    @Test
    void fieldRenameShouldFailBeforeRemoteCallUntilTransformLayerExists() {
        FakeDatasourceRunOnceClient client = new FakeDatasourceRunOnceClient(completeResponse());
        SyncExecutionLifecycleSupport lifecycleSupport = mock(SyncExecutionLifecycleSupport.class);
        SyncBatchRunOnceDispatchService service = service(client, lifecycleSupport, properties(true));
        SyncExecution execution = execution("FULL");

        SyncBatchRunOnceDispatchResult result = service.dispatchRunOnce(execution, task(),
                template("FULL", renameMapping()), workerPlan("FULL", "READY_TO_RUN", List.of()), actor());

        assertThat(result.dispatched()).isFalse();
        assertThat(result.failed()).isTrue();
        assertThat(result.issueCodes()).contains("FIELD_RENAME_TRANSFORM_NOT_SUPPORTED_BY_MINIMAL_BRIDGE");
        assertThat(client.calls()).isZero();
        assertFail(lifecycleSupport, execution, "BRIDGE_PLAN_BLOCKED");
    }

    /**
     * 远端提示“本批成功但仍有后续批次”时，当前阶段必须 fail-closed。
     *
     * <p>原因是 data-sync 外层多批循环、心跳续租、checkpoint 安全保存和退避重试还没有完整实现。
     * 如果此时把 execution 留在 RUNNING，会让用户和运营侧误以为任务仍在执行，最终形成不可解释的悬挂状态。</p>
     */
    @Test
    void moreBatchesShouldFailClosedUntilOuterLoopExists() {
        FakeDatasourceRunOnceClient client = new FakeDatasourceRunOnceClient(moreBatchesResponse());
        SyncExecutionLifecycleSupport lifecycleSupport = mock(SyncExecutionLifecycleSupport.class);
        SyncBatchRunOnceDispatchService service = service(client, lifecycleSupport, properties(true));
        SyncExecution execution = execution("FULL");

        SyncBatchRunOnceDispatchResult result = service.dispatchRunOnce(execution, task(), template("FULL", directMapping()),
                workerPlan("FULL", "READY_TO_RUN", List.of()), actor());

        assertThat(result.dispatched()).isTrue();
        assertThat(result.failed()).isTrue();
        assertThat(result.remoteRunStatus()).isEqualTo("BATCH_WRITTEN_MORE_REMAIN");
        assertThat(client.calls()).isEqualTo(1);
        assertFail(lifecycleSupport, execution, "OUTER_BATCH_LOOP_NOT_IMPLEMENTED");
    }

    private SyncBatchRunOnceDispatchService service(FakeDatasourceRunOnceClient client,
                                                   SyncExecutionLifecycleSupport lifecycleSupport,
                                                   DataSyncDatasourceRunOnceProperties properties) {
        return new SyncBatchRunOnceDispatchService(
                new SyncBatchRunnerBridgePlanSupport(new SyncFieldMappingExecutionContractSupport(new ObjectMapper())),
                client,
                properties,
                lifecycleSupport);
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
        DatasourceRunOnceResponse response = new DatasourceRunOnceResponse();
        response.setRunStatus("SOURCE_EXHAUSTED_COMPLETE_REQUIRED");
        response.setBatchRecordsRead(2L);
        response.setBatchRecordsWritten(2L);
        response.setTotalRecordsRead(12L);
        response.setTotalRecordsWritten(10L);
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
        task.setTemplateId(22L);
        task.setCurrentState("RUNNING");
        return task;
    }

    private SyncTemplate template(String syncMode, String fieldMapping) {
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
        template.setWriteStrategy("APPEND");
        template.setPrimaryKeyField("id");
        template.setIncrementalField("updated_at");
        template.setFieldMappingConfig(fieldMapping);
        template.setEnabled(true);
        return template;
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
                22L,
                10001L,
                10002L,
                "MYSQL",
                "POSTGRESQL",
                syncMode,
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

        private final DatasourceRunOnceResponse response;
        private int calls;
        private DatasourceRunOnceRequest capturedRequest;

        private FakeDatasourceRunOnceClient(DatasourceRunOnceResponse response) {
            this.response = response;
        }

        @Override
        public DatasourceRunOnceResponse runOnce(DatasourceRunOnceRequest request, SyncActorContext actorContext) {
            calls++;
            capturedRequest = request;
            return response;
        }

        private int calls() {
            return calls;
        }

        private DatasourceRunOnceRequest capturedRequest() {
            return capturedRequest;
        }
    }
}
