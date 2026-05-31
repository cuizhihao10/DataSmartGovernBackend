/**
 * @Author : Cui
 * @Date: 2026/05/31 23:55
 * @Description DataSmart Govern Backend - DataSyncAgentTaskExecutionServiceImplTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.impl;

import com.czh.datasmart.govern.datasync.controller.dto.AgentSyncTaskExecuteRequest;
import com.czh.datasmart.govern.datasync.controller.dto.AgentSyncTaskExecuteResponse;
import com.czh.datasmart.govern.datasync.controller.dto.CreateSyncTaskRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskOperationResult;
import com.czh.datasmart.govern.datasync.entity.SyncTask;
import com.czh.datasmart.govern.datasync.service.DataSyncService;
import com.czh.datasmart.govern.datasync.service.support.SyncCallbackIdempotencySupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Agent 数据同步内部执行服务测试。
 *
 * <p>测试重点不是验证公开 createTask/runTask 的细节，而是验证 Agent 专用入口是否把“创建任务 + 入队执行”
 * 包装成单个幂等业务动作，并且没有绕过 data-sync 既有服务层。</p>
 */
class DataSyncAgentTaskExecutionServiceImplTest {

    @Test
    void shouldCreateAndQueueSyncTaskOnceForAgentCommand() {
        DataSyncService dataSyncService = mock(DataSyncService.class);
        SyncCallbackIdempotencySupport idempotencySupport = mock(SyncCallbackIdempotencySupport.class);
        DataSyncAgentTaskExecutionServiceImpl service = new DataSyncAgentTaskExecutionServiceImpl(
                dataSyncService,
                idempotencySupport,
                new ObjectMapper()
        );
        AgentSyncTaskExecuteRequest request = request();
        SyncTask createdTask = task(7001L, null);
        SyncTask queuedTask = task(7001L, 8001L);
        when(idempotencySupport.isDuplicate(eq(10L), eq(null), eq(null), eq("AGENT_EXECUTE_SYNC_TASK"),
                eq("agent:session-001:run-001:audit-001"), eq("idem-001"),
                eq("task-management-agent-async-worker"), any())).thenReturn(false);
        when(dataSyncService.createTask(any(CreateSyncTaskRequest.class), any(SyncActorContext.class))).thenReturn(createdTask);
        when(dataSyncService.runTask(eq(7001L), any(SyncActorContext.class)))
                .thenReturn(new SyncTaskOperationResult(7001L, "QUEUED", "queued"));
        when(dataSyncService.getTask(eq(7001L), any(SyncActorContext.class))).thenReturn(queuedTask);

        AgentSyncTaskExecuteResponse response = service.executeAgentSyncTask(request);

        assertEquals("cmd-001", response.commandId());
        assertEquals(7001L, response.syncTaskId());
        assertEquals(8001L, response.syncExecutionId());
        assertEquals("QUEUED", response.state());
        assertFalse(response.duplicate());
        verify(dataSyncService).createTask(any(CreateSyncTaskRequest.class), any(SyncActorContext.class));
        verify(dataSyncService).runTask(eq(7001L), any(SyncActorContext.class));
        verify(idempotencySupport).markSucceeded(eq(10L), eq("AGENT_EXECUTE_SYNC_TASK"),
                eq("agent:session-001:run-001:audit-001"), eq("idem-001"), any());
    }

    private AgentSyncTaskExecuteRequest request() {
        AgentSyncTaskExecuteRequest request = new AgentSyncTaskExecuteRequest();
        request.setCommandId("cmd-001");
        request.setIdempotencyKey("idem-001");
        request.setAuditId("audit-001");
        request.setSessionId("session-001");
        request.setRunId("run-001");
        request.setToolCode("data-sync.execute");
        request.setTenantId(10L);
        request.setProjectId(20L);
        request.setWorkspaceId(30L);
        request.setActorId("1001");
        request.setTraceId("trace-001");
        request.setSyncTemplateId(6001L);
        return request;
    }

    private SyncTask task(Long taskId, Long executionId) {
        SyncTask task = new SyncTask();
        task.setId(taskId);
        task.setTenantId(10L);
        task.setProjectId(20L);
        task.setWorkspaceId(30L);
        task.setLastExecutionId(executionId);
        return task;
    }
}
