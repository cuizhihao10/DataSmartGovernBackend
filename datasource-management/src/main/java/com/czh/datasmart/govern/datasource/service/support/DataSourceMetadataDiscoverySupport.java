/**
 * @Author : Cui
 * @Date: 2026/05/05 19:35
 * @Description DataSmart Govern Backend - DataSourceMetadataDiscoverySupport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.service.support;

import com.czh.datasmart.govern.datasource.config.MetadataDiscoveryProperties;
import com.czh.datasmart.govern.datasource.controller.dto.MetadataDiscoveryRequest;
import com.czh.datasmart.govern.datasource.entity.ColumnMetadataSummary;
import com.czh.datasmart.govern.datasource.entity.DataSourceConfig;
import com.czh.datasmart.govern.datasource.entity.DataSourceMetadataDiscoveryResult;
import com.czh.datasmart.govern.datasource.entity.IndexMetadataSummary;
import com.czh.datasmart.govern.datasource.entity.SampleRowPreview;
import com.czh.datasmart.govern.datasource.entity.TableMetadataSummary;
import com.czh.datasmart.govern.datasource.support.ActorRole;
import com.czh.datasmart.govern.datasource.support.DataSourceStatus;
import com.czh.datasmart.govern.datasource.support.DataSourceType;
import com.czh.datasmart.govern.datasource.support.SyncPermissionAction;
import com.czh.datasmart.govern.datasource.support.SyncPermissionEvaluator;
import com.czh.datasmart.govern.datasource.support.SyncPermissionResource;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据源元数据发现支撑组件。
 *
 * <p>这个类从 `DataSourceManagementServiceImpl` 拆出，专门承载“读取外部数据源结构信息”的业务链路。
 * 元数据发现看起来只是调用 JDBC `DatabaseMetaData`，但在真实商业化产品中它会逐渐扩展成一个独立能力域：
 * 1. 连接器能力判断：不同数据源是否支持 catalog、schema、视图、索引、采样预览并不完全一致；
 * 2. 权限边界控制：结构信息虽然不一定包含业务值，但表名、字段名、索引也可能暴露业务系统设计；
 * 3. 性能保护：大库里可能有成千上万张表，必须限制返回数量、字段数量和采样行数；
 * 4. 结果缓存：同一用户在配置页面反复刷新时，不应每次都压到业务库；
 * 5. 后续演进：未来可以把同步探查升级为 task-management 中的异步元数据采集任务，并沉淀到数据资产模块。
 *
 * <p>因此它不是简单的工具类，而是 datasource-management 里一个明确的业务支撑边界。
 */
@Component
@RequiredArgsConstructor
public class DataSourceMetadataDiscoverySupport {

    /**
     * 元数据发现性能边界配置。
     * 这些配置控制“默认返回多少”和“绝对最多允许多少”，避免调用方通过参数制造过大的数据库扫描压力。
     */
    private final MetadataDiscoveryProperties metadataDiscoveryProperties;

    /**
     * 本地权限评估器。
     * 当前项目还没有接入统一 permission-admin 的远程策略下发，所以先在 datasource-management 内部做角色边界兜底。
     */
    private final SyncPermissionEvaluator syncPermissionEvaluator;

    /**
     * 元数据发现缓存支撑组件。
     * 主流程只负责决定“是否可以发现、发现哪些内容、如何组织响应”，缓存命中、TTL 过期和结果复制
     * 交给独立组件处理，避免这个类同时承担连接器读取和性能缓存两类职责。
     */
    private final DataSourceMetadataDiscoveryCacheSupport metadataDiscoveryCacheSupport;

