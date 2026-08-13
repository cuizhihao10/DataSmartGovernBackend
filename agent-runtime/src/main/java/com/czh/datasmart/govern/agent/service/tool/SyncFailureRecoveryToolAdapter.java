package com.czh.datasmart.govern.agent.service.tool;

import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Executes the governed diagnosis, repair, retry and case-publication lifecycle. */
@Component
@RequiredArgsConstructor
public class SyncFailureRecoveryToolAdapter implements AgentToolAdapter {

    /** Digest-bound reason shared by the ordinary preview and user-confirmed apply path. */
    private static final String USER_QUARANTINE_REASON = "USER_CONFIRMED_AGENT_DIRTY_RECORD_QUARANTINE";
    /**
     * Digest-bound reason understood by the dedicated data-sync Autopilot apply endpoint.
     * Keep this cross-service constant aligned with SyncDirtyRecordQuarantineSupport.
     */
    private static final String AUTOPILOT_QUARANTINE_REASON =
            "AUTOPILOT_PREAUTHORIZED_DIRTY_RECORD_QUARANTINE";

    public static final String DIAGNOSE = "sync.execution.diagnose";
    public static final String RAG_LOOKUP = "sync.execution.rag.lookup";
    public static final String FAILED_OBJECTS_RETRY = "sync.execution.failed-objects.retry";
    public static final String DIRTY_QUARANTINE_PREVIEW = "sync.dirty-record.quarantine.preview";
    public static final String DIRTY_QUARANTINE_APPLY = "sync.dirty-record.quarantine.apply";
    public static final String DIRTY_REPLAY = "sync.dirty-record.replay";
    public static final String SCHEMA_REPAIR_PREVIEW = "datasource.schema.repair.preview";
    public static final String SCHEMA_REPAIR_APPLY = "datasource.schema.repair.apply";
    public static final String CASE_PUBLISH = "sync.recovery.case.publish";

    private static final String DATA_SYNC = "data-sync";
    private static final String DATASOURCE = "datasource-management";
    private static final String AI_RUNTIME = "python-ai-runtime";
    private static final Set<String> SUPPORTED = Set.of(
            DIAGNOSE,
            RAG_LOOKUP,
            FAILED_OBJECTS_RETRY,
            DIRTY_QUARANTINE_PREVIEW,
            DIRTY_QUARANTINE_APPLY,
            DIRTY_REPLAY,
            SCHEMA_REPAIR_PREVIEW,
            SCHEMA_REPAIR_APPLY,
            CASE_PUBLISH);

    private final RestClient.Builder restClientBuilder;
    private final AgentToolDownstreamHttpSupport httpSupport;
    private final AgentToolOutputReferenceResolver referenceResolver;

    @Override
    public boolean supports(String toolCode) {
        return SUPPORTED.contains(toolCode);
    }

    @Override
    public AgentToolExecutionOutcome execute(AgentToolExecutionContext context) {
        try {
            return switch (context.audit().getToolCode()) {
                case DIAGNOSE -> diagnose(context);
                case RAG_LOOKUP -> lookupRecoveryEvidence(context);
                case FAILED_OBJECTS_RETRY -> retryFailedObjects(context);
                case DIRTY_QUARANTINE_PREVIEW -> previewQuarantine(context);
                case DIRTY_QUARANTINE_APPLY -> applyQuarantine(context);
                case DIRTY_REPLAY -> replayDirtyRecords(context);
                case SCHEMA_REPAIR_PREVIEW -> previewSchemaRepair(context);
                case SCHEMA_REPAIR_APPLY -> applySchemaRepair(context);
                case CASE_PUBLISH -> publishCase(context);
                default -> AgentToolExecutionOutcome.failed(
                        "SYNC_RECOVERY_TOOL_UNSUPPORTED", "不支持的同步恢复工具");
            };
        } catch (PlatformBusinessException exception) {
            return AgentToolExecutionOutcome.failed("SYNC_RECOVERY_VALIDATION_FAILED", exception.getMessage());
        } catch (RestClientException exception) {
            return AgentToolExecutionOutcome.failed(
                    "SYNC_RECOVERY_DOWNSTREAM_ERROR", "同步恢复下游调用失败: " + safeMessage(exception));
        }
    }

