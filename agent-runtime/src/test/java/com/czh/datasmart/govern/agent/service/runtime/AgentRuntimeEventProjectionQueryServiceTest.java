/**
 * @Author : Cui
 * @Date: 2026/05/27 00:00
 * @Description DataSmart Govern Backend - AgentRuntimeEventProjectionQueryServiceTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.config.AgentRuntimeEventConsumerProperties;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Agent runtime event 投影查询服务测试。
 *
 * <p>该测试保护两个产品能力：
 * - 多维过滤：不同角色会从 run、session、request、tenant、project、actor 等不同角度查事件；
 * - 诊断聚合：运维需要通过一个轻量接口看见 consumer 配置、投影大小和消费统计。</p>
 */
class AgentRuntimeEventProjectionQueryServiceTest {

    @Test
    void queryShouldFilterProjectionByBusinessScope() {
        InMemoryAgentRuntimeEventProjectionStore store = new InMemoryAgentRuntimeEventProjectionStore(10, 100);
        AgentRuntimeEventConsumerStats stats = new AgentRuntimeEventConsumerStats();
        AgentRuntimeEventConsumerService consumerService = new AgentRuntimeEventConsumerService(objectMapper(), store, stats);
        consumerService.consume(runtimeEventPayload("tenant-a", "project-a", "actor-a", "run-a", "session-a", "request-a", 1));
        consumerService.consume(runtimeEventPayload("tenant-a", "project-b", "actor-b", "run-b", "session-b", "request-b", 2));
        AgentRuntimeEventProjectionQueryService queryService = new AgentRuntimeEventProjectionQueryService(
                store,
                stats,
                properties(),
                new AgentRuntimeEventProjectionAccessSupport(),
                new AgentRuntimeEventVisibilitySupport()
        );

        var response = queryService.query(new AgentRuntimeEventProjectionQuery(
                "tenant-a",
                "project-a",
                "actor-a",
                null,
                "run-a",
                null,
                "tool_planned",
                "audit",
                10
        ));

        assertEquals(1, response.totalMatched());
        assertEquals("run-a", response.events().getFirst().runId());
        assertEquals("project-a", response.events().getFirst().projectId());
    }

    @Test
    void diagnosticsShouldExposeConfigurationAndConsumerStats() {
        InMemoryAgentRuntimeEventProjectionStore store = new InMemoryAgentRuntimeEventProjectionStore(10, 100);
        AgentRuntimeEventConsumerStats stats = new AgentRuntimeEventConsumerStats();
        AgentRuntimeEventConsumerService consumerService = new AgentRuntimeEventConsumerService(objectMapper(), store, stats);
        consumerService.consume(runtimeEventPayload("tenant-a", "project-a", "actor-a", "run-a", "session-a", "request-a", 1));
        consumerService.consume(" ");
        AgentRuntimeEventProjectionQueryService queryService = new AgentRuntimeEventProjectionQueryService(
                store,
                stats,
                properties(),
                new AgentRuntimeEventProjectionAccessSupport(),
                new AgentRuntimeEventVisibilitySupport()
        );

        var diagnostics = queryService.diagnostics();

        assertFalse(diagnostics.kafkaConsumerEnabled());
        assertEquals("datasmart.agent-runtime.events", diagnostics.topic());
        assertEquals("datasmart-agent-runtime-control-plane", diagnostics.groupId());
        assertEquals(1, diagnostics.currentProjectionSize());
        assertEquals(1, diagnostics.acceptedCount());
        assertEquals(1, diagnostics.rejectedCount());
        assertEquals(1, diagnostics.emptyPayloadCount());
        assertEquals("EMPTY_PAYLOAD", diagnostics.lastRejectedReason());
    }