    /**
     * 执行数据源元数据发现。
     *
     * <p>完整流程被刻意拆得比较清楚，方便后续学习和扩展：
     * 1. 校验数据源生命周期，逻辑删除的数据源不能继续被探查；
     * 2. 校验数据源类型能力，避免对不支持 schema discovery 的连接器硬探查；
     * 3. 校验操作者权限，元数据属于治理对象，不应由任意普通用户读取；
     * 4. 读取请求参数并应用服务端上限，防止一次请求返回过多结构信息；
     * 5. 尝试命中缓存，减少配置页重复刷新对源库造成的压力；
     * 6. 基于 JDBC `DatabaseMetaData` 读取表、字段、主键、索引等结构；
     * 7. 返回带 warning 的结果，让调用方理解哪些内容被截断、哪些开关没有打开。
     *
     * @param config  已经从数据库加载出来的数据源配置，调用方负责保证记录存在
     * @param request 元数据探查请求，包含角色、租户、过滤条件和返回上限
     * @return 本次元数据发现结果，包含表结构摘要、执行耗时、缓存命中标识和提示信息
     */
    public DataSourceMetadataDiscoveryResult discoverMetadata(DataSourceConfig config, MetadataDiscoveryRequest request) {
        ensureNotDeleted(config);
        DataSourceType type = DataSourceType.fromValue(config.getType());
        ActorRole actorRole = ActorRole.fromValue(request.getActorRole());
        syncPermissionEvaluator.assertAllowed(request.getActorRole(), SyncPermissionResource.DATASOURCE_METADATA,
                SyncPermissionAction.VIEW_STRUCTURE);
        if (!actorRole.canDiscoverMetadata()) {
            throw new IllegalStateException("当前角色无权执行元数据发现: " + actorRole.name());
        }
        if (!type.isSupportsSchemaDiscovery()) {
            throw new IllegalStateException("当前数据源类型暂不支持元数据发现: " + type.name());
        }

        String cacheKey = buildDiscoveryCacheKey(config, request);
        DataSourceMetadataDiscoveryResult cachedResult =
                metadataDiscoveryCacheSupport.getCachedDiscoveryResult(cacheKey,
                        metadataDiscoveryProperties.getCacheTtlSeconds());
        if (cachedResult != null) {
            return cachedResult;
        }

        long startTime = System.currentTimeMillis();
        int maxTables = applyBoundedValue(
                request.getMaxTables(),
                metadataDiscoveryProperties.getDefaultMaxTables(),
                metadataDiscoveryProperties.getAbsoluteMaxTables());
        int maxColumnsPerTable = applyBoundedValue(
                request.getMaxColumnsPerTable(),
                metadataDiscoveryProperties.getDefaultMaxColumnsPerTable(),
                metadataDiscoveryProperties.getAbsoluteMaxColumnsPerTable());
        int sampleRowLimit = applyBoundedValue(
                request.getSampleRowLimit(),
                metadataDiscoveryProperties.getDefaultSampleRowLimit(),
                metadataDiscoveryProperties.getAbsoluteSampleRowLimit());
        boolean includeColumns = request.getIncludeColumns() == null || request.getIncludeColumns();
        boolean includeViews = request.getIncludeViews() != null && request.getIncludeViews();
        boolean includePrimaryKeys = request.getIncludePrimaryKeys() == null || request.getIncludePrimaryKeys();
        boolean includeIndexes = request.getIncludeIndexes() != null && request.getIncludeIndexes();
        boolean includeSampleRows = request.getIncludeSampleRows() != null && request.getIncludeSampleRows();
        if (includeSampleRows) {
            throw new IllegalStateException("当前角色无权查看样本数据，请关闭 includeSampleRows 或使用更高权限角色");
        }

        List<String> warnings = new ArrayList<>();
        List<TableMetadataSummary> tables = new ArrayList<>();
        if (includeSampleRows) {
            warnings.add("样本预览可能包含敏感业务数据，生产环境建议结合权限控制、脱敏或审计能力一起使用");
        }

        try (Connection connection = openConnection(config)) {
            DatabaseMetaData metaData = connection.getMetaData();
            MetadataDiscoveryJdbcScope jdbcScope = resolveJdbcScope(connection, type, request, warnings);
            String[] tableTypes = includeViews ? new String[]{"TABLE", "VIEW"} : new String[]{"TABLE"};
            try (ResultSet tablesResultSet = metaData.getTables(
                    jdbcScope.catalog(),
                    jdbcScope.schemaPattern(),
                    request.getTableNamePattern(),
                    tableTypes)) {
                while (tablesResultSet.next() && tables.size() < maxTables) {
                    TableMetadataSummary tableSummary = discoverTableSummary(
                            connection,
                            metaData,
                            type,
                            tablesResultSet,
                            includeColumns,
                            includePrimaryKeys,
                            includeIndexes,
                            includeSampleRows,
                            maxColumnsPerTable,
                            sampleRowLimit,
                            warnings);
                    tables.add(tableSummary);
                }
                if (tablesResultSet.next()) {
                    warnings.add("本次只返回前 " + maxTables + " 张表，如需更多请增大 maxTables 或缩小过滤范围");
                }
            }
            if (tables.isEmpty()) {
                addEmptyDiscoveryWarning(type, jdbcScope, request, warnings);
            }

            addDiscoveryOptionWarnings(includeColumns, includeViews, includePrimaryKeys, includeIndexes, includeSampleRows, warnings);
            DataSourceMetadataDiscoveryResult result = buildDiscoveryResult(
                    config,
                    request,
                    jdbcScope,
                    type,
                    metaData,
                    tables,
                    warnings,
                    maxTables,
                    maxColumnsPerTable,
                    includeSampleRows ? sampleRowLimit : 0,
                    System.currentTimeMillis() - startTime);
            metadataDiscoveryCacheSupport.cacheDiscoveryResult(cacheKey, result);
            return result;
        } catch (ClassNotFoundException | SQLException exception) {
            throw new IllegalStateException("元数据发现失败: " + truncateMessage(exception.getMessage()), exception);
        }
    }

