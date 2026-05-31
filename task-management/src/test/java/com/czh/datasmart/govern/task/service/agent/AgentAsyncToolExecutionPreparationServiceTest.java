/**
 * @Author : Cui
 * @Date: 2026/05/31 18:16
 * @Description DataSmart Govern Backend - AgentAsyncToolExecutionPreparationServiceTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.service.agent;

import com.czh.datasmart.govern.task.controller.dto.TaskActorContext;
import com.czh.datasmart.govern.task.entity.Task;
import com.czh.datasmart.govern.task.service.TaskService;
import com.czh.datasmart.govern.task.service.support.TaskOperationPermissionSupport;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Agent 异步工具执行准备服务测试。
 *
 * <p>该测试验证 preparation service 的编排责任：必须先做执行器角色校验，再读取任务详情，
 * 最后才委托 payload resolver。这样内部预检接口不会绕过 task-management 既有权限和数据范围规则。</p>
 */
class AgentAsyncToolExecutionPreparationServiceTest {

    @Test
    void serviceAccountShouldPreparePayloadThroughTaskServiceAndResolver() {
        TaskService taskService = mock(TaskService.class);
        AgentAsyncToolPayloadResolver resolver = mock(AgentAsyncToolPayloadResolver.class);
        AgentAsyncToolExecutionPreparationService service = new AgentAsyncToolExecutionPreparationService(
                taskService,
                new TaskOperationPermissionSupport(),
                resolver
        );
        Task task = new Task();
        task.setId(9001L);
        TaskActorContext actorContext = new TaskActorContext(0L, null, "SERVICE_ACCOUNT", "trace-worker", null, List.of());
        AgentAsyncToolResolvedPayload expected = payload();
        when(taskService.getTaskDetail(9001L, actorContext)).thenReturn(task);
        when(resolver.resolve(task, "trace-worker")).thenReturn(expected);

        AgentAsyncToolResolvedPayload actual = service.preparePayload(9001L, actorContext);

        assertEquals(expected, actual);
        verify(taskService).getTaskDetail(9001L, actorContext);
        verify(resolver).resolve(eq(task), eq("trace-worker"));
    }

    @Test
    void ordinaryUserShouldNotPrepareWorkerPayload() {
        AgentAsyncToolExecutionPreparationService service = new AgentAsyncToolExecutionPreparationService(
                mock(TaskService.class),
                new TaskOperationPermissionSupport(),
                mock(AgentAsyncToolPayloadResolver.class)
        );
        TaskActorContext actorContext = new TaskActorContext(10L, 1001L, "USER", "trace-user", null, List.of());

        assertThrows(IllegalStateException.class, () -> service.preparePayload(9001L, actorContext));
    }

    private AgentAsyncToolResolvedPayload payload() {
        return new AgentAsyncToolResolvedPayload(
                9001L,
                "PENDING",
                "AGENT_ASYNC_TOOL",
                "aatc-worker-001",
                "agent-tool-audit://session-worker-001/run-worker-001/atea-worker-001/plan-arguments",
                "plan-arguments",
                "session-worker-001",
                "run-worker-001",
                "atea-worker-001",
                "data-sync.execute",
                "data-sync",
                "/sync-tasks",
                10L,
                20L,
                30L,
                "actor-worker",
                "trace-worker",
                "ASYNC_TASK",
                "PLANNED",
                List.of("datasourceId"),
                List.of(),
                21,
                true,
                Map.of("datasourceId", 1001L),
                Map.of(),
                Map.of(),
                List.of("预检通过"),
                List.of("等待执行器适配器"),
                LocalDateTime.now()
        );
    }
}
