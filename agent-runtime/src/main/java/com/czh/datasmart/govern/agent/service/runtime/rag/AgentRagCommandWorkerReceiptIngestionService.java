/**
 * @Author : Cui
 * @Date: 2026/07/05 01:22
 * @Description DataSmart Govern Backend - AgentRagCommandWorkerReceiptIngestionService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime.rag;

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
 * 将 Python RAG worker receipt 写入 Java command worker receipt 控制面。
 *
 * <p>RAG worker 返回的 `javaReceiptPayload` 已经由 Python 侧按白名单构造，但 Java 仍不能把 Map 原样持久化。
 * 本服务再次执行字段级转换，然后调用统一的 {@link AgentToolActionCommandWorkerReceiptService}。
 * 这样 RAG、MCP、命令 worker 都汇入同一条 runtime event projection 和 worker receipt index 链路。</p>
 *
 * <p>RAG 与写入型工具的关键差异：
 * - RAG 默认是只读查询，`sideEffectStarted=false`、`sideEffectExecuted=false`；
 * - receipt 可以记录 `RAG_QUERY_COMPLETED`，但不能声明真实副作用已经发生；
 * - answer、question、compressedContext、chunk text、sourceUri 不允许出现在 Java receipt；
 * - 如果要读取答案正文，必须走 artifactReference + grant，而不是从 receipt 或 timeline 中读取。</p>
 */
@Service
@RequiredArgsConstructor
public class AgentRagCommandWorkerReceiptIngestionService {

    private static final String RAG_TOOL_CODE = "knowledge.rag.query";
    private static final String RAG_TARGET_SERVICE = "python-ai-runtime-rag";

    private final AgentToolActionCommandWorkerReceiptService receiptService;

    /**
     * 接收一次 Python RAG worker 响应中的 receipt。
     *
     * @param record 当前 Java outbox 记录，提供 session/run/trace/tenant/project 边界。
     * @param response Python 内部 API 的低敏响应。
     * @return Java receipt 服务的幂等接收结果。
     */
    public AgentToolActionCommandWorkerReceiptResponse ingest(
            AgentAsyncTaskCommandOutboxRecord record,
            AgentRagCommandWorkerRunResponse response) {
        if (record == null || response == null) {
            throw new IllegalArgumentException("RAG worker receipt 写入缺少 outbox record 或 Python response");
        }
        Map<String, Object> receipt = response.receipt();
        Map<String, Object> payload = response.javaReceiptPayload().isEmpty()
                ? objectMap(receipt.get("javaPayload"), "receipt.javaPayload")
                : response.javaReceiptPayload();
        AgentToolActionCommandWorkerReceiptRequest request = new AgentToolActionCommandWorkerReceiptRequest(
                textOr(payload, "commandId", record.commandId()),
                longValue(payload.get("taskId")),
                longValue(payload.get("taskRunId")),
                textOr(payload, "executorId", "python-rag-query-worker"),
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
                textOr(payload, "toolCode", RAG_TOOL_CODE),
                textOr(payload, "targetService", RAG_TARGET_SERVICE),
                textOr(payload, "workerReceiptMode", "READ_ONLY_QUERY_SUMMARY"),
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
