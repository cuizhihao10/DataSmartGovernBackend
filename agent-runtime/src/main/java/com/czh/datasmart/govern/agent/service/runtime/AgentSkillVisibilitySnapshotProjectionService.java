/**
 * @Author : Cui
 * @Date: 2026/06/04 19:16
 * @Description DataSmart Govern Backend - AgentSkillVisibilitySnapshotProjectionService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.controller.dto.AgentSkillVisibilitySnapshotProjectionQueryResponse;
import com.czh.datasmart.govern.agent.controller.dto.AgentSkillVisibilitySnapshotProjectionView;
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
 * Skill 可见性快照 runtime event 的控制面查询服务。
 *
 * <p>Python Runtime 负责在 `/agent/plans` 组装阶段生成 `skillVisibility`，并把它压缩为
 * `skill_visibility_snapshot_recorded` 事件；Java agent-runtime 的职责不是重新判断 Skill 是否可见，
 * 而是把已经产生的事件事实接入控制面查询、replay 和后续审计索引。</p>
 *
 * <p>本服务位于通用 runtime event projection 之上，提供一个“面向产品语义”的查询入口：</p>
 * <p>1. 固定查询 `skill_visibility_snapshot_recorded`，调用方不用记事件类型字符串；</p>
 * <p>2. 复用 `AgentRuntimeEventProjectionAccessSupport` 做租户/项目/本人范围收口；</p>
 * <p>3. 把 attributes 中的自由 Map 解析成强类型 DTO；</p>
 * <p>4. 只返回低敏字段，避免把通用事件流中的敏感内容带到 Skill 治理页面。</p>
 *
 * <p>为什么当前不新建数据库表：现阶段事件投影仍是内存热窗口。先稳定查询语义和 DTO，后续 MySQL、
 * ClickHouse 或审计中心落地时，可以把 `projectionStore.query(...)` 换成索引查询，API 契约不需要推翻。</p>
 */
@Service
@RequiredArgsConstructor
public class AgentSkillVisibilitySnapshotProjectionService {

    /**
     * Python Runtime 6.14 阶段新增的事件类型。
     *
     * <p>这里使用字符串常量而不是 Java enum，是因为 runtime event 是跨语言契约，Java 控制面需要能消费
     * Python 未来新增的事件类型；只有当某个事件进入 Java 专用查询/展示语义时，才在对应服务中固定常量。</p>
     */
    public static final String SKILL_VISIBILITY_EVENT_TYPE = "skill_visibility_snapshot_recorded";

    private final AgentRuntimeEventProjectionStore projectionStore;
    private final AgentRuntimeEventProjectionAccessSupport accessSupport;

    /**
     * 查询会话级 Skill 可见性快照。
     *
     * @param query 调用方传入的 run/session/request/tenant/project/actor/limit/afterSequence 等过滤条件
     * @param accessContext gateway 透传并由 controller 解析出的访问上下文
     * @return 强类型低敏视图和本次返回窗口的聚合摘要
     */
    public AgentSkillVisibilitySnapshotProjectionQueryResponse querySnapshots(
            AgentRuntimeEventProjectionQuery query,
            AgentRuntimeEventQueryAccessContext accessContext) {
        AgentRuntimeEventProjectionQuery scopedQuery = forceSkillVisibilityEventType(
                accessSupport.restrict(query, accessContext)
        );
        List<AgentSkillVisibilitySnapshotProjectionView> snapshots = projectionStore.query(scopedQuery).stream()
                .map(this::toView)
                .toList();
        return buildResponse(scopedQuery, snapshots);
    }

    private AgentRuntimeEventProjectionQuery forceSkillVisibilityEventType(AgentRuntimeEventProjectionQuery query) {
        /*
         * 即使调用方传入 eventType，也不允许扩大为任意事件类型。
         * 这个专用接口的产品语义就是“Skill 可见性快照索引”，如果继续透传 eventType，
         * 前端或外部调用方会把它误用成第二个通用 runtime event 查询入口。
         */
        return new AgentRuntimeEventProjectionQuery(
                query.tenantId(),
                query.projectId(),
                query.actorId(),
                query.requestId(),
                query.runId(),
                query.sessionId(),
                SKILL_VISIBILITY_EVENT_TYPE,
                query.severity(),
                query.limit(),
                query.afterSequence(),
                query.authorizedProjectIds()
        );
    }

