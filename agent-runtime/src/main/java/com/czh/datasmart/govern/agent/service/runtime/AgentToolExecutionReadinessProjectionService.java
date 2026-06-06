/**
 * @Author : Cui
 * @Date: 2026/06/06 00:00
 * @Description DataSmart Govern Backend - AgentToolExecutionReadinessProjectionService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.controller.dto.AgentToolExecutionReadinessDecisionSummaryView;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolExecutionReadinessProjectionQueryResponse;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolExecutionReadinessProjectionView;
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
 * 工具执行准备度 runtime event 的强类型投影查询服务。
 *
 * <p>Python Runtime 5.36 会把工具执行前治理结论写成 `tool_execution_readiness_recorded` 事件。Java
 * 控制面不重新执行 readiness 判断，也不读取工具参数值；本服务只把事件里的低敏摘要解析成强类型 DTO，
 * 便于前端、审计、WebSocket replay 和后续报表统一消费。</p>
 *
 * <p>当前实现继续基于通用 runtime event projection store，不立即创建 MySQL 专用表。这样可以先稳定
 * 跨语言事件契约和 API 字段，再决定是否落 dedicated index，避免过早固化表结构。</p>
 */
@Service
@RequiredArgsConstructor
public class AgentToolExecutionReadinessProjectionService {

    public static final String TOOL_EXECUTION_READINESS_EVENT_TYPE = "tool_execution_readiness_recorded";
    private static final String PROJECTION_SOURCE = "runtime-event-projection-fallback";

    private final AgentRuntimeEventProjectionStore projectionStore;
    private final AgentRuntimeEventProjectionAccessSupport accessSupport;

    /**
     * 查询工具执行准备度快照。
     *
     * @param query run/session/request/tenant/project/actor/limit/afterSequence 等过滤条件
     * @param accessContext gateway 解析出的访问上下文，用于租户、项目和本人数据范围收口
     * @return 强类型低敏 readiness 视图和本次返回窗口聚合摘要
     */
    public AgentToolExecutionReadinessProjectionQueryResponse querySnapshots(
            AgentRuntimeEventProjectionQuery query,
            AgentRuntimeEventQueryAccessContext accessContext) {
        AgentRuntimeEventProjectionQuery scopedQuery = forceReadinessEventType(
                accessSupport.restrict(query, accessContext)
        );
        List<AgentToolExecutionReadinessProjectionView> snapshots = projectionStore.query(scopedQuery).stream()
                .map(this::toView)
                .toList();
        return buildResponse(scopedQuery, snapshots);
    }

    private AgentRuntimeEventProjectionQuery forceReadinessEventType(AgentRuntimeEventProjectionQuery query) {
        /*
         * 专用接口必须固定事件类型，不能让调用方传入任意 eventType，把它变成第二个通用事件查询入口。
         * 这样能确保本接口始终只返回“工具执行准备度事实”，并经过本服务的字段白名单裁剪。
         */
        return new AgentRuntimeEventProjectionQuery(
                query.tenantId(),
                query.projectId(),
                query.actorId(),
                query.requestId(),
                query.runId(),
                query.sessionId(),
                TOOL_EXECUTION_READINESS_EVENT_TYPE,
                query.severity(),
                query.limit(),
                query.afterSequence(),
                query.authorizedProjectIds()
        );
    }

    private AgentToolExecutionReadinessProjectionQueryResponse buildResponse(
            AgentRuntimeEventProjectionQuery query,
            List<AgentToolExecutionReadinessProjectionView> snapshots) {
        return new AgentToolExecutionReadinessProjectionQueryResponse(
                query.normalizedLimit(),
                snapshots.size(),
                PROJECTION_SOURCE,
                snapshots.stream().filter(snapshot -> safe(snapshot.executableCount()) > 0).count(),
                snapshots.stream().filter(snapshot -> safe(snapshot.approvalRequiredCount()) > 0).count(),
                snapshots.stream().filter(snapshot -> safe(snapshot.clarificationRequiredCount()) > 0).count(),
                snapshots.stream().filter(snapshot -> safe(snapshot.draftOnlyCount()) > 0).count(),
                snapshots.stream().filter(snapshot -> safe(snapshot.queuedAsyncCount()) > 0).count(),
                snapshots.stream().filter(snapshot -> safe(snapshot.throttledCount()) > 0).count(),
                snapshots.stream().filter(snapshot -> safe(snapshot.blockedCount()) > 0).count(),
                countMap(snapshots.stream().map(AgentToolExecutionReadinessProjectionView::decisionCounts).toList()),
                countList(snapshots.stream().map(AgentToolExecutionReadinessProjectionView::toolNames).toList()),
                countList(snapshots.stream().map(AgentToolExecutionReadinessProjectionView::nextActions).toList()),
                snapshots
        );
    }

