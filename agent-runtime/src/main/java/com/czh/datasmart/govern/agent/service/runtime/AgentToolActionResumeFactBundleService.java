/**
 * @Author : Cui
 * @Date: 2026/06/16 00:00
 * @Description DataSmart Govern Backend - AgentToolActionResumeFactBundleService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.config.AgentToolActionResumeFactBundleProperties;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionResumeFactBundleQueryRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionResumeFactBundleResponse;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionResumeFactOutboxSummaryView;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionResumeFactReceiptSummaryView;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionResumeFactView;
import com.czh.datasmart.govern.agent.event.command.AgentAsyncTaskCommandOutboxRecord;
import com.czh.datasmart.govern.agent.event.command.AgentAsyncTaskCommandOutboxStatus;
import com.czh.datasmart.govern.agent.event.command.AgentAsyncTaskCommandOutboxStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Agent 工具动作恢复事实包服务。
 *
 * <p>本服务把分散在多个控制面位置的事实聚合成统一、安全、可学习的“事实包”：
 * 1. permission-admin：审批事实是否真实批准且作用域匹配；
 * 2. command outbox：Java 是否已经写入 durable command；
 * 3. runtime event projection：task-management/worker 是否已经回写 dry-run/pre-check receipt。</p>
 *
 * <p>它刻意不做三件事：
 * 1. 不执行工具；
 * 2. 不写入或投递 outbox；
 * 3. 不恢复 Python/LangGraph/OpenClaw 执行图。</p>
 *
 * <p>这样做的产品意义是：Python Runtime 可以从“相信请求里带了哪些字段”升级为“消费 Java 控制面采信的事实类型”，
 * 但真实副作用仍由 Java outbox、task-management worker、permission-admin 审批和审计链共同保护。</p>
 */
@Service
@RequiredArgsConstructor
public class AgentToolActionResumeFactBundleService {

    public static final String SCHEMA_VERSION = "datasmart.agent-runtime.tool-action-resume-fact-bundle.v1";
    public static final String PAYLOAD_POLICY =
            "FACT_TYPE_AND_CONTROL_STATUS_ONLY_NO_FACT_VALUES_NO_PAYLOAD_BODY";
    public static final String QUERY_BOUNDARY = "LOW_SENSITIVE_RESUME_FACT_BUNDLE_QUERY_ONLY";

    private static final String FACT_APPROVAL = "APPROVAL_CONFIRMATION_FACT";
    private static final String FACT_CLARIFICATION = "CLARIFICATION_FACT";
    private static final String FACT_OUTBOX = "OUTBOX_WRITE_CONFIRMATION";
    private static final String FACT_WORKER_RECEIPT = "WORKER_RECEIPT_PROJECTION";

    private final AgentToolActionResumeFactBundleProperties properties;
    private final AgentToolActionApprovalFactEvaluator approvalFactEvaluator;
    private final AgentAsyncTaskCommandOutboxStore outboxStore;
    private final AgentRuntimeEventProjectionStore projectionStore;
    private final AgentRuntimeEventProjectionAccessSupport accessSupport;

