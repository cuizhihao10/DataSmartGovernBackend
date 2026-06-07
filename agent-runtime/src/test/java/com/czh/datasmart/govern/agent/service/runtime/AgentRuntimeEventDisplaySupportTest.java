/**
 * @Author : Cui
 * @Date: 2026/06/04 00:00
 * @Description DataSmart Govern Backend - AgentRuntimeEventDisplaySupportTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.controller.dto.AgentRuntimeEventDisplayView;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Agent runtime event 展示解释测试。
 *
 * <p>该测试不走完整查询服务，而是直接验证 display support 的解释规则。
 * 这样可以把“事件事实如何转换成人可读状态”与“查询权限、脱敏、分页”等逻辑拆开，避免一个测试失败时难以判断
 * 到底是访问控制问题，还是展示解释问题。</p>
 */
class AgentRuntimeEventDisplaySupportTest {

    /**
     * dry-run 事件中的沙箱与运行时保护摘要应进入展示指标和推荐动作。
     *
     * <p>这条测试保护 6.06 的核心产品语义：Agent 执行预案不只告诉用户“有节点被阻断”，还要让运营人员区分
     * 阻断来自安全边界、容量限制还是目标服务熔断。注意 display 仍然只展示低基数计数与问题码数量，
     * 不展示工具参数、完整原因或执行路径。</p>
     */
    @Test
    void dagDryRunDisplayShouldExposeGuardrailSummaryMetrics() {
        AgentRuntimeEventDisplayView display = new AgentRuntimeEventDisplaySupport().buildDisplay(new AgentRuntimeEventProjectionRecord(
                "dag-dry-run-guardrail-summary",
                "datasmart.agent-runtime.dag-execution-dry-run.v1",
                "JAVA_AGENT_RUNTIME",
                "agent.dag_execution.dry_run.completed",
                "dag_execution_dry_run_completed",
                "DAG dry-run 已生成：同步候选 0 个，异步预案 0 个，阻断 2 个，未命中 0 个。",
                "audit",
                "10",
                "20",
                "1001",
                "trace-display",
                "run-display",
                "session-display",
                1L,
                Instant.parse("2026-06-04T00:00:00Z"),
                Instant.parse("2026-06-04T00:00:00Z"),
                Instant.parse("2026-06-04T00:00:01Z"),
                Map.ofEntries(
                        Map.entry("selectedCount", 2),
                        Map.entry("syncDryRunCandidateCount", 0),
                        Map.entry("asyncEnqueuePreviewCount", 0),
                        Map.entry("blockedCount", 2),
                        Map.entry("notFoundCount", 0),
                        Map.entry("batchLimitReachedCount", 0),
                        Map.entry("sandboxRejectedCount", 1),
                        Map.entry("sandboxIssueCodes", List.of("ARGUMENT_BYTES_EXCEED_LIMIT")),
                        Map.entry("runtimeProtectionRejectedCount", 1),
                        Map.entry("runtimeProtectionIssueCodes", List.of("TARGET_SERVICE_IN_FLIGHT_LIMIT_EXCEEDED", "TARGET_SERVICE_CIRCUIT_OPEN")),
                        Map.entry("runtimeCircuitOpenCount", 1),
                        Map.entry("runtimeCapacityRejectedCount", 1),
                        Map.entry("items", List.of())
                )
        ));

        assertEquals("DAG_DRY_RUN", display.category());
        assertEquals("NEEDS_REVIEW", display.status());
        assertTrue(display.requiresAttention());
        assertTrue(display.summary().contains("沙箱拒绝 1 个"));
        assertTrue(display.summary().contains("运行时保护暂缓 1 个"));
        assertEquals(1, display.metrics().get("sandboxRejectedCount"));
        assertEquals(1, display.metrics().get("sandboxIssueCodeCount"));
        assertEquals(1, display.metrics().get("runtimeProtectionRejectedCount"));
        assertEquals(2, display.metrics().get("runtimeProtectionIssueCodeCount"));
        assertEquals(1, display.metrics().get("runtimeCircuitOpenCount"));
        assertEquals(1, display.metrics().get("runtimeCapacityRejectedCount"));
        assertTrue(display.recommendedActions().stream().anyMatch(action -> action.contains("sandboxIssueCodes")));
        assertTrue(display.recommendedActions().stream().anyMatch(action -> action.contains("runtimeProtectionIssueCodes")));
        assertTrue(display.recommendedActions().stream().anyMatch(action -> action.contains("熔断")));
    }

