/**
 * @Author : Cui
 * @Date: 2026/07/02 18:08
 * @Description DataSmart Govern Backend - AgentTurnRunnerProjectionService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.controller.dto.AgentTurnRunnerAttemptProjectionView;
import com.czh.datasmart.govern.agent.controller.dto.AgentTurnRunnerCheckpointProjectionView;
import com.czh.datasmart.govern.agent.controller.dto.AgentTurnRunnerProjectionQueryResponse;
import com.czh.datasmart.govern.agent.controller.dto.AgentTurnRunnerProjectionView;
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
 * 受控多 Agent Turn Runner runtime event 的强类型投影查询服务。
 *
 * <p>Python Runtime 在 `/agent/plans` 响应中生成 `agentTurnRunner` 后，会追加
 * `agent_turn_runner_recorded` runtime event。Java agent-runtime 在这里把该事件解释成管理台可消费的
 * turn runner 视图，回答“下一轮 specialist Agent turn 是否可以推进、缺什么 host fact、是否仍保持
 * Java 控制面副作用边界”。</p>
 *
 * <p>本服务刻意复用 `AgentRuntimeEventProjectionStore`：在项目收敛阶段，先稳定事件合同、权限收口和 API
 * 语义，比立刻新增一张可能频繁变化的专用表更稳。后续真实流量证明查询量较高时，可以在不改变 Controller
 * 和 DTO 的前提下把数据源替换成 dedicated index。</p>
 */
@Service
@RequiredArgsConstructor
public class AgentTurnRunnerProjectionService {

    public static final String AGENT_TURN_RUNNER_EVENT_TYPE = "agent_turn_runner_recorded";
    private static final String PROJECTION_SOURCE = "runtime-event-projection-fallback";

    private final AgentRuntimeEventProjectionStore projectionStore;
    private final AgentRuntimeEventProjectionAccessSupport accessSupport;

    /**
     * 查询受控多 Agent Turn Runner 快照。
     *
     * @param query 调用方传入的 run/session/request/tenant/project/actor/limit/afterSequence 等过滤条件。
     * @param accessContext gateway 透传并由 controller 解析出的访问上下文。
     * @return 强类型低敏 turn runner 视图和当前窗口聚合摘要。
     */
    public AgentTurnRunnerProjectionQueryResponse querySnapshots(
            AgentRuntimeEventProjectionQuery query,
            AgentRuntimeEventQueryAccessContext accessContext) {
        AgentRuntimeEventProjectionQuery scopedQuery = forceTurnRunnerEventType(
                accessSupport.restrict(query, accessContext)
        );
        List<AgentTurnRunnerProjectionView> snapshots = projectionStore.query(scopedQuery).stream()
                .map(this::toView)
                .toList();
        return buildResponse(scopedQuery, snapshots);
    }

    private AgentRuntimeEventProjectionQuery forceTurnRunnerEventType(AgentRuntimeEventProjectionQuery query) {
        /*
         * 专用接口必须固定 eventType。否则调用方可以通过 query string 把本接口扩大成通用事件查询，
         * 从而破坏“只返回 turn runner 低敏字段”的产品承诺。
         */
        return new AgentRuntimeEventProjectionQuery(
                query.tenantId(),
                query.projectId(),
                query.actorId(),
                query.requestId(),
                query.runId(),
                query.sessionId(),
                AGENT_TURN_RUNNER_EVENT_TYPE,
                query.severity(),
                query.limit(),
                query.afterSequence(),
                query.authorizedProjectIds()
        );
    }

    private AgentTurnRunnerProjectionQueryResponse buildResponse(
            AgentRuntimeEventProjectionQuery query,
            List<AgentTurnRunnerProjectionView> snapshots) {
        return new AgentTurnRunnerProjectionQueryResponse(
                query.normalizedLimit(),
                snapshots.size(),
                PROJECTION_SOURCE,
                countRunStatusPrefix(snapshots, "WAITING"),
                countRunStatusPrefix(snapshots, "BLOCKED"),
                countReadyRunners(snapshots),
                snapshots.stream().filter(this::hasSideEffectViolation).count(),
                sumInt(snapshots.stream().map(AgentTurnRunnerProjectionView::waitingAttemptCount).toList()),
                sumInt(snapshots.stream().map(AgentTurnRunnerProjectionView::blockedAttemptCount).toList()),
                sumInt(snapshots.stream().map(AgentTurnRunnerProjectionView::controlPlaneHandoffCount).toList()),
                sumInt(snapshots.stream().map(AgentTurnRunnerProjectionView::managerAsToolsCount).toList()),
                countCheckpointLinked(snapshots),
                countScalar(snapshots.stream().map(AgentTurnRunnerProjectionView::status).toList()),
                countScalar(snapshots.stream().map(AgentTurnRunnerProjectionView::runStatus).toList()),
                countScalar(snapshots.stream().map(AgentTurnRunnerProjectionView::sessionStatus).toList()),
                countScalar(snapshots.stream().map(AgentTurnRunnerProjectionView::durablePhase).toList()),
                countScalar(snapshots.stream()
                        .map(snapshot -> checkpointText(snapshot.turnRunnerCheckpoint(), "status"))
                        .toList()),
                countScalar(snapshots.stream()
                        .map(snapshot -> checkpointText(snapshot.turnRunnerCheckpoint(), "node"))
                        .toList()),
                countScalar(snapshots.stream()
                        .map(snapshot -> checkpointText(snapshot.turnRunnerCheckpoint(), "graph"))
                        .toList()),
                countList(snapshots.stream()
                        .map(snapshot -> checkpointList(snapshot.turnRunnerCheckpoint()))
                        .toList()),
                countMapValues(snapshots.stream().map(AgentTurnRunnerProjectionView::turnStatusCounts).toList()),
                countMapValues(snapshots.stream().map(AgentTurnRunnerProjectionView::deliveryTierCounts).toList()),
                countMapValues(snapshots.stream().map(AgentTurnRunnerProjectionView::resumeActionCounts).toList()),
                countMapValues(snapshots.stream().map(AgentTurnRunnerProjectionView::requiredEvidenceCounts).toList()),
                countList(snapshots.stream().map(AgentTurnRunnerProjectionView::nextActions).toList()),
                snapshots
        );
    }

