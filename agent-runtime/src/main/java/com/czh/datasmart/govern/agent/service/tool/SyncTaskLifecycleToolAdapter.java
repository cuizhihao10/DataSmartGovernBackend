/**
 * @Author : Cui
 * @Date: 2026/07/10 00:00
 * @Description DataSmart Govern Backend - SyncTaskLifecycleToolAdapter.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.tool;

import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 同步任务草稿、预检查、发布、运行和状态查询工具适配器。
 *
 * <p>所有节点都复用 data-sync 已有创建向导、预检查和任务状态机。Agent 不直接写 data_sync 表，也不自行
 * 组装 worker 任务，因此人工页面创建任务和自然语言创建任务始终遵守同一套业务规则。</p>
 */
@Component
@RequiredArgsConstructor
public class SyncTaskLifecycleToolAdapter implements AgentToolAdapter {

    public static final String DRAFT_SAVE = "sync.task.draft.save";
    public static final String PRECHECK = "sync.task.precheck";
    public static final String PUBLISH = "sync.task.publish";
    public static final String RUN = "sync.task.run";
    public static final String EXECUTION_STATUS = "sync.execution.status";

    private static final String TARGET_SERVICE = "data-sync";
    private static final long EXECUTION_STATUS_TIMEOUT_MILLIS = 60_000L;
    private static final long EXECUTION_STATUS_POLL_INTERVAL_MILLIS = 500L;
    private static final Set<String> TERMINAL_EXECUTION_STATES = Set.of(
            "SUCCEEDED", "PARTIALLY_SUCCEEDED", "FAILED", "CANCELLED", "MANUALLY_TERMINATED", "SKIPPED");
    private static final String SOURCE_METADATA_TOOL = DatasourceAccessToolAdapter.SOURCE_METADATA;
    private static final String TARGET_METADATA_TOOL = DatasourceAccessToolAdapter.TARGET_METADATA;
    private static final Set<String> SUPPORTED = Set.of(DRAFT_SAVE, PRECHECK, PUBLISH, RUN, EXECUTION_STATUS);

    private final RestClient.Builder restClientBuilder;
    private final AgentToolDownstreamHttpSupport httpSupport;
    private final AgentToolOutputReferenceResolver outputReferenceResolver;
    private final ObjectMapper objectMapper;

    @Override
    public boolean supports(String toolCode) {
        return SUPPORTED.contains(toolCode);
    }

    @Override
    public AgentToolExecutionOutcome execute(AgentToolExecutionContext context) {
        try {
            return switch (context.audit().getToolCode()) {
                case DRAFT_SAVE -> saveDraft(context);
                case PRECHECK -> precheck(context);
                case PUBLISH -> publish(context);
                case RUN -> run(context);
                case EXECUTION_STATUS -> executionStatus(context);
                default -> AgentToolExecutionOutcome.failed("SYNC_TOOL_UNSUPPORTED", "不支持的同步任务工具节点");
            };
        } catch (PlatformBusinessException exception) {
            return AgentToolExecutionOutcome.failed("SYNC_TOOL_VALIDATION_FAILED", exception.getMessage());
        } catch (RestClientException exception) {
            return AgentToolExecutionOutcome.failed("SYNC_DOWNSTREAM_ERROR",
                    "调用 data-sync 失败: " + exception.getMessage());
        }
    }

