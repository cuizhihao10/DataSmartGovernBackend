/**
 * @Author : Cui
 * @Date: 2026/07/02 04:20
 * @Description DataSmart Govern Backend - AgentRuntimeClarificationFactDisplayTest.java
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
 * 澄清事实事件的人机协同展示测试。
 *
 * <p>该场景独立验证 CLARIFICATION_FACT 的可用、过期、定位和数据范围摘要，同时确保展示层不暴露
 * clarificationFactId 原文、澄清正文、工具参数或 payload。
 */
class AgentRuntimeClarificationFactDisplayTest {

    @Test
    void clarificationFactDisplayShouldExposeHitlFactSummary() {
        AgentRuntimeEventDisplayView display = new AgentRuntimeEventDisplaySupport().buildDisplay(new AgentRuntimeEventProjectionRecord(
                "clarification-fact-display",
                AgentToolActionClarificationFactEventPublisher.SCHEMA_VERSION,
                "JAVA_AGENT_RUNTIME",
                AgentToolActionClarificationFactEventPublisher.EVENT_TYPE,
                "clarification_fact_available",
                "澄清事实已登记：status=AVAILABLE，available=true，runIdPresent=true，sessionIdPresent=true，commandIdPresent=true。",
                "audit",
                "10",
                "20",
                "1001",
                "trace-clarification-display",
                "run-clarification-display",
                "session-clarification-display",
                16L,
                Instant.parse("2026-06-18T00:00:00Z"),
                Instant.parse("2026-06-18T00:00:00Z"),
                Instant.parse("2026-06-18T00:00:01Z"),
                Map.ofEntries(
                        Map.entry("snapshotType", "TOOL_ACTION_CLARIFICATION_FACT"),
                        Map.entry("payloadPolicy", AgentToolActionClarificationFactEventPublisher.PAYLOAD_POLICY),
                        Map.entry("clarificationFactIdPresent", true),
                        Map.entry("status", "AVAILABLE"),
                        Map.entry("available", true),
                        Map.entry("expired", false),
                        Map.entry("expiresAtPresent", true),
                        Map.entry("runIdPresent", true),
                        Map.entry("sessionIdPresent", true),
                        Map.entry("commandIdPresent", true),
                        Map.entry("toolCode", "datasource.metadata.read"),
                        Map.entry("requestedPolicyVersion", "tool-readiness-policy.v1"),
                        Map.entry("evidenceCodeCount", 2),
                        Map.entry("issueCodeCount", 0),
                        Map.entry("securityBoundary", Map.of(
                                "identityPresent", true,
                                "actorRole", "PROJECT_OWNER",
                                "dataScopeLevel", "PROJECT",
                                "explicitProjectScope", true,
                                "authorizedProjectCount", 1
                        )),
                        Map.entry("recommendedActions", List.of(
                                "澄清事实只表示 CLARIFICATION_FACT 可回查，真实工具 resume 仍必须经过 approval、outbox、worker receipt 和审计闭环。"
                        ))
                )
        ));

        assertEquals("TOOL_ACTION_CLARIFICATION_FACT", display.category());
        assertEquals("澄清事实已登记", display.title());
        assertEquals("CLARIFICATION_FACT_AVAILABLE", display.status());
        assertTrue(!display.requiresAttention());
        assertEquals(true, display.metrics().get("available"));
        assertEquals(false, display.metrics().get("expired"));
        assertEquals(true, display.metrics().get("clarificationFactIdPresent"));
        assertEquals(true, display.metrics().get("commandIdPresent"));
        assertEquals(2, display.metrics().get("evidenceCodeCount"));
        assertEquals("PROJECT_OWNER", display.metrics().get("actorRole"));
        assertTrue(display.recommendedActions().stream().anyMatch(action -> action.contains("approval")));
    }
}
