/**
 * @Author : Cui
 * @Date: 2026/05/31 23:59
 * @Description DataSmart Govern Backend - AgentAsyncToolTaskStatusCallbackServiceTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service;

import com.czh.datasmart.govern.agent.controller.dto.AgentAsyncToolTaskStatusCallbackRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentAsyncToolTaskStatusCallbackResponse;
import com.czh.datasmart.govern.agent.event.AgentToolExecutionEventPublisher;
import com.czh.datasmart.govern.agent.model.AgentToolExecutionMode;
import com.czh.datasmart.govern.agent.model.AgentToolExecutionState;
import com.czh.datasmart.govern.agent.model.AgentToolRiskLevel;
import com.czh.datasmart.govern.agent.service.audit.AgentToolExecutionAuditMemoryStore;
import com.czh.datasmart.govern.agent.service.audit.AgentToolExecutionAuditRecord;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Agent 异步工具任务状态回调服务测试。
 *
 * <p>这组测试保护 4.53 的核心产品能力：task-management worker 执行出的真实状态必须回到 agent-runtime 审计和事件体系。
 * 如果这里退化，前端、Python 二轮推理和审计台都会看不到异步工具的真实进度。</p>
 */
class AgentAsyncToolTaskStatusCallbackServiceTest {

    @Test
    void shouldMapRunningAndSucceededCallbacksToAuditStateEvents() {
        AgentToolExecutionAuditMemoryStore store = new AgentToolExecutionAuditMemoryStore();
        CollectingPublisher publisher = new CollectingPublisher();
        AgentToolExecutionAuditService auditService = new AgentToolExecutionAuditService(store, publisher);
        AgentAsyncToolTaskStatusCallbackService callbackService = new AgentAsyncToolTaskStatusCallbackService(auditService);
        AgentToolExecutionAuditRecord record = auditRecord(AgentToolExecutionState.PLANNED);
        store.save(record);

        AgentAsyncToolTaskStatusCallbackResponse running = callbackService.applyStatusCallback(
                record.getSessionId(), record.getRunId(), record.getAuditId(),
                request("RUNNING", "worker 已领取 data-sync.execute", null)
        );
        AgentAsyncToolTaskStatusCallbackResponse succeeded = callbackService.applyStatusCallback(
                record.getSessionId(), record.getRunId(), record.getAuditId(),
                request("SUCCEEDED", "data-sync.execute 执行成功", "syncTaskId")
        );

        assertEquals("EXECUTING", running.state());
        assertEquals("SUCCEEDED", succeeded.state());
        assertEquals(2, publisher.transitions.size());
        assertEquals(new PublishedTransition(AgentToolExecutionState.PLANNED, AgentToolExecutionState.EXECUTING),
                publisher.transitions.get(0));
        assertEquals(new PublishedTransition(AgentToolExecutionState.EXECUTING, AgentToolExecutionState.SUCCEEDED),
                publisher.transitions.get(1));
    }

    @Test
    void shouldKeepDeferredCallbackInExecutingState() {
        AgentToolExecutionAuditMemoryStore store = new AgentToolExecutionAuditMemoryStore();
        AgentToolExecutionAuditService auditService = new AgentToolExecutionAuditService(store, (previousState, record) -> {
        });
        AgentAsyncToolTaskStatusCallbackService callbackService = new AgentAsyncToolTaskStatusCallbackService(auditService);
        AgentToolExecutionAuditRecord record = auditRecord(AgentToolExecutionState.PLANNED);
        store.save(record);

        AgentAsyncToolTaskStatusCallbackResponse response = callbackService.applyStatusCallback(
                record.getSessionId(), record.getRunId(), record.getAuditId(),
                request("DEFERRED", "data-sync 暂时不可用，任务回队列等待重试", null)
        );

        assertEquals("EXECUTING", response.state());
    }

    private AgentAsyncToolTaskStatusCallbackRequest request(String status, String message, String outputSummary) {
        return new AgentAsyncToolTaskStatusCallbackRequest(
                "cmd-001",
                9001L,
                9101L,
                "agent-worker-test",
                status,
                message,
                null,
                outputSummary,
                Map.of("syncTaskId", 7001L),
                "agent-async-tool:cmd-001:" + status
        );
    }

    private AgentToolExecutionAuditRecord auditRecord(AgentToolExecutionState state) {
        return new AgentToolExecutionAuditRecord(
                "atea-async-callback-001",
                "session-async-001",
                "run-async-001",
                "binding-async-001",
                "data-sync.execute",
                "DATA_SYNC",
                "data-sync",
                "/internal/data-sync/agent/tasks/execute",
                null,
                10L,
                20L,
                30L,
                "actor-async",
                AgentToolRiskLevel.MEDIUM.name(),
                AgentToolExecutionMode.ASYNC_TASK.name(),
                false,
                false,
                true,
                List.of("EXECUTE"),
                "模型判断需要发起一次数据同步任务。",
                Map.of("syncTemplateId", 6001L),
                Map.of("sensitiveFields", List.of()),
                Map.of("missingRequiredFields", List.of()),
                state,
                "trace-async-001",
                "异步工具计划已创建，等待 task-management worker 执行。",
                LocalDateTime.of(2026, 5, 31, 23, 59)
        );
    }

    private static class CollectingPublisher implements AgentToolExecutionEventPublisher {

        private final List<PublishedTransition> transitions = new ArrayList<>();

        @Override
        public void publishStateChanged(AgentToolExecutionState previousState, AgentToolExecutionAuditRecord record) {
            transitions.add(new PublishedTransition(previousState, record.getState()));
        }
    }

    private record PublishedTransition(AgentToolExecutionState previousState,
                                       AgentToolExecutionState currentState) {
    }
}
