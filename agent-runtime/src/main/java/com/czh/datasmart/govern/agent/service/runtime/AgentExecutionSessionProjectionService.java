/**
 * @Author : Cui
 * @Date: 2026/07/02 00:00
 * @Description DataSmart Govern Backend - AgentExecutionSessionProjectionService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.controller.dto.AgentExecutionSessionProjectionQueryResponse;
import com.czh.datasmart.govern.agent.controller.dto.AgentExecutionSessionProjectionView;
import com.czh.datasmart.govern.agent.controller.dto.AgentExecutionSessionWorkItemProjectionView;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 受控多 Agent 执行会话 runtime event 的强类型投影查询服务。
 *
 * <p>Python Runtime 会在 `/agent/plans` 生成 `agentExecutionSession` 后追加
 * `agent_execution_session_recorded` 事件。Java agent-runtime 的职责不是重新调度 Agent，
 * 也不是直接执行 work item，而是把这份已经发生的低敏会话事实纳入控制面查询、审计回放和后续
 * runner 恢复入口。</p>
 *
 * <p>第一版复用通用 `AgentRuntimeEventProjectionStore`，这是有意的收敛设计：先稳定 API 和字段语义，
 * 再根据真实查询量决定是否建立 MySQL/ClickHouse dedicated index。这样可以避免在多 Agent 会话契约
 * 仍快速演进时过早锁死表结构。</p>
 */
@Service
@RequiredArgsConstructor
public class AgentExecutionSessionProjectionService {

    public static final String AGENT_EXECUTION_SESSION_EVENT_TYPE = "agent_execution_session_recorded";
    private static final String PROJECTION_SOURCE = "runtime-event-projection-fallback";

    private final AgentRuntimeEventProjectionStore projectionStore;
    private final AgentRuntimeEventProjectionAccessSupport accessSupport;

    /**
     * 查询受控多 Agent 执行会话快照。
     *
     * @param query 调用方传入的 run/session/request/tenant/project/actor/limit/afterSequence 等过滤条件。
     * @param accessContext gateway 透传并由 controller 解析出的访问上下文。
     * @return 强类型低敏执行会话视图和当前返回窗口聚合摘要。
     */
    public AgentExecutionSessionProjectionQueryResponse querySnapshots(
            AgentRuntimeEventProjectionQuery query,
            AgentRuntimeEventQueryAccessContext accessContext) {
        AgentRuntimeEventProjectionQuery scopedQuery = forceExecutionSessionEventType(
                accessSupport.restrict(query, accessContext)
        );
        List<AgentExecutionSessionProjectionView> snapshots = projectionStore.query(scopedQuery).stream()
                .map(this::toView)
                .toList();
        return buildResponse(scopedQuery, snapshots);
    }

    private AgentRuntimeEventProjectionQuery forceExecutionSessionEventType(AgentRuntimeEventProjectionQuery query) {
        /*
         * 专用接口必须固定 eventType，不能让调用方通过 query string 把它扩大成通用 runtime event 查询。
         * 这既保护了安全边界，也让前端可以把该接口稳定理解为“多 Agent 执行会话事实查询”。
         */
        return new AgentRuntimeEventProjectionQuery(
                query.tenantId(),
                query.projectId(),
                query.actorId(),
                query.requestId(),
                query.runId(),
                query.sessionId(),
                AGENT_EXECUTION_SESSION_EVENT_TYPE,
                query.severity(),
                query.limit(),
                query.afterSequence(),
                query.authorizedProjectIds()
        );
    }

    private AgentExecutionSessionProjectionQueryResponse buildResponse(
            AgentRuntimeEventProjectionQuery query,
            List<AgentExecutionSessionProjectionView> snapshots) {
        return new AgentExecutionSessionProjectionQueryResponse(
                query.normalizedLimit(),
                snapshots.size(),
                PROJECTION_SOURCE,
                countStatus(snapshots, "WAITING_APPROVAL_OR_HANDOFF"),
                countStatus(snapshots, "WAITING_CONTROL_PLANE_FEEDBACK"),
                countStatus(snapshots, "BLOCKED_WAITING_RECOVERY"),
                countStatus(snapshots, "READY_FOR_AGENT_TURNS"),
                countStatus(snapshots, "READY_FOR_CONTROL_PLANE_HANDOFF"),
                countStatus(snapshots, "DEGRADED_DRAFT_ONLY"),
                countStatus(snapshots, "COMPLETED_OR_SUMMARIZED"),
                snapshots.stream().filter(snapshot -> snapshot.handoffRequiredWorkItemCount() > 0).count(),
                countScalar(snapshots.stream().map(AgentExecutionSessionProjectionView::status).toList()),
                countScalar(snapshots.stream().map(AgentExecutionSessionProjectionView::durablePhase).toList()),
                countMapValues(snapshots.stream().map(AgentExecutionSessionProjectionView::deliveryTierCounts).toList()),
                countMapValues(snapshots.stream().map(AgentExecutionSessionProjectionView::resumeActionCounts).toList()),
                countList(snapshots.stream().map(AgentExecutionSessionProjectionView::activeMustDoRoles).toList()),
                countList(snapshots.stream().map(AgentExecutionSessionProjectionView::standbyMustDoRoles).toList()),
                snapshots
        );
    }