    /**
     * 查询恢复事实包。
     *
     * <p>服务层会先构造受权限收口约束的查询边界，再逐个事实源回查。
     * 即使调用方在 body 中传入 tenantId/projectId/actorId，也不能扩大 gateway/permission-admin Header
     * 下发的数据范围；请求 body 只能进一步缩小查询。</p>
     *
     * @param request 查询请求，可为空；为空时按空请求处理并返回缺少事实的安全响应。
     * @param accessContext 当前请求的租户、actor、角色和数据范围上下文。
     * @return 低敏恢复事实包。
     */
    public AgentToolActionResumeFactBundleResponse query(AgentToolActionResumeFactBundleQueryRequest request,
                                                         AgentRuntimeEventQueryAccessContext accessContext) {
        AgentToolActionResumeFactBundleQueryRequest normalizedRequest = request == null
                ? emptyRequest()
                : request;
        Instant now = Instant.now();
        AgentRuntimeEventProjectionQuery scopedQuery = scopedQuery(normalizedRequest, accessContext);
        List<String> requiredFactTypes = requiredFactTypes(normalizedRequest);

        List<AgentToolActionResumeFactView> facts = new ArrayList<>();
        AgentToolActionResumeFactOutboxSummaryView outboxSummary = null;
        AgentToolActionResumeFactReceiptSummaryView receiptSummary = null;

        for (String factType : requiredFactTypes) {
            switch (factType) {
                case FACT_APPROVAL -> facts.add(approvalFact(normalizedRequest, accessContext, now));
                case FACT_CLARIFICATION -> facts.add(clarificationFact(now));
                case FACT_OUTBOX -> {
                    OutboxFact outboxFact = outboxFact(normalizedRequest, scopedQuery, now);
                    facts.add(outboxFact.fact());
                    if (Boolean.TRUE.equals(normalizedRequest.includeOutboxSummary())
                            || normalizedRequest.includeOutboxSummary() == null) {
                        outboxSummary = outboxFact.summary();
                    }
                }
                case FACT_WORKER_RECEIPT -> {
                    ReceiptFact receiptFact = receiptFact(normalizedRequest, scopedQuery, now);
                    facts.add(receiptFact.fact());
                    if (Boolean.TRUE.equals(normalizedRequest.includeReceiptSummary())
                            || normalizedRequest.includeReceiptSummary() == null) {
                        receiptSummary = receiptFact.summary();
                    }
                }
                default -> facts.add(unknownFact(factType, now));
            }
        }

        List<String> availableFactTypes = facts.stream()
                .filter(fact -> Boolean.TRUE.equals(fact.available()))
                .filter(fact -> !Boolean.TRUE.equals(fact.rejected()))
                .map(AgentToolActionResumeFactView::factType)
                .distinct()
                .toList();
        List<String> rejectedFactTypes = facts.stream()
                .filter(fact -> Boolean.TRUE.equals(fact.rejected()))
                .map(AgentToolActionResumeFactView::factType)
                .distinct()
                .toList();
        List<String> missingFactTypes = facts.stream()
                .filter(fact -> !Boolean.TRUE.equals(fact.available()))
                .map(AgentToolActionResumeFactView::factType)
                .distinct()
                .toList();

        return new AgentToolActionResumeFactBundleResponse(
                SCHEMA_VERSION,
                true,
                QUERY_BOUNDARY,
                PAYLOAD_POLICY,
                requestedLocator(normalizedRequest, scopedQuery),
                requiredFactTypes,
                availableFactTypes,
                missingFactTypes,
                rejectedFactTypes,
                facts,
                outboxSummary,
                receiptSummary,
                recommendedActions(facts),
                productionReadiness(),
                now
        );
    }

    private AgentToolActionResumeFactView approvalFact(AgentToolActionResumeFactBundleQueryRequest request,
                                                       AgentRuntimeEventQueryAccessContext accessContext,
                                                       Instant now) {
        if (accessContext == null || !accessContext.hasIdentity()) {
            return fact(FACT_APPROVAL, "PERMISSION_ADMIN", "MISSING", false, false, false,
                    List.of(), List.of("ACCESS_CONTEXT_REQUIRED_FOR_APPROVAL_FACT_EVALUATION"), now);
        }
        if (!hasText(request.approvalFactId())) {
            return fact(FACT_APPROVAL, "PERMISSION_ADMIN", "MISSING", false, false, false,
                    List.of(), List.of("APPROVAL_FACT_ID_REQUIRED"), now);
        }
        AgentToolActionApprovalFactRemoteResult result = approvalFactEvaluator.evaluate(
                new AgentToolActionApprovalFactRemoteRequest(
                        request.approvalFactId(),
                        request.tenantId() == null ? accessContext.tenantId() : request.tenantId(),
                        request.projectId(),
                        firstText(request.actorId(), String.valueOf(accessContext.actorId())),
                        request.sessionId(),
                        request.runId(),
                        request.commandId(),
                        request.toolCode(),
                        request.requestedPolicyVersion(),
                        accessContext.traceId()
                )
        );
        boolean approved = Boolean.TRUE.equals(result.approved());
        boolean rejected = !approved && !Boolean.TRUE.equals(result.retryable())
                && !"NOT_EVALUATED".equals(result.decision());
        String status = approved ? "AVAILABLE" : "NOT_EVALUATED".equals(result.decision()) ? "NOT_EVALUATED" : "REJECTED";
        return fact(
                FACT_APPROVAL,
                "PERMISSION_ADMIN",
                status,
                approved,
                rejected,
                Boolean.TRUE.equals(result.retryable()),
                result.evidenceCodes(),
                result.issueCodes(),
                now
        );
    }

    private AgentToolActionResumeFactView clarificationFact(Instant now) {
        /*
         * 当前阶段还没有独立 clarification fact store。
         * 这里先把事实类型列出来并明确返回缺失，避免调用方误以为 clarificationFactId 字段存在就可被采信。
         */
        return fact(FACT_CLARIFICATION, "CLARIFICATION_FACT_STORE", "MISSING", false, false, false,
                List.of(), List.of("CLARIFICATION_FACT_PROVIDER_NOT_CONNECTED"), now);
    }