    /**
     * 查询接口应暴露并支持按稳定 replaySequence 增量回放。
     *
     * <p>producer sequence 是 Python Runtime 在某次 Agent plan 内生成的局部序号；Java 工具状态事件可能没有该字段。
     * 因此 Java 控制面需要在投影写入时分配自己的 replaySequence，作为 Python 外部 replay source、WebSocket
     * 断线续传和审计回放的稳定游标。该测试固定两个关键语义：
     * 1. 响应里同时保留原始 sequence 与 Java replaySequence；
     * 2. afterSequence 过滤的是 replaySequence，而不是 producer sequence。</p>
     */
    @Test
    void queryShouldExposeAndFilterByStableReplaySequence() {
        InMemoryAgentRuntimeEventProjectionStore store = new InMemoryAgentRuntimeEventProjectionStore(10, 100);
        AgentRuntimeEventConsumerStats stats = new AgentRuntimeEventConsumerStats();
        AgentRuntimeEventConsumerService consumerService = new AgentRuntimeEventConsumerService(objectMapper(), store, stats);
        consumerService.consume(runtimeEventPayload("tenant-a", "project-a", "actor-a", "run-cursor", "session-a", "request-a", 9));
        consumerService.consume(runtimeEventPayload("tenant-a", "project-a", "actor-a", "run-cursor", "session-a", "request-a", 10));
        AgentRuntimeEventProjectionQueryService queryService = new AgentRuntimeEventProjectionQueryService(
                store,
                stats,
                properties(),
                new AgentRuntimeEventProjectionAccessSupport(),
                new AgentRuntimeEventVisibilitySupport()
        );

        var allEvents = queryService.query(new AgentRuntimeEventProjectionQuery(
                null, null, null, null, "run-cursor", null, null, null, 10
        ));
        var replayEvents = queryService.query(new AgentRuntimeEventProjectionQuery(
                null, null, null, null, "run-cursor", null, null, null, 10, 1L
        ));

        assertEquals(2, allEvents.totalMatched());
        assertEquals(9L, allEvents.events().getFirst().sequence());
        assertEquals(1L, allEvents.events().getFirst().replaySequence());
        assertEquals(2L, allEvents.events().get(1).replaySequence());
        assertEquals(1, replayEvents.totalMatched());
        assertEquals(2L, replayEvents.events().getFirst().replaySequence());
        assertEquals(10L, replayEvents.events().getFirst().sequence());
    }

    /**
     * SELF 范围下，即使请求没有显式传 actorId，也只能看到当前 actor 自己的事件。
     *
     * <p>这条测试保护“普通用户只能看自己的 Agent 执行轨迹”这个底线。Agent runtime events 往往比普通列表页更敏感，
     * 因为它会暴露模型如何规划、调用了哪些工具、哪里失败以及是否等待人工审批。</p>
     */
    @Test
    void queryShouldRestrictToCurrentActorWhenScopeIsSelf() {
        AgentRuntimeEventProjectionQueryService queryService = queryServiceWithTwoActors();
        AgentRuntimeEventQueryAccessContext context = new AgentRuntimeEventQueryAccessContext(
                10L,
                1001L,
                "ORDINARY_USER",
                "trace-self",
                "SELF",
                List.of()
        );

        var response = queryService.query(new AgentRuntimeEventProjectionQuery(
                null, null, null, null, null, null, null, null, 10
        ), context);

        assertEquals(1, response.totalMatched());
        assertEquals("1001", response.events().getFirst().actorId());
    }

    /**
     * PROJECT 范围下，查询结果必须继续落到 authorizedProjectIds。
     *
     * <p>gateway 只能把 permission-admin 的项目集合下发给 agent-runtime，真正过滤事件的责任仍然在服务层。
     * 如果这里遗漏，项目负责人或审计员只要能访问事件接口，就可能看到同租户其他项目的 Agent 执行轨迹。</p>
     */
    @Test
    void queryShouldRestrictToAuthorizedProjectsWhenScopeIsProject() {
        AgentRuntimeEventProjectionQueryService queryService = queryServiceWithTwoActors();
        AgentRuntimeEventQueryAccessContext context = new AgentRuntimeEventQueryAccessContext(
                10L,
                2001L,
                "PROJECT_OWNER",
                "trace-project",
                "PROJECT",
                List.of(20L)
        );

        var response = queryService.query(new AgentRuntimeEventProjectionQuery(
                null, null, null, null, null, null, null, null, 10
        ), context);

        assertEquals(1, response.totalMatched());
        assertEquals("20", response.events().getFirst().projectId());
    }

