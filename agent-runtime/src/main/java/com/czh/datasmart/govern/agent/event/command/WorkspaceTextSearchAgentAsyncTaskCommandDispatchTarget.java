/**
 * @Author : Cui
 * @Date: 2026/08/11 23:00
 * @Description DataSmart Govern Backend - WorkspaceTextSearchAgentAsyncTaskCommandDispatchTarget.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.event.command;

import com.czh.datasmart.govern.agent.config.AgentWorkspaceTextSearchWorkerProperties;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolPlanArgumentsPayloadView;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionCommandWorkerReceiptRequest;
import com.czh.datasmart.govern.agent.model.AgentToolExecutionState;
import com.czh.datasmart.govern.agent.service.AgentToolPlanArgumentsPayloadService;
import com.czh.datasmart.govern.agent.service.runtime.AgentToolActionCommandWorkerReceiptService;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Durable outbox dispatch target for the model-visible {@code workspace.text.search} tool.
 *
 * <p>工具码中的 workspace 指 Agent 文件执行沙箱，而不是产品已经移除的业务 Workspace 层级。该类不会
 * 读取或生成 workspaceId/workspaceKey；业务隔离只使用 tenantId、applicationId、projectId，文件系统
 * 边界则由运维配置的只读 repositoryRoot 独立控制。</p>
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "datasmart.agent-runtime.workspace-text-search-worker",
        name = "enabled",
        havingValue = "true"
)
public class WorkspaceTextSearchAgentAsyncTaskCommandDispatchTarget
        implements AgentAsyncTaskCommandDispatchTarget {

    public static final String TOOL_CODE = "workspace.text.search";
    public static final String CONSUMER_SERVICE = "python-ai-runtime-text-search";
    private static final String PAYLOAD_POLICY_MARKER = "WORKSPACE_TEXT_SEARCH_SUMMARY_ONLY";

    private final AgentWorkspaceTextSearchWorkerProperties properties;
    private final RestClient.Builder restClientBuilder;
    private final AgentToolActionCommandWorkerReceiptService receiptService;
    private final AgentToolPlanArgumentsPayloadService payloadService;
    private final ObjectMapper objectMapper;

    @Override
    public String targetName() {
        return "python-ai-runtime:workspace-text-search-worker";
    }

    /**
     * Select only the exact registered tool code.
     *
     * @param record durable command waiting for a dispatch target
     * @return true only for {@code workspace.text.search}; route hints alone never grant execution authority
     */
    @Override
    public boolean supports(AgentAsyncTaskCommandOutboxRecord record) {
        return record != null && TOOL_CODE.equalsIgnoreCase(trim(record.toolCode()));
    }

    /**
     * Deliver one search command and persist its low-sensitive receipt.
     *
     * <p>Any configuration, HTTP, response-shape or payload-policy problem throws a low-sensitive exception. The
     * outer dispatcher then keeps the outbox row retryable or dead-letters it according to the existing bounded
     * policy. The method never logs or embeds the query, configured root, response body or service token.</p>
     *
     * @param record trusted durable outbox record produced after tool governance
     */
    @Override
    public void dispatch(AgentAsyncTaskCommandOutboxRecord record) {
        if (!supports(record)) {
            return;
        }
        WorkerResponse response = callWorker(toWorkerRequest(record));
        if (!Boolean.TRUE.equals(response.accepted())) {
            throw new IllegalStateException("Workspace text search worker did not accept the command");
        }
        if (response.payloadPolicy() == null || !response.payloadPolicy().contains(PAYLOAD_POLICY_MARKER)) {
            throw new IllegalStateException("Workspace text search worker response is missing its payload policy");
        }
        receiveReceipt(record, response.javaReceiptPayload());
    }

    /**
     * Build the Java to Python worker request.
     *
     * <p>The literal query and optional relative scope come from the model-selected tool arguments. The real root and
     * workspace reference are rebuilt from server configuration and durable scope facts. A model-supplied root is
     * therefore ignored even if it appears in the original JSON.</p>
     *
     * @param record durable outbox record containing the governed tool arguments
     * @return request split into short-lived arguments and trusted control facts
     */
    WorkerRequest toWorkerRequest(AgentAsyncTaskCommandOutboxRecord record) {
        Map<String, Object> payload = parsePayload(record.payloadJson());
        Map<String, Object> sourceArguments = resolveArguments(record, payload);
        String query = firstText(sourceArguments, "query");
        if (!hasText(query)) {
            query = firstText(payload, "query");
        }
        if (!hasText(query)) {
            throw new IllegalArgumentException("Workspace text search command is missing a literal query");
        }
        String repositoryRoot = requireText(properties.getRepositoryRoot(), "repositoryRoot");
        String applicationId = requireText(firstText(payload, "applicationId"), "applicationId");

        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("query", query);
        putIfPresent(arguments, "relativePathPrefix",
                firstValue(sourceArguments, "relativePathPrefix", "relative_path_prefix"));
        putIfPresent(arguments, "caseSensitive", firstValue(sourceArguments, "caseSensitive", "case_sensitive"));
        putIfPresent(arguments, "searchMode", firstValue(sourceArguments, "searchMode", "search_mode"));
        putIfPresent(arguments, "maxResults", firstValue(sourceArguments, "maxResults", "max_results"));

        Map<String, Object> controlFacts = new LinkedHashMap<>();
        putText(controlFacts, "commandId", record.commandId());
        putText(controlFacts, "sessionId", record.sessionId());
        putText(controlFacts, "runId", record.runId());
        putText(controlFacts, "tenantId", record.tenantId());
        putText(controlFacts, "applicationId", applicationId);
        putText(controlFacts, "projectId", record.projectId());
        putText(controlFacts, "actorId", record.actorId());
        putText(controlFacts, "repositoryRoot", repositoryRoot);
        putText(controlFacts, "repositoryReference", repositoryReference(record, applicationId));
        putText(controlFacts, "traceId", record.traceId());
        putText(controlFacts, "idempotencyKey", record.idempotencyKey());
        putText(controlFacts, "toolCode", TOOL_CODE);
        return new WorkerRequest(arguments, controlFacts, false);
    }

    /**
     * 在真正调用 Python 之前临时解析工具参数。
     *
     * <p>正式 outbox 只保存 {@code agent-tool-audit://.../plan-arguments} 引用和参数名，不保存查询正文。
     * 因此 dispatcher 必须回到 Java 审计快照读取参数，并重新核对 session、run、audit、tool、tenant、
     * project 和 actor。解析结果只存活于本次 HTTP 调用，不写回 outbox、timeline 或日志。</p>
     *
     * <p>历史测试和灰度记录可能仍内联 {@code arguments/toolArguments}，这里暂时兼容；只要字段出现就必须
     * 是 JSON object。新 producer 应始终走 payloadReference 分支。</p>
     *
     * @param record 当前 dispatcher 领取的 durable command
     * @param payload 已解析的低敏 command envelope
     * @return 本次调用可使用的参数防御性副本
     */
    private Map<String, Object> resolveArguments(
            AgentAsyncTaskCommandOutboxRecord record,
            Map<String, Object> payload) {
        if (payload.containsKey("arguments") || payload.containsKey("toolArguments")) {
            return new LinkedHashMap<>(objectMap(payload, "arguments", "toolArguments"));
        }
        AgentToolPlanArgumentsPayloadView resolved = payloadService.getPlanArgumentsPayload(
                record.sessionId(),
                record.runId(),
                record.auditId()
        );
        if (resolved == null) {
            throw new IllegalStateException("Workspace text search argument snapshot is missing");
        }
        requireEqual("payloadReference", record.payloadReference(), resolved.payloadReference());
        requireEqual("sessionId", record.sessionId(), resolved.sessionId());
        requireEqual("runId", record.runId(), resolved.runId());
        requireEqual("auditId", record.auditId(), resolved.auditId());
        requireEqual("toolCode", TOOL_CODE, resolved.toolCode());
        requireEqual("tenantId", record.tenantId(), resolved.tenantId());
        requireEqual("projectId", record.projectId(), resolved.projectId());
        requireEqual("actorId", record.actorId(), resolved.actorId());
        if (!AgentToolExecutionState.PLANNED.name().equals(resolved.state())) {
            throw new IllegalStateException(
                    "Workspace text search argument snapshot is no longer in PLANNED state"
            );
        }
        return new LinkedHashMap<>(resolved.planArguments());
    }

    /**
     * 比较 durable command 与参数快照的单个控制面字段。
     *
     * @param field 仅用于低敏错误定位的字段名
     * @param expected outbox 中的可信值
     * @param actual 参数快照中的值
     */
    private void requireEqual(String field, Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) {
            throw new IllegalStateException(
                    "Workspace text search command does not match argument snapshot field=" + field
            );
        }
    }

    /**
     * Perform the internal HTTP call with bounded connect/read timeouts.
     *
     * @param request sanitized worker request
     * @return parsed low-sensitive response
     */
    private WorkerResponse callWorker(WorkerRequest request) {
        URI endpoint = endpointUri();
        String serviceAccountToken = requireText(
                properties.getServiceAccountToken(),
                "serviceAccountToken"
        );
        try {
            ResponseEntity<WorkerResponse> response = restClientBuilder
                    .requestFactory(requestFactory())
                    .build()
                    .post()
                    .uri(endpoint)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .headers(headers -> applyHeaders(headers, request, serviceAccountToken))
                    .body(request)
                    .retrieve()
                    .toEntity(WorkerResponse.class);
            if (response.getBody() == null) {
                throw new IllegalStateException("Workspace text search worker returned an empty response");
            }
            return response.getBody();
        } catch (RestClientResponseException exception) {
            throw new IllegalStateException(
                    "Workspace text search worker returned a non-success status="
                            + exception.getStatusCode().value());
        } catch (RestClientException exception) {
            throw new IllegalStateException("Workspace text search worker call failed");
        }
    }

    /**
     * Convert Python's allow-listed receipt map into the shared Java receipt DTO.
     *
     * @param record durable scope used to fill missing identifiers
     * @param payload Python allow-listed receipt fields
     */
    private void receiveReceipt(AgentAsyncTaskCommandOutboxRecord record, Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            throw new IllegalStateException("Workspace text search worker response is missing javaReceiptPayload");
        }
        AgentToolActionCommandWorkerReceiptRequest request = new AgentToolActionCommandWorkerReceiptRequest(
                textOr(payload, "commandId", record.commandId()),
                longValue(payload.get("taskId")),
                longValue(payload.get("taskRunId")),
                textOr(payload, "executorId", "python-text-search-worker"),
                longOr(payload, "tenantId", record.tenantId()),
                longOr(payload, "projectId", record.projectId()),
                longOr(payload, "actorId", parseLong(record.actorId())),
                text(payload.get("taskStatus")),
                text(payload.get("outcome")),
                bool(payload.get("preCheckPassed")),
                bool(payload.get("sideEffectStarted")),
                bool(payload.get("sideEffectExecuted")),
                bool(payload.get("workerLeaseRequired")),
                null,
                null,
                null,
                text(payload.get("commandSafetyDecision")),
                text(payload.get("commandSafetyPolicyVersion")),
                stringList(payload.get("commandSafetyIssueCodes")),
                integerValue(payload.get("normalizedTimeoutSeconds")),
                integerValue(payload.get("normalizedOutputByteLimitBytes")),
                null,
                null,
                bool(payload.get("artifactAvailable")),
                text(payload.get("errorCode")),
                textOr(payload, "auditId", record.auditId()),
                textOr(payload, "toolCode", TOOL_CODE),
                textOr(payload, "targetService", CONSUMER_SERVICE),
                textOr(payload, "workerReceiptMode", "READ_ONLY_TEXT_SEARCH_SUMMARY"),
                text(payload.get("message")),
                stringList(payload.get("recommendedActions")),
                textOr(payload, "idempotencyKey", record.idempotencyKey())
        );
        receiptService.receive(record.sessionId(), record.runId(), record.traceId(), request);
    }

    /**
     * 构造 Python 文件边界可以校验的低敏仓库引用。
     *
     * <p>引用只包含租户、应用和项目编号，不包含主机路径，也不再拼接已经退出产品模型的 workspaceId。
     * repositoryRoot 才是真实路径，而且只存在于短生命周期内部 HTTP 请求的 controlFacts 中。</p>
     *
     * @param record durable outbox 中可信的租户和项目范围
     * @param applicationId 从命令信封读取并强制存在的应用范围
     * @return 不含真实路径的稳定仓库引用
     */
    private String repositoryReference(AgentAsyncTaskCommandOutboxRecord record, String applicationId) {
        String tenant = record.tenantId() == null ? "unknown" : String.valueOf(record.tenantId());
        String project = record.projectId() == null ? "unknown" : String.valueOf(record.projectId());
        return "agent-repository:tenant-" + tenant
                + "/application-" + applicationId
                + "/project-" + project;
    }

    /** Parse the original outbox JSON without exposing parse details or payload text. */
    private Map<String, Object> parsePayload(String payloadJson) {
        if (!hasText(payloadJson)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(payloadJson, new TypeReference<LinkedHashMap<String, Object>>() { });
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Workspace text search command payload is not valid JSON");
        }
    }

    /** Read a nested JSON object while rejecting scalar or array substitutions. */
    private Map<String, Object> objectMap(Map<String, Object> payload, String... keys) {
        for (String key : keys) {
            Object value = payload.get(key);
            if (value instanceof Map<?, ?> map) {
                Map<String, Object> copy = new LinkedHashMap<>();
                map.forEach((entryKey, entryValue) -> copy.put(String.valueOf(entryKey), entryValue));
                return copy;
            }
            if (value != null) {
                throw new IllegalArgumentException("Workspace text search arguments must be a JSON object");
            }
        }
        return Map.of();
    }

    /** Construct the internal endpoint and fail before dispatch when configuration is incomplete. */
    private URI endpointUri() {
        String baseUrl = requireText(properties.getBaseUrl(), "baseUrl");
        String runPath = requireText(properties.getRunPath(), "runPath");
        String normalizedBase = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String normalizedPath = runPath.startsWith("/") ? runPath : "/" + runPath;
        try {
            return URI.create(normalizedBase + normalizedPath);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Workspace text search worker endpoint is invalid");
        }
    }

    /** Apply short timeouts so a failed worker returns control to the durable outbox. */
    private SimpleClientHttpRequestFactory requestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(Math.max(1L, properties.getConnectTimeoutMs())));
        factory.setReadTimeout(Duration.ofMillis(Math.max(1L, properties.getReadTimeoutMs())));
        return factory;
    }

    /** Add trace and optional service authentication without copying credentials into the request body. */
    private void applyHeaders(
            HttpHeaders headers,
            WorkerRequest request,
            String serviceAccountToken) {
        headers.set(PlatformContextHeaders.SOURCE_SERVICE, "agent-runtime");
        String traceId = text(request.controlFacts().get("traceId"));
        if (traceId != null) {
            headers.set(PlatformContextHeaders.TRACE_ID, traceId);
        }
        headers.setBearerAuth(serviceAccountToken);
    }

    private Object firstValue(Map<String, Object> payload, String... keys) {
        for (String key : keys) {
            if (payload.containsKey(key)) {
                return payload.get(key);
            }
        }
        return null;
    }

    private String firstText(Map<String, Object> payload, String... keys) {
        return text(firstValue(payload, keys));
    }

    private void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    private void putText(Map<String, Object> target, String key, Object value) {
        String normalized = text(value);
        if (normalized != null) {
            target.put(key, normalized);
        }
    }

    private String textOr(Map<String, Object> payload, String key, String fallback) {
        String value = text(payload.get(key));
        return value == null ? fallback : value;
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof Iterable<?> iterable)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object item : iterable) {
            String normalized = text(item);
            if (normalized != null) {
                result.add(normalized);
            }
        }
        return List.copyOf(result);
    }

    private Boolean bool(Object value) {
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        String normalized = text(value);
        return normalized == null ? null : Boolean.parseBoolean(normalized);
    }

    private Long longOr(Map<String, Object> payload, String key, Long fallback) {
        Long value = longValue(payload.get(key));
        return value == null ? fallback : value;
    }

    private Long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return parseLong(text(value));
    }

    private Long parseLong(String value) {
        try {
            return value == null ? null : Long.parseLong(value);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private Integer integerValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            String normalized = text(value);
            return normalized == null ? null : Integer.parseInt(normalized);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private String requireText(String value, String fieldName) {
        String normalized = trim(value);
        if (normalized == null) {
            throw new IllegalStateException("Workspace text search worker configuration is missing " + fieldName);
        }
        return normalized;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String text(Object value) {
        return value == null ? null : trim(String.valueOf(value));
    }

    private String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** Short-lived HTTP request; only {@code arguments.query} may contain model-selected text. */
    public record WorkerRequest(
            Map<String, Object> arguments,
            Map<String, Object> controlFacts,
            Boolean postToJava
    ) {
        public WorkerRequest {
            arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
            controlFacts = controlFacts == null ? Map.of() : Map.copyOf(controlFacts);
        }
    }

    /**
     * Python worker 返回的低敏响应。
     *
     * <p>Java 只读取执行是否被接受、payload policy 和 Java 回执白名单。Python 可以在不破坏旧 Java 客户端的
     * 前提下增加 {@code workerRunnerSchemaVersion} 等诊断字段，因此这里显式忽略未知字段，而不是要求两端
     * 每次增加低敏元数据都同步发版。</p>
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record WorkerResponse(
            String schemaVersion,
            Boolean accepted,
            String toolCode,
            Map<String, Object> workerResult,
            Map<String, Object> receipt,
            Map<String, Object> javaReceiptPayload,
            String payloadPolicy
    ) {
        public WorkerResponse {
            workerResult = workerResult == null ? Map.of() : Map.copyOf(workerResult);
            receipt = receipt == null ? Map.of() : Map.copyOf(receipt);
            javaReceiptPayload = javaReceiptPayload == null ? Map.of() : Map.copyOf(javaReceiptPayload);
        }
    }
}