    /**
     * 读取单张表的元数据摘要。
     * 这里把一张表涉及的主键、字段、索引、样本预览集中在一个方法内组装，避免主流程被太多局部细节打散。
     */
    private TableMetadataSummary discoverTableSummary(Connection connection,
                                                      DatabaseMetaData metaData,
                                                      DataSourceType type,
                                                      ResultSet tablesResultSet,
                                                      boolean includeColumns,
                                                      boolean includePrimaryKeys,
                                                      boolean includeIndexes,
                                                      boolean includeSampleRows,
                                                      int maxColumnsPerTable,
                                                      int sampleRowLimit,
                                                      List<String> warnings) throws SQLException {
        String catalog = tablesResultSet.getString("TABLE_CAT");
        String schemaName = tablesResultSet.getString("TABLE_SCHEM");
        String tableName = tablesResultSet.getString("TABLE_NAME");
        String tableType = tablesResultSet.getString("TABLE_TYPE");
        String remarks = tablesResultSet.getString("REMARKS");
        List<String> primaryKeys = includePrimaryKeys
                ? discoverPrimaryKeys(metaData, catalog, schemaName, tableName)
                : List.of();
        ColumnDiscoverySnapshot columnSnapshot = includeColumns
                ? discoverColumns(metaData, catalog, schemaName, tableName, primaryKeys, maxColumnsPerTable)
                : new ColumnDiscoverySnapshot(List.of(), 0, false);
        List<IndexMetadataSummary> indexes = includeIndexes
                ? discoverIndexes(metaData, catalog, schemaName, tableName)
                : List.of();
        List<SampleRowPreview> sampleRows = includeSampleRows
                ? discoverSampleRows(connection, type, catalog, schemaName, tableName, sampleRowLimit)
                : List.of();

        TableMetadataSummary tableSummary = new TableMetadataSummary();
        tableSummary.setCatalog(catalog);
        tableSummary.setSchemaName(schemaName);
        tableSummary.setTableName(tableName);
        tableSummary.setTableType(tableType);
        tableSummary.setRemarks(remarks);
        tableSummary.setColumnCount(columnSnapshot.columns().size());
        tableSummary.setTotalColumnCount(columnSnapshot.totalColumnCount());
        tableSummary.setColumnsTruncated(columnSnapshot.truncated());
        tableSummary.setPrimaryKeys(primaryKeys);
        tableSummary.setIndexes(indexes);
        tableSummary.setColumns(columnSnapshot.columns());
        tableSummary.setSampleRows(sampleRows);
        if (columnSnapshot.truncated()) {
            warnings.add("表 " + tableName + " 的字段数量超过单表返回上限，当前只返回前 "
                    + maxColumnsPerTable + " 个字段");
        }
        return tableSummary;
    }

