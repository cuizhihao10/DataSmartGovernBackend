/**
 * @Author : Cui
 * @Date: 2026/06/05 00:00
 * @Description DataSmart Govern Backend - AgentSessionHandoffDagExecutionBridgeServiceTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.execution;

import com.czh.datasmart.govern.agent.controller.dto.AgentRunToolDagExecutionDryRunRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentRunToolDagExecutionDryRunResponse;
import com.czh.datasmart.govern.agent.controller.dto.AgentSessionHandoffDagExecutionBridgePreviewRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentSessionHandoffDagExecutionBridgePreviewResponse;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolDagExecutionDryRunItemView;
import com.czh.datasmart.govern.agent.model.AgentRunState;
import com.czh.datasmart.govern.agent.model.AgentHandoffDagBridgeSourceEvidence;
import com.czh.datasmart.govern.agent.model.WorkspaceIsolationLevel;
import com.czh.datasmart.govern.agent.service.runtime.AgentRuntimeEventProjectionRecord;
import com.czh.datasmart.govern.agent.service.runtime.InMemoryAgentRuntimeEventProjectionStore;
import com.czh.datasmart.govern.agent.service.session.AgentRunRecord;
import com.czh.datasmart.govern.agent.service.session.AgentSessionMemoryStore;
import com.czh.datasmart.govern.agent.service.session.AgentSessionRecord;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Master Agent handoff DAG 执行桥接预检测试。
 *
 * <p>这组测试不重新验证 Tool DAG dry-run 本身，因为 dry-run 已有独立测试。这里保护的是“handoff DAG
 * 到 dry-run 的桥接规则”：只有 tool-control 才会被翻译为工具执行预案；桥接接口只生成模板，不直接确认；
 * 没有工具候选时不能被误判为可执行。</p>
 */
class AgentSessionHandoffDagExecutionBridgeServiceTest {

    private static final String SESSION_ID = "session-handoff-bridge-001";
    private static final String RUN_ID = "run-handoff-bridge-001";
    private static final LocalDateTime BASE_TIME = LocalDateTime.of(2026, 6, 5, 0, 0);

    @Test
    void toolControlNodeShouldPreviewDryRunAndBuildSelectedNodeTemplate() {
        AgentRunToolDagExecutionDryRunService dryRunService = mock(AgentRunToolDagExecutionDryRunService.class);
        AgentSessionHandoffDagExecutionBridgeEventPublisher bridgeEventPublisher = mock(AgentSessionHandoffDagExecutionBridgeEventPublisher.class);
        when(dryRunService.dryRunDagExecution(eq(SESSION_ID), eq(RUN_ID), any(AgentRunToolDagExecutionDryRunRequest.class), eq("trace-bridge")))
                .thenReturn(dryRunResponse(List.of(asyncItem("tool-node-1", "audit-1"))));
        AgentSessionHandoffDagExecutionBridgeService service = new AgentSessionHandoffDagExecutionBridgeService(dryRunService, bridgeEventPublisher);

        AgentSessionHandoffDagExecutionBridgePreviewResponse response = service.previewBridge(
                SESSION_ID,
                RUN_ID,
                new AgentSessionHandoffDagExecutionBridgePreviewRequest(
                        List.of("tool-control"),
                        List.of("tool-node-1"),
                        List.of(),
                        10,
                        false,
                        true
                ),
                "trace-bridge"
        );

        assertTrue(response.bridgeReady());
        assertEquals("TOOL_CONTROL_DRY_RUN", response.bridgeAction());
        assertEquals(List.of("tool-control"), response.handoffNodeIds());
        assertEquals(List.of("tool-node-1"), response.mappedToolNodeIds());
        assertEquals("dag-selection:test", response.selectedNodeOutboxRequestTemplate().get("expectedDryRunFingerprint"));
        assertEquals(false, response.selectedNodeOutboxRequestTemplate().get("confirmed"));
        assertEquals(List.of("tool-node-1"), response.selectedNodeOutboxRequestTemplate().get("nodeIds"));
        assertEquals(List.of("audit-1"), response.selectedNodeOutboxRequestTemplate().get("auditIds"));
        assertEquals(Map.of("audit-1", "policy:v1"), response.selectedNodeOutboxRequestTemplate().get("expectedPolicyVersionsByAuditId"));
        AgentHandoffDagBridgeSourceEvidence bridgeSourceEvidence =
                (AgentHandoffDagBridgeSourceEvidence) response.selectedNodeOutboxRequestTemplate().get("bridgeSourceEvidence");
        assertEquals(AgentHandoffDagBridgeSourceEvidence.SOURCE_TYPE_HANDOFF_DAG_BRIDGE_PREVIEW, bridgeSourceEvidence.sourceType());
        assertEquals("TOOL_CONTROL_DRY_RUN", bridgeSourceEvidence.bridgeAction());
        assertEquals("dag-selection:test", bridgeSourceEvidence.selectionFingerprint());
        assertEquals(List.of("audit-1"), bridgeSourceEvidence.mappedToolAuditIds());
        verify(dryRunService).dryRunDagExecution(eq(SESSION_ID), eq(RUN_ID), any(AgentRunToolDagExecutionDryRunRequest.class), eq("trace-bridge"));
        verify(bridgeEventPublisher).publish(eq(SESSION_ID), eq(RUN_ID), eq("trace-bridge"), any(AgentSessionHandoffDagExecutionBridgePreviewRequest.class), eq(response));
    }

