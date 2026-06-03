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
}
