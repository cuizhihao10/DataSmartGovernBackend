/**
 * @Author : Cui
 * @Date: 2026/07/07 23:28
 * @Description DataSmart Govern Backend - SyncTaskMetadataConfigurationSupport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskFieldMappingSuggestionRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskFieldMappingSuggestionResponse;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskMetadataDiscoveryRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskMetadataDiscoveryResponse;
import com.czh.datasmart.govern.datasync.integration.datasource.DatasourceCapabilitySnapshotClient;
import com.czh.datasmart.govern.datasync.integration.datasource.DatasourceCapabilitySnapshotView;
import com.czh.datasmart.govern.datasync.integration.datasource.metadata.DatasourceMetadataDiscoveryClient;
import com.czh.datasmart.govern.datasync.integration.datasource.metadata.DatasourceMetadataDiscoveryResponse;
import com.czh.datasmart.govern.datasync.support.SyncAuditActionType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 同步任务创建阶段的元数据配置支撑组件。
 *
 * <p>该组件解决的是“用户创建同步任务时如何自动拿到可选对象和字段映射建议”的问题。它刻意放在
 * data-sync 的 service/support 层，而不是 controller 层，原因有三点：</p>
 * <p>1. 前端看到的是“按 schema 筛选、按表筛选、按 schema+表筛选、字段是否同步”等同步配置语义，
 * 不是 datasource-management 的裸元数据接口；这些产品语义需要在 data-sync 内统一解释。</p>
 * <p>2. data-sync 不应该直接持有 JDBC 连接、账号密码或连接池，真实元数据读取必须继续由
 * datasource-management 完成，本组件只消费低敏 schema/table/column 摘要。</p>
 * <p>3. MySQL、PostgreSQL、Oracle、SQL Server 等连接器在 catalog/schema/table 概念上并不一致，
 * 如果把差异丢给前端，每个页面和 Agent 工具都会重复写一套判断；集中在这里可以保证体验和审计一致。</p>
 *
 * <p>安全边界说明：本组件不会返回样本数据、连接串、账号、密码、完整 SQL 或 datasource-management
 * 内部 endpoint；字段映射建议也只是“默认勾选建议”，最终是否同步仍由用户或 Agent 在高风险操作前确认。</p>
 */
@Component
@RequiredArgsConstructor
public class SyncTaskMetadataConfigurationSupport {

    /**
     * 创建任务页面一次最多展示的默认表数量。
     *
     * <p>元数据发现属于交互式接口，不适合一次把大型数据库几万张表全部拉到前端。默认值偏保守，
     * 未来如果要支持超大库，应增加分页、关键字搜索和缓存，而不是单次放大该值。</p>
     */
    private static final int DEFAULT_MAX_TABLES = 200;

    /**
     * 单张表默认最多返回的字段数。
     *
     * <p>大宽表可能有数百甚至上千列。创建任务时通常只需要先展示字段选择和映射建议，
     * 因此这里限制单表字段数量，避免前端表格和网关响应体被异常宽表拖垮。</p>
     */
    private static final int DEFAULT_MAX_COLUMNS = 200;

    /*
     * 这里必须和 datasource-management 的 MetadataDiscoveryRequest 上限保持一致。
     *
     * data-sync 是“创建任务向导”的产品语义层，datasource-management 是“真实连接与元数据读取”的能力层。
     * 如果 data-sync 允许 500，而下游 DTO @Max 只允许 200，用户在前端只会看到一次下游 400，
     * 很难理解到底是哪个模块限制了元数据扫描规模。因此这里在进入远端调用前就收口到 200，
     * 让调用合同在两个微服务之间保持一致。
     */
    private static final int HARD_MAX_TABLES = 200;
    private static final int HARD_MAX_COLUMNS = 500;

    private static final Set<String> SUPPORTED_FILTER_MODES = Set.of(
            "ALL", "TABLE", "SCHEMA", "SCHEMA_AND_TABLE", "CATALOG"
    );