    @Test
    void nonToolControlHandoffNodeShouldNotBecomeExecutableToolBridge() {
        AgentRunToolDagExecutionDryRunService dryRunService = mock(AgentRunToolDagExecutionDryRunService.class);
        AgentSessionHandoffDagExecutionBridgeEventPublisher bridgeEventPublisher = mock(AgentSessionHandoffDagExecutionBridgeEventPublisher.class);
        when(dryRunService.dryRunDagExecution(eq(SESSION_ID), eq(RUN_ID), any(AgentRunToolDagExecutionDryRunRequest.class), eq("trace-bridge")))
                .thenReturn(dryRunResponse(List.of()));
        AgentSessionHandoffDagExecutionBridgeService service = new AgentSessionHandoffDagExecutionBridgeService(dryRunService, bridgeEventPublisher);

        AgentSessionHandoffDagExecutionBridgePreviewResponse response = service.previewBridge(
                SESSION_ID,
                RUN_ID,
                new AgentSessionHandoffDagExecutionBridgePreviewRequest(
                        List.of("feedback"),
                        List.of("tool-node-1"),
                        List.of(),
                        null,
                        false,
                        true
                ),
                "trace-bridge"
        );

        assertFalse(response.bridgeReady());
        assertEquals("HANDOFF_NODE_NOT_EXECUTABLE", response.bridgeAction());
        assertTrue(response.selectedNodeOutboxRequestTemplate().get("nodeIds") instanceof List<?>);
        assertTrue(((List<?>) response.selectedNodeOutboxRequestTemplate().get("nodeIds")).isEmpty());
        verify(bridgeEventPublisher).publish(eq(SESSION_ID), eq(RUN_ID), eq("trace-bridge"), any(AgentSessionHandoffDagExecutionBridgePreviewRequest.class), eq(response));
    }

    @Test
    void toolControlWithoutDryRunCandidateShouldRemainPreviewOnly() {
        AgentRunToolDagExecutionDryRunService dryRunService = mock(AgentRunToolDagExecutionDryRunService.class);
        AgentSessionHandoffDagExecutionBridgeEventPublisher bridgeEventPublisher = mock(AgentSessionHandoffDagExecutionBridgeEventPublisher.class);
        when(dryRunService.dryRunDagExecution(eq(SESSION_ID), eq(RUN_ID), any(AgentRunToolDagExecutionDryRunRequest.class), eq("trace-bridge")))
                .thenReturn(dryRunResponse(List.of()));
        AgentSessionHandoffDagExecutionBridgeService service = new AgentSessionHandoffDagExecutionBridgeService(dryRunService, bridgeEventPublisher);

        AgentSessionHandoffDagExecutionBridgePreviewResponse response = service.previewBridge(
                SESSION_ID,
                RUN_ID,
                new AgentSessionHandoffDagExecutionBridgePreviewRequest(
                        List.of("tool-control"),
                        List.of(),
                        List.of(),
                        null,
                        false,
                        true
                ),
                "trace-bridge"
        );

        assertFalse(response.bridgeReady());
        assertEquals("NO_TOOL_CANDIDATE", response.bridgeAction());
        assertTrue(response.recommendedActions().getFirst().contains("当前没有可推进工具候选"));
    }

