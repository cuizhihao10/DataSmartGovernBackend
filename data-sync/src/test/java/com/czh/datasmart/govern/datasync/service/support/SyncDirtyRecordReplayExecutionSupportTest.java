/**
 * @Author : Cui
 * @Date: 2026/07/08 01:38
 * @Description DataSmart Govern Backend - SyncDirtyRecordReplayExecutionSupportTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExecutionCompleteRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncRecoveryPlanWorkerResult;
import com.czh.datasmart.govern.datasync.controller.dto.SyncWorkerExecutionPlanView;
import com.czh.datasmart.govern.datasync.entity.SyncErrorSample;
import com.czh.datasmart.govern.datasync.entity.SyncExecution;
import com.czh.datasmart.govern.datasync.entity.SyncTask;
import com.czh.datasmart.govern.datasync.entity.SyncTemplate;
import com.czh.datasmart.govern.datasync.mapper.SyncErrorSampleMapper;
import com.czh.datasmart.govern.datasync.support.SyncExecutionState;
import com.czh.datasmart.govern.datasync.support.SyncTriggerType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 脏数据修复重放执行支撑测试。
 *
 * <p>本测试只验证控制面关键契约，不连接真实数据库：错误样本中的 PRIMARY_KEY_EQ 定位必须转换成
 * run-once 的结构化过滤条件，而不是拼 SQL 或重新全表扫描。</p>
 */
class SyncDirtyRecordReplayExecutionSupportTest {

    @Test
    @SuppressWarnings("unchecked")
    void dispatchDirtyRecordReplayShouldConvertSamplePrimaryKeyToRunOnceFilter() {
        SyncErrorSampleMapper errorSampleMapper = mock(SyncErrorSampleMapper.class);
        SyncBatchRunnerBridgePlanSupport bridgePlanSupport = mock(SyncBatchRunnerBridgePlanSupport.class);
        SyncBatchRunOnceDispatchService runOnceDispatchService = mock(SyncBatchRunOnceDispatchService.class);
        SyncExecutionLifecycleSupport lifecycleSupport = mock(SyncExecutionLifecycleSupport.class);
        DataSyncTaskManagementReceiptPublisher receiptPublisher = mock(DataSyncTaskManagementReceiptPublisher.class);
        SyncDirtyRecordReplayExecutionSupport support = new SyncDirtyRecordReplayExecutionSupport(
                errorSampleMapper,
                bridgePlanSupport,
                runOnceDispatchService,
                lifecycleSupport,
                receiptPublisher,
                new ObjectMapper());
        SyncTask task = task();
        SyncExecution execution = execution();
        SyncTemplate template = template();
        SyncWorkerExecutionPlanView workerPlan = workerPlan();
        SyncBatchRunnerBridgePlan bridgePlan = bridgePlan();
        when(bridgePlanSupport.buildPlan(eq(execution), eq(task), eq(template), eq(workerPlan))).thenReturn(bridgePlan);
        when(errorSampleMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(errorSample()));
        when(runOnceDispatchService.executePreparedRunOnceRemoteOnly(eq(bridgePlan), any(SyncExecution.class),
                eq(task), any(SyncActorContext.class), eq("dirty-sample-501"), anyList()))
                .thenReturn(new SyncBatchRunOnceRemoteExecutionResult(
                        true,
                        true,
                        false,
                        "DISPATCHED_AND_COMPLETED",
                        88L,
                        "SOURCE_EXHAUSTED_COMPLETE_REQUIRED",
                        1L,
                        1L,
                        0L,
                        null,
                        null,
                        null,
                        false,
                        List.of(),
                        SyncBatchRunOnceRemoteExecutionResult.PAYLOAD_POLICY));

        SyncOfflineRunnerDispatchResult result = support.dispatchDirtyRecordReplay(
                execution, task, template, workerPlan, recoveryPlan(), actor());

        assertThat(result.completed()).isTrue();
        assertThat(result.dispatchStatus()).isEqualTo("DIRTY_RECORD_REPLAY_COMPLETED");
        ArgumentCaptor<List<SyncFilterExecutionCondition>> filterCaptor = ArgumentCaptor.forClass(List.class);
        verify(runOnceDispatchService).executePreparedRunOnceRemoteOnly(eq(bridgePlan), any(SyncExecution.class),
                eq(task), any(SyncActorContext.class), eq("dirty-sample-501"), filterCaptor.capture());
        assertThat(filterCaptor.getValue()).singleElement().satisfies(condition -> {
            assertThat(condition.getColumn()).isEqualTo("id");
            assertThat(condition.getOperator()).isEqualTo("EQ");
            assertThat(condition.getValue()).isEqualTo(501L);
            assertThat(condition.isValueRequired()).isTrue();
        });
        ArgumentCaptor<SyncExecutionCompleteRequest> completeCaptor =
                ArgumentCaptor.forClass(SyncExecutionCompleteRequest.class);
        verify(lifecycleSupport).completeExecution(eq(task), eq(execution), completeCaptor.capture(),
                any(SyncActorContext.class));
        assertThat(completeCaptor.getValue().getRecordsRead()).isEqualTo(1L);
        assertThat(completeCaptor.getValue().getRecordsWritten()).isEqualTo(1L);
    }

