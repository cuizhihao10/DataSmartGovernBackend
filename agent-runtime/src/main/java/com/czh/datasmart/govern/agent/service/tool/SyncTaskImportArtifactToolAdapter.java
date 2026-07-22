/**
 * @Author : Cui
 * @Date: 2026/07/22 18:50
 * @Description DataSmart Govern Backend - SyncTaskImportArtifactToolAdapter.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.tool;

import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Executes artifact-reference task import tools against data-sync.
 *
 * <p>Upload is intentionally not an Agent tool: the browser/user uploads the file
 * first and the model receives only {@code artifactRef}. Repair and commit remain
 * approval-required tool definitions, so this adapter can only be reached after
 * Java has validated the user's confirmation and current project permissions.</p>
 */
@Component
@RequiredArgsConstructor
public class SyncTaskImportArtifactToolAdapter implements AgentToolAdapter {

    public static final String DRY_RUN = "sync.task.import.dry-run";
    public static final String RAG_LOOKUP = "sync.task.import.rag.lookup";
    public static final String APPLY_REPAIR = "sync.task.import.repair.apply";
    public static final String COMMIT = "sync.task.import.commit";
    private static final Set<String> SUPPORTED = Set.of(DRY_RUN, RAG_LOOKUP, APPLY_REPAIR, COMMIT);
    private static final String TARGET_SERVICE = "data-sync";
    private static final String AI_RUNTIME_SERVICE = "python-ai-runtime";

    private final RestClient.Builder restClientBuilder;
    private final AgentToolDownstreamHttpSupport httpSupport;
    private final AgentToolOutputReferenceResolver outputReferenceResolver;

    @Override
    public boolean supports(String toolCode) {
        return SUPPORTED.contains(toolCode);
    }

    @Override
    public AgentToolExecutionOutcome execute(AgentToolExecutionContext context) {
        try {
            return switch (context.audit().getToolCode()) {
                case DRY_RUN -> dryRun(context);
                case RAG_LOOKUP -> ragLookup(context);
                case APPLY_REPAIR -> applyRepair(context);
                case COMMIT -> commit(context);
                default -> AgentToolExecutionOutcome.failed("SYNC_IMPORT_TOOL_UNSUPPORTED", "不支持的任务导入工具");
            };
        } catch (PlatformBusinessException exception) {
            return AgentToolExecutionOutcome.failed("SYNC_IMPORT_TOOL_VALIDATION_FAILED", exception.getMessage());
        } catch (RestClientException exception) {
            return AgentToolExecutionOutcome.failed("SYNC_IMPORT_DOWNSTREAM_ERROR",
                    "调用任务导入制品服务失败: " + exception.getMessage());
        }
    }

    private AgentToolExecutionOutcome ragLookup(AgentToolExecutionContext context) {
        Object value = outputReferenceResolver.resolve(
                        context,
                        context.audit().getPlanArguments().get("dryRunRef"),
                        DRY_RUN,
                        "ragQuery")
                .orElseThrow(() -> new PlatformBusinessException(
                        PlatformErrorCode.BAD_REQUEST, "缺少任务导入试运行生成的 RAG 检索问题"));
        String question = requiredText(value, "任务导入 RAG 检索问题为空");
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
        Map<String, Object> response = postToService(
                context, AI_RUNTIME_SERVICE, "/agent/rag/query", request);
        if (response == null) {
            return AgentToolExecutionOutcome.failed("SYNC_IMPORT_RAG_EMPTY", "任务导入案例检索返回空响应");
        }
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("answer", response.get("answer"));
        output.put("citations", response.getOrDefault("citations", List.of()));
        output.put("retrievalSummary", response.getOrDefault("retrievalSummary", Map.of()));
        output.put("modelSummary", response.getOrDefault("modelSummary", Map.of()));
        return AgentToolExecutionOutcome.succeeded("已检索任务导入文档、案例和处理建议。", output);
    }

    private AgentToolExecutionOutcome dryRun(AgentToolExecutionContext context) {
        Map<String, Object> arguments = context.audit().getPlanArguments();
        String artifactRef = resolvedText(
                context, arguments.get("artifactRef"), APPLY_REPAIR, "artifactRef", "缺少任务导入制品引用");
        boolean runImmediately = booleanValue(arguments.get("runImmediately"));
        Map<String, Object> data = requireSuccessData(post(
                context,
                "/sync-task-import-artifacts/{artifactRef}/dry-run?runImmediately={runImmediately}",
                null,
                artifactRef,
                runImmediately), "任务导入试运行");
        return AgentToolExecutionOutcome.succeeded("任务导入制品试运行完成。", data);
    }

