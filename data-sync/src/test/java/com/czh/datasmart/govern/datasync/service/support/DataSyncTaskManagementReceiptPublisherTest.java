/**
 * @Author : Cui
 * @Date: 2026/06/29 13:18
 * @Description DataSmart Govern Backend - DataSyncTaskManagementReceiptPublisherTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import com.czh.datasmart.govern.datasync.config.DataSyncTaskManagementReceiptProperties;
import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import com.czh.datasmart.govern.datasync.entity.SyncExecution;
import com.czh.datasmart.govern.datasync.entity.SyncTask;
import com.czh.datasmart.govern.datasync.integration.datasource.runonce.DatasourceRunOnceResponse;
import com.czh.datasmart.govern.datasync.integration.task.receipt.TaskManagementExecutionReceiptClient;
import com.czh.datasmart.govern.datasync.integration.task.receipt.TaskManagementExecutionReceiptRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * task-management receipt 发布器测试。
 *
 * <p>本测试不启动 HTTP 服务，只验证 data-sync 领域事实如何转换成低敏 receipt 请求。
 * 这能防止后续维护时不小心把 SQL、endpoint、字段值、样本数据或异常 message 放进回执。</p>
 */
class DataSyncTaskManagementReceiptPublisherTest {

    /**
     * complete receipt 应只携带数量、完成状态和 checkpoint 可见性策略。
     */
    @Test
    void publishCompleteShouldBuildLowSensitiveReceipt() {
        FakeReceiptClient client = new FakeReceiptClient(false);
        DataSyncTaskManagementReceiptPublisher publisher = publisher(client, false);

        publisher.publishComplete(task(), execution(), actor(), response());

        TaskManagementExecutionReceiptRequest request = client.captured();
        assertThat(request.getReceiptId()).isEqualTo("data-sync-execution-receipt:88:complete");
        assertThat(request.getEventType()).isEqualTo("COMPLETE");
        assertThat(request.getSyncTaskId()).isEqualTo(11L);
        assertThat(request.getSyncExecutionId()).isEqualTo(88L);
        assertThat(request.getTotalRecordsRead()).isEqualTo(12L);
        assertThat(request.getTotalRecordsWritten()).isEqualTo(10L);
        assertThat(request.getProgressPercent()).isEqualTo(100);
        assertThat(request.getCompleted()).isTrue();
        assertThat(request.getFailed()).isFalse();
        assertThat(request.getCheckpointValueVisibility()).isEqualTo("NO_CHECKPOINT_VALUE_IN_RECEIPT");
        assertThat(request.toString()).doesNotContain("jdbc:");
        assertThat(request.toString()).doesNotContain("select ");
    }

    /**
     * failed receipt 应只携带低敏错误码和 issueCode，不携带异常正文或样本。
     */
    @Test
    void publishFailedShouldBuildLowSensitiveReceipt() {
        FakeReceiptClient client = new FakeReceiptClient(false);
        DataSyncTaskManagementReceiptPublisher publisher = publisher(client, false);

        publisher.publishFailed(task(), execution(), actor(),
                "OUTER_BATCH_LOOP_NOT_IMPLEMENTED",
                List.of("OUTER_BATCH_LOOP_NOT_IMPLEMENTED", "jdbc:mysql://hidden"));

        TaskManagementExecutionReceiptRequest request = client.captured();
        assertThat(request.getReceiptId()).isEqualTo("data-sync-execution-receipt:88:failed");
        assertThat(request.getEventType()).isEqualTo("FAILED");
        assertThat(request.getFailed()).isTrue();
        assertThat(request.getErrorSummary()).isEqualTo("data-sync execution failed, errorCode=OUTER_BATCH_LOOP_NOT_IMPLEMENTED");
        assertThat(request.getWarnings()).contains("issueCode=OUTER_BATCH_LOOP_NOT_IMPLEMENTED");
        assertThat(request.toString()).doesNotContain("jdbc:mysql://hidden");
    }

    /**
     * 默认投影投递失败不阻断 data-sync 主状态机。
     */
    @Test
    void publishShouldNotThrowWhenDeliveryIsNotRequired() {
        DataSyncTaskManagementReceiptPublisher publisher = publisher(new FakeReceiptClient(true), false);

        assertThatCode(() -> publisher.publishFailed(task(), execution(), actor(), "REMOTE_FAILED", List.of()))
                .doesNotThrowAnyException();
    }

    /**
     * 强一致配置下，投递失败会向上抛出，供生产环境按需启用。
     */
    @Test
    void publishShouldThrowWhenDeliveryIsRequired() {
        DataSyncTaskManagementReceiptPublisher publisher = publisher(new FakeReceiptClient(true), true);

        assertThatThrownBy(() -> publisher.publishFailed(task(), execution(), actor(), "REMOTE_FAILED", List.of()))
                .isInstanceOf(PlatformBusinessException.class);
    }

    private DataSyncTaskManagementReceiptPublisher publisher(FakeReceiptClient client, boolean deliveryRequired) {
        DataSyncTaskManagementReceiptProperties properties = new DataSyncTaskManagementReceiptProperties();
        properties.setDeliveryRequired(deliveryRequired);
        return new DataSyncTaskManagementReceiptPublisher(client, properties);
    }

    private SyncTask task() {
        SyncTask task = new SyncTask();
        task.setId(11L);
        task.setTenantId(7L);
        task.setProjectId(101L);
        task.setWorkspaceId(301L);
        return task;
    }

    private SyncExecution execution() {
        SyncExecution execution = new SyncExecution();
        execution.setId(88L);
        execution.setSyncTaskId(11L);
        execution.setExecutorId("worker-loop-test");
        execution.setRecordsRead(10L);
        execution.setRecordsWritten(8L);
        execution.setFailedRecordCount(1L);
        return execution;
    }

    private SyncActorContext actor() {
        return new SyncActorContext(7L, 0L, "SERVICE_ACCOUNT", "trace-receipt-test");
    }

    private DatasourceRunOnceResponse response() {
        DatasourceRunOnceResponse response = new DatasourceRunOnceResponse();
        response.setBatchRecordsRead(2L);
        response.setBatchRecordsWritten(2L);
        response.setBatchFailedRecordCount(0L);
        response.setTotalRecordsRead(12L);
        response.setTotalRecordsWritten(10L);
        response.setTotalFailedRecordCount(1L);
        response.setEndOfSource(true);
        return response;
    }

    /**
     * 测试专用 receipt 客户端。
     */
    private static class FakeReceiptClient implements TaskManagementExecutionReceiptClient {

        private final boolean fail;
        private TaskManagementExecutionReceiptRequest captured;

        private FakeReceiptClient(boolean fail) {
            this.fail = fail;
        }

        @Override
        public void record(TaskManagementExecutionReceiptRequest request, SyncActorContext actorContext) {
            if (fail) {
                throw new PlatformBusinessException(PlatformErrorCode.EXTERNAL_DEPENDENCY_FAILED,
                        "task-management unavailable");
            }
            this.captured = request;
        }

        private TaskManagementExecutionReceiptRequest captured() {
            return captured;
        }
    }
}