    /**
     * 发现单张表的字段信息。
     * 字段元数据会直接影响后续字段映射、类型转换、质量规则生成和同步任务校验。
     */
    private ColumnDiscoverySnapshot discoverColumns(DatabaseMetaData metaData, String catalog,
                                                    String schemaName, String tableName,
                                                    List<String> primaryKeys, int maxColumnsPerTable) throws SQLException {
        List<ColumnMetadataSummary> columns = new ArrayList<>();
        int totalColumnCount = 0;
        try (ResultSet columnsResultSet = metaData.getColumns(catalog, schemaName, tableName, null)) {
            while (columnsResultSet.next()) {
                totalColumnCount++;
                if (columns.size() >= maxColumnsPerTable) {
                    continue;
                }
                columns.add(new ColumnMetadataSummary(
                        columnsResultSet.getString("COLUMN_NAME"),
                        columnsResultSet.getString("TYPE_NAME"),
                        columnsResultSet.getInt("COLUMN_SIZE"),
                        DatabaseMetaData.columnNullable == columnsResultSet.getInt("NULLABLE"),
                        columnsResultSet.getString("COLUMN_DEF"),
                        columnsResultSet.getInt("DECIMAL_DIGITS"),
                        "YES".equalsIgnoreCase(columnsResultSet.getString("IS_AUTOINCREMENT")),
                        primaryKeys.contains(columnsResultSet.getString("COLUMN_NAME")),
                        columnsResultSet.getString("REMARKS"),
                        columnsResultSet.getInt("ORDINAL_POSITION")
                ));
            }
        }
        return new ColumnDiscoverySnapshot(columns, totalColumnCount, totalColumnCount > columns.size());
    }

    /**
     * 发现主键信息。
     * 主键是同步任务里非常关键的结构字段，会影响去重、回写、增量策略和并行切分设计。
     */
    private List<String> discoverPrimaryKeys(DatabaseMetaData metaData, String catalog,
                                             String schemaName, String tableName) throws SQLException {
        List<String> primaryKeys = new ArrayList<>();
        try (ResultSet primaryKeysResultSet = metaData.getPrimaryKeys(catalog, schemaName, tableName)) {
            while (primaryKeysResultSet.next()) {
                primaryKeys.add(primaryKeysResultSet.getString("COLUMN_NAME"));
            }
        }
        return primaryKeys;
    }