    /**
     * PROJECT 范围下主动请求未授权项目应直接拒绝，而不是返回空结果。
     *
     * <p>返回空结果适合“没有传 projectId、但授权项目集合为空”的场景；如果用户显式传入了越权 projectId，
     * 这更像一次越界访问尝试，应该返回权限错误，方便审计和前端展示明确原因。</p>
     */
    @Test
    void queryShouldRejectRequestedProjectOutsideAuthorizedProjects() {
        AgentRuntimeEventProjectionQueryService queryService = queryServiceWithTwoActors();
        AgentRuntimeEventQueryAccessContext context = new AgentRuntimeEventQueryAccessContext(
                10L,
                2001L,
                "PROJECT_OWNER",
                "trace-project-denied",
                "PROJECT",
                List.of(20L)
        );

        assertThrows(PlatformBusinessException.class, () -> queryService.query(new AgentRuntimeEventProjectionQuery(
                null, "30", null, null, null, null, null, null, 10
        ), context));
    }

    /**
     * 项目负责人可以看到自己项目内的 Agent 事件，但不能看到事件 attributes 中的高风险字段。
     *
     * <p>这条测试覆盖“有权访问记录，不等于有权访问记录内全部字段”的商业化产品要求。
     * 例如项目负责人排查工具执行失败时，知道执行了 SQL 工具通常是合理的；但 SQL 原文、API Key、
     * authorization header、原始输入输出等内容可能包含客户数据或凭据，必须在服务层统一脱敏。</p>
     */
    @Test
    void queryShouldMaskSensitiveAttributesForProjectOwner() {
        InMemoryAgentRuntimeEventProjectionStore store = new InMemoryAgentRuntimeEventProjectionStore(10, 100);
        store.append(eventRecord(
                "sensitive-project-event",
                "tool_completed",
                "execute_sql",
                "SQL 工具执行完成",
                "10",
                "20",
                "1001",
                Map.of(
                        "apiKey", "ak-live-001",
                        "sql", "select * from customer_sensitive_table",
                        "safeCounter", 3,
                        "nested", Map.of("authorization", "Bearer secret-token", "visible", "ok")
                )
        ));
        AgentRuntimeEventProjectionQueryService queryService = new AgentRuntimeEventProjectionQueryService(
                store,
                new AgentRuntimeEventConsumerStats(),
                properties(),
                new AgentRuntimeEventProjectionAccessSupport(),
                new AgentRuntimeEventVisibilitySupport()
        );
        AgentRuntimeEventQueryAccessContext context = new AgentRuntimeEventQueryAccessContext(
                10L,
                1001L,
                "PROJECT_OWNER",
                "trace-mask",
                "PROJECT",
                List.of(20L)
        );

        var response = queryService.query(new AgentRuntimeEventProjectionQuery(
                null, null, null, null, null, null, null, null, 10
        ), context);

        assertEquals(1, response.totalMatched());
        Map<String, Object> attributes = response.events().getFirst().attributes();
        assertEquals(AgentRuntimeEventVisibilitySupport.MASKED_VALUE, attributes.get("apiKey"));
        assertEquals(AgentRuntimeEventVisibilitySupport.MASKED_VALUE, attributes.get("sql"));
        assertEquals(3, attributes.get("safeCounter"));
        assertEquals(AgentRuntimeEventVisibilitySupport.MASKED_VALUE, ((Map<?, ?>) attributes.get("nested")).get("authorization"));
        assertTrue(((List<?>) attributes.get(AgentRuntimeEventVisibilitySupport.MASKED_FIELDS_ATTRIBUTE)).contains("apiKey"));
        assertEquals("PROJECT", attributes.get(AgentRuntimeEventVisibilitySupport.VISIBILITY_LEVEL_ATTRIBUTE));
    }

