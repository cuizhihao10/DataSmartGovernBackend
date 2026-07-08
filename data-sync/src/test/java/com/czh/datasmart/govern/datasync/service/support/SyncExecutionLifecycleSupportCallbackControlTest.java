/**
 * @Author : Cui
 * @Date: 2026/06/27 02:33
 * @Description DataSmart Govern Backend - SyncExecutionLifecycleSupportCallbackControlTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExecutionCheckpointRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExecutionCompleteRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExecutionFailRequest;
import com.czh.datasmart.govern.datasync.entity.SyncCheckpoint;
import com.czh.datasmart.govern.datasync.entity.SyncErrorSample;
import com.czh.datasmart.govern.datasync.entity.SyncExecution;
import com.czh.datasmart.govern.datasync.entity.SyncTask;
import com.czh.datasmart.govern.datasync.mapper.SyncCheckpointMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncErrorSampleMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncExecutionMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncTaskMapper;
import com.czh.datasmart.govern.datasync.support.SyncExecutionState;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 执行生命周期组件的暂停/取消回调防写入测试。
 *
 * <p>上一组测试证明控制信号支撑组件本身能给出正确异常。
 * 这组测试进一步验证 `SyncExecutionLifecycleSupport` 已经把该组件放在正确位置：
 * 必须在幂等登记、checkpoint 插入、execution 更新、错误样本插入之前阻断。
 */
class SyncExecutionLifecycleSupportCallbackControlTest {

    /**
     * PAUSED 后写 checkpoint 应在幂等登记前失败，并且不插入 checkpoint。
     */
    @Test
    void pausedCheckpointShouldStopBeforeIdempotencyAndInsert() {
        Fixture fixture = fixture();

        PlatformBusinessException exception = assertThrows(PlatformBusinessException.class,
                () -> fixture.support().writeCheckpoint(task(), execution(SyncExecutionState.PAUSED),
                        checkpointRequest(), actor()));

        assertThat(exception.getMessage()).contains("STOP_FOR_PAUSE").contains("CHECKPOINT");
        verify(fixture.idempotencySupport(), never()).isDuplicate(any(), any(), any(), any(), any(), any(), any(), any());
        verify(fixture.checkpointMapper(), never()).insert(any(SyncCheckpoint.class));
        verify(fixture.executionMapper(), never()).updateById(any(SyncExecution.class));
    }

    /**
     * CANCELLED 后写 complete 应停止，不得把 execution 推进到 SUCCEEDED。
     */
    @Test
    void cancelledCompleteShouldStopBeforeExecutionUpdate() {
        Fixture fixture = fixture();

        PlatformBusinessException exception = assertThrows(PlatformBusinessException.class,
                () -> fixture.support().completeExecution(task(), execution(SyncExecutionState.CANCELLED),
                        completeRequest(), actor()));

        assertThat(exception.getMessage()).contains("STOP_FOR_CANCEL").contains("COMPLETE");
        verify(fixture.executionMapper(), never()).updateById(any(SyncExecution.class));
        verify(fixture.taskMapper(), never()).updateById(any(SyncTask.class));
    }

    /**
     * PAUSED 后写 fail 应停止，不得把用户已暂停的任务推入 FAILED 或写错误样本。
     */
    @Test
    void pausedFailShouldStopBeforeErrorSampleInsert() {
        Fixture fixture = fixture();

        PlatformBusinessException exception = assertThrows(PlatformBusinessException.class,
                () -> fixture.support().failExecution(task(), execution(SyncExecutionState.PAUSED),
                        failRequest(), actor()));

        assertThat(exception.getMessage()).contains("STOP_FOR_PAUSE").contains("FAIL");
        verify(fixture.errorSampleMapper(), never()).insert(any(SyncErrorSample.class));
        verify(fixture.executionMapper(), never()).updateById(any(SyncExecution.class));
        verify(fixture.taskMapper(), never()).updateById(any(SyncTask.class));
    }

    private Fixture fixture() {
        SyncExecutionMapper executionMapper = mock(SyncExecutionMapper.class);
        SyncTaskMapper taskMapper = mock(SyncTaskMapper.class);
        SyncCheckpointMapper checkpointMapper = mock(SyncCheckpointMapper.class);
        SyncErrorSampleMapper errorSampleMapper = mock(SyncErrorSampleMapper.class);
        SyncAuditSupport auditSupport = mock(SyncAuditSupport.class);
        SyncCallbackIdempotencySupport idempotencySupport = mock(SyncCallbackIdempotencySupport.class);
        SyncExecutionLifecycleSupport support = new SyncExecutionLifecycleSupport(
                executionMapper,
                taskMapper,
                checkpointMapper,
                errorSampleMapper,
                auditSupport,
                idempotencySupport,
                new SyncExecutionCallbackControlSignalSupport(),
                mock(SyncExecutionLogSupport.class));
        return new Fixture(support, executionMapper, taskMapper, checkpointMapper, errorSampleMapper, idempotencySupport);
    }

    private SyncTask task() {
        SyncTask task = new SyncTask();
        task.setId(1L);
        task.setTenantId(7L);
        task.setProjectId(101L);
        task.setWorkspaceId(301L);
        task.setCurrentState("RUNNING");
        return task;
    }

    private SyncExecution execution(SyncExecutionState state) {
        SyncExecution execution = new SyncExecution();
        execution.setId(88L);
        execution.setTenantId(7L);
        execution.setProjectId(101L);
        execution.setWorkspaceId(301L);
        execution.setSyncTaskId(1L);
        execution.setExecutionState(state.name());
        execution.setExecutorId("worker-1");
        return execution;
    }

    private SyncExecutionCheckpointRequest checkpointRequest() {
        SyncExecutionCheckpointRequest request = new SyncExecutionCheckpointRequest();
        request.setExecutorId("worker-1");
        request.setCheckpointType("ID_RANGE");
        request.setCheckpointValue("max_id=1000");
        request.setRecordsRead(1000L);
        request.setRecordsWritten(980L);
        request.setIdempotencyKey("checkpoint-key");
        return request;
    }

    private SyncExecutionCompleteRequest completeRequest() {
        SyncExecutionCompleteRequest request = new SyncExecutionCompleteRequest();
        request.setExecutorId("worker-1");
        request.setRecordsRead(1000L);
        request.setRecordsWritten(980L);
        request.setIdempotencyKey("complete-key");
        return request;
    }

    private SyncExecutionFailRequest failRequest() {
        SyncExecutionFailRequest request = new SyncExecutionFailRequest();
        request.setExecutorId("worker-1");
        request.setErrorType("CONNECTOR_ERROR");
        request.setErrorCode("TARGET_TIMEOUT");
        request.setErrorMessage("target timeout");
        request.setIdempotencyKey("fail-key");
        return request;
    }

    private SyncActorContext actor() {
        return new SyncActorContext(
                7L,
                1001L,
                "SERVICE_ACCOUNT",
                "trace-callback-control",
                "PROJECT",
                "project_id IN ${actorProjectIds}",
                List.of(101L),
                false
        );
    }

    private record Fixture(SyncExecutionLifecycleSupport support,
                           SyncExecutionMapper executionMapper,
                           SyncTaskMapper taskMapper,
                           SyncCheckpointMapper checkpointMapper,
                           SyncErrorSampleMapper errorSampleMapper,
                           SyncCallbackIdempotencySupport idempotencySupport) {
    }
}