    private final DatasourceCapabilitySnapshotClient capabilitySnapshotClient;
    private final DatasourceMetadataDiscoveryClient metadataDiscoveryClient;
    private final SyncDataScopeSupport dataScopeSupport;
    private final SyncAuditSupport auditSupport;

    /**
     * 发现创建任务时可选择的 schema/table/field。
     *
     * <p>业务流程：</p>
     * <p>1. 读取 datasource-management 的能力快照，用于补全 connectorType 并做租户级可见性收口；</p>
     * <p>2. 解析前端选择的筛选模式，并根据连接器差异决定是否可以继续发现；</p>
     * <p>3. 构造 datasource-management 的低敏元数据发现请求，强制关闭 sampleRows；</p>
     * <p>4. 将远端响应转换为 data-sync 创建任务页面更易消费的对象列表和 schema 列表。</p>
     */
    public SyncTaskMetadataDiscoveryResponse discoverTaskMetadata(SyncTaskMetadataDiscoveryRequest request,
                                                                  SyncActorContext actorContext) {
        if (request == null || request.getDatasourceId() == null) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR, "元数据发现请求缺少 datasourceId");
        }
        DatasourceCapabilitySnapshotView snapshot = loadSnapshot(request.getDatasourceId(), actorContext);
        String connectorType = resolveConnectorType(request.getConnectorType(), snapshot);
        String filterMode = normalizeFilterMode(request.getFilterMode());
        List<String> warnings = new ArrayList<>();

        if (isMysqlFamily(connectorType) && requiresSchemaSemantics(filterMode)) {
            warnings.add("当前数据源连接器为 " + connectorType
                    + "，MySQL/MariaDB 使用 database/catalog/table 语义，不具备 PostgreSQL 风格 schema；"
                    + "请选择 TABLE 或 CATALOG 模式后再筛选表。");
            auditSupport.saveAudit(snapshot.getTenantId(), null, null, SyncAuditActionType.DISCOVER_TASK_METADATA,
                    actorContext, "datasourceId=" + request.getDatasourceId()
                            + ",connectorType=" + connectorType
                            + ",filterMode=" + filterMode
                            + ",result=UNSUPPORTED_SCHEMA_FILTER");
            return emptyDiscoveryResponse(request, connectorType, filterMode, warnings);
        }

        com.czh.datasmart.govern.datasync.integration.datasource.metadata.DatasourceMetadataDiscoveryRequest remoteRequest =
                buildRemoteDiscoveryRequest(request, connectorType, filterMode, actorContext, warnings);
        DatasourceMetadataDiscoveryResponse remoteResponse =
                metadataDiscoveryClient.discover(request.getDatasourceId(), remoteRequest, actorContext);
        auditSupport.saveAudit(snapshot.getTenantId(), null, null, SyncAuditActionType.DISCOVER_TASK_METADATA,
                actorContext, "datasourceId=" + request.getDatasourceId()
                        + ",connectorType=" + connectorType
                        + ",filterMode=" + filterMode
                        + ",includeColumns=" + remoteRequest.getIncludeColumns());
        return toDiscoveryResponse(request, connectorType, filterMode, remoteResponse, warnings);
    }

    /**
     * 为源表和目标表生成字段映射建议。
     *
     * <p>该方法不是模板创建接口，也不会写入任务定义。它只在“用户已经选择源表和目标表”之后，
     * 帮助前端生成一份可编辑的初始映射清单。默认策略保持保守：同名字段存在且类型家族兼容才勾选同步；
     * 不匹配或不兼容的字段返回 warnings，让用户或 Agent 进一步确认。</p>
     */
    public SyncTaskFieldMappingSuggestionResponse suggestFieldMappings(SyncTaskFieldMappingSuggestionRequest request,
                                                                       SyncActorContext actorContext) {
        if (request == null || request.getSourceDatasourceId() == null || request.getTargetDatasourceId() == null) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR,
                    "字段映射建议请求缺少源端或目标端 datasourceId");
        }
        String sourceTableName = requireText(request.getSourceTable(), "源端表名不能为空");
        String targetTableName = requireText(request.getTargetTable(), "目标端表名不能为空");
        DatasourceCapabilitySnapshotView sourceSnapshot = loadSnapshot(request.getSourceDatasourceId(), actorContext);
        DatasourceCapabilitySnapshotView targetSnapshot = loadSnapshot(request.getTargetDatasourceId(), actorContext);
        String sourceConnectorType = resolveConnectorType(request.getSourceConnectorType(), sourceSnapshot);
        String targetConnectorType = resolveConnectorType(request.getTargetConnectorType(), targetSnapshot);

        DatasourceMetadataDiscoveryResponse.TableSummary sourceTable = discoverSingleTable(
                request.getSourceDatasourceId(),
                sourceConnectorType,
                request.getSourceCatalog(),
                request.getSourceSchema(),
                sourceTableName,
                normalizeColumnLimit(request.getMaxColumnsPerTable()),
                actorContext);
        DatasourceMetadataDiscoveryResponse.TableSummary targetTable = discoverSingleTable(
                request.getTargetDatasourceId(),
                targetConnectorType,
                request.getTargetCatalog(),
                request.getTargetSchema(),
                targetTableName,
                normalizeColumnLimit(request.getMaxColumnsPerTable()),
                actorContext);

        List<String> warnings = new ArrayList<>();
        List<SyncTaskFieldMappingSuggestionResponse.FieldMappingItem> mappings =
                buildFieldMappings(sourceTable, targetTable, warnings);
        auditSupport.saveAudit(sourceSnapshot.getTenantId(), null, null, SyncAuditActionType.SUGGEST_FIELD_MAPPINGS,
                actorContext, "sourceDatasourceId=" + request.getSourceDatasourceId()
                        + ",targetDatasourceId=" + request.getTargetDatasourceId()
                        + ",sourceConnectorType=" + sourceConnectorType
                        + ",targetConnectorType=" + targetConnectorType
                        + ",sourceTable=" + sourceTableName
                        + ",targetTable=" + targetTableName
                        + ",mappingCount=" + mappings.size());

        SyncTaskFieldMappingSuggestionResponse response = new SyncTaskFieldMappingSuggestionResponse();
        response.setSourceDatasourceId(request.getSourceDatasourceId());
        response.setTargetDatasourceId(request.getTargetDatasourceId());
        response.setSourceConnectorType(sourceConnectorType);
        response.setTargetConnectorType(targetConnectorType);
        response.setSourceTable(sourceTableName);
        response.setTargetTable(targetTableName);
        response.setMappings(mappings);
        response.setWarnings(warnings);
        return response;
    }

    private DatasourceCapabilitySnapshotView loadSnapshot(Long datasourceId, SyncActorContext actorContext) {
        DatasourceCapabilitySnapshotView snapshot = capabilitySnapshotClient.getSnapshot(datasourceId, actorContext);
        if (snapshot == null) {
            throw new PlatformBusinessException(PlatformErrorCode.EXTERNAL_DEPENDENCY_FAILED,
                    "数据源能力快照为空，无法进行同步任务元数据配置，datasourceId=" + datasourceId);
        }
        dataScopeSupport.validateTenantReadable(snapshot.getTenantId(), actorContext);
        return snapshot;
    }

    private String resolveConnectorType(String requestedConnectorType, DatasourceCapabilitySnapshotView snapshot) {
        String connectorType = trimToNull(requestedConnectorType);
        if (connectorType == null && snapshot != null) {
            connectorType = trimToNull(snapshot.getConnectorType());
        }
        return connectorType == null ? "UNKNOWN" : connectorType.toUpperCase(Locale.ROOT);
    }

    private String normalizeFilterMode(String rawFilterMode) {
        String mode = trimToNull(rawFilterMode);
        if (mode == null) {
            return "ALL";
        }
        String normalized = mode.toUpperCase(Locale.ROOT).replace('-', '_');
        if ("BY_TABLE".equals(normalized) || "TABLE_ONLY".equals(normalized)) {
            normalized = "TABLE";
        } else if ("BY_SCHEMA".equals(normalized) || "SCHEMA_ONLY".equals(normalized)) {
            normalized = "SCHEMA";
        } else if ("BY_SCHEMA_AND_TABLE".equals(normalized) || "SCHEMA_TABLE".equals(normalized)) {
            normalized = "SCHEMA_AND_TABLE";
        }
        if (!SUPPORTED_FILTER_MODES.contains(normalized)) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR,
                    "不支持的元数据筛选模式: " + rawFilterMode
                            + "，当前支持 ALL/TABLE/SCHEMA/SCHEMA_AND_TABLE/CATALOG");
        }
        return normalized;
    }

    private boolean requiresSchemaSemantics(String filterMode) {
        return "SCHEMA".equals(filterMode) || "SCHEMA_AND_TABLE".equals(filterMode);
    }

    private boolean isMysqlFamily(String connectorType) {
        String normalized = connectorType == null ? "" : connectorType.toUpperCase(Locale.ROOT);
        return normalized.contains("MYSQL") || normalized.contains("MARIADB");
    }

    private com.czh.datasmart.govern.datasync.integration.datasource.metadata.DatasourceMetadataDiscoveryRequest
    buildRemoteDiscoveryRequest(SyncTaskMetadataDiscoveryRequest request,
                                String connectorType,
                                String filterMode,
                                SyncActorContext actorContext,
                                List<String> warnings) {
        com.czh.datasmart.govern.datasync.integration.datasource.metadata.DatasourceMetadataDiscoveryRequest remote =
                new com.czh.datasmart.govern.datasync.integration.datasource.metadata.DatasourceMetadataDiscoveryRequest();
        /*
         * datasource-management 的元数据发现接口历史上要求 actorId、actorRole、actorTenantId 放在请求体里，
         * 而不是只读 X-DataSmart-* Header。创建向导本轮接入自动元数据发现后，data-sync 之前只传了服务账号 Header，
         * 导致下游 @Valid 返回 400，data-sync 又因为缺少统一异常处理把它漏成 500。
         *
         * 这里明确把“代表用户发起创建向导元数据发现”的低敏主体写进请求体：
         * - actorId/actorRole/actorTenantId 用于 datasource-management 审计和权限语义；
         * - Header 仍然用于服务间调用来源识别、traceId 串联和未来 mTLS/HMAC 强化；
         * - 两者不是重复，而是分别服务“业务责任人”和“机器调用链路”。
         */
        applyActorContext(remote, actorContext);
        remote.setCatalog(trimToNull(request.getCatalog()));
        if (isMysqlFamily(connectorType) && trimToNull(request.getSchemaPattern()) != null) {
            warnings.add("MySQL/MariaDB 不使用 PostgreSQL 风格 schemaPattern，本次发现已忽略 schemaPattern。");
            remote.setSchemaPattern(null);
        } else if ("SCHEMA".equals(filterMode) || "SCHEMA_AND_TABLE".equals(filterMode) || "ALL".equals(filterMode)) {
            remote.setSchemaPattern(trimToNull(request.getSchemaPattern()));
        }
        if ("TABLE".equals(filterMode) || "SCHEMA_AND_TABLE".equals(filterMode) || "ALL".equals(filterMode)) {
            remote.setTableNamePattern(trimToNull(request.getTableNamePattern()));
        }
        remote.setMaxTables(normalizeTableLimit(request.getMaxTables()));
        remote.setMaxColumnsPerTable(normalizeColumnLimit(request.getMaxColumnsPerTable()));
        remote.setIncludeColumns(Boolean.TRUE.equals(request.getIncludeColumns()));
        remote.setIncludeViews(Boolean.TRUE.equals(request.getIncludeViews()));
        remote.setIncludePrimaryKeys(Boolean.TRUE);
        remote.setIncludeIndexes(Boolean.FALSE);
        remote.setIncludeSampleRows(Boolean.FALSE);
        /*
         * 下游 DTO 对 sampleRowLimit 有 @Min(1) 约束。includeSampleRows=false 时继续传 0 会触发无意义的
         * 参数校验失败；正确表达方式是“不需要样本行，因此不传 sampleRowLimit”。
         * 这也符合低敏原则：创建同步任务只需要 schema/table/field 摘要，不应该在第一步拉取业务样本值。
         */
        remote.setSampleRowLimit(null);
        return remote;
    }

    private SyncTaskMetadataDiscoveryResponse emptyDiscoveryResponse(SyncTaskMetadataDiscoveryRequest request,
                                                                    String connectorType,
                                                                    String filterMode,
                                                                    List<String> warnings) {
        SyncTaskMetadataDiscoveryResponse response = new SyncTaskMetadataDiscoveryResponse();
        response.setDatasourceId(request.getDatasourceId());
        response.setSide(normalizeSide(request.getSide()));
        response.setConnectorType(connectorType);
        response.setFilterMode(filterMode);
        response.setDiscoverable(true);
        response.setSchemas(List.of());
        response.setTables(List.of());
        response.setWarnings(warnings);
        return response;
    }

    private SyncTaskMetadataDiscoveryResponse toDiscoveryResponse(SyncTaskMetadataDiscoveryRequest request,
                                                                  String connectorType,
                                                                  String filterMode,
                                                                  DatasourceMetadataDiscoveryResponse remoteResponse,
                                                                  List<String> localWarnings) {
        SyncTaskMetadataDiscoveryResponse response = new SyncTaskMetadataDiscoveryResponse();
        response.setDatasourceId(request.getDatasourceId());
        response.setSide(normalizeSide(request.getSide()));
        response.setConnectorType(connectorType);
        response.setFilterMode(filterMode);
        response.setDiscoverable(true);
        List<DatasourceMetadataDiscoveryResponse.TableSummary> remoteTables =
                remoteResponse == null || remoteResponse.getTables() == null ? List.of() : remoteResponse.getTables();
        response.setTables(remoteTables.stream()
                .filter(Objects::nonNull)
                .map(this::toTableObject)
                .toList());
        response.setSchemas(extractSchemas(connectorType, remoteTables));
        List<String> warnings = new ArrayList<>(localWarnings);
        if (remoteResponse != null && remoteResponse.getWarnings() != null) {
            warnings.addAll(remoteResponse.getWarnings());
        }
        response.setWarnings(warnings);
        return response;
    }

    private SyncTaskMetadataDiscoveryResponse.TableObject toTableObject(
            DatasourceMetadataDiscoveryResponse.TableSummary table) {
        SyncTaskMetadataDiscoveryResponse.TableObject object = new SyncTaskMetadataDiscoveryResponse.TableObject();
        object.setCatalog(table.getCatalog());
        object.setSchemaName(table.getSchemaName());
        object.setTableName(table.getTableName());
        object.setTableType(table.getTableType());
        object.setPrimaryKeys(table.getPrimaryKeys() == null ? List.of() : table.getPrimaryKeys());
        object.setFields(toFieldObjects(table));
        return object;
    }

    private List<SyncTaskMetadataDiscoveryResponse.FieldObject> toFieldObjects(
            DatasourceMetadataDiscoveryResponse.TableSummary table) {
        if (table.getColumns() == null) {
            return List.of();
        }
        return table.getColumns().stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(column -> column.getOrdinalPosition() == null
                        ? Integer.MAX_VALUE
                        : column.getOrdinalPosition()))
                .map(column -> {
                    SyncTaskMetadataDiscoveryResponse.FieldObject field = new SyncTaskMetadataDiscoveryResponse.FieldObject();
                    field.setFieldName(column.getColumnName());
                    field.setDataTypeName(column.getDataTypeName());
                    field.setNullable(column.isNullable());
                    field.setPrimaryKey(column.isPrimaryKey());
                    field.setOrdinalPosition(column.getOrdinalPosition());
                    field.setSyncEnabled(Boolean.TRUE);
                    return field;
                })
                .toList();
    }

    private List<String> extractSchemas(String connectorType,
                                        List<DatasourceMetadataDiscoveryResponse.TableSummary> tables) {
        if (isMysqlFamily(connectorType) || tables == null) {
            return List.of();
        }
        LinkedHashSet<String> schemas = new LinkedHashSet<>();
        for (DatasourceMetadataDiscoveryResponse.TableSummary table : tables) {
            String schema = trimToNull(table == null ? null : table.getSchemaName());
            if (schema != null) {
                schemas.add(schema);
            }
        }
        return new ArrayList<>(schemas);
    }

    private DatasourceMetadataDiscoveryResponse.TableSummary discoverSingleTable(Long datasourceId,
                                                                                 String connectorType,
                                                                                 String catalog,
                                                                                 String schema,
                                                                                 String tableName,
                                                                                 int maxColumns,
                                                                                 SyncActorContext actorContext) {
        com.czh.datasmart.govern.datasync.integration.datasource.metadata.DatasourceMetadataDiscoveryRequest remote =
                new com.czh.datasmart.govern.datasync.integration.datasource.metadata.DatasourceMetadataDiscoveryRequest();
        applyActorContext(remote, actorContext);
        remote.setCatalog(trimToNull(catalog));
        remote.setSchemaPattern(isMysqlFamily(connectorType) ? null : trimToNull(schema));
        remote.setTableNamePattern(tableName);
        remote.setIncludeColumns(Boolean.TRUE);
        remote.setIncludePrimaryKeys(Boolean.TRUE);
        remote.setIncludeViews(Boolean.TRUE);
        remote.setIncludeIndexes(Boolean.FALSE);
        remote.setIncludeSampleRows(Boolean.FALSE);
        remote.setSampleRowLimit(null);
        remote.setMaxTables(10);
        remote.setMaxColumnsPerTable(maxColumns);
        DatasourceMetadataDiscoveryResponse response = metadataDiscoveryClient.discover(datasourceId, remote, actorContext);
        List<DatasourceMetadataDiscoveryResponse.TableSummary> tables =
                response == null || response.getTables() == null ? List.of() : response.getTables();
        return tables.stream()
                .filter(table -> tableNameMatches(table, tableName, schema, connectorType))
                .findFirst()
                .orElseThrow(() -> new PlatformBusinessException(PlatformErrorCode.NOT_FOUND,
                        "未发现指定表的低敏元数据，datasourceId=" + datasourceId + ", tableName=" + tableName));
    }

    private boolean tableNameMatches(DatasourceMetadataDiscoveryResponse.TableSummary table,
                                     String requestedTableName,
                                     String requestedSchema,
                                     String connectorType) {
        if (table == null || !equalsIgnoreCase(table.getTableName(), requestedTableName)) {
            return false;
        }
        String schema = trimToNull(requestedSchema);
        return schema == null || isMysqlFamily(connectorType) || equalsIgnoreCase(table.getSchemaName(), schema);
    }

    private List<SyncTaskFieldMappingSuggestionResponse.FieldMappingItem> buildFieldMappings(
            DatasourceMetadataDiscoveryResponse.TableSummary sourceTable,
            DatasourceMetadataDiscoveryResponse.TableSummary targetTable,
            List<String> warnings) {
        List<DatasourceMetadataDiscoveryResponse.ColumnSummary> sourceColumns =
                sourceTable.getColumns() == null ? List.of() : sourceTable.getColumns();
        Map<String, DatasourceMetadataDiscoveryResponse.ColumnSummary> targetByName = new LinkedHashMap<>();
        if (targetTable.getColumns() != null) {
            for (DatasourceMetadataDiscoveryResponse.ColumnSummary targetColumn : targetTable.getColumns()) {
                String key = normalizeFieldName(targetColumn == null ? null : targetColumn.getColumnName());
                if (key != null) {
                    targetByName.putIfAbsent(key, targetColumn);
                }
            }
        }

        List<SyncTaskFieldMappingSuggestionResponse.FieldMappingItem> mappings = new ArrayList<>();
        for (DatasourceMetadataDiscoveryResponse.ColumnSummary sourceColumn : sourceColumns) {
            if (sourceColumn == null || trimToNull(sourceColumn.getColumnName()) == null) {
                continue;
            }
            DatasourceMetadataDiscoveryResponse.ColumnSummary targetColumn =
                    targetByName.get(normalizeFieldName(sourceColumn.getColumnName()));
            boolean typeCompatible = targetColumn != null
                    && typeCompatible(sourceColumn.getDataTypeName(), targetColumn.getDataTypeName());
            SyncTaskFieldMappingSuggestionResponse.FieldMappingItem item =
                    new SyncTaskFieldMappingSuggestionResponse.FieldMappingItem();
            item.setSourceField(sourceColumn.getColumnName());
            item.setSourceType(sourceColumn.getDataTypeName());
            item.setTargetField(targetColumn == null ? null : targetColumn.getColumnName());
            item.setTargetType(targetColumn == null ? null : targetColumn.getDataTypeName());
            item.setSyncEnabled(targetColumn != null && typeCompatible);
            item.setTypeCompatible(typeCompatible);
            item.setPrimaryKey(sourceColumn.isPrimaryKey());
            item.setNullable(sourceColumn.isNullable());
            item.setCompatibilityNote(compatibilityNote(sourceColumn, targetColumn, typeCompatible));
            mappings.add(item);
            appendMappingWarning(sourceColumn, targetColumn, typeCompatible, warnings);
        }
        return mappings;
    }

    private void appendMappingWarning(DatasourceMetadataDiscoveryResponse.ColumnSummary sourceColumn,
                                      DatasourceMetadataDiscoveryResponse.ColumnSummary targetColumn,
                                      boolean typeCompatible,
                                      List<String> warnings) {
        if (targetColumn == null) {
            warnings.add("源字段 " + sourceColumn.getColumnName() + " 在目标表中没有同名字段，默认不勾选同步。");
        } else if (!typeCompatible) {
            warnings.add("源字段 " + sourceColumn.getColumnName() + " 类型 " + sourceColumn.getDataTypeName()
                    + " 与目标字段 " + targetColumn.getColumnName() + " 类型 " + targetColumn.getDataTypeName()
                    + " 不属于同一兼容类型家族，默认不勾选同步。");
        }
    }

    private String compatibilityNote(DatasourceMetadataDiscoveryResponse.ColumnSummary sourceColumn,
                                     DatasourceMetadataDiscoveryResponse.ColumnSummary targetColumn,
                                     boolean typeCompatible) {
        if (targetColumn == null) {
            return "目标表缺少同名字段";
        }
        if (typeCompatible) {
            return "同名字段且类型家族兼容";
        }
        return "同名字段但类型家族不兼容，需要人工确认转换规则";
    }

    private boolean typeCompatible(String sourceType, String targetType) {
        String sourceFamily = typeFamily(sourceType);
        String targetFamily = typeFamily(targetType);
        if ("UNKNOWN".equals(sourceFamily) || "UNKNOWN".equals(targetFamily)) {
            return false;
        }
        return sourceFamily.equals(targetFamily);
    }

    private String typeFamily(String typeName) {
        String normalized = trimToNull(typeName);
        if (normalized == null) {
            return "UNKNOWN";
        }
        String type = normalized.toUpperCase(Locale.ROOT);
        if (type.contains("CHAR") || type.contains("TEXT") || type.contains("CLOB") || type.contains("STRING")
                || type.contains("ENUM")) {
            return "TEXT";
        }
        if (type.contains("INT") || type.contains("NUMBER") || type.contains("NUMERIC") || type.contains("DECIMAL")
                || type.contains("FLOAT") || type.contains("DOUBLE") || type.contains("REAL") || type.contains("SERIAL")) {
            return "NUMERIC";
        }
        if (type.contains("DATE") || type.contains("TIME")) {
            return "TEMPORAL";
        }
        if (type.contains("BOOL") || "BIT".equals(type)) {
            return "BOOLEAN";
        }
        if (type.contains("BINARY") || type.contains("BLOB") || type.contains("BYTEA")) {
            return "BINARY";
        }
        if (type.contains("JSON")) {
            return "JSON";
        }
        if (type.contains("UUID")) {
            return "UUID";
        }
        return "UNKNOWN";
    }

    private int normalizeTableLimit(Integer requestedLimit) {
        if (requestedLimit == null || requestedLimit <= 0) {
            return DEFAULT_MAX_TABLES;
        }
        return Math.min(requestedLimit, HARD_MAX_TABLES);
    }

    /**
     * 将 data-sync 的可信调用上下文转换为 datasource-management 当前元数据发现请求体所需的 actor 字段。
     *
     * <p>为什么不直接在 HTTP Header 中透传就结束：datasource-management 的旧元数据接口把 actor 字段建模在 DTO 中，
     * 并通过 Bean Validation 强制校验。为了保持两个微服务独立演进，本轮不把 datasource-management DTO 直接抽到
     * platform-common，也不临时放宽下游校验，而是在 data-sync 的适配层补齐 JSON 合同。</p>
     *
     * <p>默认值策略：</p>
     * <p>1. 如果 gateway/dev identity 已经注入真实 actor，就使用真实 actor，便于审计“谁在创建向导中探查元数据”；</p>
     * <p>2. 如果后台补偿或测试场景没有 actorId，则使用 0L + SERVICE_ACCOUNT，表达这是系统内调用，而不是匿名用户；</p>
     * <p>3. actorTenantId 必须尽力保留，缺失时使用 0L 只作为 fail-safe，避免下游 400 掩盖真正的调用链问题。</p>
     */
    private void applyActorContext(
            com.czh.datasmart.govern.datasync.integration.datasource.metadata.DatasourceMetadataDiscoveryRequest remote,
            SyncActorContext actorContext) {
        remote.setActorId(actorContext == null || actorContext.actorId() == null ? 0L : actorContext.actorId());
        remote.setActorRole(actorContext == null || trimToNull(actorContext.actorRole()) == null
                ? "SERVICE_ACCOUNT"
                : actorContext.actorRole().trim());
        remote.setActorTenantId(actorContext == null || actorContext.tenantId() == null ? 0L : actorContext.tenantId());
    }

    private int normalizeColumnLimit(Integer requestedLimit) {
        if (requestedLimit == null || requestedLimit <= 0) {
            return DEFAULT_MAX_COLUMNS;
        }
        return Math.min(requestedLimit, HARD_MAX_COLUMNS);
    }

    private String normalizeSide(String side) {
        String normalized = trimToNull(side);
        return normalized == null ? "UNKNOWN" : normalized.toUpperCase(Locale.ROOT);
    }

    private String normalizeFieldName(String fieldName) {
        String normalized = trimToNull(fieldName);
        return normalized == null ? null : normalized.toLowerCase(Locale.ROOT);
    }

    private String requireText(String value, String message) {
        String normalized = trimToNull(value);
        if (normalized == null) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR, message);
        }
        return normalized;
    }

    private boolean equalsIgnoreCase(String left, String right) {
        return left != null && right != null && left.equalsIgnoreCase(right);
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