    private AgentTurnRunnerProjectionView toView(AgentRuntimeEventProjectionRecord record) {
        Map<String, Object> attributes = safeAttributes(record);
        List<AgentTurnRunnerAttemptProjectionView> attempts = turnAttempts(attributes);
        AgentTurnRunnerCheckpointProjectionView checkpoint = turnRunnerCheckpoint(attributes);
        return new AgentTurnRunnerProjectionView(
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
                defaultedText(attributes, "snapshotType", "CONTROLLED_MULTI_AGENT_TURN_RUNNER_VIEW"),
                text(attributes, "schemaVersion"),
                defaultedText(attributes, "status", "UNKNOWN"),
                defaultedText(attributes, "runStatus", "UNKNOWN"),
                defaultedText(attributes, "sessionStatus", "UNKNOWN"),
                defaultedText(attributes, "durablePhase", "not_recorded"),
                integer(attributes, "currentTurnDepth"),
                integer(attributes, "maxTurnDepth"),
                integer(attributes, "maxConcurrentAgentTurns"),
                integer(attributes, "turnAttemptCount"),
                integer(attributes, "waitingAttemptCount"),
                integer(attributes, "blockedAttemptCount"),
                integer(attributes, "controlPlaneHandoffCount"),
                integer(attributes, "managerAsToolsCount"),
                attempts,
                integer(attributes, "turnAttemptsTruncatedCount"),
                checkpoint,
                countAttemptScalar(attempts.stream().map(AgentTurnRunnerAttemptProjectionView::turnStatus).toList()),
                countAttemptScalar(attempts.stream().map(AgentTurnRunnerAttemptProjectionView::deliveryTier).toList()),
                countAttemptScalar(attempts.stream().map(AgentTurnRunnerAttemptProjectionView::resumeAction).toList()),
                countEvidence(attempts),
                stringList(attributes, "nextActions"),
                bool(attributes, "toolExecutedByPython"),
                bool(attributes, "modelCalledByTurnRunner"),
                bool(attributes, "outboxWrittenByPython"),
                bool(attributes, "approvalCreatedByPython"),
                bool(attributes, "workerDispatchedByPython"),
                bool(attributes, "javaControlPlaneRequiredForSideEffects"),
                bool(attributes, "workerReceiptRequiredForSideEffects"),
                text(attributes, "executionBoundary"),
                text(attributes, "payloadPolicy")
        );
    }