    /**
     * 发现索引信息。
     * 索引对生产级同步产品非常重要，因为它会影响全量扫描性能、增量条件过滤和分片并发策略。
     */
    private List<IndexMetadataSummary> discoverIndexes(DatabaseMetaData metaData, String catalog,
                                                       String schemaName, String tableName) throws SQLException {
        Map<String, List<String>> indexColumns = new LinkedHashMap<>();
        Map<String, Boolean> indexUniqueFlags = new LinkedHashMap<>();
        try (ResultSet indexResultSet = metaData.getIndexInfo(catalog, schemaName, tableName, false, false)) {
            while (indexResultSet.next()) {
                String indexName = indexResultSet.getString("INDEX_NAME");
                String columnName = indexResultSet.getString("COLUMN_NAME");
                short type = indexResultSet.getShort("TYPE");
                if (indexName == null || columnName == null || type == DatabaseMetaData.tableIndexStatistic) {
                    continue;
                }
                indexColumns.computeIfAbsent(indexName, key -> new ArrayList<>()).add(columnName);
                indexUniqueFlags.put(indexName, !indexResultSet.getBoolean("NON_UNIQUE"));
            }
        }
        List<IndexMetadataSummary> indexes = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : indexColumns.entrySet()) {
            indexes.add(new IndexMetadataSummary(
                    entry.getKey(),
                    indexUniqueFlags.getOrDefault(entry.getKey(), false),
                    entry.getValue()
            ));
        }
        return indexes;
    }

    /**
     * 发现样本数据。
     * 当前主要目的是帮助配置阶段快速理解字段实际长什么样，而不是替代真正的数据预览产品。
     */
    private List<SampleRowPreview> discoverSampleRows(Connection connection, DataSourceType dataSourceType,
                                                      String catalog, String schemaName, String tableName,
                                                      int sampleRowLimit) throws SQLException {
        String previewSql = buildPreviewSql(dataSourceType, catalog, schemaName, tableName, sampleRowLimit);
        List<SampleRowPreview> sampleRows = new ArrayList<>();
        try (PreparedStatement preparedStatement = connection.prepareStatement(previewSql)) {
            preparedStatement.setQueryTimeout(metadataDiscoveryProperties.getJdbcQueryTimeoutSeconds());
            try (ResultSet resultSet = preparedStatement.executeQuery()) {
                ResultSetMetaData metaData = resultSet.getMetaData();
                int rowNumber = 1;
                while (resultSet.next()) {
                    Map<String, Object> values = new LinkedHashMap<>();
                    for (int columnIndex = 1; columnIndex <= metaData.getColumnCount(); columnIndex++) {
                        values.put(metaData.getColumnLabel(columnIndex), resultSet.getObject(columnIndex));
                    }
                    sampleRows.add(new SampleRowPreview(rowNumber++, values));
                }
            }
        }
        return sampleRows;
    }

    private String buildPreviewSql(DataSourceType dataSourceType, String catalog, String schemaName,
                                   String tableName, int sampleRowLimit) {
        String qualifiedTableName = buildQualifiedTableName(dataSourceType, catalog, schemaName, tableName);
        if (dataSourceType == DataSourceType.SQLSERVER) {
            return "SELECT TOP (" + sampleRowLimit + ") * FROM " + qualifiedTableName;
        }
        return "SELECT * FROM " + qualifiedTableName + " LIMIT " + sampleRowLimit;
    }

    private String buildQualifiedTableName(DataSourceType dataSourceType, String catalog, String schemaName, String tableName) {
        String quote;
        String quoteEnd;
        if (dataSourceType == DataSourceType.SQLSERVER) {
            quote = "[";
            quoteEnd = "]";
        } else if (dataSourceType == DataSourceType.MYSQL) {
            quote = "`";
            quoteEnd = "`";
        } else {
            quote = "\"";
            quoteEnd = "\"";
        }
        List<String> parts = new ArrayList<>();
        if (catalog != null && !catalog.isBlank() &&
                (dataSourceType == DataSourceType.SQLSERVER || dataSourceType == DataSourceType.MYSQL)) {
            parts.add(quote + catalog + quoteEnd);
        }
        if (schemaName != null && !schemaName.isBlank()) {
            parts.add(quote + schemaName + quoteEnd);
        }
        parts.add(quote + tableName + quoteEnd);
        return String.join(".", parts);
    }

    /**
     * 把产品层的 catalog/schema 筛选条件转换成 JDBC DatabaseMetaData 能正确理解的参数。
     *
     * <p>这里是“连接测试成功但创建任务查不到表”的关键修复点。JDBC 的 {@code getTables(catalog, schemaPattern, ...)}
     * 看起来是统一接口，但不同数据库的语义差别很大：</p>
     * <p>1. MySQL/MariaDB 把业务数据库 database 放在 catalog 上，schemaPattern 通常应为 null；</p>
     * <p>2. PostgreSQL 更常用 schemaPattern 表示 public、业务 schema 等命名空间；</p>
     * <p>3. SQL Server 同时存在 database/catalog 与 schema，因此两者都可能有意义。</p>
     *
     * <p>因此后端不能把前端传来的 schemaPattern 原样丢给 MySQL。否则用户在页面上按 schema 操作或历史草稿残留
     * schemaPattern 时，MySQL 驱动会按错误维度过滤，表现就是“数据源测试成功，但任务页面没有表”。</p>
     */
    private MetadataDiscoveryJdbcScope resolveJdbcScope(Connection connection,
                                                        DataSourceType dataSourceType,
                                                        MetadataDiscoveryRequest request,
                                                        List<String> warnings) throws SQLException {
        String requestCatalog = blankToNull(request.getCatalog());
        String requestSchema = blankToNull(request.getSchemaPattern());
        if (dataSourceType == DataSourceType.MYSQL) {
            String catalog = firstNonBlank(requestCatalog, requestSchema, connection.getCatalog());
            if (requestCatalog == null && requestSchema != null) {
                warnings.add("MySQL/MariaDB 使用 database/catalog 语义，本次已把 schemaPattern="
                        + requestSchema + " 作为 catalog 进行元数据发现。");
            }
            if (catalog == null) {
                warnings.add("MySQL 连接当前没有选中 database/catalog。请确认 JDBC URL 包含具体库名，例如 jdbc:mysql://host:3306/database。");
            }
            return new MetadataDiscoveryJdbcScope(catalog, null);
        }
        if (dataSourceType == DataSourceType.SQLSERVER) {
            return new MetadataDiscoveryJdbcScope(firstNonBlank(requestCatalog, connection.getCatalog()), requestSchema);
        }
        return new MetadataDiscoveryJdbcScope(requestCatalog, requestSchema);
    }

    /**
     * 当元数据发现返回空表时，生成可以直接展示给用户的排障提示。
     */
    private void addEmptyDiscoveryWarning(DataSourceType dataSourceType,
                                          MetadataDiscoveryJdbcScope jdbcScope,
                                          MetadataDiscoveryRequest request,
                                          List<String> warnings) {
        warnings.add("本次元数据发现未返回 TABLE/VIEW 对象。请检查数据源账号是否拥有元数据读取权限、目标库表是否存在、筛选条件是否过窄。");
        if (dataSourceType == DataSourceType.MYSQL) {
            warnings.add("当前连接器是 MySQL/MariaDB，任务对象选择应使用“按表选择”；如果需要限定数据库，请在 JDBC URL 或 catalog 中指定 database。");
        }
        if (blankToNull(request.getTableNamePattern()) != null) {
            warnings.add("当前携带 tableNamePattern=" + request.getTableNamePattern()
                    + "，如果页面期望展示所有表，请清空表名筛选后重新发现。");
        }
        if (blankToNull(jdbcScope.schemaPattern()) != null) {
            warnings.add("当前携带 schemaPattern=" + jdbcScope.schemaPattern()
                    + "，请确认该 schema 存在且当前账号可见。");
        }
    }

    private DataSourceMetadataDiscoveryResult buildDiscoveryResult(DataSourceConfig config,
                                                                   MetadataDiscoveryRequest request,
                                                                   MetadataDiscoveryJdbcScope jdbcScope,
                                                                   DataSourceType type,
                                                                   DatabaseMetaData metaData,
                                                                   List<TableMetadataSummary> tables,
                                                                   List<String> warnings,
                                                                   int maxTables,
                                                                   int maxColumnsPerTable,
                                                                   int appliedSampleRowLimit,
                                                                   long durationMs) throws SQLException {
        DataSourceMetadataDiscoveryResult result = new DataSourceMetadataDiscoveryResult();
        result.setDatasourceId(config.getId());
        result.setDatasourceName(config.getName());
        result.setDatasourceType(type.name());
        result.setProductName(metaData.getDatabaseProductName());
        result.setProductVersion(metaData.getDatabaseProductVersion());
        result.setDriverName(metaData.getDriverName());
        result.setCatalog(jdbcScope.catalog());
        result.setSchemaPattern(jdbcScope.schemaPattern());
        result.setTableNamePattern(request.getTableNamePattern());
        result.setTableCount(tables.size());
        result.setAppliedMaxTables(maxTables);
        result.setAppliedMaxColumnsPerTable(maxColumnsPerTable);
        result.setAppliedSampleRowLimit(appliedSampleRowLimit);
        result.setCacheHit(false);
        result.setDiscoveryDurationMs(durationMs);
        result.setDiscoveredAt(LocalDateTime.now());
        result.setTables(tables);
        result.setWarnings(warnings);
        return result;
    }

    private void addDiscoveryOptionWarnings(boolean includeColumns, boolean includeViews, boolean includePrimaryKeys,
                                            boolean includeIndexes, boolean includeSampleRows, List<String> warnings) {
        if (!includeViews) {
            warnings.add("当前结果未包含视图，如需一起探查请将 includeViews 设置为 true");
        }
        if (!includeColumns) {
            warnings.add("当前结果未展开字段清单，如需字段详情请将 includeColumns 设置为 true");
        }
        if (!includePrimaryKeys) {
            warnings.add("当前结果未返回主键信息，如需主键结构请将 includePrimaryKeys 设置为 true");
        }
        if (!includeIndexes) {
            warnings.add("当前结果未返回索引信息，如需评估扫描与增量策略请将 includeIndexes 设置为 true");
        }
        if (!includeSampleRows) {
            warnings.add("当前结果未包含样本数据，如需预览实际值请将 includeSampleRows 设置为 true");
        }
    }

    private Connection openConnection(DataSourceConfig config) throws SQLException, ClassNotFoundException {
        Class.forName(config.getDriverClassName());
        DriverManager.setLoginTimeout(5);
        Connection connection = DriverManager.getConnection(config.getJdbcUrl(), config.getUsername(), config.getPassword());
        connection.setReadOnly(true);
        return connection;
    }

    private int applyBoundedValue(Integer requestedValue, Integer defaultValue, Integer absoluteMaxValue) {
        int value = requestedValue == null ? defaultValue : requestedValue;
        return Math.min(value, absoluteMaxValue);
    }

    private void ensureNotDeleted(DataSourceConfig config) {
        if (DataSourceStatus.DELETED.equals(config.getStatus())) {
            throw new IllegalStateException("数据源已删除: " + config.getId());
        }
    }

    private String buildDiscoveryCacheKey(DataSourceConfig config, MetadataDiscoveryRequest request) {
        return config.getId() + "|" +
                safe(config.getUpdateTime()) + "|" +
                safe(request.getCatalog()) + "|" +
                safe(request.getSchemaPattern()) + "|" +
                safe(request.getTableNamePattern()) + "|" +
                safe(request.getMaxTables()) + "|" +
                safe(request.getMaxColumnsPerTable()) + "|" +
                safe(request.getIncludeColumns()) + "|" +
                safe(request.getIncludeViews()) + "|" +
                safe(request.getIncludePrimaryKeys()) + "|" +
                safe(request.getIncludeIndexes()) + "|" +
                safe(request.getIncludeSampleRows()) + "|" +
                safe(request.getSampleRowLimit());
    }

    private String truncateMessage(String message) {
        if (message == null || message.isBlank()) {
            return "未知连接错误";
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }

    private String safe(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            String normalized = blankToNull(value);
            if (normalized != null) {
                return normalized;
            }
        }
        return null;
    }

    private record ColumnDiscoverySnapshot(List<ColumnMetadataSummary> columns, int totalColumnCount, boolean truncated) {
    }

    private record MetadataDiscoveryJdbcScope(String catalog, String schemaPattern) {
    }
}
