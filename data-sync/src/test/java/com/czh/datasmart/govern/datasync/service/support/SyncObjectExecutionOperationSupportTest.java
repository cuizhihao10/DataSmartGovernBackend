/**
 * @Author : Cui
 * @Date: 2026/07/06 23:50
 * @Description DataSmart Govern Backend - SyncObjectExecutionOperationSupportTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import com.czh.datasmart.govern.datasync.controller.dto.SyncObjectRetryRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncObjectRetryResult;
import com.czh.datasmart.govern.datasync.entity.SyncExecution;
import com.czh.datasmart.govern.datasync.entity.SyncObjectExecution;
import com.czh.datasmart.govern.datasync.entity.SyncTask;
import com.czh.datasmart.govern.datasync.mapper.SyncExecutionMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncObjectExecutionMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncTaskMapper;
import com.czh.datasmart.govern.datasync.support.SyncAuditActionType;
import com.czh.datasmart.govern.datasync.support.SyncExecutionState;
import com.czh.datasmart.govern.datasync.support.SyncObjectExecutionState;
import com.czh.datasmart.govern.datasync.support.SyncTaskState;
import com.czh.datasmart.govern.datasync.support.SyncTriggerType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 对象级执行账本恢复操作测试。
 *
 * <p>这组测试保护的是 DataX-style 部分成功闭环里最容易出错的边界：</p>
 * <p>1. PARTIALLY_SUCCEEDED 之后只能重试 FAILED 对象，不能把 SUCCEEDED 对象误重跑；</p>
 * <p>2. 失败对象必须重置 attemptCount，否则 fan-out 重新进入时可能因为尝试次数耗尽而不执行；</p>
 * <p>3. 父 execution 必须重新进入 QUEUED，任务主状态必须进入 RETRYING，worker 才能通过既有租约协议继续执行；</p>
 * <p>4. 运行中、排队中或已成功的 execution 不能被人工重置，避免破坏正在执行的 worker 协议。</p>
 */
class SyncObjectExecutionOperationSupportTest {

    /**
     * 不传选择范围时，应该默认重试当前 execution 下全部 FAILED 对象，并跳过 SUCCEEDED 对象。
     */
    @Test
    void retryWithoutSelectionShouldResetAllFailedObjectsAndRequeueParentExecution() {
        Fixture fixture = fixture();
        SyncTask task = task();
        SyncExecution execution = execution(SyncExecutionState.PARTIALLY_SUCCEEDED);
        SyncObjectExecution failedOrders = objectRow(10L, 0, SyncObjectExecutionState.FAILED, 3, 3);
        SyncObjectExecution succeededCustomers = objectRow(11L, 1, SyncObjectExecutionState.SUCCEEDED, 1, 3);
        SyncObjectExecution failedProducts = objectRow(12L, 2, SyncObjectExecutionState.FAILED, 3, 3);
        when(fixture.objectExecutionMapper().selectByExecutionId(88L))
                .thenReturn(List.of(failedOrders, succeededCustomers, failedProducts));
        when(fixture.executionMapper().requeueTerminalObjectLevelRetry(eq(88L), contains("OBJECT_LEVEL_RETRY")))
                .thenReturn(1);
        when(fixture.taskMapper().markLifecycleState(1L, SyncTaskState.RETRYING.name(), SyncTriggerType.MANUAL.name(), 88L))
                .thenReturn(1);

        SyncObjectRetryResult result = fixture.support().retryFailedObjects(task, execution, null, actor());

        ArgumentCaptor<SyncObjectExecution> rowCaptor = ArgumentCaptor.forClass(SyncObjectExecution.class);
        verify(fixture.objectExecutionMapper(), times(2)).updateById(rowCaptor.capture());
        List<SyncObjectExecution> resetRows = rowCaptor.getAllValues();
        assertThat(resetRows).extracting(SyncObjectExecution::getId).containsExactly(10L, 12L);
        assertThat(resetRows).allSatisfy(row -> {
            assertThat(row.getObjectState()).isEqualTo(SyncObjectExecutionState.PENDING.name());
            assertThat(row.getAttemptCount()).isZero();
            assertThat(row.getRecordsRead()).isZero();
            assertThat(row.getRecordsWritten()).isZero();
            assertThat(row.getFailedRecordCount()).isZero();
            assertThat(row.getFinishedAt()).isNull();
        });
        assertThat(succeededCustomers.getObjectState()).isEqualTo(SyncObjectExecutionState.SUCCEEDED.name());
        verify(fixture.executionMapper()).requeueTerminalObjectLevelRetry(eq(88L), contains("OBJECT_LEVEL_RETRY"));
        verify(fixture.taskMapper()).markLifecycleState(1L, SyncTaskState.RETRYING.name(), SyncTriggerType.MANUAL.name(), 88L);
        verify(fixture.auditSupport()).saveAudit(eq(7L), eq(1L), eq(88L), eq(SyncAuditActionType.RETRY_OBJECT_EXECUTIONS),
                eq(actor()), contains("objectOrdinals=[0, 2]"));
        assertThat(result.retryObjectCount()).isEqualTo(2);
        assertThat(result.executionState()).isEqualTo(SyncExecutionState.QUEUED.name());
        assertThat(result.taskState()).isEqualTo(SyncTaskState.RETRYING.name());
    }