    private AgentToolExecutionOutcome applyRepair(AgentToolExecutionContext context) {
        Map<String, Object> arguments = context.audit().getPlanArguments();
        String artifactRef = resolvedText(
                context, arguments.get("artifactRef"), DRY_RUN, "artifact.artifactRef", "缺少任务导入制品引用");
        Object patches = arguments.get("patches");
        if (!(patches instanceof List<?> values) || values.isEmpty()) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST, "修复补丁不能为空");
        }
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("baseVersion", resolvedPositiveInteger(
                context, arguments.get("baseVersion"), DRY_RUN, "artifact.versionNumber", "缺少有效制品版本"));
        request.put("confirmationDigest", resolvedText(
                context, arguments.get("confirmationDigest"), DRY_RUN, "confirmationDigest", "缺少修复确认摘要"));
        request.put("patches", values);
        Map<String, Object> data = requireSuccessData(post(
                context, "/sync-task-import-artifacts/{artifactRef}/repairs", request, artifactRef),
                "任务导入修复制品生成");
        return AgentToolExecutionOutcome.succeeded("已按用户确认的模型建议创建修复制品新版本。", data);
    }

    private AgentToolExecutionOutcome commit(AgentToolExecutionContext context) {
        Map<String, Object> arguments = context.audit().getPlanArguments();
        String artifactRef = resolvedText(
                context, arguments.get("artifactRef"), DRY_RUN, "artifact.artifactRef", "缺少任务导入制品引用");
        Map<String, Object> request = Map.of(
                "confirmationDigest", resolvedText(
                        context, arguments.get("confirmationDigest"), DRY_RUN,
                        "confirmationDigest", "缺少正式导入确认摘要"),
                "runImmediately", booleanValue(arguments.get("runImmediately"))
        );
        Map<String, Object> data = requireSuccessData(post(
                context, "/sync-task-import-artifacts/{artifactRef}/commit", request, artifactRef),
                "任务导入正式提交");
        return AgentToolExecutionOutcome.succeeded("任务文件已正式导入。", data);
    }

    private Map<String, Object> post(AgentToolExecutionContext context,
                                     String uri,
                                     Object body,
                                     Object... variables) {
        RestClient.RequestBodySpec spec = restClientBuilder
                .baseUrl(httpSupport.baseUrl(TARGET_SERVICE))
                .build()
                .post()
                .uri(uri, variables)
                .headers(headers -> httpSupport.applyUserDelegationHeaders(headers, context));
        RestClient.ResponseSpec response = body == null ? spec.retrieve() : spec.body(body).retrieve();
        return response.body(new ParameterizedTypeReference<>() {
        });
    }

    private Map<String, Object> postToService(AgentToolExecutionContext context,
                                              String service,
                                              String uri,
                                              Object body) {
        return restClientBuilder
                .baseUrl(httpSupport.baseUrl(service))
                .build()
                .post()
                .uri(uri)
                .headers(headers -> httpSupport.applyUserDelegationHeaders(headers, context))
                .body(body)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {
                });
    }

    private Map<String, Object> requireSuccessData(Map<String, Object> response, String action) {
        if (response == null || integerValue(response.get("code"), -1) != 0) {
            String message = response == null ? "下游返回空响应" : text(response.get("message"));
            throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                    action + "失败: " + (message == null ? "未返回具体原因" : message));
        }
        if (!(response.get("data") instanceof Map<?, ?> raw)) {
            throw new PlatformBusinessException(PlatformErrorCode.INTERNAL_ERROR, action + "响应缺少 data");
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        raw.forEach((key, value) -> copy.put(String.valueOf(key), value));
        return copy;
    }

    private String requiredText(Object value, String message) {
        String normalized = text(value);
        if (normalized == null) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST, message);
        }
        return normalized;
    }

    private int requiredPositiveInteger(Object value, String message) {
        Integer parsed = integerValue(value, null);
        if (parsed == null || parsed <= 0) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST, message);
        }
        return parsed;
    }

    /**
     * Resolve a server-created output reference while still accepting the literal
     * artifact reference supplied by the browser on the first dry-run.
     */
    private String resolvedText(AgentToolExecutionContext context,
                                Object candidate,
                                String defaultTool,
                                String defaultPath,
                                String message) {
        Object value = candidate instanceof Map<?, ?>
                ? outputReferenceResolver.resolve(context, candidate, defaultTool, defaultPath).orElse(null)
                : candidate;
        return requiredText(value, message);
    }

    /** Resolve a derived numeric value such as the immutable artifact version. */
    private int resolvedPositiveInteger(AgentToolExecutionContext context,
                                        Object candidate,
                                        String defaultTool,
                                        String defaultPath,
                                        String message) {
        Object value = candidate instanceof Map<?, ?>
                ? outputReferenceResolver.resolve(context, candidate, defaultTool, defaultPath).orElse(null)
                : candidate;
        return requiredPositiveInteger(value, message);
    }

    private Integer integerValue(Object value, Integer fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? fallback : Integer.valueOf(String.valueOf(value));
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private boolean booleanValue(Object value) {
        return value instanceof Boolean bool ? bool : Boolean.parseBoolean(String.valueOf(value));
    }

    private String text(Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        return String.valueOf(value).trim();
    }
}