    private AgentToolExecutionOutcome diagnose(AgentToolExecutionContext context) {
        Map<String, Object> args = context.audit().getPlanArguments();
        Long taskId = taskId(context, args, "statusRef");
        Long executionId = optionalLong(args.get("executionId"));
        if (executionId == null) {
            executionId = referencedLong(context, args.get("statusRef"), SyncTaskLifecycleToolAdapter.EXECUTION_STATUS,
                    "executionId", false);
        }
        String uri = executionId == null
                ? "/sync-tasks/{taskId}/agent-diagnosis"
                : "/sync-tasks/{taskId}/agent-diagnosis?executionId={executionId}";
        Map<String, Object> data = executionId == null
                ? getData(context, DATA_SYNC, uri, "执行失败诊断", taskId)
                : getData(context, DATA_SYNC, uri, "执行失败诊断", taskId, executionId);
        Map<String, Object> output = new LinkedHashMap<>(data);
        output.put("taskId", taskId);
        output.put("executionId", executionId == null ? optionalLong(data.get("executionId")) : executionId);
        return AgentToolExecutionOutcome.succeeded("已根据真实执行账本完成失败诊断。", output);
    }

    /**
     * Search only with the low-sensitive query generated by the diagnosis service.
     * The model cannot replace this query with raw SQL, row payloads or credentials,
     * while the RAG service still enforces the current tenant/project scope.
     */
    private AgentToolExecutionOutcome lookupRecoveryEvidence(AgentToolExecutionContext context) {
        RetrievalRequest retrieval = retrievalRequest(context.audit().getPlanArguments());
        Object value = referenceResolver.resolve(
                        context,
                        context.audit().getPlanArguments().get("diagnosisRef"),
                        DIAGNOSE,
                        "ragQuery")
                .orElseThrow(() -> new PlatformBusinessException(
                        PlatformErrorCode.BAD_REQUEST, "缺少失败诊断生成的 RAG 检索问题"));
        String question = requiredText(value, "失败诊断生成的 RAG 检索问题为空");
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("tenantId", context.session().getTenantId());
        request.put("projectId", context.session().getProjectId());
        request.put("actorId", context.session().getActorId());
        request.put("workspaceKey", context.session().getWorkspaceKey());
        request.put("sessionId", context.session().getSessionId());
        request.put("traceId", context.traceId());
        request.put("question", question);
        request.put("topK", 5);
        request.put("generateAnswer", true);
        request.put("retrievalMode", retrieval.retrievalMode());
        if (!retrieval.sourceTypes().isEmpty()) {
            request.put("sourceTypes", retrieval.sourceTypes());
        }
        Map<String, Object> response = postRaw(context, AI_RUNTIME, "/agent/rag/query", request);
        if (response == null) {
            return AgentToolExecutionOutcome.failed("SYNC_RECOVERY_RAG_EMPTY", "同步恢复案例检索返回空响应");
        }
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("answer", response.get("answer"));
        output.put("citations", response.getOrDefault("citations", List.of()));
        output.put("retrievalSummary", response.getOrDefault("retrievalSummary", Map.of()));
        output.put("modelSummary", response.getOrDefault("modelSummary", Map.of()));
        output.put("evidenceAudit", verifyEvidenceAudit(response, context));
        return AgentToolExecutionOutcome.succeeded("已检索项目文档、历史恢复案例和 Runbook。", output);
    }