    private OutboxFact outboxFact(AgentToolActionResumeFactBundleQueryRequest request,
                                 AgentRuntimeEventProjectionQuery scopedQuery,
                                 Instant now) {
        Optional<AgentAsyncTaskCommandOutboxRecord> recordOptional = findOutboxRecord(request);
        if (recordOptional.isEmpty() || !outboxVisible(recordOptional.get(), request, scopedQuery)) {
            return new OutboxFact(
                    fact(FACT_OUTBOX, "AGENT_RUNTIME_COMMAND_OUTBOX", "MISSING", false, false, false,
                            List.of(), List.of("OUTBOX_RECORD_NOT_FOUND_OR_NOT_VISIBLE"), now),
                    null
            );
        }
        AgentAsyncTaskCommandOutboxRecord record = recordOptional.get();
        boolean rejected = record.status() == AgentAsyncTaskCommandOutboxStatus.BLOCKED
                || record.status() == AgentAsyncTaskCommandOutboxStatus.IGNORED;
        return new OutboxFact(
                fact(
                        FACT_OUTBOX,
                        "AGENT_RUNTIME_COMMAND_OUTBOX",
                        rejected ? "REJECTED" : "AVAILABLE",
                        !rejected,
                        rejected,
                        record.status() == AgentAsyncTaskCommandOutboxStatus.FAILED,
                        List.of("OUTBOX_RECORD_FOUND", "OUTBOX_PAYLOAD_REFERENCE_NOT_EXPOSED"),
                        rejected ? List.of("OUTBOX_RECORD_" + record.status().name()) : List.of(),
                        now
                ),
                AgentToolActionResumeFactOutboxSummaryView.from(record)
        );
    }

    private ReceiptFact receiptFact(AgentToolActionResumeFactBundleQueryRequest request,
                                    AgentRuntimeEventProjectionQuery scopedQuery,
                                    Instant now) {
        if (!hasText(request.commandId())) {
            return new ReceiptFact(
                    fact(FACT_WORKER_RECEIPT, "RUNTIME_EVENT_PROJECTION", "MISSING", false, false, false,
                            List.of(), List.of("WORKER_RECEIPT_COMMAND_ID_REQUIRED"), now),
                    null
            );
        }
        List<AgentRuntimeEventProjectionRecord> receipts = queryReceiptRecords(request, scopedQuery);
        if (receipts.isEmpty()) {
            return new ReceiptFact(
                    fact(FACT_WORKER_RECEIPT, "RUNTIME_EVENT_PROJECTION", "MISSING", false, false, true,
                            List.of(), List.of("WORKER_RECEIPT_NOT_FOUND"), now),
                    new AgentToolActionResumeFactReceiptSummaryView(
                            0, false, null, null, null, null, null, null, null, PAYLOAD_POLICY
                    )
            );
        }
        AgentRuntimeEventProjectionRecord latest = receipts.getLast();
        boolean preCheckPassed = bool(latest.attributes().get("preCheckPassed"));
        boolean sideEffectExecuted = bool(latest.attributes().get("sideEffectExecuted"));
        String outcome = text(latest.attributes().get("outcome"));
        boolean rejected = !preCheckPassed && outcome != null && outcome.startsWith("FAILED");
        List<String> issueCodes = rejected
                ? List.of(firstText(text(latest.attributes().get("errorCode")), "WORKER_RECEIPT_PRECHECK_FAILED"))
                : List.of();
        return new ReceiptFact(
                fact(
                        FACT_WORKER_RECEIPT,
                        "RUNTIME_EVENT_PROJECTION",
                        rejected ? "REJECTED" : "AVAILABLE",
                        !rejected,
                        rejected,
                        !preCheckPassed && !rejected,
                        List.of("WORKER_RECEIPT_PROJECTION_FOUND", "WORKER_RECEIPT_MESSAGE_NOT_EXPOSED"),
                        issueCodes,
                        now
                ),
                new AgentToolActionResumeFactReceiptSummaryView(
                        receipts.size(),
                        true,
                        latest.replaySequence(),
                        outcome,
                        text(latest.attributes().get("taskStatus")),
                        preCheckPassed,
                        sideEffectExecuted,
                        text(latest.attributes().get("errorCode")),
                        latest.consumedAt(),
                        PAYLOAD_POLICY
                )
        );
    }

