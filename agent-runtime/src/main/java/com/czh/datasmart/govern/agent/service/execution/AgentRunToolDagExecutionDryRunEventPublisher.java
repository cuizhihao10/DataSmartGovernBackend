/**
 * @Author : Cui
 * @Date: 2026/06/01 00:28
 * @Description DataSmart Govern Backend - AgentRunToolDagExecutionDryRunEventPublisher.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.execution;

import com.czh.datasmart.govern.agent.controller.dto.AgentRunToolDagExecutionDryRunRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentRunToolDagExecutionDryRunResponse;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolDagExecutionDryRunItemView;
import com.czh.datasmart.govern.agent.service.runtime.AgentRuntimeEventProjectionRecord;
import com.czh.datasmart.govern.agent.service.runtime.AgentRuntimeEventProjectionStore;
import com.czh.datasmart.govern.agent.service.session.AgentSessionMemoryStore;
import com.czh.datasmart.govern.agent.service.session.AgentSessionRecord;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * DAG execution dry-run 的 runtime event 发布器。
 *
 * <p>dry-run 本身是一个“无副作用行动预案”，但在真实产品里，预案也必须被看见和回放。
 * 如果用户或智能网关后续确认执行某些节点，审计台需要知道“确认之前系统展示过怎样的候选、阻断和批量上限”。
 * 因此本类把 dry-run 响应压缩成一条安全摘要事件，写入现有 {@link AgentRuntimeEventProjectionStore}，
 * 让 runtime event 查询、未来 WebSocket replay 和审计视图都能复用同一条投影链路。</p>
 *
 * <p>这里刻意只写“摘要”，不写完整节点 reasons、executionPath、工具参数或 payload：
 * 1. reasons 可能包含业务上下文，适合 API 直接响应给当前调用方，不适合长期扩散到事件流；
 * 2. executionPath 只是受控入口提示，不是最终执行事实，事件里保留 action 分类即可；
 * 3. 工具参数、payload 和真实结果仍应通过受控 audit/result/resource reference 查询，而不是塞进 runtime event。</p>
 *
 * <p>该类与 dry-run 核心服务拆开，是为了控制文件规模和职责边界：dry-run service 负责业务选择与响应组装，
 * publisher 负责事件摘要。后续如果事件从内存投影升级为 outbox/Kafka/持久化表，只需要替换这里的发布策略。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentRunToolDagExecutionDryRunEventPublisher {

    /**
     * dry-run 事件 schema 版本。
     *
     * <p>事件 schema 独立于 HTTP DTO 版本，是因为事件会被 replay、WebSocket、审计台和可能的外部订阅者长期消费。
     * 即使 HTTP 响应以后新增字段，事件也可以保持兼容，按版本逐步演进。</p>
     */
    public static final String SCHEMA_VERSION = "datasmart.agent-runtime.dag-execution-dry-run.v1";

    /**
     * runtime event 类型。
     *
     * <p>事件名包含 {@code completed}，不是表示工具执行完成，而是表示“本次 dry-run 预案生成完成”。
     * 这样能被现有 runtime event 可见性白名单识别为普通进度事件，同时又不会和真实工具执行事件混淆。</p>
     */
    public static final String EVENT_TYPE = "agent.dag_execution.dry_run.completed";

    private static final String SOURCE = "JAVA_AGENT_RUNTIME";
    private static final String STAGE = "dag_execution_dry_run_completed";

    private final AgentRuntimeEventProjectionStore projectionStore;
    private final AgentSessionMemoryStore sessionStore;

    /**
     * 将 dry-run 响应写入 runtime event 投影。
     *
     * <p>该方法被设计为“尽力而为”：投影失败不应阻断 dry-run API 返回，因为 dry-run 是调用方当前交互链路，
     * 而投影当前仍是热窗口能力。生产阶段如果要把该事件作为强审计事实，应进一步接入 outbox 和持久化审计表，
     * 再决定是否 fail-closed。</p>
     *
     * @param sessionId Agent 会话 ID，用于回填投影查询维度。
     * @param runId Agent Run ID，用于事件 replay 和 run 详情聚合。
     * @param traceId 当前 HTTP trace/request ID，便于把 API 请求与 runtime event 串起来。
     * @param request 原始 dry-run 请求，只抽取选择器和批量上限，不写入任何工具参数。
     * @param response 已生成的 dry-run 响应摘要。
     */
    public void publish(String sessionId,
                        String runId,
                        String traceId,
                        AgentRunToolDagExecutionDryRunRequest request,
                        AgentRunToolDagExecutionDryRunResponse response) {
        try {
            AgentRuntimeEventProjectionRecord record = buildRecord(sessionId, runId, traceId, request, response);
            boolean appended = projectionStore.append(record);
            if (!appended) {
                log.debug("DAG execution dry-run runtime event 已存在，跳过去重写入，identityKey={}", record.identityKey());
            }
        } catch (RuntimeException ex) {
            log.warn("DAG execution dry-run runtime event 写入失败，sessionId={}, runId={}, error={}",
                    sessionId, runId, ex.getMessage());
        }
    }

    private AgentRuntimeEventProjectionRecord buildRecord(String sessionId,
                                                          String runId,
                                                          String traceId,
                                                          AgentRunToolDagExecutionDryRunRequest request,
                                                          AgentRunToolDagExecutionDryRunResponse response) {
        Instant now = Instant.now();
        Optional<AgentSessionRecord> session = sessionStore.findById(sessionId);
        return new AgentRuntimeEventProjectionRecord(
                "dag-dry-run:" + runId + ":" + UUID.randomUUID(),
                SCHEMA_VERSION,
                SOURCE,
                EVENT_TYPE,
                STAGE,
                messageOf(response),
                severityOf(response),
                session.map(AgentSessionRecord::getTenantId).map(String::valueOf).orElse(null),
                session.map(AgentSessionRecord::getProjectId).map(String::valueOf).orElse(null),
                session.map(AgentSessionRecord::getActorId).orElse(null),
                traceId,
                runId,
                sessionId,
                null,
                now,
                now,
                now,
                attributesOf(request, response)
        );
    }

    private String messageOf(AgentRunToolDagExecutionDryRunResponse response) {
        return "DAG dry-run 已生成：同步候选 " + response.syncDryRunCandidateCount()
                + " 个，异步预案 " + response.asyncEnqueuePreviewCount()
                + " 个，阻断 " + response.blockedCount()
                + " 个，未命中 " + response.notFoundCount()
                + " 个。";
    }

    private String severityOf(AgentRunToolDagExecutionDryRunResponse response) {
        if (response.notFoundCount() != null && response.notFoundCount() > 0) {
            return "audit";
        }
        if ((response.blockedCount() != null && response.blockedCount() > 0)
                || (response.batchLimitReachedCount() != null && response.batchLimitReachedCount() > 0)) {
            return "audit";
        }
        return "info";
    }

    private Map<String, Object> attributesOf(AgentRunToolDagExecutionDryRunRequest request,
                                             AgentRunToolDagExecutionDryRunResponse response) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("dryRunOnly", true);
        attributes.put("requestedNodeIds", response.requestedNodeIds());
        attributes.put("requestedAuditIds", response.requestedAuditIds());
        attributes.put("requestedMaxNodes", request == null ? null : request.maxNodes());
        attributes.put("effectiveMaxNodes", response.effectiveMaxNodes());
        attributes.put("selectionFingerprint", response.selectionFingerprint());
        attributes.put("selectedCount", response.selectedCount());
        attributes.put("syncDryRunCandidateCount", response.syncDryRunCandidateCount());
        attributes.put("asyncEnqueuePreviewCount", response.asyncEnqueuePreviewCount());
        attributes.put("blockedCount", response.blockedCount());
        attributes.put("notSelectedCount", response.notSelectedCount());
        attributes.put("notFoundCount", response.notFoundCount());
        attributes.put("batchLimitReachedCount", response.batchLimitReachedCount());
        /*
         * 这里新增的是“控制面阻断摘要”，不是完整原因明细。
         *
         * 在商业化 Agent 产品里，运营和审计通常会关心：
         * - 有多少节点被沙箱策略拒绝；
         * - 有多少节点因为运行时容量/熔断保护被暂缓；
         * - 常见阻断问题码是什么，是否集中在某个保护维度上。
         *
         * 但 runtime event 又不能变成另一个工具明细表。完整 reasons、recommendedActions、工具参数、
         * executionPath 等内容要么可能包含业务上下文，要么容易形成高基数数据，不适合进入事件流长期扩散。
         * 因此这里仅写入去重后的 issueCodes 与聚合计数，既能支撑看板和告警，也保留安全边界。
         */
        attributes.put("sandboxRejectedCount", sandboxRejectedCount(response.items()));
        attributes.put("sandboxIssueCodes", issueCodes(response.items(), IssueCodeSource.SANDBOX));
        attributes.put("runtimeProtectionRejectedCount", runtimeProtectionRejectedCount(response.items()));
        attributes.put("runtimeProtectionIssueCodes", issueCodes(response.items(), IssueCodeSource.RUNTIME_PROTECTION));
        attributes.put("runtimeCircuitOpenCount", runtimeCircuitOpenCount(response.items()));
        attributes.put("runtimeCapacityRejectedCount", runtimeCapacityRejectedCount(response.items()));
        attributes.put("actionCounts", actionCounts(response.items()));
        attributes.put("items", itemSummaries(response.items()));
        attributes.put("summaryReasonCount", response.summaryReasons() == null ? 0 : response.summaryReasons().size());
        attributes.put("recommendedActionCount", response.recommendedActions() == null ? 0 : response.recommendedActions().size());
        attributes.put("eventPayloadPolicy", "SUMMARY_ONLY_NO_TOOL_ARGUMENTS_NO_EXECUTION_PATH");
        return Collections.unmodifiableMap(attributes);
    }

    private Map<String, Integer> actionCounts(List<AgentToolDagExecutionDryRunItemView> items) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        if (items == null) {
            return Map.of();
        }
        for (AgentToolDagExecutionDryRunItemView item : items) {
            String action = item.dryRunAction() == null ? "UNKNOWN" : item.dryRunAction();
            counts.put(action, counts.getOrDefault(action, 0) + 1);
        }
        return Collections.unmodifiableMap(counts);
    }

    private List<Map<String, Object>> itemSummaries(List<AgentToolDagExecutionDryRunItemView> items) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        return items.stream()
                .map(this::itemSummary)
                .toList();
    }

    private Map<String, Object> itemSummary(AgentToolDagExecutionDryRunItemView item) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("nodeId", item.nodeId());
        summary.put("auditId", item.auditId());
        summary.put("toolCode", item.toolCode());
        summary.put("selected", item.selected());
        summary.put("previewAction", item.previewAction());
        summary.put("dryRunAction", item.dryRunAction());
        summary.put("readyForExecution", item.readyForExecution());
        summary.put("targetWouldTriggerSideEffect", item.targetWouldTriggerSideEffect());
        summary.put("asyncDispatchable", item.asyncDispatchable());
        summary.put("asyncCommandId", item.asyncCommandId());
        summary.put("serviceAuthorizationDecision", item.serviceAuthorizationDecision());
        summary.put("serviceAuthorizationAllowed", item.serviceAuthorizationAllowed());
        /*
         * 节点级摘要同样只暴露低敏问题码，不暴露 sandboxReasons 或 runtimeProtectionReasons。
         *
         * 原因是 reasons 可能描述具体租户、项目、接口、参数大小、下游健康等上下文，适合在当前 API 响应中
         * 按权限直接返回给调用方，但不适合写入 runtime event 这种会被 replay、导出和多角色消费的通用事件层。
         * issueCodes 是稳定枚举，天然适合作为事件展示、聚合统计和未来低基数指标的连接点。
         */
        summary.put("sandboxAllowed", item.sandboxAllowed());
        summary.put("sandboxIssueCodes", safeList(item.sandboxIssueCodes()));
        summary.put("runtimeProtectionAllowed", item.runtimeProtectionAllowed());
        summary.put("runtimeProtectionIssueCodes", safeList(item.runtimeProtectionIssueCodes()));
        summary.put("runtimeCircuitOpen", item.runtimeCircuitOpen());
        summary.put("riskLevel", item.riskLevel());
        summary.put("readOnly", item.readOnly());
        summary.put("idempotent", item.idempotent());
        summary.put("requiresApproval", item.requiresApproval());
        return Collections.unmodifiableMap(summary);
    }

    private int sandboxRejectedCount(List<AgentToolDagExecutionDryRunItemView> items) {
        if (items == null || items.isEmpty()) {
            return 0;
        }
        return (int) items.stream()
                .filter(item -> Boolean.FALSE.equals(item.sandboxAllowed()))
                .count();
    }

    private int runtimeProtectionRejectedCount(List<AgentToolDagExecutionDryRunItemView> items) {
        if (items == null || items.isEmpty()) {
            return 0;
        }
        return (int) items.stream()
                .filter(item -> Boolean.FALSE.equals(item.runtimeProtectionAllowed()))
                .count();
    }

    private int runtimeCircuitOpenCount(List<AgentToolDagExecutionDryRunItemView> items) {
        if (items == null || items.isEmpty()) {
            return 0;
        }
        return (int) items.stream()
                .filter(item -> Boolean.TRUE.equals(item.runtimeCircuitOpen())
                        || safeList(item.runtimeProtectionIssueCodes()).contains("TARGET_SERVICE_CIRCUIT_OPEN"))
                .count();
    }

    private int runtimeCapacityRejectedCount(List<AgentToolDagExecutionDryRunItemView> items) {
        if (items == null || items.isEmpty()) {
            return 0;
        }
        return (int) items.stream()
                .filter(item -> safeList(item.runtimeProtectionIssueCodes()).stream().anyMatch(this::isRuntimeCapacityIssue))
                .count();
    }

    private boolean isRuntimeCapacityIssue(String issueCode) {
        String normalized = issueCode == null ? "" : issueCode.trim().toUpperCase(Locale.ROOT);
        return normalized.contains("IN_FLIGHT_LIMIT_EXCEEDED");
    }

    private List<String> issueCodes(List<AgentToolDagExecutionDryRunItemView> items, IssueCodeSource source) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        Set<String> codes = new LinkedHashSet<>();
        for (AgentToolDagExecutionDryRunItemView item : items) {
            List<String> itemCodes = source == IssueCodeSource.SANDBOX
                    ? item.sandboxIssueCodes()
                    : item.runtimeProtectionIssueCodes();
            codes.addAll(safeList(itemCodes));
        }
        return List.copyOf(codes);
    }

    private List<String> safeList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }

    private enum IssueCodeSource {
        SANDBOX,
        RUNTIME_PROTECTION
    }
}
