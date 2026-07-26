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
    private static final Set<String> USER_SYNC_MODES = Set.of(
            "FULL", "SCHEDULED_FULL", "SCHEDULED_BATCH", "CUSTOM_SQL_QUERY", "CDC_STREAMING");
    private static final Set<String> SCHEDULED_SYNC_MODES = Set.of("SCHEDULED_FULL", "SCHEDULED_BATCH");

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
        String syncMode = normalizeSyncMode(arguments.get("syncMode"));
        boolean customSqlMode = "CUSTOM_SQL_QUERY".equals(syncMode);
        String scheduleConfig = validateAndResolveScheduleConfig(arguments, syncMode);
        String customSqlConfig = validateAndBuildCustomSqlConfig(arguments, customSqlMode);
        List<Object> sourceMetadataReferences = referenceCandidates(
                arguments.get("sourceMetadataRefs"), arguments.get("sourceMetadataRef"));
        List<Object> targetMetadataReferences = referenceCandidates(
                arguments.get("targetMetadataRefs"), arguments.get("targetMetadataRef"));
        Map<String, Object> sourceMetadata = referencedMetadata(
                context, sourceMetadataReferences, SOURCE_METADATA_TOOL, "缺少源端元数据结果");
        Map<String, Object> targetMetadata = referencedMetadata(
                context, targetMetadataReferences, TARGET_METADATA_TOOL, "缺少目标端元数据结果");

        /*
         * 数据源 ID 必须来自已经通过权限、连接和元数据检查的工具输出。模型可以理解用户写出的数据源名称，
         * 但不能自行编造内部主键。若模型同时带回了 ID，则与可信引用中的 ID 交叉校验，避免会话状态错配。
         */
        Long sourceDatasourceId = resolveDatasourceId(
                context,
                arguments.get("sourceDatasourceId"),
                sourceMetadataReferences,
                SOURCE_METADATA_TOOL,
                "缺少有效的源端数据源 ID"
        );
        Long targetDatasourceId = resolveDatasourceId(
                context,
                arguments.get("targetDatasourceId"),
                targetMetadataReferences,
                TARGET_METADATA_TOOL,
                "缺少有效的目标端数据源 ID"
        );
        List<ObjectMapping> mappings = resolveObjectMappings(arguments.get("objectMappings"), customSqlMode);
        if (mappings.isEmpty()) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST, "同步任务至少需要一条对象映射");
        }

        String objectMappingConfig = customSqlMode ? null : serialize(buildObjectMappingConfig(mappings));
        String fieldMappingConfig = serialize(
                buildFieldMappingConfig(mappings, sourceMetadata, targetMetadata, customSqlMode));
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
        request.put("sourceSchemaName", customSqlMode ? null : first.sourceSchemaName());
        request.put("sourceObjectName", customSqlMode ? null : first.sourceObjectName());
        request.put("targetSchemaName", first.targetSchemaName());
        request.put("targetObjectName", first.targetObjectName());
        request.put("sourceConnectorType", sourceConnectorType);
        request.put("targetConnectorType", targetConnectorType);
        request.put("syncMode", syncMode);
        request.put("syncScopeType", customSqlMode
                ? "CUSTOM_SQL_QUERY"
                : mappings.size() == 1 ? "SINGLE_OBJECT" : "OBJECT_LIST");
        request.put("writeStrategy", normalizeWriteStrategy(arguments.get("writeStrategy"), syncMode));
        request.put("fieldMappingConfig", fieldMappingConfig);
        request.put("objectMappingConfig", objectMappingConfig);
        request.put("customSqlConfig", customSqlConfig);
        request.put("scheduleConfig", scheduleConfig);

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
                "syncMode", syncMode,
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
        Map<String, Object> arguments = context.audit().getPlanArguments();
        String syncMode = normalizeSyncMode(arguments.get("syncMode"));
        boolean enableSchedule = SCHEDULED_SYNC_MODES.contains(syncMode);
        if (arguments.get("enableSchedule") instanceof Boolean configured) {
            enableSchedule = configured;
        }
        Map<String, Object> request = Map.of(
                "enableSchedule", enableSchedule,
                "reason", "用户已在智能助手中确认本次 Agent 同步计划"
        );
        Map<String, Object> data = requireSuccessData(
                post(context, "/sync-tasks/{id}/publish", request, taskId),
                "同步任务发布"
        );
        return AgentToolExecutionOutcome.succeeded("同步任务已发布。", Map.of(
                "taskId", taskId,
                "state", safeText(data.get("state"), enableSchedule ? "SCHEDULED" : "CONFIGURED"),
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
            Map<String, Object> targetMetadata,
            boolean customSqlMode) {
        List<Map<String, Object>> objectMappings = new ArrayList<>();
        for (int index = 0; index < mappings.size(); index++) {
            ObjectMapping mapping = mappings.get(index);
            Map<String, Object> sourceTable = customSqlMode
                    ? null
                    : findTable(sourceMetadata, mapping.sourceSchemaName(), mapping.sourceObjectName());
            Map<String, Object> targetTable = findTable(targetMetadata, mapping.targetSchemaName(), mapping.targetObjectName());
            List<Map<String, Object>> fieldRows = mapping.fieldMappings().isEmpty()
                    ? customSqlMode ? List.of() : sameNameFieldMappings(sourceTable, targetTable)
                    : explicitFieldMappings(mapping, sourceTable, targetTable, customSqlMode);
            if (fieldRows.isEmpty()) {
                throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                        "对象 " + mappingDisplayName(mapping, customSqlMode)
                                + " 没有可执行字段映射，请至少确认一个源字段到目标字段");
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

    /**
     * 使用用户在 Agent 页面明确确认的字段映射，而不是在 Java 执行阶段重新猜测。
     *
     * <p>普通表同步同时校验源字段和目标字段都存在；SQL 模式的 sourceField 是 SQL 输出列或别名，
     * 无法从源表元数据直接校验，因此只校验目标字段。类型兼容性仍交给 data-sync 的统一预检查，
     * 保证 Agent 与手工向导得到完全相同的阻断项和修复建议。</p>
     */
    private List<Map<String, Object>> explicitFieldMappings(
            ObjectMapping objectMapping,
            Map<String, Object> sourceTable,
            Map<String, Object> targetTable,
            boolean customSqlMode) {
        Map<String, Map<String, Object>> sourceByName = customSqlMode
                ? Map.of()
                : columnsByName(sourceTable);
        Map<String, Map<String, Object>> targetByName = columnsByName(targetTable);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (FieldMapping fieldMapping : objectMapping.fieldMappings()) {
            if (!fieldMapping.syncEnabled()) {
                continue;
            }
            String sourceField = requiredText(fieldMapping.sourceField(),
                    "字段映射的源字段或 SQL 输出别名不能为空");
            String targetField = requiredText(fieldMapping.targetField(), "字段映射的目标字段不能为空");
            Map<String, Object> sourceColumn = customSqlMode
                    ? null
                    : sourceByName.get(sourceField.toLowerCase(Locale.ROOT));
            Map<String, Object> targetColumn = targetByName.get(targetField.toLowerCase(Locale.ROOT));
            if (!customSqlMode && sourceColumn == null) {
                throw new PlatformBusinessException(PlatformErrorCode.NOT_FOUND,
                        "源表 " + objectMapping.sourceObjectName() + " 中不存在字段 " + sourceField);
            }
            if (targetColumn == null) {
                throw new PlatformBusinessException(PlatformErrorCode.NOT_FOUND,
                        "目标表 " + objectMapping.targetObjectName() + " 中不存在字段 " + targetField);
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("sourceField", sourceField);
            row.put("targetField", targetField);
            row.put("sourceType", customSqlMode
                    ? fieldMapping.sourceType()
                    : sourceColumn.get("dataTypeName"));
            row.put("targetType", targetColumn.get("dataTypeName"));
            row.put("nullable", customSqlMode ? fieldMapping.nullable() : sourceColumn.get("nullable"));
            row.put("primaryKey", customSqlMode ? fieldMapping.primaryKey() : sourceColumn.get("primaryKey"));
            row.put("syncEnabled", true);
            row.put("typeCompatible", fieldMapping.typeCompatible());
            if (fieldMapping.transform() != null) {
                row.put("transform", fieldMapping.transform());
            }
            rows.add(row);
        }
        return rows;
    }

    private Map<String, Map<String, Object>> columnsByName(Map<String, Object> table) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        if (table == null) {
            return result;
        }
        for (Map<String, Object> column : mapList(table.get("columns"))) {
            String name = nullableText(column.get("columnName"));
            if (name != null) {
                result.put(name.toLowerCase(Locale.ROOT), column);
            }
        }
        return result;
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

    private List<ObjectMapping> resolveObjectMappings(Object rawMappings, boolean customSqlMode) {
        if (!(rawMappings instanceof List<?> values)) {
            return List.of();
        }
        List<ObjectMapping> mappings = new ArrayList<>();
        for (int index = 0; index < values.size(); index++) {
            if (!(values.get(index) instanceof Map<?, ?> raw)) {
                continue;
            }
            Map<String, Object> value = copyMap(raw);
            String sourceObjectName = customSqlMode
                    ? nullableText(value.get("sourceObjectName"))
                    : requiredText(value.get("sourceObjectName"), "源端表名不能为空");
            String targetObjectName = requiredText(value.get("targetObjectName"), "目标端表名不能为空");
            String sourceSchemaName = nullableText(value.get("sourceSchemaName"));
            String targetSchemaName = nullableText(value.get("targetSchemaName"));
            mappings.add(new ObjectMapping(
                    safeText(value.get("objectKey"), "mapping-" + (index + 1)),
                    sourceSchemaName,
                    sourceObjectName,
                    targetSchemaName,
                    targetObjectName,
                    customSqlMode ? null : nullableText(value.get("whereCondition")),
                    resolveFieldMappings(value.get("fieldMappings"))
            ));
        }
        return mappings;
    }

    private List<FieldMapping> resolveFieldMappings(Object rawMappings) {
        if (!(rawMappings instanceof List<?> values)) {
            return List.of();
        }
        List<FieldMapping> mappings = new ArrayList<>();
        for (Object item : values) {
            if (!(item instanceof Map<?, ?> raw)) {
                continue;
            }
            Map<String, Object> value = copyMap(raw);
            mappings.add(new FieldMapping(
                    nullableText(value.get("sourceField")),
                    nullableText(value.get("sourceType")),
                    nullableText(value.get("targetField")),
                    nullableText(value.get("targetType")),
                    booleanValue(value.get("nullable"), true),
                    booleanValue(value.get("primaryKey"), false),
                    booleanValue(value.get("syncEnabled"), true),
                    booleanValue(value.get("typeCompatible"), true),
                    nullableText(value.get("transform"))
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

    /**
     * 读取一个方向上全部元数据引用并合并表清单。
     *
     * <p>Agent 会针对用户明确列出的每张表执行一次窄范围元数据读取。这里不能只取“最近一次”结果，否则
     * 两表任务会只剩最后一张表。合并后仍然保留第一份元数据的连接器信息，并按 schema + table 去重，
     * 供对象映射和字段映射复用手工创建向导的同一套校验逻辑。</p>
     */
    private Map<String, Object> referencedMetadata(
            AgentToolExecutionContext context,
            List<Object> references,
            String defaultTool,
            String missingMessage) {
        if (references.isEmpty()) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST, missingMessage);
        }
        List<Map<String, Object>> metadataResults = new ArrayList<>();
        for (Object reference : references) {
            metadataResults.add(referencedMap(
                    context,
                    referenceWithPath(reference, "metadata"),
                    defaultTool,
                    "metadata",
                    missingMessage
            ));
        }
        return mergeMetadata(metadataResults);
    }

    /**
     * 从受控元数据引用派生数据源主键，并校验模型显式参数没有与真实工具结果冲突。
     */
    private Long resolveDatasourceId(
            AgentToolExecutionContext context,
            Object explicitValue,
            List<Object> metadataReferences,
            String defaultTool,
            String missingMessage) {
        Long explicitId = longValue(explicitValue);
        if (explicitValue != null && (explicitId == null || explicitId <= 0)) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST, missingMessage);
        }

        Long derivedId = null;
        for (Object reference : metadataReferences) {
            Object value = outputReferenceResolver.resolve(
                            context,
                            referenceWithPath(reference, "datasourceId"),
                            defaultTool,
                            "datasourceId")
                    .orElse(null);
            Long candidate = longValue(value);
            if (candidate == null || candidate <= 0) {
                continue;
            }
            if (derivedId != null && !derivedId.equals(candidate)) {
                throw new PlatformBusinessException(
                        PlatformErrorCode.BAD_REQUEST,
                        "同一方向的元数据引用来自不同数据源，请重新确认源端和目标端选择"
                );
            }
            derivedId = candidate;
        }

        if (explicitId != null && derivedId != null && !explicitId.equals(derivedId)) {
            throw new PlatformBusinessException(
                    PlatformErrorCode.BAD_REQUEST,
                    "模型给出的数据源 ID 与已验证的数据源不一致，已阻止创建任务"
            );
        }
        Long resolved = derivedId != null ? derivedId : explicitId;
        if (resolved == null) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST, missingMessage);
        }
        return resolved;
    }

    /**
     * 优先使用多值引用；兼容历史计划中的单值引用，便于已持久化会话平滑恢复。
     */
    private List<Object> referenceCandidates(Object groupedReferences, Object singleReference) {
        List<Object> result = new ArrayList<>();
        if (groupedReferences instanceof List<?> values) {
            for (Object value : values) {
                if (value != null && !result.contains(value)) {
                    result.add(value);
                }
            }
        }
        if (result.isEmpty() && singleReference != null) {
            result.add(singleReference);
        }
        return List.copyOf(result);
    }

    /**
     * 为同一个审计引用切换输出路径。审计 ID、工具编码和 Run 归属保持不变，调用方只能读取该工具已产生
     * 的其他公开字段，不能跨会话或跨工具访问任意输出。
     */
    private Object referenceWithPath(Object reference, String jsonPath) {
        if (!(reference instanceof Map<?, ?> raw)) {
            return reference;
        }
        Map<String, Object> copy = copyMap(raw);
        copy.put("jsonPath", jsonPath);
        copy.remove("path");
        return copy;
    }

    /**
     * 合并多次窄范围元数据读取结果。
     *
     * <p>该方法保持包可见，便于单元测试直接保护“多张表不会被最后一次结果覆盖”的业务约束。</p>
     */
    static Map<String, Object> mergeMetadata(List<Map<String, Object>> metadataResults) {
        if (metadataResults == null || metadataResults.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> merged = new LinkedHashMap<>(metadataResults.getFirst());
        Map<String, Map<String, Object>> tablesByKey = new LinkedHashMap<>();
        for (Map<String, Object> metadata : metadataResults) {
            Object tablesValue = metadata.get("tables");
            if (!(tablesValue instanceof List<?> tables)) {
                continue;
            }
            for (Object tableValue : tables) {
                if (!(tableValue instanceof Map<?, ?> rawTable)) {
                    continue;
                }
                Map<String, Object> table = new LinkedHashMap<>();
                rawTable.forEach((key, value) -> table.put(String.valueOf(key), value));
                String schemaName = String.valueOf(table.getOrDefault("schemaName", ""))
                        .trim()
                        .toLowerCase(Locale.ROOT);
                String tableName = String.valueOf(table.getOrDefault("tableName", ""))
                        .trim()
                        .toLowerCase(Locale.ROOT);
                if (tableName.isBlank()) {
                    continue;
                }
                tablesByKey.putIfAbsent(schemaName + "\u0000" + tableName, table);
            }
        }
        List<Map<String, Object>> tables = List.copyOf(tablesByKey.values());
        merged.put("tables", tables);
        merged.put("tableCount", tables.size());
        return merged;
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

    /**
     * 将历史 Agent 的 REAL_TIME 名称收敛到 data-sync 与手工向导使用的 CDC_STREAMING。
     * 其余未知模式直接阻断，避免静默降级成 FULL 后执行错误的数据范围。
     */
    String normalizeSyncMode(Object value) {
        String mode = safeText(value, "FULL").toUpperCase(Locale.ROOT);
        if ("REAL_TIME".equals(mode)) {
            return "CDC_STREAMING";
        }
        if (!USER_SYNC_MODES.contains(mode)) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "不支持的同步模式 " + mode + "，仅允许全量、定期全量、定期批量、SQL 语句和实时同步");
        }
        return mode;
    }

    private String validateAndResolveScheduleConfig(Map<String, Object> arguments, String syncMode) {
        String scheduleConfig = nullableText(arguments.get("scheduleConfig"));
        if (SCHEDULED_SYNC_MODES.contains(syncMode) && scheduleConfig == null) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "定期全量或定期批量任务必须提供调度配置");
        }
        if (!SCHEDULED_SYNC_MODES.contains(syncMode) && scheduleConfig != null) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "当前同步模式不能携带调度配置，请改用定期全量或定期批量");
        }
        return scheduleConfig;
    }

    private String validateAndBuildCustomSqlConfig(Map<String, Object> arguments, boolean customSqlMode) {
        String customSqlText = nullableText(arguments.get("customSqlText"));
        if (customSqlMode && customSqlText == null) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "SQL 语句同步必须提供只读 SQL");
        }
        if (!customSqlMode && customSqlText != null) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "只有 SQL 语句同步模式可以携带自定义 SQL");
        }
        return customSqlMode
                ? serialize(Map.of("version", "datasmart.sync.custom-sql.v1", "sql", customSqlText))
                : null;
    }

    private String mappingDisplayName(ObjectMapping mapping, boolean customSqlMode) {
        return customSqlMode
                ? "SQL 结果集 -> " + mapping.targetObjectName()
                : mapping.sourceObjectName() + " -> " + mapping.targetObjectName();
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

    private String normalizeWriteStrategy(Object value, String syncMode) {
        if ("CDC_STREAMING".equals(syncMode)) {
            return "UPDATE";
        }
        String strategy = safeText(value, "INSERT").toUpperCase(Locale.ROOT);
        if ("MERGE".equals(strategy) || "UPSERT".equals(strategy)) {
            return "UPDATE";
        }
        if (!Set.of("INSERT", "UPDATE").contains(strategy)) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "不支持的写入策略 " + strategy + "，仅允许 INSERT 或 UPDATE");
        }
        return strategy;
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
            String whereCondition,
            List<FieldMapping> fieldMappings) {
    }

    private record FieldMapping(
            String sourceField,
            String sourceType,
            String targetField,
            String targetType,
            boolean nullable,
            boolean primaryKey,
            boolean syncEnabled,
            boolean typeCompatible,
            String transform) {
    }
}
