/**
 * @Author : Cui
 * @Date: 2026/06/06 00:00
 * @Description DataSmart Govern Backend - AgentModelGatewayRoutingProjectionServiceTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.controller.dto.AgentModelGatewayRoutingProjectionQueryResponse;
import com.czh.datasmart.govern.agent.controller.dto.AgentModelGatewayRoutingProjectionView;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 模型网关路由投影查询服务测试。
 *
 * <p>这组测试保护 5.24 的新增能力：Java 控制面不只“收到” Python `model_gateway_routed` 事件，
 * 还要能按产品语义解释 Provider 选择、fallback、预算和 cache plan，并继续遵守租户/项目数据范围。</p>
 */
class AgentModelGatewayRoutingProjectionServiceTest {

    @Test
    void querySnapshotsShouldParseModelGatewayAttributesAndAggregateReturnedWindow() {
        InMemoryAgentRuntimeEventProjectionStore store = new InMemoryAgentRuntimeEventProjectionStore(10, 100);
        store.append(modelGatewayRecord("model-gateway-1", "20", "run-model", 1, true, "degraded"));
        store.append(nonModelGatewayRuntimeEvent("tool-event-1", "20", "run-model", 2));
        store.append(modelGatewayRecord("model-gateway-other-project", "30", "run-model", 3, false, "healthy"));
        AgentModelGatewayRoutingProjectionService service = new AgentModelGatewayRoutingProjectionService(
                store,
                new AgentRuntimeEventProjectionAccessSupport()
        );

        AgentModelGatewayRoutingProjectionQueryResponse response = service.querySnapshots(
                new AgentRuntimeEventProjectionQuery("10", null, null, null,
                        "run-model", null, null, null, 20),
                projectOwnerContext()
        );

        assertEquals(1, response.totalMatched());
        assertEquals("runtime-event-projection-fallback", response.indexSource());
        assertEquals(1L, response.fallbackUsedCount());
        assertEquals(0L, response.budgetBlockedCount());
        assertEquals(1L, response.cachePlanEnabledCount());
        assertEquals(1L, response.routeScoringTruncatedCount());
        assertEquals(1L, response.selectedProviderCounts().get("vllm-backup"));
        assertEquals(1L, response.selectedHealthStatusCounts().get("degraded"));

        AgentModelGatewayRoutingProjectionView snapshot = response.snapshots().getFirst();
        assertEquals("model_gateway_routed", snapshot.eventType());
        assertEquals("datasmart.ai-runtime.model-gateway-routed.v2", snapshot.routingSchemaVersion());
        assertEquals("SUMMARY_ONLY_NO_PROMPT_NO_TOOL_ARGS_NO_MODEL_OUTPUT_NO_KV_CACHE", snapshot.eventPayloadPolicy());
        assertEquals("vllm-backup", snapshot.selectedProvider());
        assertEquals("qwen3.5-agent", snapshot.selectedModel());
        assertEquals("openai-primary", snapshot.configuredPrimaryProvider());
        assertEquals(List.of("openai-primary", "vllm-backup"), snapshot.orderedCandidateProviders());
        assertEquals(2, snapshot.candidateCount());
        assertEquals(true, snapshot.fallbackUsed());
        assertEquals(true, snapshot.budgetAllowed());
        assertEquals(true, snapshot.cachePlanEnabled());
        assertEquals("PROJECT_SAFE", snapshot.cachePlanScope());
        assertEquals(true, snapshot.cachePlanNamespacePresent());
        assertEquals(1, snapshot.cachePlanIssueCount());
        assertEquals(2, snapshot.routeScoringCount());
        assertEquals(true, snapshot.routeScoringTruncated());
        assertEquals("openai-primary", snapshot.routeScoring().getFirst().providerName());
        assertEquals(1, snapshot.routeScoring().getFirst().cacheIssueCount());

        String serialized = response.toString();
        assertFalse(serialized.contains("secret prompt"));
        assertFalse(serialized.contains("toolArguments"));
        assertFalse(serialized.contains("https://"));
        assertFalse(serialized.contains("api-key"));
        assertFalse(serialized.contains("real-cache-key"));
    }

    @Test
    void querySnapshotsShouldUseReplaySequenceForIncrementalRead() {
        InMemoryAgentRuntimeEventProjectionStore store = new InMemoryAgentRuntimeEventProjectionStore(10, 100);
        store.append(modelGatewayRecord("model-gateway-1", "20", "run-model", 1, false, "healthy"));
        store.append(modelGatewayRecord("model-gateway-2", "20", "run-model", 2, true, "unavailable"));
        AgentModelGatewayRoutingProjectionService service = new AgentModelGatewayRoutingProjectionService(
                store,
                new AgentRuntimeEventProjectionAccessSupport()
        );

        AgentModelGatewayRoutingProjectionQueryResponse response = service.querySnapshots(
                new AgentRuntimeEventProjectionQuery("10", null, null, null,
                        "run-model", null, null, null, 20, 1L),
                projectOwnerContext()
        );

        assertEquals(1, response.totalMatched());
        assertEquals(1L, response.fallbackUsedCount());
        assertEquals(2L, response.snapshots().getFirst().replaySequence());
        assertEquals("unavailable", response.snapshots().getFirst().selectedHealthStatus());
    }