    /**
     * 模型网关路由事件应被解释成 Provider/fallback/cache 治理卡片。
     *
     * <p>这条测试保护 5.24 的控制面体验：通用 runtime event timeline 不应只显示
     * “事件类型：model_gateway_routed”，而应直接告诉用户本轮是否 fallback、选中 Provider 健康状态如何、
     * cache plan 是否启用，以及接下来该排查预算、健康还是缓存隔离。</p>
     */
    @Test
    void modelGatewayRoutedDisplayShouldExposeFallbackAndHealthSummary() {
        AgentRuntimeEventDisplayView display = new AgentRuntimeEventDisplaySupport().buildDisplay(new AgentRuntimeEventProjectionRecord(
                "model-gateway-routing-display",
                "agent-runtime-event.v1",
                "python-ai-runtime",
                "model_gateway_routed",
                "route_model_gateway",
                "模型网关已记录本轮 Provider 路由决策。",
                "audit",
                "10",
                "20",
                "1001",
                "trace-model-display",
                "run-model-display",
                "session-model-display",
                1L,
                Instant.parse("2026-06-06T00:00:00Z"),
                Instant.parse("2026-06-06T00:00:00Z"),
                Instant.parse("2026-06-06T00:00:01Z"),
                Map.ofEntries(
                        Map.entry("selectedProvider", "vllm-backup"),
                        Map.entry("selectedModel", "qwen3.5-agent"),
                        Map.entry("selectedHealthStatus", "degraded"),
                        Map.entry("configuredPrimaryProvider", "openai-primary"),
                        Map.entry("fallbackUsed", true),
                        Map.entry("budgetAllowed", true),
                        Map.entry("budgetWarning", false),
                        Map.entry("cacheAwareRouting", true),
                        Map.entry("cachePlanEnabled", true),
                        Map.entry("cachePlanIssues", List.of()),
                        Map.entry("candidateCount", 2),
                        Map.entry("orderedCandidateProviders", List.of("openai-primary", "vllm-backup")),
                        Map.entry("routeScoringCount", 2),
                        Map.entry("routeScoringTruncated", false)
                )
        ));

        assertEquals("MODEL_GATEWAY_ROUTING", display.category());
        assertEquals("模型网关已使用备用 Provider", display.title());
        assertEquals("FALLBACK_USED", display.status());
        assertTrue(display.requiresAttention());
        assertTrue(display.summary().contains("vllm-backup"));
        assertEquals("vllm-backup", display.metrics().get("selectedProvider"));
        assertEquals("degraded", display.metrics().get("selectedHealthStatus"));
        assertEquals(true, display.metrics().get("fallbackUsed"));
        assertEquals(true, display.metrics().get("cachePlanEnabled"));
        assertEquals(2, display.metrics().get("routeScoringCount"));
        assertTrue(display.recommendedActions().stream().anyMatch(action -> action.contains("主路由降级原因")));
        assertTrue(display.recommendedActions().stream().anyMatch(action -> action.contains("provider health diagnostics")));
    }