    private AgentToolExecutionReadinessProjectionView toView(AgentRuntimeEventProjectionRecord record) {
        Map<String, Object> attributes = safeAttributes(record);
        return new AgentToolExecutionReadinessProjectionView(
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
                defaultedText(attributes, "snapshotType", "TOOL_EXECUTION_READINESS"),
                defaultedText(attributes, "payloadPolicy", "LOW_SENSITIVE_METADATA_ONLY"),
                integer(attributes, "totalCount"),
                integer(attributes, "executableCount"),
                integer(attributes, "approvalRequiredCount"),
                integer(attributes, "clarificationRequiredCount"),
                integer(attributes, "draftOnlyCount"),
                integer(attributes, "queuedAsyncCount"),
                integer(attributes, "throttledCount"),
                integer(attributes, "blockedCount"),
                bool(attributes, "hasBlockingDecision"),
                stringList(attributes, "nextActions"),
                intMap(attributes, "decisionCounts"),
                intMap(attributes, "riskLevelCounts"),
                intMap(attributes, "executionModeCounts"),
                stringList(attributes, "toolNames"),
                integer(attributes, "toolNamesTruncatedCount"),
                decisionSummaries(attributes.get("decisionSummaries"))
        );
    }

    private List<AgentToolExecutionReadinessDecisionSummaryView> decisionSummaries(Object value) {
        if (!(value instanceof List<?> items) || items.isEmpty()) {
            return List.of();
        }
        List<AgentToolExecutionReadinessDecisionSummaryView> summaries = new ArrayList<>();
        for (Object item : items) {
            if (item instanceof Map<?, ?> map) {
                /*
                 * 这里仍然执行显式白名单：即使 Python 事件里未来 accidentally 带入 arguments、payload、
                 * sql、prompt 等字段，Java 投影视图也不会读取或返回。
                 */
                summaries.add(new AgentToolExecutionReadinessDecisionSummaryView(
                        text(map, "toolName"),
                        text(map, "decision"),
                        bool(map, "executable"),
                        bool(map, "queueRequired"),
                        bool(map, "requiresHumanApproval"),
                        stringList(map, "reasonCodes"),
                        stringList(map, "issueCodes"),
                        integer(map, "parameterIssueCount"),
                        stringList(map, "sensitiveArgumentNames"),
                        text(map, "retryHint")
                ));
            }
        }
        return List.copyOf(summaries);
    }

    private Map<String, Long> countMap(List<Map<String, Integer>> maps) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Map<String, Integer> map : maps) {
            for (Map.Entry<String, Integer> entry : map.entrySet()) {
                String key = bucketValue(entry.getKey(), null);
                if (key != null) {
                    counts.merge(key, (long) safe(entry.getValue()), Long::sum);
                }
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

    private Map<String, Object> safeAttributes(AgentRuntimeEventProjectionRecord record) {
        if (record.attributes() == null || record.attributes().isEmpty()) {
            return Map.of();
        }
        return record.attributes();
    }

    private String text(Map<?, ?> attributes, String key) {
        Object value = attributes.get(key);
        if (value == null) {
            return null;
        }
        String text = Objects.toString(value, "").trim();
        return text.isEmpty() ? null : text;
    }

    private String defaultedText(Map<?, ?> attributes, String key, String defaultValue) {
        return bucketValue(text(attributes, key), defaultValue);
    }

    private String bucketValue(String value, String defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return value.trim();
    }

    private Integer integer(Map<?, ?> attributes, String key) {
        return safeInt(attributes.get(key));
    }

    private int safe(Integer value) {
        return value == null ? 0 : Math.max(0, value);
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

    private Boolean bool(Map<?, ?> attributes, String key) {
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

    private List<String> stringList(Map<?, ?> attributes, String key) {
        return stringList(attributes.get(key));
    }

    private List<String> stringList(Object value) {
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

    private Map<String, Integer> intMap(Map<?, ?> attributes, String key) {
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