    private AgentTurnRunnerCheckpointProjectionView turnRunnerCheckpoint(Map<String, Object> attributes) {
        /*
         * 只解析嵌套的 turnRunnerCheckpoint 白名单对象，而不是读取 attributes 顶层 checkpointId。
         * 顶层 checkpointId 可能来自旧调试字段、第三方误传或恶意注入；如果直接读取，就会破坏“只有 Python
         * 事件构建器裁剪后的 LangGraph locator 才能进入 Java 控制面”的边界。
         */
        Object raw = attributes.get("turnRunnerCheckpoint");
        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }
        Map<String, Object> item = normalizeMap(map);
        String threadId = text(item, "threadId");
        String checkpointId = text(item, "checkpointId");
        if (threadId == null || checkpointId == null) {
            return null;
        }
        return new AgentTurnRunnerCheckpointProjectionView(
                threadId,
                checkpointId,
                text(item, "parentCheckpointId"),
                text(item, "graphName"),
                text(item, "graphVersion"),
                text(item, "nodeName"),
                text(item, "checkpointStatus"),
                integer(item, "checkpointVersion"),
                stringList(item, "nextNodes"),
                stringList(item, "resumeRequirementKeys"),
                stringList(item, "stateTopLevelKeys"),
                bool(item, "recoveryFound"),
                text(item, "recoveryStatus"),
                stringList(item, "recoveryAgentRoles"),
                stringMap(item, "recoveryAgentStatuses"),
                bool(item, "handoffRequired"),
                defaultedText(item, "payloadPolicy",
                        "LOW_SENSITIVE_MULTI_AGENT_TURN_RUNNER_CHECKPOINT_LOCATOR_ONLY")
        );
    }

    private List<AgentTurnRunnerAttemptProjectionView> turnAttempts(Map<String, Object> attributes) {
        Object raw = attributes.get("turnAttempts");
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<AgentTurnRunnerAttemptProjectionView> parsed = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                parsed.add(turnAttempt(map));
            }
        }
        return List.copyOf(parsed);
    }

    private AgentTurnRunnerAttemptProjectionView turnAttempt(Map<?, ?> raw) {
        Map<String, Object> item = normalizeMap(raw);
        return new AgentTurnRunnerAttemptProjectionView(
                text(item, "turnId"),
                text(item, "workItemId"),
                defaultedText(item, "agentRole", "UNKNOWN_AGENT"),
                defaultedText(item, "deliveryTier", "runtime_governance"),
                defaultedText(item, "turnStatus", "UNKNOWN"),
                text(item, "resumeAction"),
                text(item, "managerToolName"),
                stringList(item, "requiredEvidenceCodes"),
                stringList(item, "waitingReasonCodes"),
                stringList(item, "blockedBy"),
                integer(item, "plannedToolCount"),
                integer(item, "visibleSkillCount"),
                integer(item, "memoryDependencyCount"),
                text(item, "payloadPolicy")
        );
    }

    private boolean hasSideEffectViolation(AgentTurnRunnerProjectionView snapshot) {
        /*
         * 正常情况下这些字段都必须为 false。若未来某次 Python event 声称 turn runner 已执行工具、写 outbox
         * 或创建审批，Java 管理台应该立即把它视为边界违规，而不是静默展示为普通成功事件。
         */
        return Boolean.TRUE.equals(snapshot.toolExecutedByPython())
                || Boolean.TRUE.equals(snapshot.modelCalledByTurnRunner())
                || Boolean.TRUE.equals(snapshot.outboxWrittenByPython())
                || Boolean.TRUE.equals(snapshot.approvalCreatedByPython())
                || Boolean.TRUE.equals(snapshot.workerDispatchedByPython());
    }

    private long countReadyRunners(List<AgentTurnRunnerProjectionView> snapshots) {
        return snapshots.stream()
                .filter(snapshot -> !prefix(snapshot.runStatus(), "WAITING") && !prefix(snapshot.runStatus(), "BLOCKED"))
                .filter(snapshot -> snapshot.controlPlaneHandoffCount() != null && snapshot.controlPlaneHandoffCount() > 0)
                .count();
    }

    private long countCheckpointLinked(List<AgentTurnRunnerProjectionView> snapshots) {
        return snapshots.stream().filter(snapshot -> snapshot.turnRunnerCheckpoint() != null).count();
    }

    private String checkpointText(AgentTurnRunnerCheckpointProjectionView checkpoint, String field) {
        if (checkpoint == null) {
            return null;
        }
        return switch (field) {
            case "status" -> checkpoint.checkpointStatus();
            case "node" -> checkpoint.nodeName();
            case "graph" -> checkpoint.graphName();
            default -> null;
        };
    }

    private List<String> checkpointList(AgentTurnRunnerCheckpointProjectionView checkpoint) {
        if (checkpoint == null) {
            return List.of();
        }
        return checkpoint.resumeRequirementKeys();
    }

    private long countRunStatusPrefix(List<AgentTurnRunnerProjectionView> snapshots, String prefix) {
        return snapshots.stream().filter(snapshot -> prefix(snapshot.runStatus(), prefix)).count();
    }

    private boolean prefix(String value, String prefix) {
        return value != null && value.toUpperCase(Locale.ROOT).startsWith(prefix);
    }

    private long sumInt(List<Integer> values) {
        return values.stream().filter(Objects::nonNull).mapToLong(Integer::longValue).sum();
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

    private Map<String, Integer> countAttemptScalar(List<String> values) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String value : values) {
            String normalized = bucketValue(value, null);
            if (normalized != null) {
                counts.merge(normalized, 1, Integer::sum);
            }
        }
        return Collections.unmodifiableMap(counts);
    }

    private Map<String, Integer> countEvidence(List<AgentTurnRunnerAttemptProjectionView> attempts) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (AgentTurnRunnerAttemptProjectionView attempt : attempts) {
            for (String code : attempt.requiredEvidenceCodes()) {
                String normalized = bucketValue(code, null);
                if (normalized != null) {
                    counts.merge(normalized, 1, Integer::sum);
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

    private Map<String, String> stringMap(Map<String, Object> attributes, String key) {
        Object value = attributes.get(key);
        if (!(value instanceof Map<?, ?> raw)) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            String mapKey = Objects.toString(entry.getKey(), "").trim();
            String mapValue = Objects.toString(entry.getValue(), "").trim();
            if (!mapKey.isEmpty() && !mapValue.isEmpty()) {
                result.put(mapKey, mapValue);
            }
        }
        return Collections.unmodifiableMap(result);
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
