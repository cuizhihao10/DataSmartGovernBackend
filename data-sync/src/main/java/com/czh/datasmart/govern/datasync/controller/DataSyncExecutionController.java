/**
 * @Author : Cui
 * @Date: 2026/05/07 21:42
 * @Description DataSmart Govern Backend - DataSyncExecutionController.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller;

import com.czh.datasmart.govern.common.api.PlatformApiResponse;
import com.czh.datasmart.govern.common.api.PlatformPageResponse;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import com.czh.datasmart.govern.datasync.controller.dto.SyncAuditQueryCriteria;
import com.czh.datasmart.govern.datasync.controller.dto.SyncCheckpointQueryCriteria;
import com.czh.datasmart.govern.datasync.controller.dto.SyncDirtyRecordReplayRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncDirtyRecordReplayResult;
import com.czh.datasmart.govern.datasync.controller.dto.SyncDirtyRecordQuarantineRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncDirtyRecordQuarantineResult;
import com.czh.datasmart.govern.datasync.controller.dto.SyncRecoveryCasePublishRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncRecoveryCasePublishResult;
import com.czh.datasmart.govern.datasync.controller.dto.SyncErrorSampleQueryCriteria;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExecutionLogQueryCriteria;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExecutionQueryCriteria;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExecutionDiagnosisResponse;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskMetadataDiscoveryRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskMetadataDiscoveryResponse;
import com.czh.datasmart.govern.datasync.controller.dto.SyncObjectExecutionQueryCriteria;
import com.czh.datasmart.govern.datasync.controller.dto.SyncObjectExecutionView;
import com.czh.datasmart.govern.datasync.controller.dto.SyncObjectRetryRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncObjectRetryResult;
import com.czh.datasmart.govern.datasync.controller.support.SyncActorContextHeaderSupport;
import com.czh.datasmart.govern.datasync.controller.support.DataSyncAgentRuntimeTrustedAccessSupport;
import com.czh.datasmart.govern.datasync.entity.SyncAuditRecord;
import com.czh.datasmart.govern.datasync.entity.SyncCheckpoint;
import com.czh.datasmart.govern.datasync.entity.SyncErrorSample;
import com.czh.datasmart.govern.datasync.entity.SyncExecution;
import com.czh.datasmart.govern.datasync.entity.SyncExecutionLog;
import com.czh.datasmart.govern.datasync.entity.SyncTask;
import com.czh.datasmart.govern.datasync.entity.SyncTaskDefinition;
import com.czh.datasmart.govern.datasync.service.DataSyncService;
import lombok.RequiredArgsConstructor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 数据同步执行与追踪 API。
 *
 * <p>这个控制器只承载“任务运行后的可观测事实”：执行历史、checkpoint、错误样本和审计。
 * 这样可以让 DataSyncTaskController 继续聚焦任务定义与生命周期动作，避免一个 Controller 又慢慢长成万能控制器。
 */
@RestController
@RequestMapping("/sync-tasks/{taskId}")
@RequiredArgsConstructor
public class DataSyncExecutionController {

    private final DataSyncService dataSyncService;
    private final DataSyncAgentRuntimeTrustedAccessSupport trustedAccessSupport;
    private final ObjectMapper objectMapper;