    /**
     * Translate the model's bounded retrieval choice into the storage-level query contract.
     * Scope and question remain server-owned values from the diagnosis reference and current
     * agent session; a model cannot widen either boundary through tool arguments.
     */
    private RetrievalRequest retrievalRequest(Map<String, Object> arguments) {
        String strategy = safeText(arguments.get("retrievalStrategy"), "RAG")
                .toUpperCase(Locale.ROOT);
        return switch (strategy) {
            case "EXACT_SEARCH" -> new RetrievalRequest("lexical", List.of());
            case "RAG", "AUTO" -> new RetrievalRequest("hybrid", List.of());
            case "WIKI" -> new RetrievalRequest("hybrid", List.of("wiki"));
            case "GIT_HISTORY" -> new RetrievalRequest("hybrid", List.of("git_history"));
            case "STRUCTURED_DIAGNOSTIC" -> throw new PlatformBusinessException(
                    PlatformErrorCode.BAD_REQUEST,
                    "STRUCTURED_DIAGNOSTIC 必须调用结构化诊断工具，不能伪装成 RAG 检索");
            default -> throw new PlatformBusinessException(
                    PlatformErrorCode.BAD_REQUEST, "不支持的 Recovery 检索策略: " + strategy);
        };
    }

    /**
     * Validate low-sensitive durable retrieval facts. Full document bodies and model answers are
     * intentionally excluded; the digest binds the evidence set without creating a second store.
     */
    private Map<String, Object> verifyEvidenceAudit(Map<String, Object> response,
                                                    AgentToolExecutionContext context) {
        Object summaryValue = response.get("retrievalSummary");
        if (!(summaryValue instanceof Map<?, ?> rawSummary)) {
            throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                    "RAG 响应缺少 retrievalSummary 证据摘要");
        }
        Map<String, Object> summary = copyMap(rawSummary);
        String evidenceDigest = requiredText(summary.get("evidenceDigest"),
                "RAG 证据摘要缺少 evidenceDigest");
        if (!evidenceDigest.matches("sha256:[0-9a-fA-F]{64}")) {
            throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                    "RAG 证据摘要 evidenceDigest 格式无效");
        }
        int evidenceCount = integerValue(summary.get("evidenceCount"), -1);
        if (evidenceCount <= 0) {
            throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                    "RAG 证据摘要 evidenceCount 必须大于零");
        }
        Object sourceTypes = firstNonNull(summary.get("evidenceSourceTypes"),
                firstNonNull(summary.get("sourceTypes"), summary.get("sourceTypeCounts")));
        if (!hasNonEmptyValue(sourceTypes)) {
            throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                    "RAG 证据摘要缺少 evidenceSourceTypes");
        }
        String retrievedAt = requiredText(summary.get("retrievedAt"), "RAG 证据摘要缺少 retrievedAt");
        try {
            Instant.parse(retrievedAt);
        } catch (DateTimeParseException exception) {
            throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                    "RAG 证据摘要 retrievedAt 不是有效 ISO-8601 时间");
        }
        Object scopeValue = firstNonNull(summary.get("scope"), summary.get("evidenceScope"));
        verifyScope(scopeValue, context);

        Object recordsValue = summary.get("evidenceRecords");
        if (recordsValue != null) {
            if (!(recordsValue instanceof Collection<?> records) || records.size() != evidenceCount) {
                throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                        "RAG 证据摘要 evidenceRecords 与 evidenceCount 不一致");
            }
        }
        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("evidenceDigest", evidenceDigest);
        audit.put("evidenceCount", evidenceCount);
        audit.put("evidenceSourceTypes", sourceTypes);
        audit.put("retrievedAt", retrievedAt);
        audit.put("scope", scopeValue);
        return audit;
    }

    private void verifyScope(Object scopeValue, AgentToolExecutionContext context) {
        if (!(scopeValue instanceof Map<?, ?> rawScope)) {
            throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                    "RAG 证据摘要缺少结构化 scope");
        }
        Map<String, Object> scope = copyMap(rawScope);
        String tenantId = requiredText(firstNonNull(scope.get("tenantId"), scope.get("tenant_id")),
                "RAG 证据 scope 缺少 tenantId");
        String projectId = requiredText(firstNonNull(scope.get("projectId"), scope.get("project_id")),
                "RAG 证据 scope 缺少 projectId");
        if (!tenantId.equals(String.valueOf(context.session().getTenantId()))
                || !projectId.equals(String.valueOf(context.session().getProjectId()))) {
            throw new PlatformBusinessException(PlatformErrorCode.FORBIDDEN,
                    "RAG 证据 scope 与当前 Agent 会话不一致");
        }
        Object workspaceValue = firstNonNull(scope.get("workspaceKey"), scope.get("workspace_key"));
        if (workspaceValue != null
                && !String.valueOf(workspaceValue).equals(context.session().getWorkspaceKey())) {
            throw new PlatformBusinessException(PlatformErrorCode.FORBIDDEN,
                    "RAG 证据 workspace scope 与当前 Agent 会话不一致");
        }
    }

    private boolean hasNonEmptyValue(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof CharSequence text) {
            return !text.toString().isBlank();
        }
        if (value instanceof Collection<?> collection) {
            return !collection.isEmpty();
        }
        if (value instanceof Map<?, ?> map) {
            return !map.isEmpty();
        }
        return true;
    }

    private record RetrievalRequest(String retrievalMode, List<String> sourceTypes) {
    }

    private AgentToolExecutionOutcome retryFailedObjects(AgentToolExecutionContext context) {
        Map<String, Object> args = context.audit().getPlanArguments();
        Long taskId = taskId(context, args, "diagnosisRef");
        Long executionId = executionId(context, args, "diagnosisRef");
        Map<String, Object> request = new LinkedHashMap<>();
        putIfPresent(request, "objectExecutionIds", args.get("objectExecutionIds"));
        putIfPresent(request, "objectOrdinals", args.get("objectOrdinals"));
        request.put("retryAttemptBudget", integerValue(args.get("retryAttemptBudget"), 3));
        request.put("resetAttemptCount", true);
        request.put("reason", "AGENT_DIAGNOSED_FAILED_OBJECT_RETRY");
        Map<String, Object> data = postData(context, DATA_SYNC,
                "/sync-tasks/{taskId}/executions/{executionId}/objects/retry",
                request, "失败对象重试", taskId, executionId);
        return AgentToolExecutionOutcome.succeeded("失败对象已重新进入有界执行队列。", data);
    }

    private AgentToolExecutionOutcome previewQuarantine(AgentToolExecutionContext context) {
        Map<String, Object> args = context.audit().getPlanArguments();
        Long taskId = taskId(context, args, "diagnosisRef");
        Long executionId = executionId(context, args, "diagnosisRef");
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("executionId", executionId);
        Object errorSampleIds = args.get("errorSampleIds");
        putIfPresent(request, "errorSampleIds", errorSampleIds);

        /*
         * The preview endpoint deliberately requires exactly one selector: explicit sample IDs, or
         * "all retryable samples in this execution". Recovery Agent output must not contain dirty-row
         * values and often cannot safely name sample IDs, so an otherwise valid read-only preview would
         * be rejected when both optional fields are absent. In that one bounded, side-effect-free case
         * we select all retryable samples. Data-sync still caps the result at 500 rows and returns only a
         * confirmation digest; the later apply action remains approval-required and binds the exact IDs
         * from that preview. Explicit IDs always win and never silently expand to a broader selection.
         */
        boolean hasExplicitSampleIds = errorSampleIds instanceof List<?> items && !items.isEmpty();
        boolean previewAllRetryable = hasExplicitSampleIds
                ? booleanValue(args.get("quarantineAllRetryableInExecution"), false)
                : true;
        request.put("quarantineAllRetryableInExecution", previewAllRetryable);
        request.put("reason", quarantinePreviewReason(context));
        Map<String, Object> data = postData(context, DATA_SYNC,
                "/sync-tasks/{taskId}/errors/quarantine/preview", request,
                "脏数据隔离预览", taskId);
        return AgentToolExecutionOutcome.succeeded("已生成精确坏行隔离预览，尚未改变执行策略。", data);
    }

    /**
     * Selects the server-owned reason that becomes part of the quarantine confirmation digest.
     *
     * <p>A system recovery preview is created only by the Java-ingested Run whose trusted variables contain
     * {@code interactionOrigin=SYSTEM_RECOVERY}. Model arguments are deliberately ignored. All ordinary Agent
     * previews use the user-confirmed reason, so their later apply request recomputes the same digest while still
     * requiring approval and {@code confirmed=true}.</p>
     */
    private String quarantinePreviewReason(AgentToolExecutionContext context) {
        Object origin = context.variables() == null ? null : context.variables().get("interactionOrigin");
        return "SYSTEM_RECOVERY".equalsIgnoreCase(String.valueOf(origin))
                ? AUTOPILOT_QUARANTINE_REASON
                : USER_QUARANTINE_REASON;
    }

    private AgentToolExecutionOutcome applyQuarantine(AgentToolExecutionContext context) {
        Map<String, Object> preview = referencedMap(context,
                context.audit().getPlanArguments().get("previewRef"), DIRTY_QUARANTINE_PREVIEW, null,
                "缺少脏数据隔离预览");
        Long taskId = requiredLong(preview.get("taskId"), "隔离预览缺少 taskId");
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("executionId", requiredLong(preview.get("executionId"), "隔离预览缺少 executionId"));
        request.put("errorSampleIds", preview.get("selectedSampleIds"));
        request.put("quarantineAllRetryableInExecution", false);
        request.put("reason", USER_QUARANTINE_REASON);
        request.put("confirmationDigest", requiredText(preview.get("confirmationDigest"), "隔离预览缺少确认摘要"));
        request.put("confirmed", true);
        Map<String, Object> data = postData(context, DATA_SYNC,
                "/sync-tasks/{taskId}/errors/quarantine/apply", request,
                "脏数据隔离应用", taskId);
        return AgentToolExecutionOutcome.succeeded("用户确认的坏行已被隔离；源端记录未被删除。", data);
    }

    private AgentToolExecutionOutcome replayDirtyRecords(AgentToolExecutionContext context) {
        Map<String, Object> args = context.audit().getPlanArguments();
        Long taskId = taskId(context, args, "diagnosisRef");
        Long executionId = executionId(context, args, "diagnosisRef");
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("executionId", executionId);
        putIfPresent(request, "errorSampleIds", args.get("errorSampleIds"));
        request.put("replayAllRetryableInExecution",
                booleanValue(args.get("replayAllRetryableInExecution"), false));
        request.put("repairConfirmed", true);
        request.put("repairStrategy", safeText(args.get("repairStrategy"), "AGENT_CONFIRMED_REPAIR_REPLAY"));
        request.put("maxSampleCount", integerValue(args.get("maxSampleCount"), 500));
        request.put("reason", "USER_CONFIRMED_AGENT_REPAIR_REPLAY");
        Map<String, Object> data = postData(context, DATA_SYNC,
                "/sync-tasks/{taskId}/errors/replay", request, "脏数据修复重放", taskId);
        return AgentToolExecutionOutcome.succeeded("已为确认修复的错误样本创建受控重放执行。", data);
    }

    private AgentToolExecutionOutcome previewSchemaRepair(AgentToolExecutionContext context) {
        Map<String, Object> args = context.audit().getPlanArguments();
        Long taskId = referencedLong(context, args.get("diagnosisRef"), DIAGNOSE,
                "taskId", true);
        Long executionId = referencedLong(context, args.get("diagnosisRef"), DIAGNOSE,
                "executionId", true);
        Long datasourceId = optionalLong(args.get("datasourceId"));
        if (datasourceId == null) {
            datasourceId = referencedLong(context, args.get("diagnosisRef"), DIAGNOSE,
                    "targetDatasourceId", true);
        }
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("operation", requiredText(args.get("operation"), "结构修复必须提供白名单 operation"));
        putIfPresent(request, "schemaName", args.get("schemaName"));
        request.put("tableName", requiredText(args.get("tableName"), "结构修复必须提供目标表"));
        request.put("columnName", requiredText(args.get("columnName"), "结构修复必须提供目标字段"));
        putIfPresent(request, "requestedType", args.get("requestedType"));
        putIfPresent(request, "requestedLength", args.get("requestedLength"));
        Map<String, Object> data = postData(context, DATASOURCE,
                "/datasources/{datasourceId}/schema-repair-plans/preview", request,
                "目标结构修复预览", datasourceId);
        Map<String, Object> output = new LinkedHashMap<>(data);
        output.put("taskId", taskId);
        output.put("diagnosisExecutionId", executionId);
        return AgentToolExecutionOutcome.succeeded("已生成目标结构修复预览，尚未执行 DDL。", output);
    }

    private AgentToolExecutionOutcome applySchemaRepair(AgentToolExecutionContext context) {
        Map<String, Object> preview = referencedMap(context,
                context.audit().getPlanArguments().get("previewRef"), SCHEMA_REPAIR_PREVIEW, null,
                "缺少目标结构修复预览");
        Long datasourceId = requiredLong(preview.get("datasourceId"), "结构修复预览缺少 datasourceId");
        Map<String, Object> request = Map.of(
                "planId", requiredLong(preview.get("planId"), "结构修复预览缺少 planId"),
                "confirmationDigest", requiredText(preview.get("confirmationDigest"), "结构修复预览缺少确认摘要"),
                "confirmed", true);
        Map<String, Object> data = postData(context, DATASOURCE,
                "/datasources/{datasourceId}/schema-repair-plans/apply", request,
                "目标结构修复应用", datasourceId);
        Map<String, Object> output = new LinkedHashMap<>(data);
        putIfPresent(output, "taskId", preview.get("taskId"));
        putIfPresent(output, "diagnosisExecutionId", preview.get("diagnosisExecutionId"));
        return AgentToolExecutionOutcome.succeeded("用户确认的白名单目标结构修复已应用。", output);
    }

    private AgentToolExecutionOutcome publishCase(AgentToolExecutionContext context) {
        Map<String, Object> args = context.audit().getPlanArguments();
        Map<String, Object> diagnosis = referencedMap(context, args.get("diagnosisRef"), DIAGNOSE, null,
                "发布案例前缺少失败诊断");
        Map<String, Object> validation = referencedMap(context, args.get("validationRef"),
                SyncTaskLifecycleToolAdapter.EXECUTION_STATUS, null, "发布案例前缺少成功验证");
        Long taskId = requiredLong(diagnosis.get("taskId"), "失败诊断缺少 taskId");
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("diagnosisExecutionId", requiredLong(diagnosis.get("executionId"), "失败诊断缺少 executionId"));
        request.put("validationExecutionId", requiredLong(validation.get("executionId"), "验证结果缺少 executionId"));
        request.put("rootCauseCodes", diagnosis.get("rootCauseCodes"));
        request.put("repairActionCodes", valueOr(args.get("repairActionCodes"), diagnosis.get("recommendedRepairActions")));
        putIfPresent(request, "evidenceReferences", args.get("evidenceReferences"));
        Map<String, Object> data = postData(context, DATA_SYNC,
                "/sync-tasks/{taskId}/agent-recovery-cases", request,
                "恢复案例发布", taskId);
        return AgentToolExecutionOutcome.succeeded("本次修复已验证并沉淀为项目内可检索案例。", data);
    }

    private Long taskId(AgentToolExecutionContext context, Map<String, Object> args, String refName) {
        Long direct = optionalLong(args.get("taskId"));
        return direct != null ? direct : referencedLong(context, args.get(refName),
                defaultReferenceTool(refName), "taskId", true);
    }

    private Long executionId(AgentToolExecutionContext context, Map<String, Object> args, String refName) {
        Long direct = optionalLong(args.get("executionId"));
        return direct != null ? direct : referencedLong(context, args.get(refName),
                defaultReferenceTool(refName), "executionId", true);
    }

    private String defaultReferenceTool(String refName) {
        return "statusRef".equals(refName) ? SyncTaskLifecycleToolAdapter.EXECUTION_STATUS : DIAGNOSE;
    }

    private Long referencedLong(AgentToolExecutionContext context,
                                Object reference,
                                String defaultTool,
                                String path,
                                boolean required) {
        Object value = referenceResolver.resolve(context, reference, defaultTool, path).orElse(null);
        Long result = optionalLong(value);
        if (required && (result == null || result <= 0)) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "缺少有效工具输出引用: " + defaultTool + "." + path);
        }
        return result;
    }

    private Map<String, Object> referencedMap(AgentToolExecutionContext context,
                                              Object reference,
                                              String defaultTool,
                                              String path,
                                              String message) {
        Object value = referenceResolver.resolve(context, reference, defaultTool, path).orElse(null);
        if (!(value instanceof Map<?, ?> raw)) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST, message);
        }
        return copyMap(raw);
    }

    private Map<String, Object> getData(AgentToolExecutionContext context,
                                        String service,
                                        String uri,
                                        String action,
                                        Object... variables) {
        Map<String, Object> response = httpSupport.serviceClient(restClientBuilder, service)
                .get().uri(uri, variables)
                .headers(headers -> httpSupport.applyUserDelegationHeaders(headers, context))
                .retrieve().body(new ParameterizedTypeReference<>() {
                });
        return requireSuccessData(response, action);
    }

    private Map<String, Object> postData(AgentToolExecutionContext context,
                                         String service,
                                         String uri,
                                         Object body,
                                         String action,
                                         Object... variables) {
        Map<String, Object> response = httpSupport.serviceClient(restClientBuilder, service)
                .post().uri(uri, variables).body(body)
                .headers(headers -> httpSupport.applyUserDelegationHeaders(headers, context))
                .retrieve().body(new ParameterizedTypeReference<>() {
                });
        return requireSuccessData(response, action);
    }

    /** Call a product endpoint whose response is not wrapped by Java ApiResponse. */
    private Map<String, Object> postRaw(AgentToolExecutionContext context,
                                        String service,
                                        String uri,
                                        Object body) {
        return httpSupport.serviceClient(restClientBuilder, service)
                .post().uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .headers(headers -> {
                    httpSupport.applyUserDelegationHeaders(headers, context);
                    httpSupport.applyPythonRuntimeInternalServiceToken(headers);
                })
                .body(body)
                .retrieve().body(new ParameterizedTypeReference<>() {
                });
    }

    private Map<String, Object> requireSuccessData(Map<String, Object> response, String action) {
        if (response == null || integerValue(response.get("code"), -1) != 0) {
            throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                    action + "失败: " + safeText(response == null ? null : response.get("message"), "下游未返回具体原因"));
        }
        if (!(response.get("data") instanceof Map<?, ?> data)) {
            throw new PlatformBusinessException(PlatformErrorCode.INTERNAL_ERROR, action + "响应缺少 data");
        }
        return copyMap(data);
    }

    private Map<String, Object> copyMap(Map<?, ?> raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null && !(value instanceof String text && text.isBlank())) {
            target.put(key, value);
        }
    }

    private Object valueOr(Object preferred, Object fallback) {
        return preferred == null ? fallback : preferred;
    }

    private Object firstNonNull(Object first, Object second) {
        return first != null ? first : second;
    }

    private Long optionalLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return value == null ? null : Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Long requiredLong(Object value, String message) {
        Long result = optionalLong(value);
        if (result == null || result <= 0) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST, message);
        }
        return result;
    }

    private Integer integerValue(Object value, int fallback) {
        Long parsed = optionalLong(value);
        return parsed == null ? fallback : parsed.intValue();
    }

    private boolean booleanValue(Object value, boolean fallback) {
        return value == null ? fallback : value instanceof Boolean bool ? bool : Boolean.parseBoolean(String.valueOf(value));
    }

    private String requiredText(Object value, String message) {
        String text = value == null ? null : String.valueOf(value).trim();
        if (text == null || text.isBlank()) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST, message);
        }
        return text;
    }

    private String safeText(Object value, String fallback) {
        String text = value == null ? null : String.valueOf(value).trim();
        return text == null || text.isBlank() ? fallback : text;
    }

    private String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? "未返回具体原因" : message;
    }
}