    private long countStatus(List<AgentExecutionSessionProjectionView> snapshots, String status) {
        return snapshots.stream()
                .filter(snapshot -> status.equalsIgnoreCase(Objects.toString(snapshot.status(), "")))
                .count();
    }

    private Map<String, Long> countScalar(List<String> values) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String value : values) {
            String normalized = bucketValue(value, null);
            if (normalized != null) {
                counts.merge(normalized, 1L, Long::sum);
            }
        }
        return Collections.unmodifiableMap(counts);
    }

    private Map<String, Long> countList(List<List<String>> windows) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (List<String> values : windows) {
            for (String value : values) {
                String normalized = bucketValue(value, null);
                if (normalized != null) {
                    counts.merge(normalized, 1L, Long::sum);
                }
            }
        }
        return Collections.unmodifiableMap(counts);
    }

    private Map<String, Long> countMapValues(List<Map<String, Integer>> windows) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Map<String, Integer> window : windows) {
            for (Map.Entry<String, Integer> entry : window.entrySet()) {
                String normalized = bucketValue(entry.getKey(), null);
                if (normalized != null) {
                    counts.merge(normalized, (long) Math.max(0, entry.getValue()), Long::sum);
                }
            }
        }
        return Collections.unmodifiableMap(counts);
    }

    private AgentExecutionSessionProjectionView toView(AgentRuntimeEventProjectionRecord record) {
        Map<String, Object> attributes = safeAttributes(record);
        return new AgentExecutionSessionProjectionView(
                record.identityKey(),
                record.schemaVersion(),
                record.source(),
                record.eventType(),
                record.severity(),
                record.tenantId(),
                record.projectId(),
                record.actorId(),
                record.requestId(),
                record.runId(),
                record.sessionId(),
                record.sequence(),
                record.replaySequence(),
                record.createdAt(),
                record.consumedAt(),
                text(attributes, "eventPayloadVersion"),
                defaultedText(attributes, "snapshotType", "AGENT_EXECUTION_SESSION_CONTROL_PLANE_VIEW"),
                bool(attributes, "available"),
                defaultedText(attributes, "status", "UNKNOWN"),
                defaultedText(attributes, "durablePhase", "not_recorded"),
                text(attributes, "durableResumeAction"),
                defaultedText(attributes, "executionPlanStatus", "UNKNOWN"),
                defaultedText(attributes, "executionSessionSource", "UNKNOWN"),
                integer(attributes, "workItemCount"),
                stringList(attributes, "activeRoles"),
                integer(attributes, "activeRolesTruncatedCount"),
                workItems(attributes),
                integer(attributes, "workItemsTruncatedCount"),
                intMap(attributes, "workItemStatusCounts"),
                intMap(attributes, "deliveryTierCounts"),
                intMap(attributes, "resumeActionCounts"),
                intMap(attributes, "sourceStatusCounts"),
                integer(attributes, "handoffRequiredWorkItemCount"),
                stringList(attributes, "waitingReasonCodes"),
                stringList(attributes, "blockedByCodes"),
                stringList(attributes, "activeMustDoRoles"),
                stringList(attributes, "standbyMustDoRoles"),
                stringList(attributes, "activeControlledScopeRoles"),
                stringList(attributes, "standbyControlledScopeRoles"),
                stringList(attributes, "deferredLightweightRoles"),
                integer(attributes, "activeRoleCount"),
                integer(attributes, "mustDoRoleCount"),
                integer(attributes, "activeMustDoRoleCount"),
                text(attributes, "coveragePolicy"),
                integer(attributes, "collaborationEdgeCount"),
                integer(attributes, "handoffContractCount"),
                stringList(attributes, "nextActions"),
                bool(attributes, "toolExecutedByPython"),
                bool(attributes, "modelCalledByExecutionSession"),
                bool(attributes, "outboxWrittenByPython"),
                bool(attributes, "approvalCreatedByPython"),
                bool(attributes, "workerDispatchedByPython"),
                bool(attributes, "checkpointMutatedByExecutionSession"),
                bool(attributes, "javaControlPlaneRequiredForSideEffects"),
                bool(attributes, "workerReceiptRequiredForSideEffects"),
                text(attributes, "executionBoundary"),
                text(attributes, "payloadPolicy")
        );
    }

    private List<AgentExecutionSessionWorkItemProjectionView> workItems(Map<String, Object> attributes) {
        Object raw = attributes.get("workItems");
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<AgentExecutionSessionWorkItemProjectionView> parsed = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                parsed.add(workItem(map));
            }
        }
        return List.copyOf(parsed);
    }

    private AgentExecutionSessionWorkItemProjectionView workItem(Map<?, ?> raw) {
        Map<String, Object> item = normalizeMap(raw);
        return new AgentExecutionSessionWorkItemProjectionView(
                text(item, "workItemId"),
                defaultedText(item, "agentRole", "UNKNOWN_AGENT"),
                defaultedText(item, "deliveryTier", "runtime_governance"),
                defaultedText(item, "participationMode", "UNKNOWN"),
                defaultedText(item, "sessionStatus", "UNKNOWN"),
                defaultedText(item, "resumeAction", "WAIT_FOR_SESSION_ORCHESTRATOR_REVIEW"),
                text(item, "executionLane"),
                stringList(item, "dependsOnRoles"),
                bool(item, "handoffRequired"),
                integer(item, "plannedToolCount"),
                integer(item, "visibleSkillCount"),
                integer(item, "memoryDependencyCount"),
                stringList(item, "waitingReasonCodes"),
                stringList(item, "blockedBy"),
                defaultedText(item, "durablePhase", "not_recorded"),
                defaultedText(item, "sourceStatus", "UNKNOWN"),
                text(item, "payloadPolicy")
        );
    }

    private Map<String, Object> safeAttributes(AgentRuntimeEventProjectionRecord record) {
        if (record.attributes() == null || record.attributes().isEmpty()) {
            return Map.of();
        }
        return record.attributes();
    }

    private Map<String, Object> normalizeMap(Map<?, ?> raw) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            String key = Objects.toString(entry.getKey(), "").trim();
            if (!key.isEmpty()) {
                normalized.put(key, entry.getValue());
            }
        }
        return normalized;
    }

    private String text(Map<String, Object> attributes, String key) {
        Object value = attributes.get(key);
        if (value == null) {
            return null;
        }
        String text = Objects.toString(value, "").trim();
        return text.isEmpty() ? null : text;
    }

    private String defaultedText(Map<String, Object> attributes, String key, String defaultValue) {
        return bucketValue(text(attributes, key), defaultValue);
    }

    private String bucketValue(String value, String defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return value.trim();
    }

    private Integer integer(Map<String, Object> attributes, String key) {
        return safeInt(attributes.get(key));
    }

    private int safeInt(Object value) {
        if (value instanceof Number number) {
            return Math.max(0, number.intValue());
        }
        if (value instanceof String text) {
            try {
                return Math.max(0, Integer.parseInt(text.trim()));
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private Boolean bool(Map<String, Object> attributes, String key) {
        Object value = attributes.get(key);
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        if (value == null) {
            return false;
        }
        return switch (Objects.toString(value, "").trim().toLowerCase(Locale.ROOT)) {
            case "1", "true", "yes", "on", "enabled" -> true;
            default -> false;
        };
    }

    private List<String> stringList(Map<String, Object> attributes, String key) {
        Object value = attributes.get(key);
        List<String> values = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                appendString(values, item);
            }
        } else if (value instanceof Object[] array) {
            for (Object item : array) {
                appendString(values, item);
            }
        } else {
            appendString(values, value);
        }
        return List.copyOf(values);
    }

    private void appendString(List<String> values, Object value) {
        if (value == null) {
            return;
        }
        String text = Objects.toString(value, "").trim();
        if (!text.isEmpty()) {
            values.add(text);
        }
    }

    private Map<String, Integer> intMap(Map<String, Object> attributes, String key) {
        Object value = attributes.get(key);
        if (!(value instanceof Map<?, ?> rawMap) || rawMap.isEmpty()) {
            return Map.of();
        }
        Map<String, Integer> parsed = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
            String mapKey = Objects.toString(entry.getKey(), "").trim();
            if (!mapKey.isEmpty()) {
                parsed.put(mapKey, safeInt(entry.getValue()));
            }
        }
        return Collections.unmodifiableMap(parsed);
    }
}