    /**
     * 为 GraphRAG 构建器导出真实业务快照。
     *
     * <p>这是内部事实投影，不是新的用户查询 API：Python Runtime 必须先通过服务身份令牌，
     * 再携带租户、项目和应用上下文访问。快照只包含任务、执行、日志、错误码和连接器能力等低敏
     * 结构化事实，故意不返回 SQL、连接串、凭据、样本行或字段映射正文。返回的事实仍是 PROPOSED，
     * 后续必须经过 permission-admin、Kafka、Java audit/outbox 和 Neo4j consumer 的审批门禁。</p>
     */
    @GetMapping("/internal/data-sync/graph-facts/snapshot")
    public PlatformApiResponse<Map<String, Object>> graphFactsSnapshot(
            @PathVariable Long taskId,
            @RequestParam(required = false) Long executionId,
            @RequestParam String applicationId,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long tenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId,
            @RequestHeader HttpHeaders headers) {
        trustedAccessSupport.requireService(headers, "python-ai-runtime");
        SyncActorContext context = actorContext(tenantId, actorId, actorRole, traceId, headers);
        if (context.applicationId() == null || !applicationId.equals(String.valueOf(context.applicationId()))) {
            throw new IllegalArgumentException("applicationId 必须与受信上下文一致");
        }
        SyncTask task = dataSyncService.getTask(taskId, context);
        SyncTaskDefinition definition = task.getDefinition();
        SyncExecutionDiagnosisResponse diagnosis = dataSyncService.diagnoseExecution(taskId, executionId, context);
        Long resolvedExecutionId = diagnosis.executionId() == null ? executionId : diagnosis.executionId();
        List<SyncExecutionLog> logs = new ArrayList<>();
        if (resolvedExecutionId != null) {
            logs.addAll(dataSyncService.pageExecutionLogs(
                    new SyncExecutionLogQueryCriteria(taskId, resolvedExecutionId, null, null, 1L, 200L),
                    context).getRecords());
        }
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("schemaVersion", "datasmart.business-graph-snapshot.v1");
        snapshot.put("snapshotId", "task-" + taskId + "-execution-" + (resolvedExecutionId == null ? "latest" : resolvedExecutionId));
        snapshot.put("asOf", java.time.OffsetDateTime.now().toString());
        snapshot.put("sourceUri", "datasync://tasks/" + taskId + "/graph-facts");
        snapshot.put("scope", fact(
                "tenantId", String.valueOf(task.getTenantId()),
                "applicationId", applicationId,
                "projectId", String.valueOf(task.getProjectId()),
                "sensitivityLevel", "internal"));
        snapshot.put("applications", List.of(fact("id", applicationId, "name", "application-" + applicationId)));
        snapshot.put("projects", List.of(fact("id", task.getProjectId(), "applicationId", applicationId, "name", "project-" + task.getProjectId())));
        List<Map<String, Object>> errorFacts = errorFacts(diagnosis);
        List<String> errorIds = errorFacts.stream().map(item -> String.valueOf(item.get("id"))).toList();
        List<String> logIds = logs.stream().map(item -> String.valueOf(item.getId())).toList();
        snapshot.put("dataSources", dataSources(definition, task));
        snapshot.put("tasks", List.of(taskFact(task, definition)));
        snapshot.put("taskVersions", List.of(taskVersionFact(task, definition)));
        snapshot.put("executions", List.of(executionFact(diagnosis, errorIds, logIds)));
        snapshot.put("logs", logs.stream().map(item -> logFact(item, errorIds)).toList());
        snapshot.put("errors", errorFacts);
        Map<String, Object> metadataFacts = metadataFacts(definition, task, context);
        snapshot.putAll(metadataFacts);
        @SuppressWarnings("unchecked")
        List<String> metadataWarnings = (List<String>) metadataFacts.get("metadataWarnings");
        snapshot.put("mappings", mappingFacts(definition, metadataWarnings));
        snapshot.put("actions", recoveryActionFacts(diagnosis));
        snapshot.put("runbooks", runbookFacts(diagnosis, errorIds));
        snapshot.put("incidents", diagnosis.similarCases() == null ? List.of()
                : diagnosis.similarCases().stream().map(item -> caseFact(item, errorIds)).toList());
        snapshot.put("sourceStatus", metadataWarnings.isEmpty() ? "COMPLETE" : "INCOMPLETE");
        return PlatformApiResponse.success(snapshot, traceId);
    }

    /** 将任务定义投影为图构建器可消费的数据源事实，不复制连接配置。 */
    private List<Map<String, Object>> dataSources(SyncTaskDefinition definition, SyncTask task) {
        List<Map<String, Object>> values = new ArrayList<>();
        values.add(fact("id", definition.getSourceDatasourceId(), "projectId", task.getProjectId(),
                "name", "source-datasource-" + definition.getSourceDatasourceId(),
                "connectorType", String.valueOf(definition.getSourceConnectorType())));
        values.add(fact("id", definition.getTargetDatasourceId(), "projectId", task.getProjectId(),
                "name", "target-datasource-" + definition.getTargetDatasourceId(),
                "connectorType", String.valueOf(definition.getTargetConnectorType())));
        return values;
    }

