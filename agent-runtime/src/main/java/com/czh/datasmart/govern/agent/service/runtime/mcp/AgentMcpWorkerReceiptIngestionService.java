/**
 * @Author : Cui
 * @Date: 2026/07/03 19:40
 * @Description DataSmart Govern Backend - AgentMcpWorkerReceiptIngestionService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime.mcp;

import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionCommandWorkerReceiptRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionCommandWorkerReceiptResponse;
import com.czh.datasmart.govern.agent.event.command.AgentAsyncTaskCommandOutboxRecord;
import com.czh.datasmart.govern.agent.service.runtime.AgentToolActionCommandWorkerReceiptService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 将 Python MCP worker receipt 写入 Java command worker receipt 控制面。
 *
 * <p>Python 内部 API 返回的 {@code receipt} 是一个低敏摘要，其中真实 Java 请求字段位于 {@code javaPayload}。
 * 本服务不会把 Map 直接持久化，也不会相信 Python 可以自行签发执行资格；它只做字段白名单转换，然后调用现有
 * {@link AgentToolActionCommandWorkerReceiptService}。后者会继续执行：</p>
 * <p>1. session/run/command/executor 与 lease fencing token 校验；</p>
 * <p>2. outcome、preCheckPassed、sideEffectStarted、sideEffectExecuted 的状态组合校验；</p>
 * <p>3. artifactReference scheme、消息敏感片段和推荐动作治理；</p>
 * <p>4. runtime event projection 与 worker receipt index 幂等物化。</p>
 *
 * <p>因此，该适配器只是协议边界，不是新的“宽松 receipt 通道”。任何缺少 lease 的真实执行结果仍会被 Java fail-closed。</p>
 */
@Service
@RequiredArgsConstructor
public class AgentMcpWorkerReceiptIngestionService {

    private final AgentToolActionCommandWorkerReceiptService receiptService;

    /**
     * 接收一次 Python MCP worker 响应中的 receipt。
     *
     * @param record 当前 Java outbox 记录，提供可信 session/run/trace/tenant/project 边界。
     * @param response Python 内部 API 的低敏响应。
     * @return Java receipt 服务的幂等接收结果。
     */
    public AgentToolActionCommandWorkerReceiptResponse ingest(
            AgentAsyncTaskCommandOutboxRecord record,
            AgentMcpDurableWorkerRunResponse response) {
        if (record == null || response == null) {
            throw new IllegalArgumentException("MCP worker receipt 写入缺少 outbox record 或 Python response");
        }
        Map<String, Object> receipt = response.receipt();
        Map<String, Object> payload = response.javaReceiptPayload().isEmpty()
                ? objectMap(receipt.get("javaPayload"), "receipt.javaPayload")
                : response.javaReceiptPayload();
        AgentToolActionCommandWorkerReceiptRequest request = new AgentToolActionCommandWorkerReceiptRequest(
                textOr(payload, "commandId", record.commandId()),
                longValue(payload.get("taskId")),
                longValue(payload.get("taskRunId")),
                textOr(payload, "executorId", "python-mcp-durable-worker"),
                longOr(payload, "tenantId", record.tenantId()),
                longOr(payload, "projectId", record.projectId()),
                longOr(payload, "actorId", parseLong(record.actorId())),
                text(payload.get("taskStatus")),
                textOr(payload, "outcome", text(receipt.get("outcome"))),
                bool(payload.get("preCheckPassed")),
                bool(payload.get("sideEffectStarted")),
                bool(payload.get("sideEffectExecuted")),
                bool(payload.get("workerLeaseRequired")),
                text(payload.get("fencingToken")),
                longValue(payload.get("workerLeaseVersion")),
                longValue(payload.get("workerLeaseExpiresAtMs")),
                text(payload.get("commandSafetyDecision")),
                text(payload.get("commandSafetyPolicyVersion")),
                stringList(payload.get("commandSafetyIssueCodes")),
                integerValue(payload.get("normalizedTimeoutSeconds")),
                integerValue(payload.get("normalizedOutputByteLimitBytes")),
                text(payload.get("artifactReferenceType")),
                text(payload.get("artifactReference")),
                bool(payload.get("artifactAvailable")),
                text(payload.get("errorCode")),
                textOr(payload, "auditId", record.auditId()),
                textOr(payload, "toolCode", record.toolCode()),
                textOr(payload, "targetService", "python-ai-runtime-mcp-client"),
                textOr(payload, "workerReceiptMode", "MCP_DURABLE_EXECUTION_RESULT"),
                text(payload.get("message")),
                stringList(payload.get("recommendedActions")),
                text(payload.get("idempotencyKey"))
        );
        return receiptService.receive(record.sessionId(), record.runId(), record.traceId(), request);
    }

    private Map<String, Object> objectMap(Object value, String fieldName) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(fieldName + " 必须是 JSON object");
        }
        java.util.LinkedHashMap<String, Object> result = new java.util.LinkedHashMap<>();
        map.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof Iterable<?> iterable)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object item : iterable) {
            String text = text(item);
            if (text != null) {
                result.add(text);
            }
        }
        return List.copyOf(result);
    }

    private String textOr(Map<String, Object> payload, String key, String fallback) {
        String value = text(payload.get(key));
        return value == null ? fallback : value;
    }

    private String text(Object value) {
        if (value == null) {
            return null;
        }
        String normalized = String.valueOf(value).trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private Boolean bool(Object value) {
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        String normalized = text(value);
        return normalized == null ? null : Boolean.parseBoolean(normalized);
    }

    private Long longOr(Map<String, Object> payload, String key, Long fallback) {
        Long value = longValue(payload.get(key));
        return value == null ? fallback : value;
    }

    private Long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return parseLong(text(value));
    }

    private Long parseLong(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private Integer integerValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        String normalized = text(value);
        if (normalized == null) {
            return null;
        }
        try {
            return Integer.parseInt(normalized);
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}
