/**
 * @Author : Cui
 * @Date: 2026/06/06 02:37
 * @Description DataSmart Govern Backend - AgentExternalProtocolDiscoveryEventPublisherTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.controller.dto.AgentA2aAgentCapabilitiesView;
import com.czh.datasmart.govern.agent.controller.dto.AgentA2aAgentInterfaceView;
import com.czh.datasmart.govern.agent.controller.dto.AgentA2aAgentProviderView;
import com.czh.datasmart.govern.agent.controller.dto.AgentA2aPublicAgentCardView;
import com.czh.datasmart.govern.agent.controller.dto.AgentA2aPublicSkillView;
import com.czh.datasmart.govern.agent.controller.dto.AgentMcpToolsListResponse;
import com.czh.datasmart.govern.agent.controller.dto.AgentMcpToolsListResultView;
import com.czh.datasmart.govern.agent.controller.dto.AgentRuntimeEventDisplayView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 外部协议发现事件发布器测试。
 *
 * <p>5.27 的核心不是“发现接口能返回什么”，而是“发现接口被读取这件事能否被低敏审计”。
 * 因此本测试不重复验证 MCP/A2A 响应体，而是直接保护 runtime event 边界：
 * 事件应该记录协议、入口、计数、分页和是否可执行；不能记录 tools/list 的完整工具清单、inputSchema、
 * A2A Agent Card URL、Skill ID、内部 endpoint、工具参数、Prompt、资源正文或凭证。</p>
 */
class AgentExternalProtocolDiscoveryEventPublisherTest {

    private InMemoryAgentRuntimeEventProjectionStore projectionStore;
    private AgentExternalProtocolDiscoveryEventPublisher publisher;

    @BeforeEach
    void setUp() {
        projectionStore = new InMemoryAgentRuntimeEventProjectionStore(50, 200);
        publisher = new AgentExternalProtocolDiscoveryEventPublisher(projectionStore);
    }

    /**
     * 验证 MCP tools/list 发现事件只保存分页和工具数量摘要。
     *
     * <p>即使真实响应中会包含工具名和 inputSchema，事件也只保留 returnedCount、totalCount、nextCursorPresent。
     * 这样 timeline 可以回答“有人读取过工具目录”，但不会成为第二份工具目录缓存或敏感 schema 扩散点。</p>
     */
    @Test
    void publishMcpToolsListShouldAppendLowSensitiveRuntimeEvent() throws Exception {
        publisher.publishMcpToolsList(
                auditContext(),
                "data-quality",
                "HIGH",
                "offset:2",
                2,
                mcpToolsListResponse()
        );

        AgentRuntimeEventProjectionRecord record = onlyDiscoveryEvent();
        assertEquals(AgentExternalProtocolDiscoveryEventPublisher.EVENT_TYPE, record.eventType());
        assertEquals("mcp_tools_list_discovered", record.stage());
        assertEquals("tenant-001", record.tenantId());
        assertEquals("workspace-001", record.projectId());
        assertEquals("actor-001", record.actorId());
        assertEquals("trace-001", record.requestId());
        assertEquals("MCP", record.attributes().get("protocol"));
        assertEquals("tools/list", record.attributes().get("discoveryMethod"));
        assertEquals(2, record.attributes().get("returnedCount"));
        assertEquals(3, record.attributes().get("totalCount"));
        assertEquals(true, record.attributes().get("nextCursorPresent"));
        assertEquals(false, record.attributes().get("callEnabled"));
        assertEquals(true, record.attributes().get("domainFilterPresent"));
        assertEquals(true, record.attributes().get("riskLevelFilterPresent"));

        String serialized = objectMapper().writeValueAsString(record).toLowerCase(Locale.ROOT);
        assertFalse(serialized.contains("inputschema"));
        assertFalse(serialized.contains("datasource.metadata.read"));
        assertFalse(serialized.contains("internal.example.com"));
        assertFalse(serialized.contains("api-key"));
        assertFalse(serialized.contains("secret"));
        assertFalse(serialized.contains("\"datasourceid\":1001"));
    }

    /**
     * 验证 A2A public Agent Card 发现事件不会复制公开卡片正文。
     *
     * <p>Agent Card 本身是公开发现资源，但 runtime event 仍不应该把卡片完整复制一份。
     * 原因是事件层会被 replay、导出和跨角色展示；把 URL、Skill ID 和能力正文复制到事件中，会让后续治理更难收口。</p>
     */
    @Test
    void publishA2aAgentCardShouldRecordPublicEndpointWithoutSkillIdsOrUrls() throws Exception {
        publisher.publishA2aAgentCard(
                new AgentExternalProtocolDiscoveryAuditContext(
                        "trace-public",
                        null,
                        null,
                        null,
                        null,
                        "PUBLIC_WELL_KNOWN",
                        null
                ),
                "PUBLIC_WELL_KNOWN",
                null,
                null,
                a2aPublicAgentCard()
        );

        AgentRuntimeEventProjectionRecord record = onlyDiscoveryEvent();
        assertEquals("a2a_agent_card_discovered", record.stage());
        assertEquals("A2A", record.attributes().get("protocol"));
        assertEquals("agent-card", record.attributes().get("discoveryMethod"));
        assertEquals("PUBLIC_WELL_KNOWN", record.attributes().get("endpointKind"));
        assertEquals(1, record.attributes().get("readySkillCount"));
        assertEquals(1, record.attributes().get("supportedInterfaceCount"));
        assertEquals(1, record.attributes().get("securitySchemeCount"));
        assertEquals(0, record.attributes().get("signatureCount"));
        assertEquals(false, record.attributes().get("streamingEnabled"));

        String serialized = objectMapper().writeValueAsString(record).toLowerCase(Locale.ROOT);
        assertFalse(serialized.contains("quality.rule.design"));
        assertFalse(serialized.contains("datasmart-govern.example.com"));
        assertFalse(serialized.contains("agent-card.json"));
        assertFalse(serialized.contains("bearer jwt"));
        assertFalse(serialized.contains("inputschema"));
    }