    /**
     * 精确选择某个 FAILED 对象时，只能重置被选中的失败对象。
     */
    @Test
    void retrySelectedFailedObjectShouldOnlyResetSelectedOrdinal() {
        Fixture fixture = fixture();
        SyncObjectRetryRequest request = new SyncObjectRetryRequest();
        request.setObjectOrdinals(List.of(2));
        request.setRetryAttemptBudget(4);
        request.setReason("目标端锁已释放，可以重传失败对象");
        SyncObjectExecution failedOrders = objectRow(10L, 0, SyncObjectExecutionState.FAILED, 3, 3);
        SyncObjectExecution failedProducts = objectRow(12L, 2, SyncObjectExecutionState.FAILED, 3, 3);
        when(fixture.objectExecutionMapper().selectByExecutionId(88L))
                .thenReturn(List.of(failedOrders, failedProducts));
        when(fixture.executionMapper().requeueTerminalObjectLevelRetry(eq(88L), contains("OBJECT_LEVEL_RETRY")))
                .thenReturn(1);
        when(fixture.taskMapper().markLifecycleState(1L, SyncTaskState.RETRYING.name(), SyncTriggerType.MANUAL.name(), 88L))
                .thenReturn(1);

        SyncObjectRetryResult result = fixture.support().retryFailedObjects(
                task(), execution(SyncExecutionState.PARTIALLY_SUCCEEDED), request, actor());

        ArgumentCaptor<SyncObjectExecution> rowCaptor = ArgumentCaptor.forClass(SyncObjectExecution.class);
        verify(fixture.objectExecutionMapper()).updateById(rowCaptor.capture());
        assertThat(rowCaptor.getValue().getId()).isEqualTo(12L);
        assertThat(rowCaptor.getValue().getAttemptCount()).isZero();
        assertThat(rowCaptor.getValue().getMaxAttemptCount()).isEqualTo(4);
        assertThat(result.retryObjectCount()).isEqualTo(1);
    }

