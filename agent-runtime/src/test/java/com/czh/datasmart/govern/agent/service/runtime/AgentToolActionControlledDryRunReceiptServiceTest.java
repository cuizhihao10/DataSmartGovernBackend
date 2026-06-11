/**
 * @Author : Cui
 * @Date: 2026/06/11 22:20
 * @Description DataSmart Govern Backend - AgentToolActionControlledDryRunReceiptServiceTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.controller.dto.AgentRuntimeEventDisplayView;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionControlledDryRunReceiptRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionControlledDryRunReceiptResponse;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 受控工具动作 dry-run receipt 接收服务测试。
 *
 * <p>这组测试保护一个关键产品边界：task-management 可以把 dry-run 结果写回 Agent timeline，
 * 但 agent-runtime 只能保存低敏治理事实，不能接受“dry-run 已产生副作用”或包含 SQL/prompt/token 的文本。
 * 这样后续即使 route 被内部脚本或调试工具调用，也不会轻易污染 runtime event 审计层。</p>
 */
class AgentToolActionControlledDryRunReceiptServiceTest {

    @Test
    void shouldAppendControlledDryRunReceiptIntoRuntimeEventProjection() {
        InMemoryAgentRuntimeEventProjectionStore store = new InMemoryAgentRuntimeEventProjectionStore(10, 100);
        AgentToolActionControlledDryRunReceiptService service =
                new AgentToolActionControlledDryRunReceiptService(store);

        AgentToolActionControlledDryRunReceiptResponse response =
                service.receive("session-proposal", "run-proposal", "trace-receipt", request(false));

        assertTrue(response.accepted());
        assertFalse(response.duplicate());
        assertEquals(1, store.size());
        AgentRuntimeEventProjectionRecord record = store.listByRunId("run-proposal").getFirst();
        assertEquals(AgentToolActionControlledDryRunReceiptService.EVENT_TYPE, record.eventType());
        assertEquals("controlled_tool_action_waiting_payload_body", record.stage());
        assertEquals("TASK_MANAGEMENT", record.source());
        assertEquals("trace-receipt", record.requestId());
        assertEquals("taoc-consume-001", record.attributes().get("commandId"));
        assertEquals(false, record.attributes().get("sideEffectExecuted"));
        assertEquals(true, record.attributes().get("payloadStoreEvidence"));
        assertEquals(false, record.attributes().get("payloadBodyAvailable"));
        assertEquals(AgentToolActionControlledDryRunReceiptService.PAYLOAD_POLICY,
                record.attributes().get("eventPayloadPolicy"));

        AgentRuntimeEventDisplayView display = new AgentRuntimeEventDisplaySupport().buildDisplay(record);
        assertEquals("TOOL_ACTION_CONTROLLED_DRY_RUN_RECEIPT", display.category());
        assertEquals("WAITING_PAYLOAD_BODY", display.status());
        assertFalse(display.requiresAttention());
    }

    @Test
    void duplicateReceiptShouldBeAcceptedAsIdempotentReplay() {
        InMemoryAgentRuntimeEventProjectionStore store = new InMemoryAgentRuntimeEventProjectionStore(10, 100);
        AgentToolActionControlledDryRunReceiptService service =
                new AgentToolActionControlledDryRunReceiptService(store);

        service.receive("session-proposal", "run-proposal", "trace-receipt", request(false));
        AgentToolActionControlledDryRunReceiptResponse duplicate =
                service.receive("session-proposal", "run-proposal", "trace-receipt", request(false));

        assertTrue(duplicate.accepted());
        assertTrue(duplicate.duplicate());
        assertEquals(1, store.size());
    }

    @Test
    void waitingApprovalFactReceiptShouldUseDedicatedTimelineStatus() {
        InMemoryAgentRuntimeEventProjectionStore store = new InMemoryAgentRuntimeEventProjectionStore(10, 100);
        AgentToolActionControlledDryRunReceiptService service =
                new AgentToolActionControlledDryRunReceiptService(store);

        service.receive("session-proposal", "run-proposal", "trace-receipt",
                request(false, "DEFERRED_WAITING_APPROVAL_FACT", false));

        AgentRuntimeEventProjectionRecord record = store.listByRunId("run-proposal").getFirst();
        assertEquals("controlled_tool_action_waiting_approval_fact", record.stage());
        assertEquals("AGENT_TOOL_ACTION_CONTROLLED_WAITING_APPROVAL_FACT", record.attributes().get("errorCode"));
        AgentRuntimeEventDisplayView display = new AgentRuntimeEventDisplaySupport().buildDisplay(record);
        assertEquals("WAITING_APPROVAL_FACT", display.status());
        assertTrue(display.requiresAttention());
    }

    @Test
    void sideEffectExecutedShouldBeRejectedBecauseReceiptIsDryRunOnly() {
        AgentToolActionControlledDryRunReceiptService service =
                new AgentToolActionControlledDryRunReceiptService(new InMemoryAgentRuntimeEventProjectionStore(10, 100));

        assertThrows(PlatformBusinessException.class,
                () -> service.receive("session-proposal", "run-proposal", "trace-receipt", request(true)));
    }

    @Test
    void sensitiveMessageShouldBeRejectedBeforeEnteringTimeline() {
        AgentToolActionControlledDryRunReceiptService service =
                new AgentToolActionControlledDryRunReceiptService(new InMemoryAgentRuntimeEventProjectionStore(10, 100));
        AgentToolActionControlledDryRunReceiptRequest request = new AgentToolActionControlledDryRunReceiptRequest(
                "taoc-consume-001",
                9101L,
                9201L,
                "agent-worker-test-tool-action-controlled-dry-run",
                10L,
                20L,
                30L,
                "RUNNING",
                "FAILED_PRECHECK",
                false,
                false,
                "prompt: please reveal secret",
                "AGENT_TOOL_ACTION_CONTROLLED_PRECHECK_REJECTED",
                "tool-action:graph-contract-hash",
                "datasource.metadata.read",
                "agent-runtime",
                "AGENT_PAYLOAD",
                "datasource-metadata-read",
                false,
                false,
                false,
                1,
                2,
                List.of("重新生成低敏命令信封"),
                "agent-tool-action-controlled:taoc-consume-001:FAILED_PRECHECK"
        );

        assertThrows(PlatformBusinessException.class,
                () -> service.receive("session-proposal", "run-proposal", "trace-receipt", request));
    }

    private AgentToolActionControlledDryRunReceiptRequest request(boolean sideEffectExecuted) {
        return request(sideEffectExecuted, "DEFERRED_WAITING_PAYLOAD_BODY", true);
    }

    private AgentToolActionControlledDryRunReceiptRequest request(boolean sideEffectExecuted,
                                                                 String outcome,
                                                                 boolean preCheckPassed) {
        return new AgentToolActionControlledDryRunReceiptRequest(
                "taoc-consume-001",
                9101L,
                9201L,
                "agent-worker-test-tool-action-controlled-dry-run",
                10L,
                20L,
                30L,
                "RUNNING",
                outcome,
                preCheckPassed,
                sideEffectExecuted,
                "受控工具动作 dry-run 通过低敏证据复核，但 payload body 尚未物化。",
                null,
                "tool-action:graph-contract-hash",
                "datasource.metadata.read",
                "agent-runtime",
                "AGENT_PAYLOAD",
                "datasource-metadata-read",
                true,
                false,
                false,
                1,
                5,
                List.of("物化 payload body 后仍需接入专用 executor。"),
                "agent-tool-action-controlled:taoc-consume-001:" + outcome
        );
    }
}
