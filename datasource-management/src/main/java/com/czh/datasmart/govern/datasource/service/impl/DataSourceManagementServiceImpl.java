package com.czh.datasmart.govern.datasource.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.czh.datasmart.govern.datasource.config.MetadataDiscoveryProperties;
import com.czh.datasmart.govern.datasource.controller.dto.MetadataDiscoveryRequest;
import com.czh.datasmart.govern.datasource.entity.ColumnMetadataSummary;
import com.czh.datasmart.govern.datasource.entity.DataSourceCapabilityProfile;
import com.czh.datasmart.govern.datasource.entity.DataSourceConfig;
import com.czh.datasmart.govern.datasource.entity.DataSourceConnectionTestResult;
import com.czh.datasmart.govern.datasource.entity.DataSourceMetadataDiscoveryResult;
import com.czh.datasmart.govern.datasource.entity.IndexMetadataSummary;
import com.czh.datasmart.govern.datasource.entity.SampleRowPreview;
import com.czh.datasmart.govern.datasource.entity.TableMetadataSummary;
import com.czh.datasmart.govern.datasource.mapper.DataSourceConfigMapper;
import com.czh.datasmart.govern.datasource.service.DataSourceManagementService;
import com.czh.datasmart.govern.datasource.support.ConnectionTestStatus;
import com.czh.datasmart.govern.datasource.support.ActorRole;
import com.czh.datasmart.govern.datasource.support.DataSourceStatus;
import com.czh.datasmart.govern.datasource.support.DataSourceType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.DatabaseMetaData;
import java.sql.Connection;
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
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @Author : Cui
 * @Date: 2026/4/18 22:00
 * @Description DataSmart Govern Backend - DataSourceManagementServiceImpl.java
 * @Version:1.0.0
 *
 * 数据源管理服务实现。
 * 当前模块的第一阶段目标，不是做复杂元数据采集，而是先把“外部数据源登记与可用性管理”做好。
 * 因此这里的核心职责主要有三类：
 * 1. 维护数据源配置主记录。
 * 2. 管理数据源启用、停用、删除等生命周期状态。
 * 3. 通过 JDBC 做最基础的连通性测试，验证配置是否真实可用。
 *
 * 这也是很多后续能力的前置条件：
 * - 如果连通性不可靠，元数据采集就无从谈起。
 * - 如果没有启停管理，其他模块很难判断某个数据源是否允许被使用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataSourceManagementServiceImpl extends ServiceImpl<DataSourceConfigMapper, DataSourceConfig>
        implements DataSourceManagementService {

    /**
     * 元数据发现性能边界配置。
     */
    private final MetadataDiscoveryProperties metadataDiscoveryProperties;

    /**
     * 简单的进程内缓存。
     * 当前阶段先用内存缓存降低短时间重复点击带来的数据库压力；
     * 后续如果需要多实例共享、统一失效或更强一致性，再演进到 Redis 等外部缓存。
     */
    private static final Map<String, CachedDiscoveryEntry> METADATA_DISCOVERY_CACHE = new ConcurrentHashMap<>();

    /**
     * 创建数据源。
     * 这里会把 type 解析成 driverClassName 并固化到数据库中，
     * 后面执行连接测试或元数据采集时就不需要每次重新推导。
     */
    @Override
    @Transactional
    public DataSourceConfig createDataSource(String name, String type, String jdbcUrl, String username,
                                             String password, String description) {
        ensureNameNotDuplicated(name, null);
        DataSourceType dataSourceType = DataSourceType.fromValue(type);

        DataSourceConfig config = new DataSourceConfig();
        config.setName(name);
        config.setType(dataSourceType.name());
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(username);
        config.setPassword(password);
        config.setDriverClassName(dataSourceType.getDriverClassName());
        config.setDescription(description);
        config.setStatus(DataSourceStatus.ACTIVE);
        config.setLastTestStatus(ConnectionTestStatus.UNKNOWN);
        config.setCreateTime(LocalDateTime.now());
        config.setUpdateTime(LocalDateTime.now());
        save(config);

        log.info("创建数据源配置成功，datasourceId={}", config.getId());
        return config;
    }

    /**
     * 更新数据源配置。
     * 当前不允许修改 type，目的是避免“逻辑上同一条数据源记录”的语义发生根本变化。
     * 如果真要从 MySQL 改成 PostgreSQL，通常更合理的方式是新建一条数据源。
     */
    @Override
    @Transactional
    public DataSourceConfig updateDataSource(Long id, String name, String jdbcUrl, String username,
                                             String password, String description) {
        DataSourceConfig config = getRequiredDataSource(id);
        ensureNotDeleted(config);
        ensureNameNotDuplicated(name, id);

        config.setName(name);
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(username);
        config.setPassword(password);
        config.setDescription(description);
        config.setUpdateTime(LocalDateTime.now());
        updateById(config);

        log.info("更新数据源配置成功，datasourceId={}", id);
        return config;
    }

    /**
     * 启用数据源。
     */
    @Override
    @Transactional
    public DataSourceConfig enableDataSource(Long id) {
        DataSourceConfig config = getRequiredDataSource(id);
        ensureNotDeleted(config);
        config.setStatus(DataSourceStatus.ACTIVE);
        config.setUpdateTime(LocalDateTime.now());
        updateById(config);
        log.info("启用数据源成功，datasourceId={}", id);
        return config;
    }

    /**
     * 停用数据源。
     * 停用并不会删除配置，而是表达“这条配置暂时不允许被其他业务使用”。
     */
    @Override
    @Transactional
    public DataSourceConfig disableDataSource(Long id) {
        DataSourceConfig config = getRequiredDataSource(id);
        ensureNotDeleted(config);
        config.setStatus(DataSourceStatus.INACTIVE);
        config.setUpdateTime(LocalDateTime.now());
        updateById(config);
        log.info("停用数据源成功，datasourceId={}", id);
        return config;
    }

    /**
     * 逻辑删除数据源。
     * 当前阶段先用状态位做逻辑删除，优点是：
     * 1. 便于后续审计和恢复。
     * 2. 避免把引用这条数据源的历史业务记录直接“删断”。
     */
    @Override
    @Transactional
    public DataSourceConfig deleteDataSource(Long id) {
        DataSourceConfig config = getRequiredDataSource(id);
        config.setStatus(DataSourceStatus.DELETED);
        config.setUpdateTime(LocalDateTime.now());
        updateById(config);
        log.info("逻辑删除数据源成功，datasourceId={}", id);
        return config;
    }

    /**
     * 测试数据源连接。
     * 这里使用最基础的 JDBC 连通性测试逻辑：
     * 1. 根据数据源类型加载驱动类。
     * 2. 通过 DriverManager 尝试建立连接。
     * 3. 如果能在超时时间内拿到连接并通过 isValid，则视为成功。
     *
     * 这个实现虽然简单，但非常适合作为学习起点，因为它把 JDBC 的核心机制直接暴露出来：
     * 驱动、URL、凭证、连接建立、异常捕获。
     */
    @Override
    @Transactional
    public DataSourceConnectionTestResult testConnection(Long id) {
        DataSourceConfig config = getRequiredDataSource(id);
        ensureNotDeleted(config);

        LocalDateTime testedAt = LocalDateTime.now();
        try {
            try (Connection connection = openConnection(config)) {
                boolean valid = connection.isValid(5);
                if (!valid) {
                    throw new SQLException("connection validation failed");
                }
            }

            config.setLastTestStatus(ConnectionTestStatus.SUCCESS);
            config.setLastTestMessage("连接测试成功");
            config.setLastTestTime(testedAt);
            config.setUpdateTime(testedAt);
            updateById(config);

            log.info("数据源连接测试成功，datasourceId={}", id);
            return new DataSourceConnectionTestResult(id, ConnectionTestStatus.SUCCESS, "连接测试成功", testedAt);
        } catch (ClassNotFoundException | SQLException exception) {
            String message = truncateMessage(exception.getMessage());
            config.setLastTestStatus(ConnectionTestStatus.FAILED);
            config.setLastTestMessage(message);
            config.setLastTestTime(testedAt);
            config.setUpdateTime(testedAt);
            updateById(config);

            log.warn("数据源连接测试失败，datasourceId={}, reason={}", id, message);
            return new DataSourceConnectionTestResult(id, ConnectionTestStatus.FAILED, message, testedAt);
        }
    }

    /**
     * 获取数据源能力画像。
     * 这里不去真实探查外部数据库，而是基于当前连接器类型返回一份“产品能力层面的能力声明”。
     *
     * 这种设计对产品配置页很有价值：
     * - 如果某类数据源不支持 schema 发现，前端就不应该展示相关入口；
     * - 如果某类数据源不适合流式同步，模板层就可以提前限制；
     * - 后续扩展新连接器时，只要补齐这个画像，就能减少散落在各处的 if/else。
     */
    @Override
    public DataSourceCapabilityProfile getCapabilityProfile(Long id) {
        DataSourceConfig config = getRequiredDataSource(id);
        ensureNotDeleted(config);
        DataSourceType type = DataSourceType.fromValue(config.getType());
        return new DataSourceCapabilityProfile(
                config.getId(),
                config.getName(),
                type.name(),
                type.isCanRead(),
                type.isCanWrite(),
                type.isSupportsFullSync(),
                type.isSupportsIncrementalSync(),
                type.isSupportsStreaming(),
                type.isSupportsSchemaDiscovery(),
                type.isSupportsFieldMapping(),
                type.isSupportsCheckpointResume(),
                type.isSupportsPreviewSampling(),
                type.isSupportsPartitionParallelism(),
                LocalDateTime.now()
        );
    }

    /**
     * 发现数据源元数据。
     * 当前实现基于 JDBC DatabaseMetaData，优先解决关系型数据库场景下最核心的三个问题：
     * 1. 这个库里有哪些表；
     * 2. 表里有哪些字段；
     * 3. 这些字段的基础类型和可空性如何。
     *
     * 这一步虽然还不是完整的元数据采集中心，但已经足够支撑：
     * - 模板创建时的源表选择；
     * - 字段映射的第一版预览；
     * - 数据同步任务的前置配置校验。
     */
    @Override
    @Transactional
    public DataSourceMetadataDiscoveryResult discoverMetadata(Long id, MetadataDiscoveryRequest request) {
        DataSourceConfig config = getRequiredDataSource(id);
        ensureNotDeleted(config);
        DataSourceType type = DataSourceType.fromValue(config.getType());
        ActorRole actorRole = ActorRole.fromValue(request.getActorRole());
        if (!actorRole.canDiscoverMetadata()) {
            throw new IllegalStateException("当前角色无权执行元数据发现: " + actorRole.name());
        }
        if (!type.isSupportsSchemaDiscovery()) {
            throw new IllegalStateException("当前数据源类型暂不支持元数据发现: " + type.name());
        }

        String cacheKey = buildDiscoveryCacheKey(id, request);
        DataSourceMetadataDiscoveryResult cachedResult = getCachedDiscoveryResult(cacheKey);
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
        if (includeSampleRows && !actorRole.canPreviewSampleRows()) {
            throw new IllegalStateException("当前角色无权查看样本数据，请关闭 includeSampleRows 或使用更高权限角色");
        }
        List<String> warnings = new ArrayList<>();
        List<TableMetadataSummary> tables = new ArrayList<>();

        if (includeSampleRows) {
            warnings.add("样本预览可能包含敏感业务数据，生产环境建议结合权限控制、脱敏或审计能力一起使用");
        }

        try (Connection connection = openConnection(config)) {
            DatabaseMetaData metaData = connection.getMetaData();
            String[] tableTypes = includeViews ? new String[]{"TABLE", "VIEW"} : new String[]{"TABLE"};
            try (ResultSet tablesResultSet = metaData.getTables(
                    request.getCatalog(),
                    request.getSchemaPattern(),
                    request.getTableNamePattern(),
                    tableTypes)) {
                while (tablesResultSet.next() && tables.size() < maxTables) {
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
                    tables.add(tableSummary);

                    if (columnSnapshot.truncated()) {
                        warnings.add("表 " + tableName + " 的字段数量超过单表返回上限，当前只返回前 "
                                + maxColumnsPerTable + " 个字段");
                    }
                }

                if (tablesResultSet.next()) {
                    warnings.add("本次只返回前 " + maxTables + " 张表，如需更多请增大 maxTables 或缩小过滤范围");
                }
            }

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

            DataSourceMetadataDiscoveryResult result = new DataSourceMetadataDiscoveryResult();
            result.setDatasourceId(config.getId());
            result.setDatasourceName(config.getName());
            result.setDatasourceType(type.name());
            result.setProductName(metaData.getDatabaseProductName());
            result.setProductVersion(metaData.getDatabaseProductVersion());
            result.setDriverName(metaData.getDriverName());
            result.setCatalog(request.getCatalog());
            result.setSchemaPattern(request.getSchemaPattern());
            result.setTableNamePattern(request.getTableNamePattern());
            result.setTableCount(tables.size());
            result.setAppliedMaxTables(maxTables);
            result.setAppliedMaxColumnsPerTable(maxColumnsPerTable);
            result.setAppliedSampleRowLimit(includeSampleRows ? sampleRowLimit : 0);
            result.setCacheHit(false);
            result.setDiscoveryDurationMs(System.currentTimeMillis() - startTime);
            result.setDiscoveredAt(LocalDateTime.now());
            result.setTables(tables);
            result.setWarnings(warnings);
            cacheDiscoveryResult(cacheKey, result);
            return result;
        } catch (ClassNotFoundException | SQLException exception) {
            throw new IllegalStateException("元数据发现失败: " + truncateMessage(exception.getMessage()), exception);
        }
    }

    /**
     * 查询必须存在的数据源。
     */
    private DataSourceConfig getRequiredDataSource(Long id) {
        DataSourceConfig config = getById(id);
        if (config == null) {
            throw new NoSuchElementException("数据源不存在: " + id);
        }
        return config;
    }

    /**
     * 逻辑删除后的数据源不允许继续参与常规业务操作。
     */
    private void ensureNotDeleted(DataSourceConfig config) {
        if (DataSourceStatus.DELETED.equals(config.getStatus())) {
            throw new IllegalStateException("数据源已删除: " + config.getId());
        }
    }

    /**
     * 名称去重校验。
     * 在数据源管理中，名称通常会被用于下拉选择、运维识别和人工排查，
     * 因此重复名称会明显增加误操作风险。
     */
    private void ensureNameNotDuplicated(String name, Long currentId) {
        LambdaQueryWrapper<DataSourceConfig> wrapper = new LambdaQueryWrapper<DataSourceConfig>()
                .eq(DataSourceConfig::getName, name)
                .ne(currentId != null, DataSourceConfig::getId, currentId)
                .ne(DataSourceConfig::getStatus, DataSourceStatus.DELETED);
        if (count(wrapper) > 0) {
            throw new IllegalArgumentException("数据源名称已存在: " + name);
        }
    }

    /**
     * 避免把异常信息原样无限制写入数据库字段。
     */
    private String truncateMessage(String message) {
        if (message == null || message.isBlank()) {
            return "未知连接错误";
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }

    /**
     * 打开 JDBC 连接。
     * 这里单独抽出一个方法，是为了把“连接建立”从具体业务动作中解耦。
     * 后续如果要加入：
     * - 连接池；
     * - 限流；
     * - 探查专用超时；
     * - 安全审计；
     * 都可以在这里集中扩展。
     */
    private Connection openConnection(DataSourceConfig config) throws SQLException, ClassNotFoundException {
        Class.forName(config.getDriverClassName());
        DriverManager.setLoginTimeout(5);
        Connection connection = DriverManager.getConnection(config.getJdbcUrl(), config.getUsername(), config.getPassword());
        connection.setReadOnly(true);
        return connection;
    }

    /**
     * 发现单张表的字段信息。
     * 这一轮开始补充默认值、精度、自增和主键信息，并允许按上限截断结果。
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
     * 这里把同一个索引的多行结果聚合为一个索引摘要对象，便于前端和服务层直接消费。
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
     * 因此这里做了三个刻意的控制：
     * 1. 行数必须受限；
     * 2. 只做简单的 SELECT，不拼接复杂条件；
     * 3. 先按不同数据库方言生成最小可用的采样 SQL。
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

    /**
     * 构建样本预览 SQL。
     * 当前先覆盖本模块已支持的三类关系型数据库。
     */
    private String buildPreviewSql(DataSourceType dataSourceType, String catalog, String schemaName,
                                   String tableName, int sampleRowLimit) {
        String qualifiedTableName = buildQualifiedTableName(dataSourceType, catalog, schemaName, tableName);
        if (dataSourceType == DataSourceType.SQLSERVER) {
            return "SELECT TOP (" + sampleRowLimit + ") * FROM " + qualifiedTableName;
        }
        return "SELECT * FROM " + qualifiedTableName + " LIMIT " + sampleRowLimit;
    }

    /**
     * 构建表名限定路径。
     * 当前优先兼容最常见的 schema.table 形式。
     */
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
     * 应用边界限制。
     * 这是控制元数据探查性能的关键一环：调用方可以表达需求，但最终仍要服从系统上限。
     */
    private int applyBoundedValue(Integer requestedValue, Integer defaultValue, Integer absoluteMaxValue) {
        int value = requestedValue == null ? defaultValue : requestedValue;
        return Math.min(value, absoluteMaxValue);
    }

    /**
     * 构建探查缓存键。
     */
    private String buildDiscoveryCacheKey(Long datasourceId, MetadataDiscoveryRequest request) {
        return datasourceId + "|" +
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

    /**
     * 尝试命中缓存。
     */
    private DataSourceMetadataDiscoveryResult getCachedDiscoveryResult(String cacheKey) {
        CachedDiscoveryEntry cachedEntry = METADATA_DISCOVERY_CACHE.get(cacheKey);
        if (cachedEntry == null) {
            return null;
        }
        long ttlMillis = metadataDiscoveryProperties.getCacheTtlSeconds() * 1000L;
        if (System.currentTimeMillis() - cachedEntry.cachedAt() > ttlMillis) {
            METADATA_DISCOVERY_CACHE.remove(cacheKey);
            return null;
        }
        return copyDiscoveryResult(cachedEntry.result(), true);
    }

    /**
     * 写入缓存。
     */
    private void cacheDiscoveryResult(String cacheKey, DataSourceMetadataDiscoveryResult result) {
        METADATA_DISCOVERY_CACHE.put(cacheKey, new CachedDiscoveryEntry(copyDiscoveryResult(result, false), System.currentTimeMillis()));
    }

    /**
     * 复制探查结果。
     * 缓存返回时会把 cacheHit 改为 true，避免直接修改缓存里的原对象。
     */
    private DataSourceMetadataDiscoveryResult copyDiscoveryResult(DataSourceMetadataDiscoveryResult source, boolean cacheHit) {
        DataSourceMetadataDiscoveryResult copied = new DataSourceMetadataDiscoveryResult();
        copied.setDatasourceId(source.getDatasourceId());
        copied.setDatasourceName(source.getDatasourceName());
        copied.setDatasourceType(source.getDatasourceType());
        copied.setProductName(source.getProductName());
        copied.setProductVersion(source.getProductVersion());
        copied.setDriverName(source.getDriverName());
        copied.setCatalog(source.getCatalog());
        copied.setSchemaPattern(source.getSchemaPattern());
        copied.setTableNamePattern(source.getTableNamePattern());
        copied.setTableCount(source.getTableCount());
        copied.setAppliedMaxTables(source.getAppliedMaxTables());
        copied.setAppliedMaxColumnsPerTable(source.getAppliedMaxColumnsPerTable());
        copied.setAppliedSampleRowLimit(source.getAppliedSampleRowLimit());
        copied.setCacheHit(cacheHit);
        copied.setDiscoveryDurationMs(source.getDiscoveryDurationMs());
        copied.setDiscoveredAt(source.getDiscoveredAt());
        copied.setTables(source.getTables());
        copied.setWarnings(source.getWarnings());
        return copied;
    }

    private String safe(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /**
     * 字段探查快照。
     * 它不是外部 API 对象，而是服务层内部为了同时表达“当前返回了哪些字段”和“真实总字段数是多少”引入的中间结构。
     */
    private record ColumnDiscoverySnapshot(List<ColumnMetadataSummary> columns, int totalColumnCount, boolean truncated) {
    }

    /**
     * 缓存项。
     */
    private record CachedDiscoveryEntry(DataSourceMetadataDiscoveryResult result, long cachedAt) {
    }
}