    private SyncRecoveryPlanWorkerResult recoveryPlan() {
        return new SyncRecoveryPlanWorkerResult(
                true,
                7L,
                101L,
                301L,
                11L,
                88L,
                7001L,
                "REPLAY",
                77L,
                null,
                null,
                null,
                "DIRTY_RECORD_REPLAY",
                "{\"selectorVersion\":\"1.0\",\"sourceExecutionId\":77,\"errorSampleIds\":[501]}",
                "repair confirmed",
                "CONSUMED",
                "ok");
    }

    private SyncErrorSample errorSample() {
        SyncErrorSample sample = new SyncErrorSample();
        sample.setId(501L);
        sample.setTenantId(7L);
        sample.setProjectId(101L);
        sample.setWorkspaceId(301L);
        sample.setSyncTaskId(11L);
        sample.setExecutionId(77L);
        sample.setRetryable(true);
        sample.setSourceRecordKey("{\"strategy\":\"PRIMARY_KEY_EQ\",\"column\":\"id\",\"value\":501,\"valueType\":\"Long\"}");
        return sample;
    }

    private SyncBatchRunnerBridgePlan bridgePlan() {
        return new SyncBatchRunnerBridgePlan(
                true,
                "READY_TO_DISPATCH",
                7L,
                101L,
                301L,
                11L,
                88L,
                22L,
                10001L,
                10002L,
                "MYSQL",
                "POSTGRESQL",
                "FULL",
                "FULL_OBJECT_SCAN",
                "UPSERT",
                "NONE_OR_FINAL_WATERMARK",
                "ods.orders",
                "dwd.orders",
                new SyncFieldMappingExecutionContract(
                        true,
                        true,
                        2,
                        List.of("id", "amount"),
                        List.of("id", "amount"),
                        List.of("id"),
                        false,
                        List.of(),
                        List.of()),
                List.of(),
                null,
                null,
                null,
                null,
                0L,
                0L,
                0L,
                List.of(),
                List.of(),
                List.of());
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

    private SyncExecution execution() {
        SyncExecution execution = new SyncExecution();
        execution.setId(88L);
        execution.setTenantId(7L);
        execution.setProjectId(101L);
        execution.setWorkspaceId(301L);
        execution.setSyncTaskId(11L);
        execution.setExecutionNo(3L);
        execution.setExecutionState(SyncExecutionState.RUNNING.name());
        execution.setTriggerType(SyncTriggerType.REPLAY.name());
        execution.setExecutorId("worker-loop-test");
        execution.setLeaseExpireTime(LocalDateTime.now().plusMinutes(2));
        return execution;
    }

    private SyncTemplate template() {
        SyncTemplate template = new SyncTemplate();
        template.setId(22L);
        template.setTenantId(7L);
        template.setProjectId(101L);
        template.setWorkspaceId(301L);
        template.setSourceDatasourceId(10001L);
        template.setTargetDatasourceId(10002L);
        template.setSyncMode("FULL");
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
                SyncTriggerType.REPLAY.name(),
                "worker-loop-test",
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
        return new SyncActorContext(7L, 0L, "SERVICE_ACCOUNT", "trace-dirty-replay-execution");
    }
}
