/**
 * @Author : Cui
 * @Date: 2026/07/08 00:01
 * @Description DataSmart Govern Backend - SyncDiscoveredObjectFanOutDispatchService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExecutionFailRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncWorkerExecutionPlanView;
import com.czh.datasmart.govern.datasync.entity.SyncExecution;
import com.czh.datasmart.govern.datasync.entity.SyncTask;
import com.czh.datasmart.govern.datasync.entity.SyncTemplate;
import com.czh.datasmart.govern.datasync.integration.datasource.metadata.DatasourceMetadataDiscoveryClient;
import com.czh.datasmart.govern.datasync.integration.datasource.metadata.DatasourceMetadataDiscoveryRequest;
import com.czh.datasmart.govern.datasync.integration.datasource.metadata.DatasourceMetadataDiscoveryResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * SCHEMA_FULL / DATABASE_FULL 元数据发现 fan-out 调度器。
 *
 * <p>该组件补齐的是“全 schema/全库搬迁从控制面建模走到真实执行”的关键链路。它不直接读写业务数据，
 * 也不自己连接源库；它只做三件事：</p>
 * <p>1. 调用 datasource-management 的元数据发现能力，拿到源端表和字段摘要；</p>
 * <p>2. 将发现结果转换成临时 OBJECT_LIST 配置，每张表变成一个可恢复对象级执行单元；</p>
 * <p>3. 把临时模板交给已有 {@link SyncObjectListFanOutDispatchService}，复用对象级账本、失败重试和父 execution 汇总。</p>
 *
 * <p>为什么不为 SCHEMA_FULL/DATABASE_FULL 另做一套执行器：全库迁移本质上是“很多表的同步任务集合”。
 * 如果另起状态机，就会重复实现对象级成功/失败、选择性重试、父任务部分成功、receipt 回写和审计。复用 OBJECT_LIST
 * 更符合 DataX Job 拆 Task 的思想，也能让后续 UI 统一展示“哪些表成功、哪些表失败”。</p>
 */
@Component
@RequiredArgsConstructor
public class SyncDiscoveredObjectFanOutDispatchService {