    /**
     * A2A task planning 接入会话调度后，timeline 应直接解释等待授权状态。
     *
     * <p>这条测试保护 5.34 的用户体验：Python 5.33 已经把 A2A task planning 写进
     * `agent_session_scheduling_recorded`，Java timeline 不能只显示“事件类型”，而应该告诉用户当前是
     * A2A 授权等待、需要权限控制面返回事实，并且不展示 task id、prompt、工具参数或内部 endpoint。</p>
     */
    @Test
    void agentSessionSchedulingDisplayShouldExposeA2aAuthorizationState() {
        AgentRuntimeEventDisplayView display = new AgentRuntimeEventDisplaySupport().buildDisplay(new AgentRuntimeEventProjectionRecord(
                "agent-session-scheduling-a2a-display",
                "agent-runtime-event.v1",
                "python-ai-runtime",
                "agent_session_scheduling_recorded",
                "record_agent_session_scheduling",
                "已记录本轮多 Agent 会话调度策略视图。",
                "audit",
                "10",
                "20",
                "1001",
                "trace-a2a-display",
                "run-a2a-display",
                "session-a2a-display",
                12L,
                Instant.parse("2026-06-06T01:00:00Z"),
                Instant.parse("2026-06-06T01:00:00Z"),
                Instant.parse("2026-06-06T01:00:01Z"),
                Map.ofEntries(
                        Map.entry("eventPayloadVersion", "v1"),
                        Map.entry("snapshotType", "AGENT_SESSION_SCHEDULING_POLICY_VIEW"),
                        Map.entry("available", true),
                        Map.entry("status", "APPROVAL_REQUIRED"),
                        Map.entry("primaryAgentRole", "MASTER_ORCHESTRATOR"),
                        Map.entry("participatingAgentCount", 3),
                        Map.entry("participatingAgentRoles", List.of("MASTER_ORCHESTRATOR", "TASK_AGENT", "PERMISSION_AGENT")),
                        Map.entry("handoffRequired", true),
                        Map.entry("handoffAgentRoles", List.of("TASK_AGENT", "PERMISSION_AGENT")),
                        Map.entry("selectedSkillCodes", List.of("governed.task.creation")),
                        Map.entry("plannedToolNames", List.of("task.create.draft")),
                        Map.entry("a2aTaskPlanningAvailable", true),
                        Map.entry("a2aTaskPlanningMode", "WAIT_FOR_AUTHORIZATION"),
                        Map.entry("a2aTaskPlanningStatus", "WAITING_FOR_CONTROL_PLANE"),
                        Map.entry("a2aTaskState", "TASK_STATE_AUTH_REQUIRED"),
                        Map.entry("a2aTaskInternalPhase", "APPROVAL_WAITING"),
                        Map.entry("a2aTaskShouldWaitForHuman", true),
                        Map.entry("a2aTaskGuardrailCodes", List.of("CREDENTIALS_MUST_STAY_OUTSIDE_A2A_MESSAGE_BODY")),
                        Map.entry("a2aTaskSuggestedActions", List.of("REQUEST_AUTHORIZATION", "QUERY_TASK_HISTORY")),
                        Map.entry("a2aTaskSensitiveFieldIgnoredCount", 2)
                )
        ));

        assertEquals("AGENT_SESSION_SCHEDULING", display.category());
        assertEquals("A2A 任务等待授权", display.title());
        assertEquals("A2A_WAITING_AUTHORIZATION", display.status());
        assertTrue(display.requiresAttention());
        assertTrue(display.summary().contains("WAIT_FOR_AUTHORIZATION"));
        assertEquals("WAIT_FOR_AUTHORIZATION", display.metrics().get("a2aTaskPlanningMode"));
        assertEquals("TASK_STATE_AUTH_REQUIRED", display.metrics().get("a2aTaskState"));
        assertEquals("APPROVAL_WAITING", display.metrics().get("a2aTaskInternalPhase"));
        assertEquals(1, display.metrics().get("a2aTaskGuardrailCodeCount"));
        assertEquals(2, display.metrics().get("a2aTaskSuggestedActionCount"));
        assertEquals(2, display.metrics().get("a2aTaskSensitiveFieldIgnoredCount"));
        assertTrue(display.recommendedActions().stream().anyMatch(action -> action.contains("permission-admin")));
    }

