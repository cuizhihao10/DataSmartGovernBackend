/**
 * @Author : Cui
 * @Date: 2026/06/06 00:00
 * @Description DataSmart Govern Backend - AgentModelGatewayRoutingProjectionService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.controller.dto.AgentModelGatewayRouteScoringView;
import com.czh.datasmart.govern.agent.controller.dto.AgentModelGatewayRoutingProjectionQueryResponse;
import com.czh.datasmart.govern.agent.controller.dto.AgentModelGatewayRoutingProjectionView;
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
 * 模型网关路由 runtime event 的强类型投影查询服务。
 *
 * <p>Python Runtime 负责真正执行模型路由，Java agent-runtime 不重新计算 Provider 评分，也不猜测
 * Python 当时为什么 fallback。本服务只做一件事：把已经产生的 `model_gateway_routed` 事件事实，
 * 以 Java 控制面更稳定、更低敏的 DTO 形式暴露出来。</p>
 *
 * <p>为什么不让前端直接解析通用 runtime event attributes：</p>
 * <p>1. attributes 是跨语言自由 Map，字段类型可能来自 JSON 数字、字符串、布尔或列表；</p>
 * <p>2. 前端不应该知道哪些字段敏感、哪些字段只是内部评分细节；</p>
 * <p>3. 后续模型网关事件 v3/v4 变更时，服务层可以兼容新旧字段，而不推翻前端和审计导出。</p>
 */
@Service
@RequiredArgsConstructor
public class AgentModelGatewayRoutingProjectionService {

    public static final String MODEL_GATEWAY_ROUTED_EVENT_TYPE = "model_gateway_routed";
    private static final String PROJECTION_SOURCE = "runtime-event-projection-fallback";

    private final AgentRuntimeEventProjectionStore projectionStore;
    private final AgentRuntimeEventProjectionAccessSupport accessSupport;

    /**
     * 查询模型网关路由快照。
     *
     * @param query run/session/request/tenant/project/actor/limit/afterSequence 等过滤条件
     * @param accessContext gateway 透传的访问上下文，用于租户、项目和本人数据范围收口
     * @return 强类型低敏路由视图和本次返回窗口聚合摘要
     */
    public AgentModelGatewayRoutingProjectionQueryResponse querySnapshots(
            AgentRuntimeEventProjectionQuery query,
            AgentRuntimeEventQueryAccessContext accessContext) {
        AgentRuntimeEventProjectionQuery scopedQuery = forceModelGatewayEventType(
                accessSupport.restrict(query, accessContext)
        );
        List<AgentModelGatewayRoutingProjectionView> snapshots = projectionStore.query(scopedQuery).stream()
                .map(this::toView)
                .toList();
        return buildResponse(scopedQuery, snapshots);
    }

    private AgentRuntimeEventProjectionQuery forceModelGatewayEventType(AgentRuntimeEventProjectionQuery query) {
        /*
         * 专用接口必须固定事件类型。否则调用方可以把它当成第二个通用 runtime event 查询入口，
         * 绕开模型网关投影 DTO 的字段裁剪和产品语义。
         */
        return new AgentRuntimeEventProjectionQuery(
                query.tenantId(),
                query.projectId(),
                query.actorId(),
                query.requestId(),
                query.runId(),
                query.sessionId(),
                MODEL_GATEWAY_ROUTED_EVENT_TYPE,
                query.severity(),
                query.limit(),
                query.afterSequence(),
                query.authorizedProjectIds()
        );
    }

    private AgentModelGatewayRoutingProjectionQueryResponse buildResponse(
            AgentRuntimeEventProjectionQuery query,
            List<AgentModelGatewayRoutingProjectionView> snapshots) {
        return new AgentModelGatewayRoutingProjectionQueryResponse(
                query.normalizedLimit(),
                snapshots.size(),
                PROJECTION_SOURCE,
                snapshots.stream().filter(snapshot -> Boolean.TRUE.equals(snapshot.fallbackUsed())).count(),
                snapshots.stream().filter(snapshot -> !Boolean.TRUE.equals(snapshot.budgetAllowed())).count(),
                snapshots.stream().filter(snapshot -> Boolean.TRUE.equals(snapshot.cachePlanEnabled())).count(),
                snapshots.stream().filter(snapshot -> Boolean.TRUE.equals(snapshot.routeScoringTruncated())).count(),
                countScalar(snapshots.stream().map(AgentModelGatewayRoutingProjectionView::selectedProvider).toList()),
                countScalar(snapshots.stream().map(AgentModelGatewayRoutingProjectionView::selectedHealthStatus).toList()),
                snapshots
        );
    }

    private AgentModelGatewayRoutingProjectionView toView(AgentRuntimeEventProjectionRecord record) {
        Map<String, Object> attributes = safeAttributes(record);
        List<String> cachePlanIssues = stringList(attributes, "cachePlanIssues");
        return new AgentModelGatewayRoutingProjectionView(
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
                text(attributes, "schemaVersion"),
                text(attributes, "eventPayloadPolicy"),
                text(attributes, "selectedProvider"),
                text(attributes, "selectedModel"),
                defaultedText(attributes, "selectedHealthStatus", "unknown"),
                text(attributes, "configuredPrimaryProvider"),
                stringList(attributes, "orderedCandidateProviders"),
                integer(attributes, "candidateCount"),
                bool(attributes, "fallbackUsed"),
                bool(attributes, "budgetAllowed"),
                bool(attributes, "budgetWarning"),
                bool(attributes, "cacheAwareRouting"),
                text(attributes, "cacheKeyScope"),
                bool(attributes, "cachePlanEnabled"),
                text(attributes, "cachePlanScope"),
                text(attributes, "cachePlanNamespace") != null,
                integer(attributes, "cachePlanTtlSeconds"),
                cachePlanIssues,
                cachePlanIssues.size(),
                integer(attributes, "routeScoringCount"),
                bool(attributes, "routeScoringTruncated"),
                routeScoringViews(attributes.get("routeScoring"))
        );
    }

    private List<AgentModelGatewayRouteScoringView> routeScoringViews(Object value) {
        if (!(value instanceof List<?> items) || items.isEmpty()) {
            return List.of();
        }
        List<AgentModelGatewayRouteScoringView> views = new ArrayList<>();
        for (Object item : items) {
            if (item instanceof Map<?, ?> map) {
                views.add(new AgentModelGatewayRouteScoringView(
                        text(map, "providerName"),
                        text(map, "modelName"),
                        defaultedText(map, "healthStatus", "unknown"),
                        text(map, "latencyTier"),
                        text(map, "cacheScope"),
                        bool(map, "cachePlanEnabled"),
                        stringList(map, "cacheIssues").size(),
                        integer(map, "priority")
                ));
            }
        }
        return List.copyOf(views);
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
}
