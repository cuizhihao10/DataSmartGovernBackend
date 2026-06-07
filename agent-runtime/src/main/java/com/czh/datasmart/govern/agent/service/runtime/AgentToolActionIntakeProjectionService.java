/**
 * @Author : Cui
 * @Date: 2026/06/07 13:39
 * @Description DataSmart Govern Backend - AgentToolActionIntakeProjectionService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionIntakeDecisionSummaryView;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionIntakeProjectionQueryResponse;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionIntakeProjectionView;
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
 * 工具动作意图入口 runtime event 的强类型投影查询服务。
 *
 * <p>Python Runtime 5.48 会把 MCP `tools/call` preview 结果压缩成 `tool_action_intake_recorded`
 * 事件。该事件不是工具执行结果，而是“外部工具动作意图已经进入 DataSmart host-level intake 治理”的事实。
 * Java 侧只做字段白名单解析、聚合和展示，不重新执行 intake/readiness，也不读取原始 MCP arguments。</p>
 *
 * <p>为什么单独建服务：通用 runtime event 查询可以返回原始 attributes，但商业化管理台需要稳定字段、默认值、
 * 窗口聚合和低敏保障。把解析集中在服务层，可以避免 controller、前端或测试脚本各自解析 Map，从而减少耦合。</p>
 */
@Service
@RequiredArgsConstructor
public class AgentToolActionIntakeProjectionService {

    public static final String TOOL_ACTION_INTAKE_EVENT_TYPE = "tool_action_intake_recorded";
    private static final String PROJECTION_SOURCE = "runtime-event-projection-fallback";

    private final AgentRuntimeEventProjectionStore projectionStore;
    private final AgentRuntimeEventProjectionAccessSupport accessSupport;

    /**
     * 查询工具动作意图入口快照。
     *
     * @param query run/session/request/tenant/project/actor/limit/afterSequence 等过滤条件。
     * @param accessContext gateway 与 permission-admin 解析出的访问上下文，用于租户、项目和本人数据范围收口。
     * @return 低敏强类型快照与本次返回窗口聚合。
     */
    public AgentToolActionIntakeProjectionQueryResponse querySnapshots(
            AgentRuntimeEventProjectionQuery query,
            AgentRuntimeEventQueryAccessContext accessContext) {
        AgentRuntimeEventProjectionQuery scopedQuery = forceToolActionIntakeEventType(
                accessSupport.restrict(query, accessContext)
        );
        List<AgentToolActionIntakeProjectionView> snapshots = projectionStore.query(scopedQuery).stream()
                .map(this::toView)
                .toList();
        return buildResponse(scopedQuery, snapshots);
    }

    private AgentRuntimeEventProjectionQuery forceToolActionIntakeEventType(AgentRuntimeEventProjectionQuery query) {
        /*
         * 专用接口必须固定 eventType，不能让调用方传入任意事件类型来绕过字段白名单。
         * 这也是后续 dedicated index 的契约边界：该接口永远只解释工具动作入口事实。
         */
        return new AgentRuntimeEventProjectionQuery(
                query.tenantId(),
                query.projectId(),
                query.actorId(),
                query.requestId(),
                query.runId(),
                query.sessionId(),
                TOOL_ACTION_INTAKE_EVENT_TYPE,
                query.severity(),
                query.limit(),
                query.afterSequence(),
                query.authorizedProjectIds()
        );
    }

    private AgentToolActionIntakeProjectionQueryResponse buildResponse(
            AgentRuntimeEventProjectionQuery query,
            List<AgentToolActionIntakeProjectionView> snapshots) {
        return new AgentToolActionIntakeProjectionQueryResponse(
                query.normalizedLimit(),
                snapshots.size(),
                PROJECTION_SOURCE,
                snapshots.stream().filter(snapshot -> safe(snapshot.acceptedToolPlanCount()) > 0).count(),
                snapshots.stream().filter(snapshot -> safe(snapshot.rejectedBeforeReadinessCount()) > 0).count(),
                snapshots.stream().filter(snapshot -> safe(snapshot.readinessExecutableCount()) > 0).count(),
                snapshots.stream().filter(snapshot -> safe(snapshot.readinessApprovalRequiredCount()) > 0).count(),
                snapshots.stream().filter(snapshot -> safe(snapshot.readinessClarificationRequiredCount()) > 0).count(),
                snapshots.stream().filter(snapshot -> safe(snapshot.readinessBlockedCount()) > 0).count(),
                countMap(snapshots.stream().map(AgentToolActionIntakeProjectionView::boundaryCounts).toList()),
                countMap(snapshots.stream().map(AgentToolActionIntakeProjectionView::graphBranchCounts).toList()),
                countList(snapshots.stream().map(AgentToolActionIntakeProjectionView::toolNames).toList()),
                countList(snapshots.stream().map(AgentToolActionIntakeProjectionView::issueCodes).toList()),
                countList(snapshots.stream().map(AgentToolActionIntakeProjectionView::readinessNextActions).toList()),
                snapshots
        );
    }