    /**
     * 工具执行准备度事件应被解释成执行前治理卡片。
     *
     * <p>这条测试保护 5.37 的用户体验：Python 5.36 已经把 readiness 写入 runtime event，Java timeline
     * 不能只显示“事件类型”，而应该解释当前是等待审批、需要澄清、预算限流还是已经可进入控制面执行。</p>
     */
    @Test
    void toolExecutionReadinessDisplayShouldExposeApprovalAndDraftSummary() {
        AgentRuntimeEventDisplayView display = new AgentRuntimeEventDisplaySupport().buildDisplay(new AgentRuntimeEventProjectionRecord(
                "tool-readiness-display",
                "agent-runtime-event.v1",
                "python-ai-runtime",
                "tool_execution_readiness_recorded",
                "record_tool_execution_readiness",
                "已记录本轮工具执行准备度治理快照。",
                "audit",
                "10",
                "20",
                "1001",
                "trace-readiness-display",
                "run-readiness-display",
                "session-readiness-display",
                13L,
                Instant.parse("2026-06-06T02:00:00Z"),
                Instant.parse("2026-06-06T02:00:00Z"),
                Instant.parse("2026-06-06T02:00:01Z"),
                Map.ofEntries(
                        Map.entry("snapshotType", "TOOL_EXECUTION_READINESS"),
                        Map.entry("graphSnapshotType", "TOOL_EXECUTION_READINESS_GRAPH"),
                        Map.entry("graphVersion", "tool-readiness-graph:v1"),
                        Map.entry("graphExecutionBoundary", "PRE_EXECUTION_CONDITION_GRAPH_ONLY"),
                        Map.entry("graphNodeCount", 4),
                        Map.entry("graphEdgeCount", 3),
                        Map.entry("graphBranchCounts", Map.of(
                                "READY_TO_EXECUTE", 1,
                                "SHOW_DRAFT_FOR_REVIEW", 1,
                                "WAITING_APPROVAL", 1
                        )),
                        Map.entry("graphToolExecuted", false),
                        Map.entry("graphOutboxWritten", false),
                        Map.entry("graphApprovalCreated", false),
                        Map.entry("graphWorkerReceiptRequiredForSideEffects", true),
                        Map.entry("totalCount", 3),
                        Map.entry("executableCount", 1),
                        Map.entry("approvalRequiredCount", 1),
                        Map.entry("clarificationRequiredCount", 0),
                        Map.entry("draftOnlyCount", 1),
                        Map.entry("queuedAsyncCount", 0),
                        Map.entry("throttledCount", 0),
                        Map.entry("blockedCount", 0),
                        Map.entry("nextActions", List.of("EXECUTE_READY_TOOLS", "CREATE_OR_WAIT_APPROVAL", "SHOW_DRAFT_FOR_REVIEW")),
                        Map.entry("toolNames", List.of("datasource.metadata.read", "quality.rule.suggest", "task.create.draft")),
                        Map.entry("decisionSummaries", List.of(
                                Map.of("toolName", "task.create.draft", "decision", "waiting_approval")
                        ))
                )
        ));

        assertEquals("TOOL_EXECUTION_READINESS", display.category());
        assertEquals("工具等待人工审批", display.title());
        assertEquals("WAITING_APPROVAL", display.status());
        assertTrue(display.requiresAttention());
        assertTrue(display.summary().contains("等待审批 1 个"));
        assertTrue(display.summary().contains("草案 1 个"));
        assertEquals(3, display.metrics().get("totalCount"));
        assertEquals(1, display.metrics().get("executableCount"));
        assertEquals(1, display.metrics().get("approvalRequiredCount"));
        assertEquals(3, display.metrics().get("toolNameCount"));
        assertEquals(3, display.metrics().get("nextActionCount"));
        assertEquals(4, display.metrics().get("graphNodeCount"));
        assertEquals(3, display.metrics().get("graphEdgeCount"));
        assertEquals(3, display.metrics().get("graphBranchCount"));
        assertEquals(false, display.metrics().get("graphToolExecuted"));
        assertEquals(false, display.metrics().get("graphOutboxWritten"));
        assertEquals(false, display.metrics().get("graphApprovalCreated"));
        assertEquals(true, display.metrics().get("graphWorkerReceiptRequiredForSideEffects"));
        assertTrue(display.recommendedActions().stream().anyMatch(action -> action.contains("审批面板")));
    }