    @Test
    void bridgePreviewShouldAppendLowSensitiveRuntimeEvent() {
        AgentRunToolDagExecutionDryRunService dryRunService = mock(AgentRunToolDagExecutionDryRunService.class);
        when(dryRunService.dryRunDagExecution(eq(SESSION_ID), eq(RUN_ID), any(AgentRunToolDagExecutionDryRunRequest.class), eq("trace-bridge-event")))
                .thenReturn(dryRunResponse(List.of(asyncItem("tool-node-1", "audit-1"))));
        InMemoryAgentRuntimeEventProjectionStore projectionStore = new InMemoryAgentRuntimeEventProjectionStore(20, 100);
        AgentSessionMemoryStore sessionStore = new AgentSessionMemoryStore();
        sessionStore.save(session());
        AgentSessionHandoffDagExecutionBridgeEventPublisher bridgeEventPublisher = new AgentSessionHandoffDagExecutionBridgeEventPublisher(
                projectionStore,
                sessionStore
        );
        AgentSessionHandoffDagExecutionBridgeService service = new AgentSessionHandoffDagExecutionBridgeService(dryRunService, bridgeEventPublisher);

        service.previewBridge(
                SESSION_ID,
                RUN_ID,
                new AgentSessionHandoffDagExecutionBridgePreviewRequest(
                        List.of("tool-control"),
                        List.of("tool-node-1"),
                        List.of("audit-1"),
                        10,
                        false,
                        true
                ),
                "trace-bridge-event"
        );

        List<AgentRuntimeEventProjectionRecord> events = projectionStore.listByRunId(RUN_ID);
        assertEquals(1, events.size());
        AgentRuntimeEventProjectionRecord event = events.getFirst();
        assertEquals(AgentSessionHandoffDagExecutionBridgeEventPublisher.EVENT_TYPE, event.eventType());
        assertEquals("handoff_dag_execution_bridge_previewed", event.stage());
        assertEquals("trace-bridge-event", event.requestId());
        assertEquals("10", event.tenantId());
        assertEquals("20", event.projectId());
        assertEquals("actor-handoff-bridge", event.actorId());
        assertEquals(true, event.attributes().get("previewOnly"));
        assertEquals(true, event.attributes().get("bridgeReady"));
        assertEquals("TOOL_CONTROL_DRY_RUN", event.attributes().get("bridgeAction"));
        assertEquals(List.of("tool-control"), event.attributes().get("handoffNodeIds"));
        assertEquals(List.of("tool-node-1"), event.attributes().get("mappedToolNodeIds"));
        assertEquals(List.of("audit-1"), event.attributes().get("mappedToolAuditIds"));
        assertEquals(1, event.attributes().get("templateAsyncNodeCount"));
        assertEquals(1, event.attributes().get("templateAsyncAuditCount"));
        assertEquals(false, event.attributes().get("templateConfirmedDefault"));
        assertEquals("SUMMARY_ONLY_NO_TOOL_ARGS_NO_PROMPT_NO_EXECUTION_PATH_NO_TEMPLATE_BODY", event.attributes().get("eventPayloadPolicy"));
        assertFalse(event.attributes().containsKey("executionPath"));
        assertFalse(event.attributes().containsKey("targetEndpoint"));
        assertFalse(event.attributes().containsKey("toolArguments"));
        assertFalse(event.attributes().containsKey("prompt"));
        assertFalse(event.attributes().containsKey("sql"));
        assertFalse(event.attributes().containsKey("selectedNodeOutboxRequestTemplate"));
    }