    private AgentToolActionIntakeProjectionView toView(AgentRuntimeEventProjectionRecord record) {
        Map<String, Object> attributes = safeAttributes(record);
        return new AgentToolActionIntakeProjectionView(
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
                defaultedText(attributes, "snapshotType", "TOOL_ACTION_INTAKE"),
                defaultedText(attributes, "payloadPolicy", "LOW_SENSITIVE_TOOL_ACTION_INTAKE_EVENT_ONLY"),
                text(attributes, "schemaVersion"),
                text(attributes, "protocolFamily"),
                bool(attributes, "previewOnly"),
                bool(attributes, "toolExecutionEnabled"),
                bool(attributes, "jsonRpcDetected"),
                bool(attributes, "methodAccepted"),
                bool(attributes, "callDetected"),
                integer(attributes, "sensitiveFieldIgnoredCount"),
                text(attributes, "source"),
                integer(attributes, "totalCount"),
                integer(attributes, "acceptedToolPlanCount"),
                integer(attributes, "rejectedBeforeReadinessCount"),
                intMap(attributes, "boundaryCounts"),
                stringList(attributes, "issueCodes"),
                integer(attributes, "blockingIssueCount"),
                stringList(attributes, "toolNames"),
                integer(attributes, "toolNamesTruncatedCount"),
                integer(attributes, "readinessTotalCount"),
                integer(attributes, "readinessExecutableCount"),
                integer(attributes, "readinessApprovalRequiredCount"),
                integer(attributes, "readinessClarificationRequiredCount"),
                integer(attributes, "readinessDraftOnlyCount"),
                integer(attributes, "readinessQueuedAsyncCount"),
                integer(attributes, "readinessThrottledCount"),
                integer(attributes, "readinessBlockedCount"),
                bool(attributes, "readinessHasBlockingDecision"),
                stringList(attributes, "readinessNextActions"),
                stringList(attributes, "readinessReasonCodes"),
                text(attributes, "graphExecutionBoundary"),
                integer(attributes, "graphNodeCount"),
                integer(attributes, "graphEdgeCount"),
                stringList(attributes, "graphBranches"),
                intMap(attributes, "graphBranchCounts"),
                bool(attributes, "graphToolExecuted"),
                bool(attributes, "graphOutboxWritten"),
                bool(attributes, "graphApprovalCreated"),
                bool(attributes, "graphWorkerReceiptRequiredForSideEffects"),
                bool(attributes, "productionReadyForExecution"),
                stringList(attributes, "missingProductionRequirements"),
                decisionSummaries(attributes.get("decisionSummaries"))
        );
    }

    private List<AgentToolActionIntakeDecisionSummaryView> decisionSummaries(Object value) {
        if (!(value instanceof List<?> items) || items.isEmpty()) {
            return List.of();
        }
        List<AgentToolActionIntakeDecisionSummaryView> summaries = new ArrayList<>();
        for (Object item : items) {
            if (item instanceof Map<?, ?> map) {
                /*
                 * 这里只解析 Python 事件定义的低敏摘要字段。即使 attributes 中混入 arguments、payload、sql、
                 * prompt、sampleData 或 internalEndpoint，本服务也不会读取，更不会返回。
                 */
                summaries.add(new AgentToolActionIntakeDecisionSummaryView(
                        text(map, "toolName"),
                        text(map, "decision"),
                        bool(map, "executable"),
                        bool(map, "queueRequired"),
                        bool(map, "requiresHumanApproval"),
                        integer(map, "parameterIssueCount"),
                        stringList(map, "issueCodes"),
                        stringList(map, "reasonCodes"),
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