    /**
     * 如果调用方选中了 SUCCEEDED 对象，必须拒绝，避免重复写入目标端。
     */
    @Test
    void retrySelectedSucceededObjectShouldBeRejected() {
        Fixture fixture = fixture();
        SyncObjectRetryRequest request = new SyncObjectRetryRequest();
        request.setObjectExecutionIds(List.of(11L));
        when(fixture.objectExecutionMapper().selectByExecutionId(88L))
                .thenReturn(List.of(objectRow(11L, 1, SyncObjectExecutionState.SUCCEEDED, 1, 3)));

        assertThrows(PlatformBusinessException.class,
                () -> fixture.support().retryFailedObjects(
                        task(), execution(SyncExecutionState.PARTIALLY_SUCCEEDED), request, actor()));

        verify(fixture.objectExecutionMapper(), never()).updateById(org.mockito.ArgumentMatchers.<SyncObjectExecution>any());
        verify(fixture.executionMapper(), never()).requeueTerminalObjectLevelRetry(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    /**
     * RUNNING execution 仍处于 worker 协议窗口内，不能被对象级重试接口重置。
     */
    @Test
    void retryRunningExecutionShouldBeRejectedBeforeReadingObjectLedger() {
        Fixture fixture = fixture();

        assertThrows(PlatformBusinessException.class,
                () -> fixture.support().retryFailedObjects(task(), execution(SyncExecutionState.RUNNING), null, actor()));

        verify(fixture.objectExecutionMapper(), never()).selectByExecutionId(org.mockito.ArgumentMatchers.any());
    }

    private Fixture fixture() {
        SyncObjectExecutionMapper objectExecutionMapper = mock(SyncObjectExecutionMapper.class);
        SyncExecutionMapper executionMapper = mock(SyncExecutionMapper.class);
        SyncTaskMapper taskMapper = mock(SyncTaskMapper.class);
        SyncAuditSupport auditSupport = mock(SyncAuditSupport.class);
        SyncObjectExecutionOperationSupport support = new SyncObjectExecutionOperationSupport(
                objectExecutionMapper,
                executionMapper,
                taskMapper,
                new SyncQuerySupport(),
                auditSupport
        );
        return new Fixture(support, objectExecutionMapper, executionMapper, taskMapper, auditSupport);
    }

    private SyncTask task() {
        SyncTask task = new SyncTask();
        task.setId(1L);
        task.setTenantId(7L);
        task.setProjectId(101L);
        task.setWorkspaceId(301L);
        task.setCurrentState(SyncTaskState.PARTIALLY_SUCCEEDED.name());
        task.setLastExecutionId(88L);
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
        return execution;
    }

    private SyncObjectExecution objectRow(Long id,
                                          int ordinal,
                                          SyncObjectExecutionState state,
                                          int attemptCount,
                                          int maxAttemptCount) {
        SyncObjectExecution row = new SyncObjectExecution();
        row.setId(id);
        row.setTenantId(7L);
        row.setProjectId(101L);
        row.setWorkspaceId(301L);
        row.setSyncTaskId(1L);
        row.setExecutionId(88L);
        row.setTemplateId(9L);
        row.setObjectOrdinal(ordinal);
        row.setSourceSchemaName("ods");
        row.setSourceObjectName("source_" + ordinal);
        row.setTargetSchemaName("dwd");
        row.setTargetObjectName("target_" + ordinal);
        row.setObjectState(state.name());
        row.setAttemptCount(attemptCount);
        row.setMaxAttemptCount(maxAttemptCount);
        row.setRecordsRead(100L);
        row.setRecordsWritten(80L);
        row.setFailedRecordCount(state == SyncObjectExecutionState.FAILED ? 1L : 0L);
        row.setLastErrorCode(state == SyncObjectExecutionState.FAILED ? "TARGET_WRITE_ERROR" : null);
        row.setStartedAt(LocalDateTime.now().minusMinutes(5));
        row.setFinishedAt(LocalDateTime.now().minusMinutes(1));
        return row;
    }

    private SyncActorContext actor() {
        return new SyncActorContext(
                7L,
                1001L,
                "OPERATOR",
                "trace-object-retry",
                "PROJECT",
                "project_id IN ${actorProjectIds}",
                List.of(101L),
                false
        );
    }

    /**
     * 测试夹具。
     *
     * <p>把 support 和 mapper mock 放在一起，避免每个测试重复声明大量依赖，让测试聚焦对象级恢复规则。</p>
     */
    private record Fixture(SyncObjectExecutionOperationSupport support,
                           SyncObjectExecutionMapper objectExecutionMapper,
                           SyncExecutionMapper executionMapper,
                           SyncTaskMapper taskMapper,
                           SyncAuditSupport auditSupport) {
    }
}