    private List<AgentRuntimeEventProjectionRecord> queryReceiptRecords(AgentToolActionResumeFactBundleQueryRequest request,
                                                                        AgentRuntimeEventProjectionQuery scopedQuery) {
        AgentRuntimeEventProjectionQuery receiptQuery = new AgentRuntimeEventProjectionQuery(
                scopedQuery.tenantId(),
                scopedQuery.projectId(),
                scopedQuery.actorId(),
                null,
                scopedQuery.runId(),
                scopedQuery.sessionId(),
                AgentToolActionControlledDryRunReceiptService.EVENT_TYPE,
                null,
                normalizedReceiptLimit(),
                scopedQuery.afterSequence(),
                scopedQuery.authorizedProjectIds()
        );
        return projectionStore.query(receiptQuery).stream()
                .filter(record -> request.commandId().equals(text(record.attributes().get("commandId"))))
                .toList();
    }

    private Optional<AgentAsyncTaskCommandOutboxRecord> findOutboxRecord(
            AgentToolActionResumeFactBundleQueryRequest request) {
        if (hasText(request.outboxId())) {
            return outboxStore.findByOutboxId(request.outboxId().trim());
        }
        if (hasText(request.commandId())) {
            return outboxStore.findByCommandId(request.commandId().trim());
        }
        return Optional.empty();
    }

    private boolean outboxVisible(AgentAsyncTaskCommandOutboxRecord record,
                                  AgentToolActionResumeFactBundleQueryRequest request,
                                  AgentRuntimeEventProjectionQuery scopedQuery) {
        List<String> authorizedProjectIds = scopedQuery.normalizedAuthorizedProjectIds();
        if (authorizedProjectIds != null && !authorizedProjectIds.contains(String.valueOf(record.projectId()))) {
            return false;
        }
        return matches(scopedQuery.tenantId(), record.tenantId())
                && matches(scopedQuery.projectId(), record.projectId())
                && matches(scopedQuery.actorId(), record.actorId())
                && matches(scopedQuery.runId(), record.runId())
                && matches(scopedQuery.sessionId(), record.sessionId())
                && matches(request.toolCode(), record.toolCode());
    }

    private AgentRuntimeEventProjectionQuery scopedQuery(AgentToolActionResumeFactBundleQueryRequest request,
                                                         AgentRuntimeEventQueryAccessContext accessContext) {
        AgentRuntimeEventProjectionQuery rawQuery = new AgentRuntimeEventProjectionQuery(
                request.tenantId() == null ? null : String.valueOf(request.tenantId()),
                request.projectId() == null ? null : String.valueOf(request.projectId()),
                request.actorId(),
                null,
                request.runId(),
                request.sessionId(),
                null,
                null,
                normalizedReceiptLimit()
        );
        return accessSupport.restrict(rawQuery, accessContext);
    }

    private List<String> requiredFactTypes(AgentToolActionResumeFactBundleQueryRequest request) {
        Set<String> result = new LinkedHashSet<>();
        if (request.requiredFactTypes() != null) {
            request.requiredFactTypes().stream()
                    .filter(this::hasText)
                    .map(value -> value.trim().toUpperCase())
                    .forEach(result::add);
        }
        if (result.isEmpty()) {
            if (hasText(request.approvalFactId())) {
                result.add(FACT_APPROVAL);
            }
            if (hasText(request.clarificationFactId())) {
                result.add(FACT_CLARIFICATION);
            }
            if (hasText(request.commandId()) || hasText(request.outboxId())) {
                result.add(FACT_OUTBOX);
                result.add(FACT_WORKER_RECEIPT);
            }
        }
        if (result.isEmpty()) {
            result.add(FACT_APPROVAL);
            result.add(FACT_OUTBOX);
            result.add(FACT_WORKER_RECEIPT);
        }
        return List.copyOf(result);
    }

    private Map<String, Object> requestedLocator(AgentToolActionResumeFactBundleQueryRequest request,
                                                 AgentRuntimeEventProjectionQuery scopedQuery) {
        Map<String, Object> locator = new LinkedHashMap<>();
        locator.put("checkpointIdPresent", hasText(request.checkpointId()));
        locator.put("threadIdPresent", hasText(request.threadId()));
        locator.put("sessionId", text(request.sessionId()));
        locator.put("runId", text(request.runId()));
        locator.put("commandId", text(request.commandId()));
        locator.put("outboxIdPresent", hasText(request.outboxId()));
        locator.put("approvalFactIdPresent", hasText(request.approvalFactId()));
        locator.put("clarificationFactIdPresent", hasText(request.clarificationFactId()));
        locator.put("toolCode", text(request.toolCode()));
        locator.put("scopedTenantId", scopedQuery.tenantId());
        locator.put("scopedProjectId", scopedQuery.projectId());
        locator.put("scopedActorId", scopedQuery.actorId());
        return locator;
    }

