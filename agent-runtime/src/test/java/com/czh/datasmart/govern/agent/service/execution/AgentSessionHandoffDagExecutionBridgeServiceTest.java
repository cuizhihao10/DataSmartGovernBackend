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
import org.junit.jupiter.api.Test;

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

    @Test
    void toolControlNodeShouldPreviewDryRunAndBuildSelectedNodeTemplate() {
        AgentRunToolDagExecutionDryRunService dryRunService = mock(AgentRunToolDagExecutionDryRunService.class);
        when(dryRunService.dryRunDagExecution(eq(SESSION_ID), eq(RUN_ID), any(AgentRunToolDagExecutionDryRunRequest.class), eq("trace-bridge")))
                .thenReturn(dryRunResponse(List.of(asyncItem("tool-node-1", "audit-1"))));
        AgentSessionHandoffDagExecutionBridgeService service = new AgentSessionHandoffDagExecutionBridgeService(dryRunService);

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
        verify(dryRunService).dryRunDagExecution(eq(SESSION_ID), eq(RUN_ID), any(AgentRunToolDagExecutionDryRunRequest.class), eq("trace-bridge"));
    }

    @Test
    void nonToolControlHandoffNodeShouldNotBecomeExecutableToolBridge() {
        AgentRunToolDagExecutionDryRunService dryRunService = mock(AgentRunToolDagExecutionDryRunService.class);
        when(dryRunService.dryRunDagExecution(eq(SESSION_ID), eq(RUN_ID), any(AgentRunToolDagExecutionDryRunRequest.class), eq("trace-bridge")))
                .thenReturn(dryRunResponse(List.of()));
        AgentSessionHandoffDagExecutionBridgeService service = new AgentSessionHandoffDagExecutionBridgeService(dryRunService);

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
    }

    @Test
    void toolControlWithoutDryRunCandidateShouldRemainPreviewOnly() {
        AgentRunToolDagExecutionDryRunService dryRunService = mock(AgentRunToolDagExecutionDryRunService.class);
        when(dryRunService.dryRunDagExecution(eq(SESSION_ID), eq(RUN_ID), any(AgentRunToolDagExecutionDryRunRequest.class), eq("trace-bridge")))
                .thenReturn(dryRunResponse(List.of()));
        AgentSessionHandoffDagExecutionBridgeService service = new AgentSessionHandoffDagExecutionBridgeService(dryRunService);

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