    private AgentToolExecutionOutcome saveDraft(AgentToolExecutionContext context) {
        Map<String, Object> arguments = context.audit().getPlanArguments();
        Long sourceDatasourceId = requiredPositiveLong(arguments.get("sourceDatasourceId"), "缺少有效的源端数据源 ID");
        Long targetDatasourceId = requiredPositiveLong(arguments.get("targetDatasourceId"), "缺少有效的目标端数据源 ID");
        Map<String, Object> sourceMetadata = referencedMap(context, arguments.get("sourceMetadataRef"),
                SOURCE_METADATA_TOOL, "metadata", "缺少源端元数据结果");
        Map<String, Object> targetMetadata = referencedMap(context, arguments.get("targetMetadataRef"),
                TARGET_METADATA_TOOL, "metadata", "缺少目标端元数据结果");
        List<ObjectMapping> mappings = resolveObjectMappings(arguments.get("objectMappings"));
        if (mappings.isEmpty()) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST, "同步任务至少需要一条对象映射");
        }

        String objectMappingConfig = serialize(buildObjectMappingConfig(mappings));
        String fieldMappingConfig = serialize(buildFieldMappingConfig(mappings, sourceMetadata, targetMetadata));
        ObjectMapping first = mappings.getFirst();
        String sourceConnectorType = safeText(sourceMetadata.get("datasourceType"),
                safeText(arguments.get("sourceConnectorType"), "MYSQL"));
        String targetConnectorType = safeText(targetMetadata.get("datasourceType"),
                safeText(arguments.get("targetConnectorType"), "POSTGRESQL"));

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("stepCode", "PRECHECK");
        request.put("taskName", safeText(arguments.get("taskName"), "Agent 创建的全量同步任务"));
        request.put("taskDescription", safeText(arguments.get("taskDescription"),
                "由智能助手根据用户确认的计划创建，执行仍由 data-sync 状态机和 worker 负责。"));
        request.put("groupCode", safeText(arguments.get("groupCode"), "DEFAULT"));
        request.put("groupName", safeText(arguments.get("groupName"), "默认分组"));
        request.put("priority", safeText(arguments.get("priority"), "MEDIUM"));
        request.put("ownerId", httpSupport.numericActorId(context));
        request.put("sourceDatasourceId", sourceDatasourceId);
        request.put("targetDatasourceId", targetDatasourceId);
        request.put("sourceSchemaName", first.sourceSchemaName());
        request.put("sourceObjectName", first.sourceObjectName());
        request.put("targetSchemaName", first.targetSchemaName());
        request.put("targetObjectName", first.targetObjectName());
        request.put("sourceConnectorType", sourceConnectorType);
        request.put("targetConnectorType", targetConnectorType);
        request.put("syncMode", safeText(arguments.get("syncMode"), "FULL"));
        request.put("syncScopeType", mappings.size() == 1 ? "SINGLE_OBJECT" : "OBJECT_LIST");
        request.put("writeStrategy", normalizeWriteStrategy(arguments.get("writeStrategy")));
        request.put("fieldMappingConfig", fieldMappingConfig);
        request.put("objectMappingConfig", objectMappingConfig);

        Map<String, Object> response = post(context, "/sync-tasks/create-wizard/drafts", request);
        Map<String, Object> data = requireSuccessData(response, "同步任务草稿保存");
        Long taskId = longValue(data.get("taskId"));
        Long templateId = longValue(data.get("templateId"));
        if (taskId == null || templateId == null) {
            return AgentToolExecutionOutcome.failed("SYNC_DRAFT_MISSING_IDS",
                    "同步任务草稿已返回成功响应，但缺少 taskId/templateId");
        }
        return AgentToolExecutionOutcome.succeeded("同步任务草稿与字段映射已保存。", Map.of(
                "taskId", taskId,
                "templateId", templateId,
                "state", safeText(data.get("currentState"), "DRAFT"),
                "objectCount", mappings.size(),
                "sourceDatasourceId", sourceDatasourceId,
                "targetDatasourceId", targetDatasourceId
        ));
    }

    private AgentToolExecutionOutcome precheck(AgentToolExecutionContext context) {
        Long templateId = draftReference(context, "templateId");
        Map<String, Object> response = post(context, "/sync-templates/{id}/precheck", null, templateId);
        Map<String, Object> data = requireSuccessData(response, "同步任务预检查");
        boolean canStartExecution = booleanValue(data.get("canStartExecution"), false);
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("templateId", templateId);
        output.put("precheckStatus", safeText(data.get("precheckStatus"), "UNKNOWN"));
        output.put("canStartExecution", canStartExecution);
        output.put("issueCodes", listValue(data.get("issueCodes")));
        output.put("recommendedActions", listValue(data.get("recommendedActions")));
        output.put("connectorCompatibilitySupported", booleanValue(data.get("connectorCompatibilitySupported"), false));
        output.put("scopeContractValid", booleanValue(data.get("scopeContractValid"), false));
        output.put("fieldMappingDeclared", booleanValue(data.get("fieldMappingDeclared"), false));
        if (!canStartExecution) {
            return AgentToolExecutionOutcome.failed(
                    "SYNC_PRECHECK_BLOCKED",
                    "同步任务预检查未通过，请根据问题项修正配置后重试。",
                    output
            );
        }
        return AgentToolExecutionOutcome.succeeded("同步任务预检查通过。", output);
    }

    private AgentToolExecutionOutcome publish(AgentToolExecutionContext context) {
        Long taskId = draftReference(context, "taskId");
        boolean precheckPassed = referencedBoolean(
                context,
                context.audit().getPlanArguments().get("precheckRef"),
                PRECHECK,
                "canStartExecution",
                "缺少同步任务预检查通过事实"
        );
        if (!precheckPassed) {
            return AgentToolExecutionOutcome.failed(
                    "SYNC_PRECHECK_NOT_PASSED",
                    "同步任务预检查尚未通过，Agent 不会发布任务。"
            );
        }
        Map<String, Object> request = Map.of(
                "enableSchedule", false,
                "reason", "用户已在智能助手中确认本次 Agent 同步计划"
        );
        Map<String, Object> data = requireSuccessData(
                post(context, "/sync-tasks/{id}/publish", request, taskId),
                "同步任务发布"
        );
        return AgentToolExecutionOutcome.succeeded("同步任务已发布。", Map.of(
                "taskId", taskId,
                "state", safeText(data.get("state"), "CONFIGURED"),
                "message", safeText(data.get("message"), "同步任务已发布")
        ));
    }

    private AgentToolExecutionOutcome run(AgentToolExecutionContext context) {
        Long taskId = referencedLong(context, context.audit().getPlanArguments().get("taskRef"),
                PUBLISH, "taskId", "缺少已发布同步任务结果");
        Map<String, Object> data = requireSuccessData(
                post(context, "/sync-tasks/{id}/run", null, taskId),
                "同步任务运行提交"
        );
        return AgentToolExecutionOutcome.succeeded("同步任务已提交 worker 队列。", Map.of(
                "taskId", taskId,
                "state", safeText(data.get("state"), "QUEUED"),
                "message", safeText(data.get("message"), "同步任务已提交运行")
        ));
    }

    private AgentToolExecutionOutcome executionStatus(AgentToolExecutionContext context) {
        Map<String, Object> arguments = context.audit().getPlanArguments();
        Long taskId = longValue(arguments.get("taskId"));
        if (taskId == null || taskId <= 0) {
            taskId = referencedLong(context, arguments.get("taskRef"),
                    RUN, "taskId", "缺少同步任务运行结果或待验证任务 ID");
        }
        long deadline = System.currentTimeMillis() + EXECUTION_STATUS_TIMEOUT_MILLIS;
        int pollCount = 0;
        Map<String, Object> latest = Map.of();
        String executionState = "QUEUED";
        do {
            pollCount++;
            Map<String, Object> response = get(context, "/sync-tasks/{id}/executions?current=1&size=1", taskId);
            Map<String, Object> page = requireSuccessData(response, "同步执行状态查询");
            List<?> records = page.get("records") instanceof List<?> values ? values : List.of();
            latest = records.isEmpty() || !(records.getFirst() instanceof Map<?, ?> raw)
                    ? Map.of()
                    : copyMap(raw);
            executionState = safeText(latest.get("executionState"), "QUEUED").toUpperCase(Locale.ROOT);
            if (TERMINAL_EXECUTION_STATES.contains(executionState)) {
                break;
            }
            try {
                Thread.sleep(EXECUTION_STATUS_POLL_INTERVAL_MILLIS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                break;
            }
        } while (System.currentTimeMillis() < deadline);

        boolean terminal = TERMINAL_EXECUTION_STATES.contains(executionState);
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("taskId", taskId);
        output.put("executionFound", !latest.isEmpty());
        output.put("executionId", longValue(latest.get("id")));
        output.put("executionState", executionState);
        output.put("recordsRead", defaultLong(latest.get("recordsRead")));
        output.put("recordsWritten", defaultLong(latest.get("recordsWritten")));
        output.put("failedRecordCount", defaultLong(latest.get("failedRecordCount")));
        output.put("terminal", terminal);
        output.put("pollCount", pollCount);
        output.put("trackingTimedOut", !terminal);
        if ("FAILED".equals(executionState) || "CANCELLED".equals(executionState)
                || "MANUALLY_TERMINATED".equals(executionState) || "SKIPPED".equals(executionState)) {
            return AgentToolExecutionOutcome.failed(
                    "SYNC_EXECUTION_" + executionState,
                    "同步执行已进入终态 " + executionState + "，请查看运行日志和对象级账本。",
                    output);
        }
        if (!terminal) {
            return AgentToolExecutionOutcome.succeeded(
                    "同步执行在等待窗口内尚未结束，已返回可继续追踪的异步状态。", output);
        }
        return AgentToolExecutionOutcome.succeeded("同步执行已到达终态 " + executionState + "。", output);
    }

    private Map<String, Object> buildObjectMappingConfig(List<ObjectMapping> mappings) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int index = 0; index < mappings.size(); index++) {
            ObjectMapping mapping = mappings.get(index);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("ordinal", index + 1);
            row.put("objectKey", mapping.objectKey());
            row.put("sourceSchemaName", mapping.sourceSchemaName());
            row.put("sourceObjectName", mapping.sourceObjectName());
            row.put("targetSchemaName", mapping.targetSchemaName());
            row.put("targetObjectName", mapping.targetObjectName());
            row.put("objectType", "TABLE");
            if (mapping.whereCondition() != null) {
                row.put("whereCondition", mapping.whereCondition());
            }
            rows.add(row);
        }
        return Map.of(
                "version", "datasmart.sync.object-mapping.v1",
                "discoveryPolicy", Map.of("filterMode", "TABLE", "includeTables", true, "includeViews", false),
                "mappings", rows
        );
    }

    private Map<String, Object> buildFieldMappingConfig(
            List<ObjectMapping> mappings,
            Map<String, Object> sourceMetadata,
            Map<String, Object> targetMetadata) {
        List<Map<String, Object>> objectMappings = new ArrayList<>();
        for (int index = 0; index < mappings.size(); index++) {
            ObjectMapping mapping = mappings.get(index);
            Map<String, Object> sourceTable = findTable(sourceMetadata, mapping.sourceSchemaName(), mapping.sourceObjectName());
            Map<String, Object> targetTable = findTable(targetMetadata, mapping.targetSchemaName(), mapping.targetObjectName());
            List<Map<String, Object>> fieldRows = sameNameFieldMappings(sourceTable, targetTable);
            if (fieldRows.isEmpty()) {
                throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                        "无法为对象 " + mapping.sourceObjectName() + " -> " + mapping.targetObjectName()
                                + " 生成同名字段映射，请确认两端表和字段均存在");
            }
            Map<String, Object> objectConfig = new LinkedHashMap<>();
            objectConfig.put("ordinal", index + 1);
            objectConfig.put("objectKey", mapping.objectKey());
            objectConfig.put("sourceSchemaName", mapping.sourceSchemaName());
            objectConfig.put("sourceObjectName", mapping.sourceObjectName());
            objectConfig.put("targetSchemaName", mapping.targetSchemaName());
            objectConfig.put("targetObjectName", mapping.targetObjectName());
            objectConfig.put("mappings", fieldRows);
            objectMappings.add(objectConfig);
        }
        return Map.of(
                "version", "datasmart.sync.field-mapping.v2",
                "objectMappings", objectMappings
        );
    }

    private List<Map<String, Object>> sameNameFieldMappings(
            Map<String, Object> sourceTable,
            Map<String, Object> targetTable) {
        List<Map<String, Object>> sourceColumns = mapList(sourceTable.get("columns"));
        Map<String, Map<String, Object>> targetByName = new LinkedHashMap<>();
        for (Map<String, Object> targetColumn : mapList(targetTable.get("columns"))) {
            String name = nullableText(targetColumn.get("columnName"));
            if (name != null) {
                targetByName.put(name.toLowerCase(Locale.ROOT), targetColumn);
            }
        }
        List<Map<String, Object>> mappings = new ArrayList<>();
        for (Map<String, Object> sourceColumn : sourceColumns) {
            String sourceField = nullableText(sourceColumn.get("columnName"));
            if (sourceField == null) {
                continue;
            }
            Map<String, Object> targetColumn = targetByName.get(sourceField.toLowerCase(Locale.ROOT));
            if (targetColumn == null) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("sourceField", sourceField);
            row.put("targetField", targetColumn.get("columnName"));
            row.put("sourceType", sourceColumn.get("dataTypeName"));
            row.put("targetType", targetColumn.get("dataTypeName"));
            row.put("nullable", sourceColumn.get("nullable"));
            row.put("primaryKey", sourceColumn.get("primaryKey"));
            row.put("syncEnabled", true);
            row.put("typeCompatible", true);
            mappings.add(row);
        }
        return mappings;
    }

    private Map<String, Object> findTable(Map<String, Object> metadata, String schemaName, String tableName) {
        for (Map<String, Object> table : mapList(metadata.get("tables"))) {
            String candidateTable = nullableText(table.get("tableName"));
            String candidateSchema = nullableText(table.get("schemaName"));
            boolean tableMatches = candidateTable != null && candidateTable.equalsIgnoreCase(tableName);
            boolean schemaMatches = schemaName == null || schemaName.isBlank()
                    || (candidateSchema != null && candidateSchema.equalsIgnoreCase(schemaName));
            if (tableMatches && schemaMatches) {
                return table;
            }
        }
        throw new PlatformBusinessException(PlatformErrorCode.NOT_FOUND,
                "元数据中未找到表 " + (schemaName == null ? "" : schemaName + ".") + tableName);
    }

    private List<ObjectMapping> resolveObjectMappings(Object rawMappings) {
        if (!(rawMappings instanceof List<?> values)) {
            return List.of();
        }
        List<ObjectMapping> mappings = new ArrayList<>();
        for (int index = 0; index < values.size(); index++) {
            if (!(values.get(index) instanceof Map<?, ?> raw)) {
                continue;
            }
            Map<String, Object> value = copyMap(raw);
            String sourceObjectName = requiredText(value.get("sourceObjectName"), "源端表名不能为空");
            String targetObjectName = requiredText(value.get("targetObjectName"), "目标端表名不能为空");
            String sourceSchemaName = nullableText(value.get("sourceSchemaName"));
            String targetSchemaName = nullableText(value.get("targetSchemaName"));
            mappings.add(new ObjectMapping(
                    safeText(value.get("objectKey"), "mapping-" + (index + 1)),
                    sourceSchemaName,
                    sourceObjectName,
                    targetSchemaName,
                    targetObjectName,
                    nullableText(value.get("whereCondition"))
            ));
        }
        return mappings;
    }

    private Long draftReference(AgentToolExecutionContext context, String path) {
        return referencedLong(context, context.audit().getPlanArguments().get("draftRef"),
                DRAFT_SAVE, path, "缺少同步任务草稿结果中的 " + path);
    }

    private Long referencedLong(
            AgentToolExecutionContext context,
            Object reference,
            String defaultTool,
            String path,
            String missingMessage) {
        Object value = outputReferenceResolver.resolve(context, reference, defaultTool, path)
                .orElseThrow(() -> new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST, missingMessage));
        Long result = longValue(value);
        if (result == null) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST, missingMessage);
        }
        return result;
    }

    private Map<String, Object> referencedMap(
            AgentToolExecutionContext context,
            Object reference,
            String defaultTool,
            String path,
            String missingMessage) {
        Object value = outputReferenceResolver.resolve(context, reference, defaultTool, path)
                .orElseThrow(() -> new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST, missingMessage));
        if (!(value instanceof Map<?, ?> raw)) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST, missingMessage);
        }
        return copyMap(raw);
    }

    private boolean referencedBoolean(
            AgentToolExecutionContext context,
            Object reference,
            String defaultTool,
            String path,
            String missingMessage) {
        Object value = outputReferenceResolver.resolve(context, reference, defaultTool, path)
                .orElseThrow(() -> new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST, missingMessage));
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String text && ("true".equalsIgnoreCase(text) || "false".equalsIgnoreCase(text))) {
            return Boolean.parseBoolean(text);
        }
        throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST, missingMessage);
    }

    private Map<String, Object> post(AgentToolExecutionContext context, String uri, Object body, Object... variables) {
        RestClient.RequestBodySpec spec = restClientBuilder
                .baseUrl(httpSupport.baseUrl(TARGET_SERVICE))
                .build()
                .post()
                .uri(uri, variables)
                .headers(headers -> httpSupport.applyUserDelegationHeaders(headers, context));
        RestClient.ResponseSpec responseSpec = body == null ? spec.retrieve() : spec.body(body).retrieve();
        return responseSpec.body(new ParameterizedTypeReference<>() {
        });
    }

    private Map<String, Object> get(AgentToolExecutionContext context, String uri, Object... variables) {
        return restClientBuilder
                .baseUrl(httpSupport.baseUrl(TARGET_SERVICE))
                .build()
                .get()
                .uri(uri, variables)
                .headers(headers -> httpSupport.applyUserDelegationHeaders(headers, context))
                .retrieve()
                .body(new ParameterizedTypeReference<>() {
                });
    }

    private Map<String, Object> requireSuccessData(Map<String, Object> response, String action) {
        if (response == null) {
            throw new PlatformBusinessException(PlatformErrorCode.INTERNAL_ERROR, action + "返回空响应");
        }
        if (integerValue(response.get("code"), -1) != 0) {
            throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                    action + "失败: " + safeText(response.get("message"), "下游未返回具体原因"));
        }
        if (!(response.get("data") instanceof Map<?, ?> rawData)) {
            throw new PlatformBusinessException(PlatformErrorCode.INTERNAL_ERROR, action + "响应缺少 data");
        }
        return copyMap(rawData);
    }

    private String serialize(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new PlatformBusinessException(PlatformErrorCode.INTERNAL_ERROR,
                    "Agent 无法序列化同步任务配置: " + exception.getMessage());
        }
    }

    private List<Map<String, Object>> mapList(Object value) {
        if (!(value instanceof List<?> values)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : values) {
            if (item instanceof Map<?, ?> map) {
                result.add(copyMap(map));
            }
        }
        return result;
    }

    private List<?> listValue(Object value) {
        return value instanceof List<?> list ? List.copyOf(list) : List.of();
    }

    private Map<String, Object> copyMap(Map<?, ?> raw) {
        Map<String, Object> copy = new LinkedHashMap<>();
        raw.forEach((key, value) -> copy.put(String.valueOf(key), value));
        return copy;
    }

    private String normalizeWriteStrategy(Object value) {
        String strategy = safeText(value, "INSERT").toUpperCase(Locale.ROOT);
        return "MERGE".equals(strategy) || "UPSERT".equals(strategy) ? "UPDATE" : strategy;
    }

    private String requiredText(Object value, String message) {
        String text = nullableText(value);
        if (text == null) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST, message);
        }
        return text;
    }

    private String nullableText(Object value) {
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value).trim();
    }

    private String safeText(Object value, String fallback) {
        String text = nullableText(value);
        return text == null ? fallback : text;
    }

    private Long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return value == null ? null : Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private Long requiredPositiveLong(Object value, String message) {
        Long result = longValue(value);
        if (result == null || result <= 0) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST, message);
        }
        return result;
    }

    private long defaultLong(Object value) {
        Long result = longValue(value);
        return result == null ? 0L : result;
    }

    private Integer integerValue(Object value, int fallback) {
        Long result = longValue(value);
        return result == null ? fallback : result.intValue();
    }

    private boolean booleanValue(Object value, boolean fallback) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value == null ? fallback : Boolean.parseBoolean(String.valueOf(value));
    }

    private record ObjectMapping(
            String objectKey,
            String sourceSchemaName,
            String sourceObjectName,
            String targetSchemaName,
            String targetObjectName,
            String whereCondition) {
    }
}