    private static final Set<String> SUPPORTED_SCOPES = Set.of("SCHEMA_FULL", "DATABASE_FULL");
    private static final Set<String> SUPPORTED_SYNC_MODES = Set.of("FULL", "ONE_TIME_MIGRATION", "SCHEDULED_BATCH");
    private static final String OBJECT_LIST = "OBJECT_LIST";
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,127}");
    private static final int DEFAULT_MAX_DISCOVERED_OBJECTS = 200;
    private static final int ABSOLUTE_MAX_DISCOVERED_OBJECTS = 1000;
    private static final int DEFAULT_MAX_COLUMNS_PER_TABLE = 500;

    private final DatasourceMetadataDiscoveryClient metadataDiscoveryClient;
    private final SyncObjectListFanOutDispatchService objectListFanOutDispatchService;
    private final SyncExecutionLifecycleSupport lifecycleSupport;
    private final DataSyncTaskManagementReceiptPublisher receiptPublisher;
    private final ObjectMapper objectMapper;

    /**
     * 判断当前合同是否应由“元数据发现 -> 对象列表”路径接管。
     */
    public boolean supports(SyncOfflineRunnerJobContract contract, SyncActorContext actorContext) {
        if (contract == null) {
            return false;
        }
        return contract.offlineChannel()
                && SUPPORTED_SCOPES.contains(normalize(contract.syncScopeType()))
                && SUPPORTED_SYNC_MODES.contains(normalize(contract.syncMode()))
                && !contract.checkpointRequired()
                && !Boolean.TRUE.equals(actorContext == null ? null : actorContext.approvalRequired());
    }

    /**
     * 发现源端对象并派发到 OBJECT_LIST fan-out。
     */
    public SyncOfflineRunnerDispatchResult dispatchDiscoveredObjects(SyncExecution execution,
                                                                     SyncTask task,
                                                                     SyncTemplate template,
                                                                     SyncWorkerExecutionPlanView workerPlan,
                                                                     SyncActorContext actorContext,
                                                                     SyncOfflineRunnerJobContract parentContract) {
        DiscoveryPolicy policy = parseDiscoveryPolicy(template);
        DatasourceMetadataDiscoveryResponse discoveryResponse = metadataDiscoveryClient.discover(
                template.getSourceDatasourceId(),
                discoveryRequest(template, actorContext, policy),
                actorContext);
        List<DatasourceMetadataDiscoveryResponse.TableSummary> tables = filterTables(discoveryResponse, policy);
        if (tables.isEmpty()) {
            return failDiscoveryFanOut(task, execution, actorContext, parentContract,
                    "DISCOVERY_OBJECT_LIST_EMPTY",
                    "元数据发现未得到可执行表清单，SCHEMA_FULL/DATABASE_FULL 未触发真实读写",
                    List.of("DISCOVERY_OBJECT_LIST_EMPTY"));
        }
        try {
            SyncTemplate objectListTemplate = objectListTemplate(template, tables, policy);
            SyncWorkerExecutionPlanView objectListWorkerPlan = objectListWorkerPlan(workerPlan, tables.size());
            return objectListFanOutDispatchService.dispatchObjectList(execution, task, objectListTemplate,
                    objectListWorkerPlan, actorContext, parentContract);
        } catch (Exception exception) {
            return failDiscoveryFanOut(task, execution, actorContext, parentContract,
                    "DISCOVERY_OBJECT_LIST_BUILD_FAILED",
                    "元数据发现结果无法转换为 OBJECT_LIST 执行配置，已按 fail-closed 终止",
                    List.of("DISCOVERY_OBJECT_LIST_BUILD_FAILED"));
        }
    }

    private DatasourceMetadataDiscoveryRequest discoveryRequest(SyncTemplate template,
                                                               SyncActorContext actorContext,
                                                               DiscoveryPolicy policy) {
        DatasourceMetadataDiscoveryRequest request = new DatasourceMetadataDiscoveryRequest();
        request.setActorId(actorContext == null || actorContext.actorId() == null ? 0L : actorContext.actorId());
        request.setActorRole(actorContext == null || actorContext.actorRole() == null ? "SERVICE_ACCOUNT" : actorContext.actorRole());
        request.setActorTenantId(actorContext == null || actorContext.tenantId() == null
                ? template.getTenantId()
                : actorContext.tenantId());
        request.setCatalog(policy.catalog());
        request.setSchemaPattern(firstText(policy.schemaPattern(), template.getSourceSchemaName()));
        request.setTableNamePattern(policy.tableNamePattern());
        request.setMaxTables(policy.maxObjects());
        request.setMaxColumnsPerTable(DEFAULT_MAX_COLUMNS_PER_TABLE);
        request.setIncludeColumns(true);
        request.setIncludeViews(policy.includeViews());
        request.setIncludePrimaryKeys(true);
        request.setIncludeIndexes(false);
        request.setIncludeSampleRows(false);
        request.setSampleRowLimit(1);
        return request;
    }

    private List<DatasourceMetadataDiscoveryResponse.TableSummary> filterTables(
            DatasourceMetadataDiscoveryResponse response,
            DiscoveryPolicy policy) {
        if (response == null || response.getTables() == null) {
            return List.of();
        }
        List<DatasourceMetadataDiscoveryResponse.TableSummary> tables = new ArrayList<>();
        for (DatasourceMetadataDiscoveryResponse.TableSummary table : response.getTables()) {
            if (table == null || !safeIdentifier(table.getTableName())) {
                continue;
            }
            if (hasText(table.getSchemaName()) && !safeIdentifier(table.getSchemaName())) {
                continue;
            }
            if (!policy.includeViews() && "VIEW".equalsIgnoreCase(table.getTableType())) {
                continue;
            }
            if (!matchesAny(policy.includePatterns(), table.getTableName(), true)) {
                continue;
            }
            if (matchesAny(policy.excludeObjects(), table.getTableName(), false)) {
                continue;
            }
            tables.add(table);
            if (tables.size() >= policy.maxObjects()) {
                break;
            }
        }
        return List.copyOf(tables);
    }

    private SyncTemplate objectListTemplate(SyncTemplate template,
                                            List<DatasourceMetadataDiscoveryResponse.TableSummary> tables,
                                            DiscoveryPolicy policy) throws Exception {
        SyncTemplate child = copyTemplate(template);
        child.setSyncScopeType(OBJECT_LIST);
        child.setObjectMappingConfig(objectMappingConfig(template, tables, policy));
        child.setFieldMappingConfig(firstText(template.getFieldMappingConfig(), "[]"));
        return child;
    }

    private String objectMappingConfig(SyncTemplate template,
                                       List<DatasourceMetadataDiscoveryResponse.TableSummary> tables,
                                       DiscoveryPolicy policy) throws Exception {
        List<Map<String, Object>> mappings = new ArrayList<>();
        for (DatasourceMetadataDiscoveryResponse.TableSummary table : tables) {
            Map<String, Object> mapping = new LinkedHashMap<>();
            mapping.put("sourceSchema", firstText(table.getSchemaName(), template.getSourceSchemaName()));
            mapping.put("sourceObject", table.getTableName());
            mapping.put("targetSchema", firstText(template.getTargetSchemaName(), table.getSchemaName()));
            mapping.put("targetObject", targetObjectName(table.getTableName(), policy));
            mapping.put("fieldMappings", fieldMappings(table));
            mappings.add(mapping);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("mappings", mappings);
        root.put("generatedBy", "SCHEMA_DATABASE_METADATA_DISCOVERY_FAN_OUT");
        root.put("payloadPolicy", "INTERNAL_OBJECT_MAPPING_NO_ROWS_NO_CREDENTIALS");
        return objectMapper.writeValueAsString(root);
    }

    private List<Map<String, String>> fieldMappings(DatasourceMetadataDiscoveryResponse.TableSummary table) {
        if (table.getColumns() == null || table.getColumns().isEmpty()) {
            return List.of();
        }
        List<Map<String, String>> mappings = new ArrayList<>();
        for (DatasourceMetadataDiscoveryResponse.ColumnSummary column : table.getColumns()) {
            if (column == null || !safeIdentifier(column.getColumnName())) {
                continue;
            }
            Map<String, String> mapping = new LinkedHashMap<>();
            mapping.put("sourceField", column.getColumnName());
            mapping.put("targetField", column.getColumnName());
            mappings.add(mapping);
        }
        return mappings;
    }

    private String targetObjectName(String sourceTableName, DiscoveryPolicy policy) {
        return firstText(policy.targetPrefix(), "") + sourceTableName + firstText(policy.targetSuffix(), "");
    }

    private SyncWorkerExecutionPlanView objectListWorkerPlan(SyncWorkerExecutionPlanView workerPlan, int selectedObjectCount) {
        return new SyncWorkerExecutionPlanView(
                true,
                "READY_WITH_DISCOVERED_OBJECTS",
                workerPlan.tenantId(),
                workerPlan.projectId(),
                workerPlan.workspaceId(),
                workerPlan.syncTaskId(),
                workerPlan.executionId(),
                workerPlan.executionNo(),
                workerPlan.executionState(),
                workerPlan.triggerType(),
                workerPlan.executorId(),
                workerPlan.leaseExpireTime(),
                workerPlan.templateId(),
                workerPlan.sourceDatasourceId(),
                workerPlan.targetDatasourceId(),
                workerPlan.sourceConnectorType(),
                workerPlan.targetConnectorType(),
                workerPlan.syncMode(),
                workerPlan.transferChannel(),
                workerPlan.referenceRuntime(),
                OBJECT_LIST,
                false,
                true,
                false,
                selectedObjectCount,
                workerPlan.requiresApproval(),
                false,
                true,
                true,
                workerPlan.writeStrategy(),
                workerPlan.writeStrategyRequiresConflictKey(),
                workerPlan.primaryKeyDeclared(),
                workerPlan.incrementalFieldDeclared(),
                workerPlan.connectorCompatibilitySupported(),
                workerPlan.consistencyGoal(),
                false,
                workerPlan.retryPattern(),
                true,
                true,
                workerPlan.customSqlDeclared(),
                workerPlan.filterDeclared(),
                workerPlan.partitionDeclared(),
                workerPlan.retryPolicyDeclared(),
                workerPlan.timeoutPolicyDeclared(),
                sanitizedParentIssueCodes(workerPlan.issueCodes()),
                List.of("DISCOVER_SCHEMA_OR_DATABASE_OBJECTS", "DISPATCH_DISCOVERED_OBJECT_LIST_TO_RUN_ONCE"),
                workerPlan.performanceNotes(),
                mergeIssueCodes(workerPlan.safetyNotes(), List.of("SCHEMA_DATABASE_DISCOVERY_CONVERTED_TO_OBJECT_LIST")),
                workerPlan.payloadPolicy()
        );
    }

    private List<String> sanitizedParentIssueCodes(List<String> issueCodes) {
        if (issueCodes == null || issueCodes.isEmpty()) {
            return List.of();
        }
        return issueCodes.stream()
                .filter(issueCode -> !"SCOPE_NOT_EXECUTABLE_BY_MINIMAL_RUN_ONCE_BRIDGE".equals(issueCode))
                .filter(issueCode -> !"FIELD_MAPPING_NOT_DECLARED".equals(issueCode))
                .filter(issueCode -> !"SCHEMA_FULL_REQUIRES_SCHEMA_PAIR".equals(issueCode))
                .filter(issueCode -> !"DATABASE_FULL_REQUIRES_DISCOVERY_POLICY".equals(issueCode))
                .toList();
    }

    private SyncOfflineRunnerDispatchResult failDiscoveryFanOut(SyncTask task,
                                                                SyncExecution execution,
                                                                SyncActorContext actorContext,
                                                                SyncOfflineRunnerJobContract parentContract,
                                                                String errorCode,
                                                                String errorMessage,
                                                                List<String> issueCodes) {
        SyncExecutionFailRequest request = new SyncExecutionFailRequest();
        request.setExecutorId(execution.getExecutorId());
        request.setErrorType("SCHEMA_DATABASE_DISCOVERY_FAN_OUT_FAILED");
        request.setErrorCode(errorCode);
        request.setErrorMessage(errorMessage);
        request.setRetryable(false);
        request.setFailedRecordCount(1L);
        request.setIdempotencyKey("discovered-object-fan-out-fail-" + execution.getId() + "-" + errorCode);
        lifecycleSupport.failExecution(task, execution, request, actorContext);
        receiptPublisher.publishFailed(task, execution, actorContext, errorCode, issueCodes);
        return new SyncOfflineRunnerDispatchResult(
                false,
                false,
                true,
                "DISCOVERY_OBJECT_FAN_OUT_FAILED",
                execution.getId(),
                null,
                parentContract == null ? "SCHEMA_DATABASE_DISCOVERY_FAN_OUT" : parentContract.contractStatus(),
                mergeIssueCodes(issueCodes, parentContract == null ? List.of() : parentContract.issueCodes()),
                SyncOfflineRunnerDispatchResult.PAYLOAD_POLICY
        );
    }

    private DiscoveryPolicy parseDiscoveryPolicy(SyncTemplate template) {
        if (!hasText(template.getObjectMappingConfig())) {
            return DiscoveryPolicy.defaultPolicy();
        }
        try {
            JsonNode root = objectMapper.readTree(template.getObjectMappingConfig());
            JsonNode policyNode = root.path("discoveryPolicy");
            JsonNode source = policyNode.isMissingNode() ? root : policyNode;
            return new DiscoveryPolicy(
                    text(source, "catalog"),
                    firstText(text(source, "schemaPattern"), template.getSourceSchemaName()),
                    text(source, "tableNamePattern"),
                    bounded(firstInt(source, "maxObjects", "maxTables"), DEFAULT_MAX_DISCOVERED_OBJECTS, ABSOLUTE_MAX_DISCOVERED_OBJECTS),
                    stringList(firstNode(source, "includePatterns", "includeObjects")),
                    stringList(firstNode(source, "excludeObjects", "excludePatterns")),
                    booleanValue(source, "includeViews", false),
                    text(source, "targetNamePrefix"),
                    text(source, "targetNameSuffix")
            );
        } catch (Exception exception) {
            return DiscoveryPolicy.defaultPolicy();
        }
    }

    private SyncTemplate copyTemplate(SyncTemplate template) {
        SyncTemplate copy = new SyncTemplate();
        copy.setId(template.getId());
        copy.setTenantId(template.getTenantId());
        copy.setProjectId(template.getProjectId());
        copy.setWorkspaceId(template.getWorkspaceId());
        copy.setName(template.getName());
        copy.setDescription(template.getDescription());
        copy.setSourceDatasourceId(template.getSourceDatasourceId());
        copy.setTargetDatasourceId(template.getTargetDatasourceId());
        copy.setSourceSchemaName(template.getSourceSchemaName());
        copy.setSourceObjectName(template.getSourceObjectName());
        copy.setTargetSchemaName(template.getTargetSchemaName());
        copy.setTargetObjectName(template.getTargetObjectName());
        copy.setSourceConnectorType(template.getSourceConnectorType());
        copy.setTargetConnectorType(template.getTargetConnectorType());
        copy.setSyncMode(template.getSyncMode());
        copy.setSyncScopeType(template.getSyncScopeType());
        copy.setWriteStrategy(template.getWriteStrategy());
        copy.setPrimaryKeyField(template.getPrimaryKeyField());
        copy.setIncrementalField(template.getIncrementalField());
        copy.setFieldMappingConfig(template.getFieldMappingConfig());
        copy.setObjectMappingConfig(template.getObjectMappingConfig());
        copy.setFilterConfig(template.getFilterConfig());
        copy.setCustomSqlConfig(template.getCustomSqlConfig());
        copy.setPartitionConfig(template.getPartitionConfig());
        copy.setRetryPolicy(template.getRetryPolicy());
        copy.setTimeoutPolicy(template.getTimeoutPolicy());
        copy.setEnabled(template.getEnabled());
        copy.setCreatedBy(template.getCreatedBy());
        copy.setUpdatedBy(template.getUpdatedBy());
        copy.setCreateTime(template.getCreateTime());
        copy.setUpdateTime(template.getUpdateTime());
        return copy;
    }

    private boolean matchesAny(List<String> patterns, String value, boolean emptyMeansMatch) {
        if (patterns == null || patterns.isEmpty()) {
            return emptyMeansMatch;
        }
        return patterns.stream().anyMatch(pattern -> globMatches(pattern, value));
    }

    private boolean globMatches(String pattern, String value) {
        if (!hasText(pattern) || !hasText(value)) {
            return false;
        }
        String regex = pattern.trim().replace(".", "\\.").replace("*", ".*").replace("?", ".");
        return value.matches("(?i)" + regex);
    }

    private List<String> stringList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            if (item != null && item.isTextual() && hasText(item.asText())) {
                values.add(item.asText().trim());
            }
        }
        return List.copyOf(values);
    }

    private JsonNode firstNode(JsonNode node, String... fieldNames) {
        for (String fieldName : fieldNames) {
            JsonNode value = node.get(fieldName);
            if (value != null && !value.isNull()) {
                return value;
            }
        }
        return null;
    }

    private Integer firstInt(JsonNode node, String... fieldNames) {
        for (String fieldName : fieldNames) {
            JsonNode value = node.get(fieldName);
            if (value != null && value.canConvertToInt()) {
                return value.asInt();
            }
        }
        return null;
    }

    private int bounded(Integer value, int defaultValue, int absoluteMaxValue) {
        int actual = value == null || value <= 0 ? defaultValue : value;
        return Math.min(actual, absoluteMaxValue);
    }

    private boolean booleanValue(JsonNode node, String fieldName, boolean defaultValue) {
        JsonNode value = node.get(fieldName);
        return value == null || !value.isBoolean() ? defaultValue : value.asBoolean();
    }

    private String text(JsonNode node, String fieldName) {
        JsonNode value = node == null ? null : node.get(fieldName);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText();
        return hasText(text) ? text.trim() : null;
    }

    private List<String> mergeIssueCodes(List<String> first, List<String> second) {
        List<String> values = new ArrayList<>();
        values.addAll(first == null ? List.of() : first);
        values.addAll(second == null ? List.of() : second);
        return new ArrayList<>(new LinkedHashSet<>(values));
    }

    private String firstText(String value, String fallback) {
        return hasText(value) ? value.trim() : fallback;
    }

    private String normalize(String value) {
        return hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : null;
    }

    private boolean safeIdentifier(String value) {
        return hasText(value) && SAFE_IDENTIFIER.matcher(value.trim()).matches();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private record DiscoveryPolicy(String catalog,
                                   String schemaPattern,
                                   String tableNamePattern,
                                   int maxObjects,
                                   List<String> includePatterns,
                                   List<String> excludeObjects,
                                   boolean includeViews,
                                   String targetPrefix,
                                   String targetSuffix) {

        private DiscoveryPolicy {
            includePatterns = includePatterns == null ? List.of() : List.copyOf(includePatterns);
            excludeObjects = excludeObjects == null ? List.of() : List.copyOf(excludeObjects);
        }

        private static DiscoveryPolicy defaultPolicy() {
            return new DiscoveryPolicy(null, null, null, DEFAULT_MAX_DISCOVERED_OBJECTS,
                    List.of(), List.of(), false, null, null);
        }
    }
}