    private AgentSkillVisibilitySnapshotProjectionQueryResponse buildResponse(
            AgentRuntimeEventProjectionQuery query,
            List<AgentSkillVisibilitySnapshotProjectionView> snapshots) {
        long availableCount = snapshots.stream().filter(snapshot -> Boolean.TRUE.equals(snapshot.available())).count();
        int totalVisibleSkillCount = snapshots.stream()
                .mapToInt(snapshot -> safeInt(snapshot.visibleSkillCount()))
                .sum();
        int totalHiddenSkillCount = snapshots.stream()
                .mapToInt(snapshot -> safeInt(snapshot.hiddenSkillCount()))
                .sum();
        return new AgentSkillVisibilitySnapshotProjectionQueryResponse(
                query.normalizedLimit(),
                snapshots.size(),
                availableCount,
                snapshots.size() - availableCount,
                totalVisibleSkillCount,
                totalHiddenSkillCount,
                aggregatePermissionFactSources(snapshots),
                aggregateHiddenAdmissionStatuses(snapshots),
                snapshots
        );
    }

    private AgentSkillVisibilitySnapshotProjectionView toView(AgentRuntimeEventProjectionRecord record) {
        Map<String, Object> attributes = safeAttributes(record);
        return new AgentSkillVisibilitySnapshotProjectionView(
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
                text(attributes, "snapshotType"),
                text(attributes, "snapshotSource"),
                bool(attributes, "available"),
                integer(attributes, "availableSkillCount"),
                integer(attributes, "visibleSkillCount"),
                integer(attributes, "hiddenSkillCount"),
                integer(attributes, "conditionalVisibleSkillCount"),
                normalizedText(attributes, "permissionFactSource", "unknown"),
                normalizedText(attributes, "actorRoleSource", "unknown"),
                text(attributes, "actorRole"),
                integer(attributes, "grantedPermissionCount"),
                bool(attributes, "tenantSkillEnabled"),
                text(attributes, "workspaceRiskLevel"),
                text(attributes, "tenantPlanCode"),
                text(attributes, "policyVersion"),
                bool(attributes, "legacyRequestVariablesDetected"),
                bool(attributes, "modelGatewayAvailable"),
                bool(attributes, "toolBudgetAllowed"),
                stringList(attributes, "visibleSkillCodes"),
                integer(attributes, "visibleSkillCodesTruncatedCount"),
                stringList(attributes, "hiddenSkillCodes"),
                integer(attributes, "hiddenSkillCodesTruncatedCount"),
                intMap(attributes, "visibleRiskLevelCounts"),
                intMap(attributes, "visibleDomainCounts"),
                intMap(attributes, "hiddenAdmissionStatusCounts"),
                text(attributes, "displaySummary"),
                integer(attributes, "recommendedActionCount")
        );
    }

    private Map<String, Long> aggregatePermissionFactSources(
            List<AgentSkillVisibilitySnapshotProjectionView> snapshots) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (AgentSkillVisibilitySnapshotProjectionView snapshot : snapshots) {
            String source = normalizeValue(snapshot.permissionFactSource(), "unknown");
            counts.merge(source, 1L, Long::sum);
        }
        return Collections.unmodifiableMap(counts);
    }

    private Map<String, Integer> aggregateHiddenAdmissionStatuses(
            List<AgentSkillVisibilitySnapshotProjectionView> snapshots) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (AgentSkillVisibilitySnapshotProjectionView snapshot : snapshots) {
            for (Map.Entry<String, Integer> entry : snapshot.hiddenAdmissionStatusCounts().entrySet()) {
                counts.merge(entry.getKey(), safeInt(entry.getValue()), Integer::sum);
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

    private String text(Map<String, Object> attributes, String key) {
        Object value = attributes.get(key);
        if (value == null) {
            return null;
        }
        String text = Objects.toString(value, "").trim();
        return text.isEmpty() ? null : text;
    }

    private String normalizedText(Map<String, Object> attributes, String key, String defaultValue) {
        return normalizeValue(text(attributes, key), defaultValue);
    }

    private String normalizeValue(String value, String defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return value.trim().toLowerCase(Locale.ROOT);
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

    @SuppressWarnings("unchecked")
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
