/**
 * @Author : Cui
 * @Date: 2026/06/05 00:00
 * @Description DataSmart Govern Backend - AgentSessionSchedulingProjectionService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.controller.dto.AgentSessionSchedulingProjectionQueryResponse;
import com.czh.datasmart.govern.agent.controller.dto.AgentSessionSchedulingProjectionView;
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
 * 多 Agent 会话调度 runtime event 的强类型投影查询服务。
 *
 * <p>Python Runtime 负责生成 `agent_session_scheduling_recorded` 事件；Java agent-runtime 的职责不是重新
 * 调度 Agent，而是把已经发生的调度事实纳入控制面查询、审计和后续运营报表。</p>
 *
 * <p>本服务当前基于通用 `AgentRuntimeEventProjectionStore` 查询，不立即引入 MySQL 专用表。这样做是为了
 * 先稳定 API 契约和字段语义：当管理台、审计台和后续 Java projection 都认可这些字段后，再把同一契约
 * 迁移到 dedicated index，会比先建表再反复改字段更稳。</p>
 */
@Service
@RequiredArgsConstructor
public class AgentSessionSchedulingProjectionService {

    public static final String AGENT_SESSION_SCHEDULING_EVENT_TYPE = "agent_session_scheduling_recorded";
    private static final String PROJECTION_SOURCE = "runtime-event-projection-fallback";

    private final AgentRuntimeEventProjectionStore projectionStore;
    private final AgentRuntimeEventProjectionAccessSupport accessSupport;

    /**
     * 查询多 Agent 会话调度快照。
     *
     * @param query 调用方传入的 run/session/request/tenant/project/actor/limit/afterSequence 等过滤条件
     * @param accessContext gateway 透传并由 controller 解析出的访问上下文
     * @return 强类型低敏调度视图和本次返回窗口聚合摘要
     */
    public AgentSessionSchedulingProjectionQueryResponse querySnapshots(
            AgentRuntimeEventProjectionQuery query,
            AgentRuntimeEventQueryAccessContext accessContext) {
        AgentRuntimeEventProjectionQuery scopedQuery = forceSchedulingEventType(
                accessSupport.restrict(query, accessContext)
        );
        List<AgentSessionSchedulingProjectionView> snapshots = projectionStore.query(scopedQuery).stream()
                .map(this::toView)
                .toList();
        return buildResponse(scopedQuery, snapshots);
    }

    private AgentRuntimeEventProjectionQuery forceSchedulingEventType(AgentRuntimeEventProjectionQuery query) {
        /*
         * 专用接口必须固定事件类型，不能被调用方传入 eventType 扩大成第二个通用事件查询入口。
         * 这样能保证本接口始终只返回“多 Agent 调度事实”，方便前端、审计和后续 projection 建表。
         */
        return new AgentRuntimeEventProjectionQuery(
                query.tenantId(),
                query.projectId(),
                query.actorId(),
                query.requestId(),
                query.runId(),
                query.sessionId(),
                AGENT_SESSION_SCHEDULING_EVENT_TYPE,
                query.severity(),
                query.limit(),
                query.afterSequence(),
                query.authorizedProjectIds()
        );
    }

    private AgentSessionSchedulingProjectionQueryResponse buildResponse(
            AgentRuntimeEventProjectionQuery query,
            List<AgentSessionSchedulingProjectionView> snapshots) {
        return new AgentSessionSchedulingProjectionQueryResponse(
                query.normalizedLimit(),
                snapshots.size(),
                PROJECTION_SOURCE,
                countStatus(snapshots, "READY"),
                countStatus(snapshots, "DEGRADED"),
                countStatus(snapshots, "APPROVAL_REQUIRED"),
                countStatus(snapshots, "BLOCKED"),
                snapshots.stream().filter(snapshot -> Boolean.TRUE.equals(snapshot.handoffRequired())).count(),
                countScalar(snapshots.stream().map(AgentSessionSchedulingProjectionView::primaryAgentRole).toList()),
                countList(snapshots.stream().map(AgentSessionSchedulingProjectionView::participatingAgentRoles).toList()),
                countList(snapshots.stream().map(AgentSessionSchedulingProjectionView::intentDomains).toList()),
                countList(snapshots.stream().map(AgentSessionSchedulingProjectionView::plannedToolNames).toList()),
                countList(snapshots.stream().map(AgentSessionSchedulingProjectionView::selectedSkillCodes).toList()),
                snapshots
        );
    }

    private long countStatus(List<AgentSessionSchedulingProjectionView> snapshots, String status) {
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

    private AgentSessionSchedulingProjectionView toView(AgentRuntimeEventProjectionRecord record) {
        Map<String, Object> attributes = safeAttributes(record);
        return new AgentSessionSchedulingProjectionView(
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
                defaultedText(attributes, "snapshotType", "AGENT_SESSION_SCHEDULING_POLICY_VIEW"),
                bool(attributes, "available"),
                defaultedText(attributes, "status", "UNKNOWN"),
                defaultedText(attributes, "primaryAgentRole", "UNKNOWN"),
                integer(attributes, "participatingAgentCount"),
                stringList(attributes, "participatingAgentRoles"),
                integer(attributes, "participatingAgentRolesTruncatedCount"),
                intMap(attributes, "participationModeCounts"),
                intMap(attributes, "agentStatusCounts"),
                bool(attributes, "handoffRequired"),
                stringList(attributes, "handoffAgentRoles"),
                stringList(attributes, "intentDomains"),
                stringList(attributes, "selectedSkillCodes"),
                stringList(attributes, "visibleSkillCodes"),
                stringList(attributes, "plannedToolNames"),
                stringList(attributes, "memoryDependencies"),
                bool(attributes, "modelGatewayAvailable"),
                bool(attributes, "skillAdmissionAllowed"),
                bool(attributes, "toolBudgetAllowed"),
                bool(attributes, "approvalRequired"),
                bool(attributes, "tenantScoped"),
                bool(attributes, "projectScoped"),
                text(attributes, "displaySummary"),
                integer(attributes, "recommendedActionCount")
        );
    }

    private Map<String, Object> safeAttributes(AgentRuntimeEventProjectionRecord record) {
        if (record.attributes() == null || record.attributes().isEmpty()) {
            return Map.of();
        }
        return record.attributes();
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