    private List<String> recommendedActions(List<AgentToolActionResumeFactView> facts) {
        Set<String> actions = new LinkedHashSet<>();
        for (AgentToolActionResumeFactView fact : facts) {
            if (Boolean.TRUE.equals(fact.rejected())) {
                actions.add("RECREATE_OR_REPAIR_REJECTED_" + fact.factType());
                continue;
            }
            if (Boolean.TRUE.equals(fact.available())) {
                continue;
            }
            switch (fact.factType()) {
                case FACT_APPROVAL -> actions.add("WAIT_FOR_OR_RECREATE_PERMISSION_ADMIN_APPROVAL_FACT");
                case FACT_CLARIFICATION -> actions.add("CONNECT_CLARIFICATION_FACT_STORE_OR_REQUEST_USER_INPUT");
                case FACT_OUTBOX -> actions.add("CALL_JAVA_COMMAND_OUTBOX_WRITER_AFTER_GRAPH_CONFIRMATION");
                case FACT_WORKER_RECEIPT -> actions.add("WAIT_FOR_TASK_MANAGEMENT_RECEIPT_OR_RETRY_DISPATCHER");
                default -> actions.add("REVIEW_UNKNOWN_RESUME_FACT_TYPE");
            }
        }
        if (actions.isEmpty()) {
            actions.add("SERVER_FACT_TYPES_READY_FOR_PYTHON_RESUME_PREVIEW_BUT_DO_NOT_EXECUTE_TOOL_DIRECTLY");
        }
        return List.copyOf(actions);
    }

    private Map<String, Object> productionReadiness() {
        return Map.of(
                "currentMode", "CONTROL_PLANE_FACT_BUNDLE_PREVIEW_ONLY",
                "missingProductionRequirements", List.of(
                        "DURABLE_CHECKPOINT_STORE_TO_DISCOVER_FACTS_BY_CHECKPOINT_ID",
                        "CLARIFICATION_FACT_PROVIDER",
                        "WORKER_RECEIPT_PERSISTENT_INDEX",
                        "SERVICE_ACCOUNT_SIGNATURE_OR_MTLS",
                        "PROMETHEUS_LOW_CARDINALITY_METRICS_FOR_RESUME_FACT_QUERY",
                        "AUDIT_EVENT_FOR_RESUME_FACT_BUNDLE_QUERY"
                )
        );
    }

    private AgentToolActionResumeFactView unknownFact(String factType, Instant now) {
        return fact(factType, "UNKNOWN", "MISSING", false, false, false,
                List.of(), List.of("UNKNOWN_RESUME_FACT_TYPE"), now);
    }

    private AgentToolActionResumeFactView fact(String factType,
                                               String source,
                                               String status,
                                               boolean available,
                                               boolean rejected,
                                               boolean retryable,
                                               List<String> evidenceCodes,
                                               List<String> issueCodes,
                                               Instant checkedAt) {
        return new AgentToolActionResumeFactView(
                factType,
                source,
                status,
                available,
                rejected,
                retryable,
                evidenceCodes,
                issueCodes,
                PAYLOAD_POLICY,
                checkedAt
        );
    }

    private AgentToolActionResumeFactBundleQueryRequest emptyRequest() {
        return new AgentToolActionResumeFactBundleQueryRequest(
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null
        );
    }

    private int normalizedReceiptLimit() {
        Integer configured = properties.getReceiptProjectionQueryLimit();
        if (configured == null || configured <= 0) {
            return 50;
        }
        return Math.min(configured, 500);
    }

    private boolean matches(String expected, Long actual) {
        return expected == null || expected.isBlank() || expected.equals(String.valueOf(actual));
    }

    private boolean matches(String expected, String actual) {
        return expected == null || expected.isBlank() || expected.equals(actual);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private String firstText(String first, String fallback) {
        return hasText(first) ? first.trim() : text(fallback);
    }

    private boolean bool(Object value) {
        if (value instanceof Boolean boolValue) {
            return boolValue;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private record OutboxFact(AgentToolActionResumeFactView fact, AgentToolActionResumeFactOutboxSummaryView summary) {
    }

    private record ReceiptFact(AgentToolActionResumeFactView fact, AgentToolActionResumeFactReceiptSummaryView summary) {
    }
}
