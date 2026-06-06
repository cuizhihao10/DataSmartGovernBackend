/**
 * @Author : Cui
 * @Date: 2026/06/06 02:37
 * @Description DataSmart Govern Backend - AgentExternalProtocolDiscoveryEventPublisher.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.controller.dto.AgentA2aPublicAgentCardView;
import com.czh.datasmart.govern.agent.controller.dto.AgentMcpToolsListResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 外部 Agent 协议发现 runtime event 发布器。
 *
 * <p>5.26 让 MCP tools/list 和 A2A Agent Card 可以被外部 Agent 发现；5.27 的目标是让“发现行为”
 * 本身也进入 runtime event timeline。这样平台后续可以回答：</p>
 *
 * <p>1. 外部 Agent 或 Python Runtime 什么时候读取过工具目录？</p>
 * <p>2. 当时返回了多少工具、是否还有下一页？</p>
 * <p>3. public Agent Card 暴露了多少 READY Skill？</p>
 * <p>4. 这些发现行为是否发生在匿名/public well-known 入口，还是管理 API 入口？</p>
 *
 * <p>重要边界：发现事件不是执行事件。这里不会写入 tool names、inputSchema、skillIds、Agent Card URL、
 * 内部 endpoint、targetService、参数 example、工具参数值、Prompt、资源正文或模型输出。事件只保存低敏计数、
 * 协议、入口、分页和治理策略摘要。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentExternalProtocolDiscoveryEventPublisher {

    public static final String SCHEMA_VERSION =
            "datasmart.agent-runtime.external-protocol-discovery-event.v1";
    public static final String EVENT_TYPE = "agent.external_protocol.discovery.completed";

    private static final String SOURCE = "JAVA_AGENT_RUNTIME";
    private static final String MCP_STAGE = "mcp_tools_list_discovered";
    private static final String A2A_STAGE = "a2a_agent_card_discovered";
    private static final String PAYLOAD_POLICY =
            "SUMMARY_ONLY_NO_TOOL_NAMES_NO_SCHEMA_NO_SKILL_IDS_NO_URL_NO_PROMPT_NO_RESOURCE_BODY";

    private final AgentRuntimeEventProjectionStore projectionStore;

    /**
     * 发布 MCP tools/list 发现事件。
     *
     * <p>调用时机应在响应成功构建之后。这样事件里的 returnedCount、totalCount、nextCursorPresent 都来自真实响应，
     * 不会出现“记录成功但响应失败”的错觉。该发布器采用尽力而为策略，投影失败不影响发现接口返回。</p>
     */
    public void publishMcpToolsList(AgentExternalProtocolDiscoveryAuditContext context,
                                    String domain,
                                    String riskLevel,
                                    String cursor,
                                    Integer requestedLimit,
                                    AgentMcpToolsListResponse response) {
        try {
            AgentRuntimeEventProjectionRecord record = buildRecord(
                    context,
                    "MCP",
                    "tools/list",
                    MCP_STAGE,
                    mcpMessage(response),
                    mcpAttributes(domain, riskLevel, cursor, requestedLimit, response)
            );
            append(record);
        } catch (RuntimeException ex) {
            log.warn("MCP tools/list 发现事件写入失败，traceId={}, error={}",
                    context == null ? null : context.traceId(), ex.getMessage());
        }
    }

    /**
     * 发布 A2A Agent Card 发现事件。
     *
     * <p>同一套 publisher 同时服务管理路径和 public well-known 根路径。两者的安全语义不同：
     * 管理路径通常带租户/操作者上下文，并可用于部署前检查；public 根路径可能被外部 Agent 自动读取。
     * 因此事件必须记录 endpointKind，但仍不能记录实际 URL。</p>
     */
    public void publishA2aAgentCard(AgentExternalProtocolDiscoveryAuditContext context,
                                    String endpointKind,
                                    String domain,
                                    String riskLevel,
                                    AgentA2aPublicAgentCardView card) {
        try {
            AgentRuntimeEventProjectionRecord record = buildRecord(
                    context,
                    "A2A",
                    "agent-card",
                    A2A_STAGE,
                    a2aMessage(endpointKind, card),
                    a2aAttributes(endpointKind, domain, riskLevel, card)
            );
            append(record);
        } catch (RuntimeException ex) {
            log.warn("A2A Agent Card 发现事件写入失败，traceId={}, endpointKind={}, error={}",
                    context == null ? null : context.traceId(), endpointKind, ex.getMessage());
        }
    }

    private AgentRuntimeEventProjectionRecord buildRecord(AgentExternalProtocolDiscoveryAuditContext context,
                                                          String protocol,
                                                          String discoveryMethod,
                                                          String stage,
                                                          String message,
                                                          Map<String, Object> attributes) {
        Instant now = Instant.now();
        AgentExternalProtocolDiscoveryAuditContext safeContext = context == null
                ? new AgentExternalProtocolDiscoveryAuditContext(null, null, null, null, null, null, null)
                : context;
        return new AgentRuntimeEventProjectionRecord(
                "external-protocol-discovery:" + protocol + ":" + discoveryMethod + ":" + UUID.randomUUID(),
                SCHEMA_VERSION,
                SOURCE,
                EVENT_TYPE,
                stage,
                message,
                "info",
                blankToNull(safeContext.tenantId()),
                /*
                 * 当前统一 Header 中没有独立 projectId，只有 workspaceId。
                 * 为了让 PROJECT 范围查询未来仍能收口发现事件，这里暂时把 workspaceId 写入投影 projectId。
                 * 如果后续平台明确区分 project 与 workspace，应新增专门 Header 或在 gateway 完成映射。
                 */
                blankToNull(safeContext.workspaceId()),
                blankToNull(safeContext.actorId()),
                blankToNull(safeContext.traceId()),
                null,
                null,
                null,
                now,
                now,
                now,
                mergeContextAttributes(safeContext, attributes)
        );
    }

    private void append(AgentRuntimeEventProjectionRecord record) {
        boolean appended = projectionStore.append(record);
        if (!appended) {
            log.debug("外部协议发现事件已存在，跳过去重写入，identityKey={}", record.identityKey());
        }
    }

    private String mcpMessage(AgentMcpToolsListResponse response) {
        return "MCP tools/list 发现已完成：返回工具 " + response.result().returnedCount()
                + " 个，总计 " + response.result().totalCount()
                + " 个，仍有下一页=" + (response.result().nextCursor() != null) + "。";
    }

    private String a2aMessage(String endpointKind, AgentA2aPublicAgentCardView card) {
        return "A2A Agent Card 发现已完成：入口=" + normalizeEndpointKind(endpointKind)
                + "，公开 READY Skill " + card.skills().size()
                + " 个，签名数量 " + card.signatures().size() + "。";
    }

    private Map<String, Object> mcpAttributes(String domain,
                                              String riskLevel,
                                              String cursor,
                                              Integer requestedLimit,
                                              AgentMcpToolsListResponse response) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("protocol", "MCP");
        attributes.put("discoveryMethod", "tools/list");
        attributes.put("protocolVersion", response.protocolVersion());
        attributes.put("previewOnly", response.previewOnly());
        attributes.put("callEnabled", response.callEnabled());
        attributes.put("domainFilterPresent", isPresent(domain));
        attributes.put("riskLevelFilterPresent", isPresent(riskLevel));
        attributes.put("cursorPresent", isPresent(cursor));
        attributes.put("requestedLimit", requestedLimit);
        attributes.put("effectiveLimit", response.result().limit());
        attributes.put("returnedCount", response.result().returnedCount());
        attributes.put("totalCount", response.result().totalCount());
        attributes.put("nextCursorPresent", response.result().nextCursor() != null);
        attributes.put("payloadPolicy", PAYLOAD_POLICY);
        return Collections.unmodifiableMap(attributes);
    }

    private Map<String, Object> a2aAttributes(String endpointKind,
                                              String domain,
                                              String riskLevel,
                                              AgentA2aPublicAgentCardView card) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("protocol", "A2A");
        attributes.put("discoveryMethod", "agent-card");
        attributes.put("endpointKind", normalizeEndpointKind(endpointKind));
        attributes.put("domainFilterPresent", isPresent(domain));
        attributes.put("riskLevelFilterPresent", isPresent(riskLevel));
        attributes.put("readySkillCount", card.skills().size());
        attributes.put("supportedInterfaceCount", card.supportedInterfaces().size());
        attributes.put("securitySchemeCount", card.securitySchemes().size());
        attributes.put("signatureCount", card.signatures().size());
        attributes.put("streamingEnabled", Boolean.TRUE.equals(card.capabilities().streaming()));
        attributes.put("pushNotificationEnabled", Boolean.TRUE.equals(card.capabilities().pushNotifications()));
        attributes.put("extendedAgentCardEnabled", Boolean.TRUE.equals(card.capabilities().extendedAgentCard()));
        attributes.put("payloadPolicy", PAYLOAD_POLICY);
        return Collections.unmodifiableMap(attributes);
    }

    private Map<String, Object> mergeContextAttributes(AgentExternalProtocolDiscoveryAuditContext context,
                                                       Map<String, Object> eventAttributes) {
        Map<String, Object> attributes = new LinkedHashMap<>(eventAttributes);
        attributes.put("actorRolePresent", isPresent(context.actorRole()));
        attributes.put("requestSource", safeLowCardinality(context.requestSource(), "UNKNOWN"));
        attributes.put("sourceService", safeLowCardinality(context.sourceService(), "UNKNOWN"));
        attributes.put("eventPayloadPolicy", PAYLOAD_POLICY);
        return Collections.unmodifiableMap(attributes);
    }

    private String normalizeEndpointKind(String endpointKind) {
        return safeLowCardinality(endpointKind, "UNKNOWN_ENDPOINT")
                .toUpperCase(Locale.ROOT);
    }

    private boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }

    private String safeLowCardinality(String value, String fallback) {
        String resolved = value == null || value.isBlank() ? fallback : value.trim();
        String sanitized = resolved.replaceAll("[^A-Za-z0-9_.-]", "_");
        return sanitized.length() <= 64 ? sanitized : sanitized.substring(0, 64);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : Objects.toString(value).trim();
    }
}