    /**
     * 工具动作入口事件应在 timeline 中展示为“执行前入口治理”卡片。
     *
     * <p>这条测试保护 5.49 的用户体验：Python 5.48 已经把 MCP `tools/call` preview 写成
     * `tool_action_intake_recorded` 低敏事件，Java timeline 不能退回到通用事件展示，而应该直接告诉用户：
     * 外部工具动作意图是否被入口接收、是否在 readiness 前被拒绝、是否还缺少可见工具配置或协议 method。</p>
     */
    @Test
    void toolActionIntakeDisplayShouldExposeRejectedBeforeReadinessSummary() {
        AgentRuntimeEventDisplayView display = new AgentRuntimeEventDisplaySupport().buildDisplay(new AgentRuntimeEventProjectionRecord(
                "tool-action-intake-display",
                "agent-runtime-event.v1",
                "python-ai-runtime",
                "tool_action_intake_recorded",
                "record_tool_action_intake",
                "已记录外部工具动作入口治理快照。",
                "warning",
                "10",
                "20",
                "1001",
                "trace-tool-intake-display",
                "run-tool-intake-display",
                "session-tool-intake-display",
                14L,
                Instant.parse("2026-06-07T05:49:00Z"),
                Instant.parse("2026-06-07T05:49:00Z"),
                Instant.parse("2026-06-07T05:49:01Z"),
                Map.ofEntries(
                        Map.entry("snapshotType", "TOOL_ACTION_INTAKE"),
                        Map.entry("schemaVersion", "datasmart.python-ai-runtime.mcp-tools-call-intake-preview.v1"),
                        Map.entry("protocolFamily", "MCP"),
                        Map.entry("source", "MCP_TOOLS_CALL"),
                        Map.entry("previewOnly", true),
                        Map.entry("toolExecutionEnabled", false),
                        Map.entry("jsonRpcDetected", true),
                        Map.entry("methodAccepted", true),
                        Map.entry("callDetected", true),
                        Map.entry("acceptedToolPlanCount", 0),
                        Map.entry("rejectedBeforeReadinessCount", 1),
                        Map.entry("readinessExecutableCount", 0),
                        Map.entry("readinessApprovalRequiredCount", 0),
                        Map.entry("readinessClarificationRequiredCount", 0),
                        Map.entry("readinessDraftOnlyCount", 0),
                        Map.entry("readinessBlockedCount", 0),
                        Map.entry("readinessThrottledCount", 0),
                        Map.entry("toolNames", List.of("datasource.metadata.read")),
                        Map.entry("issueCodes", List.of("MODEL_TOOL_CALL_NOT_EXPOSED")),
                        Map.entry("readinessNextActions", List.of("CHECK_VISIBLE_TOOL_NAMES")),
                        Map.entry("graphBranchCounts", Map.of("NO_TOOL_PLAN", 1)),
                        Map.entry("graphToolExecuted", false),
                        Map.entry("graphOutboxWritten", false),
                        Map.entry("graphApprovalCreated", false),
                        Map.entry("productionReadyForExecution", false)
                )
        ));

        assertEquals("TOOL_ACTION_INTAKE", display.category());
        assertEquals("工具动作入口已在准备度前拒绝", display.title());
        assertEquals("REJECTED_BEFORE_READINESS", display.status());
        assertTrue(display.requiresAttention());
        assertTrue(display.summary().contains("准备度前拒绝 1 个"));
        assertEquals(0, display.metrics().get("acceptedToolPlanCount"));
        assertEquals(1, display.metrics().get("rejectedBeforeReadinessCount"));
        assertEquals(1, display.metrics().get("toolNameCount"));
        assertEquals(1, display.metrics().get("issueCodeCount"));
        assertEquals(1, display.metrics().get("nextActionCount"));
        assertEquals(1, display.metrics().get("graphBranchCount"));
        assertEquals(false, display.metrics().get("graphToolExecuted"));
        assertEquals(false, display.metrics().get("graphOutboxWritten"));
        assertEquals(false, display.metrics().get("graphApprovalCreated"));
        assertEquals(false, display.metrics().get("productionReadyForExecution"));
        assertTrue(display.recommendedActions().stream().anyMatch(action -> action.contains("可见工具集合")));
    }
}