    @Test
    void bridgeEventPublisherFailureShouldNotBlockPreviewResponse() {
        AgentRunToolDagExecutionDryRunService dryRunService = mock(AgentRunToolDagExecutionDryRunService.class);
        AgentSessionHandoffDagExecutionBridgeEventPublisher bridgeEventPublisher = mock(AgentSessionHandoffDagExecutionBridgeEventPublisher.class);
        when(dryRunService.dryRunDagExecution(eq(SESSION_ID), eq(RUN_ID), any(AgentRunToolDagExecutionDryRunRequest.class), eq("trace-bridge")))
                .thenReturn(dryRunResponse(List.of(asyncItem("tool-node-1", "audit-1"))));
        org.mockito.Mockito.doThrow(new RuntimeException("projection down"))
                .when(bridgeEventPublisher)
                .publish(eq(SESSION_ID), eq(RUN_ID), eq("trace-bridge"), any(AgentSessionHandoffDagExecutionBridgePreviewRequest.class), any(AgentSessionHandoffDagExecutionBridgePreviewResponse.class));
        AgentSessionHandoffDagExecutionBridgeService service = new AgentSessionHandoffDagExecutionBridgeService(dryRunService, bridgeEventPublisher);

        AgentSessionHandoffDagExecutionBridgePreviewResponse response = service.previewBridge(
                SESSION_ID,
                RUN_ID,
                new AgentSessionHandoffDagExecutionBridgePreviewRequest(
                        List.of("tool-control"),
                        List.of("tool-node-1"),
                        List.of(),
                        10,
                        false,
                        true
                ),
                "trace-bridge"
        );

        assertTrue(response.bridgeReady());
    }

    private AgentSessionRecord session() {
        AgentSessionRecord session = new AgentSessionRecord(
                SESSION_ID,
                10L,
                20L,
                30L,
                "actor-handoff-bridge",
                "JAVA_AGENT_RUNTIME",
                "Handoff DAG 桥接预览事件测试会话",
                WorkspaceIsolationLevel.PROJECT,
                "tenant:10:project:20",
                BASE_TIME
        );
        session.addRun(new AgentRunRecord(
                RUN_ID,
                SESSION_ID,
                AgentRunState.PLANNING,
                "HANDOFF_DAG_BRIDGE_PREVIEW",
                "测试 handoff DAG bridge preview runtime event",
                true,
                false,
                List.of(),
                Map.of(),
                BASE_TIME,
                "Run 已创建"
        ));
        return session;
    }

    private AgentRunToolDagExecutionDryRunResponse dryRunResponse(List<AgentToolDagExecutionDryRunItemView> items) {
        return new AgentRunToolDagExecutionDryRunResponse(
                SESSION_ID,
                RUN_ID,
                true,
                List.of("tool-node-1"),
                List.of(),
                10,
                10,
                "dag-selection:test",
                items.size(),
                0,
                (int) items.stream()
                        .filter(item -> AgentToolDagExecutionDryRunAction.ASYNC_OUTBOX_ENQUEUE_PREVIEW.name().equals(item.dryRunAction()))
                        .count(),
                0,
                0,
                0,
                0,
                List.of("测试 dry-run 摘要。"),
                List.of("测试 dry-run 下一步。"),
                items
        );
    }

    private AgentToolDagExecutionDryRunItemView asyncItem(String nodeId, String auditId) {
        return new AgentToolDagExecutionDryRunItemView(
                nodeId,
                auditId,
                "nodeId:" + nodeId,
                "data-sync.execute",
                true,
                "ASYNC_COMMAND_DISPATCH_CANDIDATE",
                AgentToolDagExecutionDryRunAction.ASYNC_OUTBOX_ENQUEUE_PREVIEW.name(),
                "异步 outbox enqueue 预案",
                true,
                true,
                true,
                "aatc-test",
                "ALLOW",
                true,
                List.of("policy:v1"),
                List.of("delegation:evidence:test"),
                true,
                "TENANT_PROJECT_WORKSPACE",
                List.of(),
                List.of(),
                List.of(),
                true,
                0,
                0,
                0,
                100,
                20,
                10,
                false,
                null,
                0,
                List.of(),
                List.of(),
                List.of(),
                "MEDIUM",
                false,
                true,
                false,
                List.of("该节点可进入异步 outbox 预案。"),
                List.of("确认后调用 selected-node outbox 入箱。")
        );
    }
}