    /**
     * 验证展示层能把发现事件解释成可读 timeline 项。
     *
     * <p>这不是为了让前端做复杂决策，而是让用户能看懂“这条事件只是发现，不是执行”。
     * 一旦未来某个版本启用真实 tools/call，display 会把 callEnabled=true 标记为需要关注。</p>
     */
    @Test
    void displaySupportShouldExplainDiscoveryEvent() {
        publisher.publishMcpToolsList(auditContext(), null, null, null, 10, mcpToolsListResponse());

        AgentRuntimeEventDisplayView display =
                new AgentRuntimeEventDisplaySupport().buildDisplay(onlyDiscoveryEvent());

        assertEquals("EXTERNAL_PROTOCOL_DISCOVERY", display.category());
        assertEquals("DISCOVERY_ONLY", display.status());
        assertFalse(display.requiresAttention());
        assertEquals("protocol-discovery", display.iconKey());
        assertEquals("MCP", display.metrics().get("protocol"));
        assertEquals("tools/list", display.metrics().get("discoveryMethod"));
        assertEquals(2, display.metrics().get("returnedCount"));
        assertTrue(display.summary().contains("MCP tools/list"));
    }

    private AgentRuntimeEventProjectionRecord onlyDiscoveryEvent() {
        List<AgentRuntimeEventProjectionRecord> records = projectionStore.query(new AgentRuntimeEventProjectionQuery(
                null,
                null,
                null,
                null,
                null,
                null,
                AgentExternalProtocolDiscoveryEventPublisher.EVENT_TYPE,
                null,
                10
        ));
        assertEquals(1, records.size());
        AgentRuntimeEventProjectionRecord record = records.getFirst();
        assertNotNull(record.replaySequence());
        return record;
    }

    private AgentExternalProtocolDiscoveryAuditContext auditContext() {
        return new AgentExternalProtocolDiscoveryAuditContext(
                "trace-001",
                "tenant-001",
                "workspace-001",
                "actor-001",
                "TENANT_ADMIN",
                "WEB_CONSOLE",
                "gateway"
        );
    }

    private AgentMcpToolsListResponse mcpToolsListResponse() {
        return new AgentMcpToolsListResponse(
                "datasmart.agent-runtime.mcp-tools-list-discovery.v1",
                Instant.now(),
                "2025-11-25",
                "2.0",
                "req-001",
                "tools/list",
                new AgentMcpToolsListResultView(
                        List.of(),
                        "offset:2",
                        "offset:4",
                        2,
                        2,
                        3
                ),
                true,
                false,
                "METADATA_ONLY_NO_TOOL_ARGUMENTS_NO_PROMPT_BODY_NO_RESOURCE_BODY_NO_MODEL_OUTPUT",
                List.of(),
                List.of()
        );
    }

    private AgentA2aPublicAgentCardView a2aPublicAgentCard() {
        return new AgentA2aPublicAgentCardView(
                "DataSmart Govern Master Agent",
                "企业级数据治理 Master Agent。",
                List.of(new AgentA2aAgentInterfaceView(
                        "https://datasmart-govern.example.com/api/agent/a2a/v1",
                        "HTTP+JSON",
                        null,
                        "1.0"
                )),
                new AgentA2aAgentProviderView(
                        "https://datasmart-govern.example.com/docs",
                        "DataSmart Govern"
                ),
                "5.26-preview",
                "https://datasmart-govern.example.com/docs/agent-card.json",
                new AgentA2aAgentCapabilitiesView(
                        false,
                        false,
                        true,
                        List.of()
                ),
                Map.of("bearerAuth", Map.of("description", "Bearer JWT")),
                List.of(Map.of("bearerAuth", List.of("agent-runtime:discover"))),
                List.of("text/plain"),
                List.of("application/json"),
                List.of(new AgentA2aPublicSkillView(
                        "quality.rule.design",
                        "质量规则设计 Skill",
                        "根据元数据生成质量规则草稿。",
                        List.of("data-quality"),
                        List.of("为客户主数据生成质量规则草案"),
                        List.of("text/plain"),
                        List.of("application/json"),
                        List.of(Map.of("bearerAuth", List.of("quality:rule:draft")))
                )),
                List.of(),
                null
        );
    }

    private ObjectMapper objectMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }
}