    private AgentRuntimeEventProjectionRecord modelGatewayRecord(String identityKey,
                                                                 String projectId,
                                                                 String runId,
                                                                 long sequence,
                                                                 boolean fallbackUsed,
                                                                 String selectedHealthStatus) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("schemaVersion", "datasmart.ai-runtime.model-gateway-routed.v2");
        attributes.put("eventPayloadPolicy", "SUMMARY_ONLY_NO_PROMPT_NO_TOOL_ARGS_NO_MODEL_OUTPUT_NO_KV_CACHE");
        attributes.put("selectedProvider", fallbackUsed ? "vllm-backup" : "openai-primary");
        attributes.put("selectedModel", "qwen3.5-agent");
        attributes.put("selectedHealthStatus", selectedHealthStatus);
        attributes.put("configuredPrimaryProvider", "openai-primary");
        attributes.put("orderedCandidateProviders", List.of("openai-primary", "vllm-backup"));
        attributes.put("candidateCount", 2);
        attributes.put("fallbackUsed", fallbackUsed);
        attributes.put("budgetAllowed", true);
        attributes.put("budgetWarning", false);
        attributes.put("cacheAwareRouting", true);
        attributes.put("cacheKeyScope", "PROJECT_SAFE");
        attributes.put("cachePlanEnabled", true);
        attributes.put("cachePlanScope", "PROJECT_SAFE");
        attributes.put("cachePlanNamespace", "real-cache-key-should-not-be-returned");
        attributes.put("cachePlanTtlSeconds", 600);
        attributes.put("cachePlanIssues", List.of("PRIMARY_UNHEALTHY"));
        attributes.put("routeScoringCount", 2);
        attributes.put("routeScoringTruncated", true);
        attributes.put("routeScoring", List.of(
                Map.of(
                        "providerName", "openai-primary",
                        "modelName", "qwen3.5-agent",
                        "healthStatus", "unavailable",
                        "latencyTier", "LOW_LATENCY",
                        "cacheScope", "PROJECT_SAFE",
                        "cachePlanEnabled", false,
                        "cacheIssues", List.of("PROVIDER_UNAVAILABLE"),
                        "priority", 1,
                        "sortKey", List.of(1, 2, 3)
                ),
                Map.of(
                        "providerName", "vllm-backup",
                        "modelName", "qwen3.5-agent",
                        "healthStatus", selectedHealthStatus,
                        "latencyTier", "STANDARD",
                        "cacheScope", "PROJECT_SAFE",
                        "cachePlanEnabled", true,
                        "cacheIssues", List.of(),
                        "priority", 2
                )
        ));
        attributes.put("prompt", "secret prompt should not be parsed");
        attributes.put("toolArguments", Map.of("sql", "select * from customer"));
        attributes.put("providerUrl", "https://models.example.com?api-key=hidden");
        return eventRecord(identityKey, "model_gateway_routed", projectId,
                runId, sequence, "audit", attributes);
    }

    private AgentRuntimeEventProjectionRecord nonModelGatewayRuntimeEvent(String identityKey,
                                                                          String projectId,
                                                                          String runId,
                                                                          long sequence) {
        return eventRecord(identityKey, "tool_planned", projectId, runId, sequence,
                "info", Map.of("toolName", "datasource.metadata.read"));
    }

    private AgentRuntimeEventProjectionRecord eventRecord(String identityKey,
                                                          String eventType,
                                                          String projectId,
                                                          String runId,
                                                          long sequence,
                                                          String severity,
                                                          Map<String, Object> attributes) {
        Instant timestamp = Instant.parse("2026-06-06T12:00:0" + sequence + "Z");
        return new AgentRuntimeEventProjectionRecord(
                identityKey,
                "agent-runtime-event.v1",
                "python-ai-runtime",
                eventType,
                "route_model_gateway",
                "模型网关已记录本轮 Provider 路由决策。",
                severity,
                "10",
                projectId,
                "1001",
                "request-model-gateway",
                runId,
                "session-model",
                sequence,
                timestamp,
                timestamp,
                timestamp,
                attributes
        );
    }

    private AgentRuntimeEventQueryAccessContext projectOwnerContext() {
        return new AgentRuntimeEventQueryAccessContext(
                10L,
                1001L,
                "PROJECT_OWNER",
                "trace-model-gateway-routing-test",
                "PROJECT",
                List.of(20L)
        );
    }
}