    /**
     * 普通用户只看到面向业务进度的事件，内部 prompt / debug / raw context 事件不应出现在查询结果中。
     *
     * <p>SELF 数据范围已经保证“只能看自己的事件”，但 Agent 运行轨迹仍然可能包含很多内部实现细节。
     * 对普通用户采用白名单可见策略，能降低 prompt 泄露、工具参数泄露和推理链路被误读的风险。</p>
     */
    @Test
    void queryShouldHideInternalEventsForOrdinaryUser() {
        InMemoryAgentRuntimeEventProjectionStore store = new InMemoryAgentRuntimeEventProjectionStore(10, 100);
        store.append(eventRecord(
                "prompt-snapshot-event",
                "prompt_snapshot",
                "build_prompt",
                "已生成模型 prompt 快照",
                "10",
                "20",
                "1001",
                Map.of("prompt", "system: secret governance prompt")
        ));
        store.append(eventRecord(
                "run-completed-event",
                "run_completed",
                "finish",
                "Agent 运行完成",
                "10",
                "20",
                "1001",
                Map.of("safeCounter", 1)
        ));
        AgentRuntimeEventProjectionQueryService queryService = new AgentRuntimeEventProjectionQueryService(
                store,
                new AgentRuntimeEventConsumerStats(),
                properties(),
                new AgentRuntimeEventProjectionAccessSupport(),
                new AgentRuntimeEventVisibilitySupport()
        );
        AgentRuntimeEventQueryAccessContext context = new AgentRuntimeEventQueryAccessContext(
                10L,
                1001L,
                "ORDINARY_USER",
                "trace-basic",
                "SELF",
                List.of()
        );

        var response = queryService.query(new AgentRuntimeEventProjectionQuery(
                null, null, null, null, null, null, null, null, 10
        ), context);

        assertEquals(1, response.totalMatched());
        assertEquals("run_completed", response.events().getFirst().eventType());
        assertEquals("事件详情已按当前角色权限脱敏", response.events().getFirst().message());
        assertEquals(AgentRuntimeEventVisibilitySupport.MASKED_VALUE,
                response.events().getFirst().attributes().get("safeCounter"));
        assertEquals("BASIC",
                response.events().getFirst().attributes().get(AgentRuntimeEventVisibilitySupport.VISIBILITY_LEVEL_ATTRIBUTE));
    }

    private ObjectMapper objectMapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule());
    }

    private AgentRuntimeEventProjectionQueryService queryServiceWithTwoActors() {
        InMemoryAgentRuntimeEventProjectionStore store = new InMemoryAgentRuntimeEventProjectionStore(10, 100);
        AgentRuntimeEventConsumerStats stats = new AgentRuntimeEventConsumerStats();
        AgentRuntimeEventConsumerService consumerService = new AgentRuntimeEventConsumerService(objectMapper(), store, stats);
        consumerService.consume(runtimeEventPayload("10", "20", "1001", "run-self", "session-a", "request-a", 1));
        consumerService.consume(runtimeEventPayload("10", "30", "1002", "run-other", "session-b", "request-b", 2));
        return new AgentRuntimeEventProjectionQueryService(
                store,
                stats,
                properties(),
                new AgentRuntimeEventProjectionAccessSupport(),
                new AgentRuntimeEventVisibilitySupport()
        );
    }

    private AgentRuntimeEventProjectionRecord eventRecord(String identityKey,
                                                          String eventType,
                                                          String stage,
                                                          String message,
                                                          String tenantId,
                                                          String projectId,
                                                          String actorId,
                                                          Map<String, Object> attributes) {
        return new AgentRuntimeEventProjectionRecord(
                identityKey,
                "agent-runtime-event.v1",
                "python-ai-runtime",
                eventType,
                stage,
                message,
                "audit",
                tenantId,
                projectId,
                actorId,
                "request-test",
                "run-test",
                "session-test",
                1L,
                Instant.parse("2026-05-27T00:00:01Z"),
                Instant.parse("2026-05-27T00:00:02Z"),
                Instant.parse("2026-05-27T00:00:03Z"),
                attributes
        );
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

    private String runtimeEventPayload(String tenantId,
                                       String projectId,
                                       String actorId,
                                       String runId,
                                       String sessionId,
                                       String requestId,
                                       long sequence) {
        return """
                {
                  "schemaVersion": "agent-runtime-event.v1",
                  "source": "python-ai-runtime",
                  "publishedAt": "2026-05-27T00:00:01Z",
                  "eventType": "tool_planned",
                  "stage": "plan_tools",
                  "message": "已生成工具调用计划",
                  "severity": "audit",
                  "tenantId": "%s",
                  "projectId": "%s",
                  "actorId": "%s",
                  "requestId": "%s",
                  "runId": "%s",
                  "sessionId": "%s",
                  "sequence": %d,
                  "attributes": {"tokens": 128},
                  "createdAt": "2026-05-27T00:00:%02dZ"
                }
                """.formatted(tenantId, projectId, actorId, requestId, runId, sessionId, sequence, sequence);
    }
}
