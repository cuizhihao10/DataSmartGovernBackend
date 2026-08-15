/**
 * @Author : Cui
 * @Date: 2026/08/15
 * @Description DataSmart Govern Backend - HttpAgentRuntimeAuditObservationClient.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.integration.agent;

import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import com.czh.datasmart.govern.datasync.config.DataSyncAgentRuntimeObservationProperties;
import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * Agent Runtime 审计只读查询客户端。
 *
 * <p>它只调用固定的“按 run 查询工具审计”接口，并按 auditId 过滤一条结果。远端不可用时返回 PARTIAL，
 * 不阻断 data-sync execution，也不把异常正文复制进运维图；这样图是观察面，而不是新的跨服务故障放大器。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HttpAgentRuntimeAuditObservationClient {

    private final RestClient.Builder restClientBuilder;
    private final DataSyncAgentRuntimeObservationProperties properties;

    /**
     * 查询一条 Agent 工具审计的低敏快照。
     *
     * @param sessionId Agent 会话 ID
     * @param runId Agent Run ID
     * @param auditId 工具审计 ID
     * @param actorContext 当前已通过 data-sync 任务可见性校验的上下文
     * @return 可投影到统一图的低敏事实
     */
    public AgentRuntimeAuditObservation observe(String sessionId,
                                                String runId,
                                                String auditId,
                                                SyncActorContext actorContext) {
        if (!properties.isEnabled()) {
            return AgentRuntimeAuditObservation.unavailable("AUDIT_QUERY_DISABLED");
        }
        if (blank(sessionId) || blank(runId) || blank(auditId)) {
            return AgentRuntimeAuditObservation.unavailable("AUDIT_CORRELATION_INCOMPLETE");
        }
        try {
            JsonNode response = restClientBuilder
                    .baseUrl(properties.getBaseUrl())
                    .requestFactory(requestFactory())
                    .build()
                    .get()
                    .uri(properties.getAuditPathTemplate(), sessionId, runId)
                    .headers(headers -> applyHeaders(headers, actorContext))
                    .retrieve()
                    .body(JsonNode.class);
            JsonNode records = response == null ? null : response.path("data");
            if (records == null || !records.isArray()) {
                return AgentRuntimeAuditObservation.unavailable("AUDIT_RESPONSE_INVALID");
            }
            for (JsonNode record : records) {
                if (auditId.equals(text(record, "auditId"))) {
                    return observation(record);
                }
            }
            return AgentRuntimeAuditObservation.missing();
        } catch (RestClientException exception) {
            log.warn("Agent Runtime 审计快照暂不可用: sessionId={}, runId={}, auditId={}, exceptionType={}",
                    sessionId, runId, auditId, exception.getClass().getSimpleName());
            return AgentRuntimeAuditObservation.unavailable("AGENT_RUNTIME_UNAVAILABLE");
        }
    }

    /**
     * 查询初始 Agent 异步命令的 outbox 投递事实。
     *
     * <p>接口按 sessionId/runId/commandId 三元组精确查询，不依赖列表分页。本方法只输出目标命令的状态、
     * 尝试次数和时间，不转发 payload、幂等键、topic、endpoint 或错误正文。</p>
     */
    public AgentRuntimeCommandObservation observeCommand(String sessionId,
                                                          String runId,
                                                          String commandId,
                                                          SyncActorContext actorContext) {
        if (!properties.isEnabled()) {
            return AgentRuntimeCommandObservation.unavailable("COMMAND_QUERY_DISABLED");
        }
        if (blank(sessionId) || blank(runId) || blank(commandId)) {
            return AgentRuntimeCommandObservation.unavailable("COMMAND_CORRELATION_INCOMPLETE");
        }
        try {
            JsonNode response = restClientBuilder
                    .baseUrl(properties.getBaseUrl())
                    .requestFactory(requestFactory())
                    .build()
                    .get()
                    .uri(properties.getCommandOutboxPathTemplate(), sessionId, runId, commandId)
                    .headers(headers -> applyHeaders(headers, actorContext))
                    .retrieve()
                    .body(JsonNode.class);
            JsonNode item = response == null ? null : response.path("data");
            if (item == null || !item.isObject()) {
                return AgentRuntimeCommandObservation.unavailable("COMMAND_RESPONSE_INVALID");
            }
            if (!item.path("found").asBoolean(false)) {
                return AgentRuntimeCommandObservation.missing();
            }
            return new AgentRuntimeCommandObservation(
                    true,
                    true,
                    text(item, "status"),
                    integerValue(item, "attemptCount"),
                    time(item, "publishedAt"),
                    time(item, "updatedAt"),
                    text(item, "sourceStatus")
            );
        } catch (RestClientException exception) {
            log.warn("Agent Runtime 异步命令快照暂不可用: sessionId={}, runId={}, commandId={}, exceptionType={}",
                    sessionId, runId, commandId, exception.getClass().getSimpleName());
            return AgentRuntimeCommandObservation.unavailable("AGENT_RUNTIME_COMMAND_UNAVAILABLE");
        }
    }

    /** 创建有界 HTTP 工厂，避免运维查询长时间占住 web 线程。 */
    private SimpleClientHttpRequestFactory requestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(Math.max(1L, properties.getConnectTimeoutMs())));
        factory.setReadTimeout(Duration.ofMillis(Math.max(1L, properties.getReadTimeoutMs())));
        return factory;
    }

    /** 设置固定服务身份和只读查询所需的范围 Header；令牌不会被打印。 */
    private void applyHeaders(HttpHeaders headers, SyncActorContext actorContext) {
        headers.set(PlatformContextHeaders.SOURCE_SERVICE, properties.getSourceService());
        if (!blank(properties.getInternalServiceToken())) {
            headers.set(PlatformContextHeaders.INTERNAL_SERVICE_TOKEN, properties.getInternalServiceToken());
        }
        headers.set(PlatformContextHeaders.ACTOR_ROLE, "SERVICE_ACCOUNT");
        headers.set(PlatformContextHeaders.ACTOR_TYPE, "SERVICE_ACCOUNT");
        if (actorContext != null && actorContext.tenantId() != null) {
            headers.set(PlatformContextHeaders.TENANT_ID, String.valueOf(actorContext.tenantId()));
        }
        if (actorContext != null && actorContext.projectId() != null) {
            headers.set(PlatformContextHeaders.PROJECT_ID, String.valueOf(actorContext.projectId()));
        }
        if (actorContext != null && !blank(actorContext.traceId())) {
            headers.set(PlatformContextHeaders.TRACE_ID, actorContext.traceId());
        }
    }

    /** 把远端审计记录限制为生命周期图所需字段。 */
    private AgentRuntimeAuditObservation observation(JsonNode record) {
        return new AgentRuntimeAuditObservation(
                true,
                true,
                text(record, "state"),
                text(record, "toolCode"),
                text(record, "riskLevel"),
                booleanValue(record, "requiresApproval"),
                time(record, "executionStartTime"),
                time(record, "executionFinishTime"),
                time(record, "updateTime"),
                text(record, "errorCode"),
                text(record, "message"),
                "AGENT_RUNTIME_AUDIT",
                text(record, "auditId"),
                text(record, "sessionId"),
                text(record, "runId"),
                longValue(record, "tenantId"),
                longValue(record, "projectId")
        );
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() || value.asText().isBlank() ? null : value.asText();
    }

    private Boolean booleanValue(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asBoolean();
    }

    private Integer integerValue(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() || !value.canConvertToInt() ? null : value.asInt();
    }

    private Long longValue(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() || !value.canConvertToLong() ? null : value.asLong();
    }

    private LocalDateTime time(JsonNode node, String field) {
        String value = text(node, field);
        if (value == null) {
            return null;
        }
        try {
            return LocalDateTime.parse(value);
        } catch (RuntimeException ignored) {
            try {
                return java.time.OffsetDateTime.parse(value).toLocalDateTime();
            } catch (RuntimeException offsetIgnored) {
                return null;
            }
        }
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