    /** 投影任务主状态和执行所需的稳定定位字段。 */
    private Map<String, Object> taskFact(SyncTask task, SyncTaskDefinition definition) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", task.getId());
        value.put("projectId", task.getProjectId());
        value.put("name", task.getName());
        value.put("state", task.getCurrentState());
        value.put("sourceDatasourceId", definition.getSourceDatasourceId());
        value.put("targetDatasourceId", definition.getTargetDatasourceId());
        value.put("sourceTableId", tableId("SOURCE", definition.getSourceDatasourceId(),
                definition.getSourceSchemaName(), definition.getSourceObjectName()));
        value.put("targetTableId", tableId("TARGET", definition.getTargetDatasourceId(),
                definition.getTargetSchemaName(), definition.getTargetObjectName()));
        value.put("successfulVersionId", definition.getId());
        return value;
    }

    /** 任务定义版本只保留模式、策略、对象和映射声明状态，不输出配置正文。 */
    private Map<String, Object> taskVersionFact(SyncTask task, SyncTaskDefinition definition) {
        return fact("id", definition.getId(), "taskId", task.getId(),
                "version", definition.getUpdateTime() == null ? "current" : definition.getUpdateTime().toString(),
                "syncMode", String.valueOf(definition.getSyncMode()),
                "writeStrategy", String.valueOf(definition.getWriteStrategy()),
                "fieldMappingDeclared", definition.getFieldMappingConfig() != null && !definition.getFieldMappingConfig().isBlank());
    }

    /**
     * 将执行诊断投影为执行实体，并显式绑定本次快照中的错误和日志实体。
     *
     * <p>GraphRAG 不能依靠错误码字符串猜测 execution 与 error 的关系，因此这里把稳定 ID 列表作为
     * 一等事实输出。列表只包含控制面主键和摘要 ID，不包含日志正文、样本值或堆栈。</p>
     */
    private Map<String, Object> executionFact(SyncExecutionDiagnosisResponse diagnosis,
                                              List<String> errorIds,
                                              List<String> logIds) {
        return fact("id", diagnosis.executionId() == null ? "latest" : diagnosis.executionId(),
                "taskId", diagnosis.taskId(), "state", diagnosis.executionState(),
                "recordsRead", diagnosis.recordsRead(), "recordsWritten", diagnosis.recordsWritten(),
                "failedRecordCount", diagnosis.failedRecordCount(), "rootCauseCodes",
                diagnosis.rootCauseCodes() == null ? List.of() : diagnosis.rootCauseCodes(),
                "errorIds", errorIds, "logIds", logIds);
    }

    /**
     * 将运行日志绑定到 execution；失败级日志同时绑定该 execution 的稳定错误实体。
     *
     * <p>日志表当前没有 error 外键，因此只对 ERROR、WARN 或 FAILED/BLOCKED 事件建立错误关系。
     * 正常 INFO 时间线不会被错误实体污染。</p>
     */
    private Map<String, Object> logFact(SyncExecutionLog log, List<String> errorIds) {
        boolean failureLog = "ERROR".equalsIgnoreCase(safe(log.getLogLevel()))
                || "WARN".equalsIgnoreCase(safe(log.getLogLevel()))
                || "FAILED".equalsIgnoreCase(safe(log.getEventStatus()))
                || "BLOCKED".equalsIgnoreCase(safe(log.getEventStatus()));
        return fact("id", log.getId(), "executionId", log.getExecutionId(), "logStage", log.getLogStage(),
                "eventType", log.getEventType(), "eventStatus", safe(log.getEventStatus()),
                "message", safe(log.getMessage()), "errorIds", failureLog ? errorIds : List.of());
    }

    /** 为一次 execution 的聚合错误生成不会跨运行碰撞的稳定实体 ID。 */
    private String errorId(SyncExecutionDiagnosisResponse diagnosis,
                           SyncExecutionDiagnosisResponse.ErrorSummary error) {
        return "execution:" + (diagnosis.executionId() == null ? "latest" : diagnosis.executionId())
                + ":error:" + safe(error.errorCode()) + ":" + safe(error.errorType());
    }

    /** 错误摘要只保留稳定分类字段，禁止图谱建立在样本值或堆栈正文上。 */
    private Map<String, Object> errorFact(SyncExecutionDiagnosisResponse diagnosis,
                                          SyncExecutionDiagnosisResponse.ErrorSummary error) {
        return fact("id", errorId(diagnosis, error), "errorType", error.errorType(),
                "errorCode", error.errorCode(), "count", error.count(), "retryable", error.retryable());
    }

    /** 把可空的诊断错误集合转换为有序、稳定且低敏的图实体列表。 */
    private List<Map<String, Object>> errorFacts(SyncExecutionDiagnosisResponse diagnosis) {
        return diagnosis.errors() == null ? List.of()
                : diagnosis.errors().stream().map(item -> errorFact(diagnosis, item)).toList();
    }

    /** 将历史案例关联转换为可追溯的事故实体，并绑定当前相同错误分类。 */
    private Map<String, Object> caseFact(SyncExecutionDiagnosisResponse.KnowledgeCaseSummary item,
                                         List<String> errorIds) {
        return fact("id", item.caseId(), "title", safe(item.title()), "incidentType", safe(item.incidentType()),
                "resolutionSummary", safe(item.resolutionSummary()), "errorIds", errorIds);
    }

    /** 调用 data-sync 已有元数据发现能力，把真实 schema/table/field 投影成图实体。 */
    private Map<String, Object> metadataFacts(SyncTaskDefinition definition,
                                              SyncTask task,
                                              SyncActorContext context) {
        List<Map<String, Object>> schemas = new ArrayList<>();
        List<Map<String, Object>> tables = new ArrayList<>();
        List<Map<String, Object>> fields = new ArrayList<>();
        List<Map<String, Object>> constraints = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<String> advisories = new ArrayList<>();
        discoverSide("SOURCE", definition.getSourceDatasourceId(), definition.getSourceConnectorType(),
                definition.getSourceSchemaName(), definition.getSourceObjectName(), task, context,
                schemas, tables, fields, constraints, warnings, advisories);
        discoverSide("TARGET", definition.getTargetDatasourceId(), definition.getTargetConnectorType(),
                definition.getTargetSchemaName(), definition.getTargetObjectName(), task, context,
                schemas, tables, fields, constraints, warnings, advisories);
        return fact("schemas", schemas, "tables", tables, "fields", fields,
                "constraints", constraints, "metadataWarnings", warnings,
                "metadataAdvisories", advisories);
    }

    /** 单侧元数据查询的低敏投影；异常只降级为 warning，保留任务与执行事实。 */
    private void discoverSide(String side,
                              Long datasourceId,
                              String connectorType,
                              String schemaName,
                              String tableName,
                              SyncTask task,
                              SyncActorContext context,
                              List<Map<String, Object>> schemas,
                              List<Map<String, Object>> tables,
                              List<Map<String, Object>> fields,
                              List<Map<String, Object>> constraints,
                              List<String> warnings,
                              List<String> advisories) {
        if (datasourceId == null || tableName == null || tableName.isBlank()) {
            warnings.add(side + "_METADATA_SCOPE_INCOMPLETE");
            return;
        }
        try {
            SyncTaskMetadataDiscoveryRequest request = new SyncTaskMetadataDiscoveryRequest();
            request.setDatasourceId(datasourceId);
            request.setSide(side);
            request.setConnectorType(connectorType);
            request.setFilterMode("TABLE");
            request.setSchemaPattern(schemaName);
            request.setTableNamePattern(tableName);
            request.setIncludeColumns(true);
            request.setIncludeViews(false);
            request.setMaxTables(16);
            request.setMaxColumnsPerTable(256);
            SyncTaskMetadataDiscoveryResponse response = dataSyncService.discoverTaskMetadata(request, context);
            for (String schema : response.getSchemas() == null ? List.<String>of() : response.getSchemas()) {
                schemas.add(fact("id", side + ":" + datasourceId + ":schema:" + schema,
                        "dataSourceId", datasourceId, "name", safe(schema)));
            }
            // MySQL 的 JDBC metadata 通常不返回 PostgreSQL 意义上的 schema 列，但任务定义仍保存了
            // 实际 database/schema 名称。为了让 table -> schema 关系拥有可解析端点，这里在驱动省略
            // schema 时用受信任务定义中的 schemaName 补齐一个低敏 schema 实体。
            if (schemaName != null && !schemaName.isBlank()
                    && schemas.stream().noneMatch(item -> (side + ":" + datasourceId + ":schema:" + schemaName)
                    .equals(String.valueOf(item.get("id"))))) {
                schemas.add(fact("id", side + ":" + datasourceId + ":schema:" + schemaName,
                        "dataSourceId", datasourceId, "name", safe(schemaName)));
            }
            for (SyncTaskMetadataDiscoveryResponse.TableObject table :
                    response.getTables() == null ? List.<SyncTaskMetadataDiscoveryResponse.TableObject>of() : response.getTables()) {
                String resolvedSchemaName = table.getSchemaName() == null || table.getSchemaName().isBlank()
                        ? schemaName : table.getSchemaName();
                String tableId = tableId(side, datasourceId, resolvedSchemaName, table.getTableName());
                String schemaId = side + ":" + datasourceId + ":schema:" + safe(resolvedSchemaName);
                tables.add(fact("id", tableId, "schemaId", schemaId, "dataSourceId", datasourceId,
                        "name", safe(table.getTableName()), "tableType", safe(table.getTableType())));
                for (SyncTaskMetadataDiscoveryResponse.FieldObject field :
                        table.getFields() == null ? List.<SyncTaskMetadataDiscoveryResponse.FieldObject>of() : table.getFields()) {
                    String fieldId = tableId + ":field:" + safe(field.getFieldName());
                    Map<String, Object> fieldFact = new LinkedHashMap<>();
                    fieldFact.put("id", fieldId);
                    fieldFact.put("tableId", tableId);
                    fieldFact.put("name", safe(field.getFieldName()));
                    fieldFact.put("dataType", safe(field.getDataTypeName()));
                    fieldFact.put("nullable", field.getNullable());
                    fields.add(fieldFact);
                    if (Boolean.TRUE.equals(field.getPrimaryKey())) {
                        constraints.add(fact("id", fieldId + ":primary-key", "fieldId", fieldId,
                                "constraintType", "PRIMARY_KEY"));
                    }
                    if (Boolean.FALSE.equals(field.getNullable())) {
                        constraints.add(fact("id", fieldId + ":not-null", "fieldId", fieldId,
                                "constraintType", "NOT_NULL"));
                    }
                }
            }
            if (response.getWarnings() != null) {
                for (String item : response.getWarnings()) {
                    String warning = side + ":" + safe(item);
                    // 元数据服务会对“本次请求主动关闭的可选内容”返回提示，例如未请求视图、索引或样本。
                    // 这些提示不影响表/字段/约束事实完整性，不能阻断业务图谱审批；真正的连接失败、目标
                    // 表缺失和字段读取异常仍然进入 metadataWarnings，由 BusinessGraphBuilder fail-closed。
                    if (isAdvisoryMetadataWarning(item)) {
                        advisories.add(warning);
                    } else {
                        warnings.add(warning);
                    }
                }
            }
        } catch (RuntimeException exception) {
            warnings.add(side + "_METADATA_DISCOVERY_UNAVAILABLE");
        }
    }

    /**
     * 判断元数据发现提示是否只是“可选投影未启用”的建议。
     *
     * <p>业务图谱当前需要表、字段、主键/唯一键/非空/外键等核心事实；视图、索引和样本值属于后续
     * 丰富关系链的可选投影。把两者混为同一个 warnings 列表，会让真实表字段已经发现成功的任务被
     * 错误标记为 INCOMPLETE，进而无法进入审批闭环。</p>
     */
    private boolean isAdvisoryMetadataWarning(String warning) {
        String normalized = safe(warning);
        return normalized.contains("未包含视图")
                || normalized.contains("未返回索引信息")
                || normalized.contains("未包含样本数据")
                || normalized.contains("不使用 PostgreSQL 风格 schemaPattern");
    }

    /**
     * 从已持久化字段映射 JSON 构造两端完整 FIELD ID。
     *
     * <p>旧实现只输出裸字段名，源表和目标表出现同名字段时会错误地把它们解析成同一实体。新实现把
     * side、datasource、schema、table、field 全部纳入 ID，同时仍不输出转换表达式、默认值或 SQL。</p>
     */
    private List<Map<String, Object>> mappingFacts(SyncTaskDefinition definition, List<String> warnings) {
        if (definition.getFieldMappingConfig() == null || definition.getFieldMappingConfig().isBlank()) {
            return List.of();
        }
        try {
            JsonNode root = objectMapper.readTree(definition.getFieldMappingConfig());
            JsonNode mappings = root.isArray() ? root : root.path("mappings");
            if (!mappings.isArray()) {
                return List.of();
            }
            List<Map<String, Object>> facts = new ArrayList<>();
            for (JsonNode item : mappings) {
                String source = safe(item.path("sourceField").asText(item.path("source").asText("")));
                String target = safe(item.path("targetField").asText(item.path("target").asText("")));
                if (!source.isBlank() && !target.isBlank()) {
                    String sourceFieldId = fieldId("SOURCE", definition.getSourceDatasourceId(),
                            definition.getSourceSchemaName(), definition.getSourceObjectName(), source);
                    String targetFieldId = fieldId("TARGET", definition.getTargetDatasourceId(),
                            definition.getTargetSchemaName(), definition.getTargetObjectName(), target);
                    facts.add(fact("id", "mapping:" + sourceFieldId + "->" + targetFieldId,
                            "sourceTableId", tableId("SOURCE", definition.getSourceDatasourceId(),
                                    definition.getSourceSchemaName(), definition.getSourceObjectName()),
                            "targetTableId", tableId("TARGET", definition.getTargetDatasourceId(),
                                    definition.getTargetSchemaName(), definition.getTargetObjectName()),
                            "sourceFieldId", sourceFieldId, "targetFieldId", targetFieldId));
                } else {
                    warnings.add("FIELD_MAPPING_ENDPOINT_INCOMPLETE");
                }
                if (facts.size() >= 512) {
                    break;
                }
            }
            return facts;
        } catch (Exception exception) {
            warnings.add("FIELD_MAPPING_CONFIG_UNREADABLE");
            return List.of();
        }
    }

    /** 将诊断服务给出的受治理恢复动作目录投影为图实体。 */
    private List<Map<String, Object>> recoveryActionFacts(SyncExecutionDiagnosisResponse diagnosis) {
        if (diagnosis.recommendedRepairActions() == null) {
            return List.of();
        }
        return diagnosis.recommendedRepairActions().stream()
                .filter(item -> item != null && !item.isBlank())
                .map(item -> item.trim().toUpperCase(Locale.ROOT))
                .distinct()
                .map(item -> fact("id", item, "name", item, "status", "GOVERNED_ACTION_CATALOG"))
                .toList();
    }

    /**
     * 把 Java 恢复目录物化为可追溯 Runbook 候选。
     *
     * <p>这里的 Runbook 不是模型生成的自然语言正文，而是 data-sync 已实现动作目录的结构化引用。
     * 每条记录同时绑定错误实体和动作实体，GraphRAG 可以回答“该错误有哪些已实现处置入口”，真正执行时
     * 仍必须经过 Autopilot 策略、指纹、权限、风险和幂等回执校验。</p>
     */
    private List<Map<String, Object>> runbookFacts(SyncExecutionDiagnosisResponse diagnosis,
                                                    List<String> errorIds) {
        if (diagnosis.recommendedRepairActions() == null) {
            return List.of();
        }
        return diagnosis.recommendedRepairActions().stream()
                .filter(item -> item != null && !item.isBlank())
                .map(item -> item.trim().toUpperCase(Locale.ROOT))
                .distinct()
                .map(item -> fact("id", "datasync-recovery:" + item, "name", "data-sync " + item + " runbook",
                        "recommendedAction", item, "errorIds", errorIds,
                        "sourceType", "DATA_SYNC_GOVERNED_RECOVERY_CATALOG"))
                .toList();
    }

    /** 构造包含业务侧、数据源、schema 和表名的稳定 TABLE ID。 */
    private String tableId(String side, Long datasourceId, String schemaName, String tableName) {
        return safe(side).toUpperCase(Locale.ROOT) + ":" + datasourceId + ":table:"
                + safe(schemaName) + "." + safe(tableName);
    }

    /** 构造与元数据发现输出完全一致的稳定 FIELD ID。 */
    private String fieldId(String side, Long datasourceId, String schemaName, String tableName, String fieldName) {
        return tableId(side, datasourceId, schemaName, tableName) + ":field:" + safe(fieldName);
    }

    private String safe(String value) {
        return value == null ? "" : value.length() > 256 ? value.substring(0, 256) : value;
    }

    /**
     * 构造允许 null 值的事实 Map。
     *
     * <p>真实任务的连接器元数据可能尚未完整发现，Java {@link Map#of(Object, Object, Object, Object)}
     * 遇到 null 会直接抛出 NPE，反而阻断整份快照。快照是受控的事实投影，允许保留 null 让下游按
     * metadataWarnings 和字段缺失做降级判断。</p>
     */
    private Map<String, Object> fact(Object... entries) {
        if (entries.length % 2 != 0) {
            throw new IllegalArgumentException("事实 Map 参数必须成对出现");
        }
        Map<String, Object> value = new LinkedHashMap<>();
        for (int index = 0; index < entries.length; index += 2) {
            value.put(String.valueOf(entries[index]), entries[index + 1]);
        }
        return value;
    }

    /**
     * 查询某个同步任务的执行历史。
     *
     * <p>执行历史用于回答“这个任务跑过几次、每次是什么状态、是否失败、由谁触发”。
     */
    @GetMapping("/executions")
    public PlatformApiResponse<PlatformPageResponse<SyncExecution>> pageExecutions(
            @PathVariable Long taskId,
            @RequestParam(required = false) String executionState,
            @RequestParam(required = false) String triggerType,
            @RequestParam(defaultValue = "1") Long current,
            @RequestParam(defaultValue = "20") Long size,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long tenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId,
            @RequestHeader HttpHeaders headers) {
        SyncExecutionQueryCriteria criteria = new SyncExecutionQueryCriteria(taskId, executionState, triggerType, current, size);
        return PlatformApiResponse.success(dataSyncService.pageExecutions(
                criteria, actorContext(tenantId, actorId, actorRole, traceId, headers)), traceId);
    }

    /**
     * 查询某次 execution 的对象级执行账本。
     *
     * <p>路由语义：</p>
     * <p>1. {@code taskId} 表示同步任务，先用于数据范围校验；</p>
     * <p>2. {@code executionId} 表示某一次真实运行；</p>
     * <p>3. {@code objects} 表示该运行内部的对象/分片级事实，例如 OBJECT_LIST 中的每张表；</p>
     * <p>4. {@code objectState/objectOrdinal} 是运维筛选条件，方便快速定位失败对象。</p>
     *
     * <p>权限与安全边界：该接口应由 gateway 标记为 {@code SYNC_EXECUTION + VIEW}。响应允许展示对象名用于排障，
     * 但不会返回 SQL、where/filter 原文、字段映射正文、连接串、凭据或样本行。</p>
     */
    @GetMapping("/executions/{executionId}/objects")
    public PlatformApiResponse<PlatformPageResponse<SyncObjectExecutionView>> pageObjectExecutions(
            @PathVariable Long taskId,
            @PathVariable Long executionId,
            @RequestParam(required = false) String objectState,
            @RequestParam(required = false) Integer objectOrdinal,
            @RequestParam(defaultValue = "1") Long current,
            @RequestParam(defaultValue = "20") Long size,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long tenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId,
            @RequestHeader HttpHeaders headers) {
        SyncObjectExecutionQueryCriteria criteria = new SyncObjectExecutionQueryCriteria(
                taskId, executionId, objectState, objectOrdinal, current, size);
        return PlatformApiResponse.success(dataSyncService.pageObjectExecutions(
                criteria, actorContext(tenantId, actorId, actorRole, traceId, headers)), traceId);
    }

    /**
     * 查询某次 execution 的运行日志。
     *
     * <p>路由语义：</p>
     * <p>1. {@code taskId} 用于先锁定同步任务和权限范围；</p>
     * <p>2. {@code executionId} 用于锁定某一次真实运行；</p>
     * <p>3. {@code logs} 表示该运行内部按时间排序的阶段事件，例如入队、认领、计划生成、创建通道、批次同步、checkpoint 和终态。</p>
     *
     * <p>与普通应用日志不同，本接口返回的是低敏产品日志：可以展示给任务负责人、运营人员和 Agent，
     * 但不会返回 SQL 正文、连接串、凭据、样本行、where 原文或真实分片边界。</p>
     */
    @GetMapping("/executions/{executionId}/logs")
    public PlatformApiResponse<PlatformPageResponse<SyncExecutionLog>> pageExecutionLogs(
            @PathVariable Long taskId,
            @PathVariable Long executionId,
            @RequestParam(required = false) String logStage,
            @RequestParam(required = false) String logLevel,
            @RequestParam(defaultValue = "1") Long current,
            @RequestParam(defaultValue = "100") Long size,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long tenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId,
            @RequestHeader HttpHeaders headers) {
        SyncExecutionLogQueryCriteria criteria = new SyncExecutionLogQueryCriteria(
                taskId, executionId, logStage, logLevel, current, size);
        return PlatformApiResponse.success(dataSyncService.pageExecutionLogs(
                criteria, actorContext(tenantId, actorId, actorRole, traceId, headers)), traceId);
    }

    /**
     * 对某次 execution 内部的 FAILED 对象发起选择性重试。
     *
     * <p>该接口是 PARTIALLY_SUCCEEDED 真正闭环的关键：如果一个 OBJECT_LIST 任务中 10 张表有 8 张成功、2 张失败，
     * 运维人员不应该被迫整单重跑 10 张表，而应能够只重传失败对象。服务端会把选中的 FAILED 对象重置为 PENDING，
     * 并把父 execution 放回 QUEUED，后续 worker 重新认领时会自动跳过已成功对象。</p>
     *
     * <p>请求体可以为空：为空代表重试当前 execution 下全部 FAILED 对象；也可以传 objectExecutionIds 或
     * objectOrdinals 做精确选择。成功对象、运行中对象、取消对象都不会被重试。</p>
     */
    @PostMapping("/executions/{executionId}/objects/retry")
    public PlatformApiResponse<SyncObjectRetryResult> retryObjectExecutions(
            @PathVariable Long taskId,
            @PathVariable Long executionId,
            @RequestBody(required = false) SyncObjectRetryRequest request,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long tenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId,
            @RequestHeader HttpHeaders headers) {
        return PlatformApiResponse.success(dataSyncService.retryObjectExecutions(
                taskId, executionId, request, actorContext(tenantId, actorId, actorRole, traceId, headers)), traceId);
    }

    /**
     * 查询某个同步任务的 checkpoint。
     *
     * <p>checkpoint 是恢复和回放的关键依据；即使当前还没有 worker 写入，API 先稳定下来也便于后续执行器对接。
     */
    @GetMapping("/checkpoints")
    public PlatformApiResponse<PlatformPageResponse<SyncCheckpoint>> pageCheckpoints(
            @PathVariable Long taskId,
            @RequestParam(required = false) Long executionId,
            @RequestParam(required = false) String checkpointType,
            @RequestParam(defaultValue = "1") Long current,
            @RequestParam(defaultValue = "20") Long size,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long tenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId,
            @RequestHeader HttpHeaders headers) {
        SyncCheckpointQueryCriteria criteria = new SyncCheckpointQueryCriteria(taskId, executionId, checkpointType, current, size);
        return PlatformApiResponse.success(dataSyncService.pageCheckpoints(
                criteria, actorContext(tenantId, actorId, actorRole, traceId, headers)), traceId);
    }

    /**
     * 查询某个同步任务的错误样本。
     */
    @GetMapping("/errors")
    public PlatformApiResponse<PlatformPageResponse<SyncErrorSample>> pageErrorSamples(
            @PathVariable Long taskId,
            @RequestParam(required = false) Long executionId,
            @RequestParam(required = false) String errorType,
            @RequestParam(required = false) Boolean retryable,
            @RequestParam(defaultValue = "1") Long current,
            @RequestParam(defaultValue = "20") Long size,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long tenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId,
            @RequestHeader HttpHeaders headers) {
        SyncErrorSampleQueryCriteria criteria = new SyncErrorSampleQueryCriteria(taskId, executionId, errorType, retryable, current, size);
        return PlatformApiResponse.success(dataSyncService.pageErrorSamples(
                criteria, actorContext(tenantId, actorId, actorRole, traceId, headers)), traceId);
    }

    /**
     * Aggregate the latest real execution ledger into a bounded Agent diagnosis package.
     * The response intentionally excludes credentials, SQL, source keys and row samples.
     */
    @GetMapping("/agent-diagnosis")
    public PlatformApiResponse<SyncExecutionDiagnosisResponse> diagnoseExecution(
            @PathVariable Long taskId,
            @RequestParam(required = false) Long executionId,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long tenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId,
            @RequestHeader HttpHeaders headers) {
        return PlatformApiResponse.success(dataSyncService.diagnoseExecution(
                taskId, executionId, actorContext(tenantId, actorId, actorRole, traceId, headers)), traceId);
    }

    /** Preview reversible dirty-record quarantine and return a confirmation digest. */
    @PostMapping("/errors/quarantine/preview")
    public PlatformApiResponse<SyncDirtyRecordQuarantineResult> previewDirtyRecordQuarantine(
            @PathVariable Long taskId,
            @RequestBody SyncDirtyRecordQuarantineRequest request,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long tenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId,
            @RequestHeader HttpHeaders headers) {
        return PlatformApiResponse.success(dataSyncService.previewDirtyRecordQuarantine(
                taskId, request, actorContext(tenantId, actorId, actorRole, traceId, headers)), traceId);
    }

    /** Apply the exact preview after explicit confirmation; source rows are never physically deleted. */
    @PostMapping("/errors/quarantine/apply")
    public PlatformApiResponse<SyncDirtyRecordQuarantineResult> applyDirtyRecordQuarantine(
            @PathVariable Long taskId,
            @RequestBody SyncDirtyRecordQuarantineRequest request,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long tenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId,
            @RequestHeader HttpHeaders headers) {
        return PlatformApiResponse.success(dataSyncService.applyDirtyRecordQuarantine(
                taskId, request, actorContext(tenantId, actorId, actorRole, traceId, headers)), traceId);
    }

    /** Persist a reusable case only after the repaired execution has succeeded. */
    @PostMapping("/agent-recovery-cases")
    public PlatformApiResponse<SyncRecoveryCasePublishResult> publishRecoveryCase(
            @PathVariable Long taskId,
            @RequestBody SyncRecoveryCasePublishRequest request,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long tenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId,
            @RequestHeader HttpHeaders headers) {
        return PlatformApiResponse.success(dataSyncService.publishRecoveryCase(
                taskId, request, actorContext(tenantId, actorId, actorRole, traceId, headers)), traceId);
    }

    /**
     * 基于错误样本发起脏数据修复重放。
     *
     * <p>路由语义：</p>
     * <p>1. {@code taskId} 表示当前修复动作归属的同步任务，服务层会先按任务做租户、项目、SELF 数据范围校验；</p>
     * <p>2. 请求体中的 {@code executionId} 表示错误样本来源 execution，服务端会校验它确实属于该任务；</p>
     * <p>3. {@code errorSampleIds} 表示精确重放一批错误样本；如果想重放全部可重试样本，必须显式传
     * {@code replayAllRetryableInExecution=true}；</p>
     * <p>4. {@code repairConfirmed=true} 是安全闸门，用来表达操作者已经修复字段映射、目标约束、重复主键、
     * 数据格式等根因，不允许“还没修就盲目重跑”。</p>
     *
     * <p>权限边界：该接口应由 gateway/permission-admin 标记为 {@code SYNC_TASK + REPLAY_DIRTY_RECORDS}。
     * 普通查询错误样本是 VIEW，修复重放会创建新的 replay execution，属于写操作和高影响恢复动作，必须单独授权和审计。</p>
     *
     * <p>低敏边界：响应只返回新 executionId、recoveryPlanId、样本数量和 selector 模式，不返回错误样本原文、
     * SQL、连接串、凭据、where 条件、字段映射正文或 worker 内部参数。</p>
     */
    @PostMapping("/errors/replay")
    public PlatformApiResponse<SyncDirtyRecordReplayResult> replayDirtyRecords(
            @PathVariable Long taskId,
            @RequestBody(required = false) SyncDirtyRecordReplayRequest request,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long tenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId,
            @RequestHeader HttpHeaders headers) {
        return PlatformApiResponse.success(dataSyncService.replayDirtyRecords(
                taskId, request, actorContext(tenantId, actorId, actorRole, traceId, headers)), traceId);
    }

    /**
     * 查询某个同步任务的审计记录。
     */
    @GetMapping("/audit")
    public PlatformApiResponse<PlatformPageResponse<SyncAuditRecord>> pageAuditRecords(
            @PathVariable Long taskId,
            @RequestParam(required = false) Long executionId,
            @RequestParam(required = false) String actionType,
            @RequestParam(required = false) Long actorIdFilter,
            @RequestParam(defaultValue = "1") Long current,
            @RequestParam(defaultValue = "20") Long size,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long tenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId,
            @RequestHeader HttpHeaders headers) {
        SyncAuditQueryCriteria criteria = new SyncAuditQueryCriteria(taskId, executionId, actionType, actorIdFilter, current, size);
        return PlatformApiResponse.success(dataSyncService.pageAuditRecords(
                criteria, actorContext(tenantId, actorId, actorRole, traceId, headers)), traceId);
    }

    private SyncActorContext actorContext(Long tenantId, Long actorId, String actorRole, String traceId, HttpHeaders headers) {
        return SyncActorContextHeaderSupport.fromHeaders(tenantId, actorId, actorRole, traceId, headers);
    }
}
