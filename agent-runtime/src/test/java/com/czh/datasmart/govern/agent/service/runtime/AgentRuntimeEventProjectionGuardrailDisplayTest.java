/**
 * @Author : Cui
 * @Date: 2026/07/02 01:30
 * @Description DataSmart Govern Backend - AgentRuntimeEventProjectionGuardrailDisplayTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.config.AgentRuntimeEventConsumerProperties;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Agent worker guardrail 事件展示测试。
 *
 * <p>该场景从综合查询测试中拆出，专门验证 permission denied、policy drift、confirmation unavailable
 * 等执行前保护事件不会被当成普通系统日志。前端依赖稳定 category/status/metrics 展示阻断原因，
 * 因此该测试同时保护低敏错误码和推荐动作契约。</p>
 */
class AgentRuntimeEventProjectionGuardrailDisplayTest {

    @Test
    void queryShouldExposeDisplayForAgentToolGuardrailEvent() {
        InMemoryAgentRuntimeEventProjectionStore store = new InMemoryAgentRuntimeEventProjectionStore(10, 100);
        store.append(new AgentRuntimeEventProjectionRecord(
                "guardrail-permission-denied-event",
                "agent-tool-execution-event.v1",
                "agent-runtime",
                "agent.tool_execution.state_changed",
                "tool_failed",
                "permission-admin 拒绝 Agent worker 执行已确认异步工具，reason=命中显式拒绝策略",
                "error",
                "10",
                "20",
                "1001",
                "trace-guardrail",
                "run-test",
                "session-test",
                1L,
                Instant.parse("2026-06-01T22:26:01Z"),
                Instant.parse("2026-06-01T22:26:01Z"),
                Instant.parse("2026-06-01T22:26:02Z"),
                Map.of(
                        "errorCode", "AGENT_ASYNC_TOOL_PERMISSION_DENIED",
                        "toolCode", "data-sync.execute",
                        "currentState", "FAILED",
                        "targetService", "data-sync"
                )
        ));
        AgentRuntimeEventProjectionQueryService queryService = new AgentRuntimeEventProjectionQueryService(
                store,
                new AgentRuntimeEventConsumerStats(),
                properties(),
                new AgentRuntimeEventProjectionAccessSupport(),
                new AgentRuntimeEventVisibilitySupport(),
                new AgentRuntimeEventDisplaySupport()
        );

        var response = queryService.query(new AgentRuntimeEventProjectionQuery(
                null, null, null, null, "run-test", null, null, null, 10
        ));

        var display = response.events().getFirst().display();
        assertEquals("AGENT_TOOL_GUARDRAIL", display.category());
        assertEquals("Agent 工具被权限策略阻断", display.title());
        assertEquals("BLOCKED_BEFORE_SIDE_EFFECT", display.status());
        assertTrue(display.requiresAttention());
        assertTrue(display.recommendedActions().stream().anyMatch(action -> action.contains("permission-admin")));
        assertEquals("AGENT_ASYNC_TOOL_PERMISSION_DENIED", display.metrics().get("errorCode"));
    }

    private AgentRuntimeEventConsumerProperties properties() {
        AgentRuntimeEventConsumerProperties properties = new AgentRuntimeEventConsumerProperties();
        properties.setEnabled(false);
        properties.setTopic("datasmart.agent-runtime.events");
        properties.setGroupId("datasmart-agent-runtime-control-plane");
        properties.setMaxEventsPerRun(1000);
        properties.setMaxTotalEvents(10000);
        return properties;
    }
}
