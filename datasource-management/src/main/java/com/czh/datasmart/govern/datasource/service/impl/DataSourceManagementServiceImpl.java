package com.czh.datasmart.govern.datasource.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.czh.datasmart.govern.datasource.config.MetadataDiscoveryProperties;
import com.czh.datasmart.govern.datasource.config.ReadOnlySqlAuditMaskingProperties;
import com.czh.datasmart.govern.datasource.config.ReadOnlySqlExecutionProperties;
import com.czh.datasmart.govern.datasource.controller.dto.MetadataDiscoveryRequest;
import com.czh.datasmart.govern.datasource.controller.dto.ReadOnlySqlExecutionRequest;
import com.czh.datasmart.govern.datasource.controller.dto.ReadOnlySqlExecutionResult;
import com.czh.datasmart.govern.datasource.entity.ColumnMetadataSummary;
import com.czh.datasmart.govern.datasource.entity.DataSourceCapabilityProfile;
import com.czh.datasmart.govern.datasource.entity.DataSourceConfig;
import com.czh.datasmart.govern.datasource.entity.DataSourceConnectionTestResult;
import com.czh.datasmart.govern.datasource.entity.DataSourceMetadataDiscoveryResult;
import com.czh.datasmart.govern.datasource.entity.DataSourceReadOnlySqlExecutionAudit;
import com.czh.datasmart.govern.datasource.entity.IndexMetadataSummary;
import com.czh.datasmart.govern.datasource.entity.SampleRowPreview;
import com.czh.datasmart.govern.datasource.entity.TableMetadataSummary;
import com.czh.datasmart.govern.datasource.mapper.DataSourceConfigMapper;
import com.czh.datasmart.govern.datasource.mapper.DataSourceReadOnlySqlExecutionAuditMapper;
import com.czh.datasmart.govern.datasource.service.DataSourceManagementService;
import com.czh.datasmart.govern.datasource.support.ActorRole;
import com.czh.datasmart.govern.datasource.support.ConnectionTestStatus;
import com.czh.datasmart.govern.datasource.support.DataSourceStatus;
import com.czh.datasmart.govern.datasource.support.DataSourceType;
import com.czh.datasmart.govern.datasource.support.SyncPermissionAction;
import com.czh.datasmart.govern.datasource.support.SyncPermissionEvaluator;
import com.czh.datasmart.govern.datasource.support.SyncPermissionResource;
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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
     * 受控只读 SQL 执行边界配置。
     *
     * 元数据发现主要关注“结构”，只读 SQL 执行会读取真实业务值或统计值，
     * 因此必须拥有独立配置开关和更明确的行数、超时保护。
     */
    private final ReadOnlySqlExecutionProperties readOnlySqlExecutionProperties;

    /**
     * 只读 SQL 审计预览脱敏配置。
     *
     * readOnlySqlExecutionProperties 负责“能不能执行、最多返回多少行、最多执行多久”，
     * 本配置负责“执行记录进入审计表之前，SQL 预览是否需要做敏感字面量遮蔽”。
     *
     * 把脱敏配置拆成独立类，是为了让后续产品可以分别控制：
     * - 执行入口是否开放；
     * - 审计预览是否脱敏；
     * - 脱敏规则是否升级到独立 compliance-masking 模块。
     */
    private final ReadOnlySqlAuditMaskingProperties readOnlySqlAuditMaskingProperties;

    /**
     * 本地权限评估器。
     */
    private final SyncPermissionEvaluator syncPermissionEvaluator;

    /**
     * 受控只读 SQL 执行审计 Mapper。
     *
     * 只读 SQL 执行是当前模块里真正触达客户源库数据的入口之一，
     * 因此需要比普通连接测试和元数据发现更明确地沉淀审计记录。
     */
    private final DataSourceReadOnlySqlExecutionAuditMapper readOnlySqlExecutionAuditMapper;

    /**
     * 简单的进程内缓存。
     * 当前阶段先用内存缓存降低短时间重复点击带来的数据库压力；
     * 后续如果需要多实例共享、统一失效或更强一致性，再演进到 Redis 等外部缓存。
     */
    private static final Map<String, CachedDiscoveryEntry> METADATA_DISCOVERY_CACHE = new ConcurrentHashMap<>();

    /**
     * 只读 SQL 的危险关键字匹配。
     *
     * 这里使用单词边界，而不是简单 contains，是为了减少字段名里偶然包含 update/delete 等字符串时的误判。
     * 当前仍然保持保守策略：如果 SQL 字符串字面量里出现这些词也会被拒绝。
     * 商业产品里更严格的方案是引入 SQL Parser，把语句解析成 AST 后判断语句类型和访问对象。
     */
    private static final Pattern FORBIDDEN_SQL_KEYWORD_PATTERN = Pattern.compile(
            "(?i)\\b(insert|update|delete|drop|alter|truncate|create|replace|merge|call|exec|execute|grant|revoke|commit|rollback|use|show|describe|explain)\\b"
    );

    /**
     * 邮箱地址识别规则。
     *
     * 这里用于审计预览脱敏，不用于注册登录等强校验场景，因此采用“覆盖常见邮箱形态”的工程规则，
     * 而不是试图完整实现 RFC 邮箱语法。审计脱敏更关注降低泄露概率，宁可少量误遮蔽，也不要把明显邮箱原样落库。
     */
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"
    );

    /**
     * 中国大陆手机号识别规则。
     *
     * (?<!\\d) 与 (?!\\d) 用于避免把更长数字串中间的 11 位误识别为手机号。
     * 例如订单号、流水号可能包含很多数字，边界保护可以减少审计预览里不必要的遮蔽噪声。
     */
    private static final Pattern PHONE_PATTERN = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");

    /**
     * 中国大陆居民身份证号识别规则。
     *
     * 当前只做基础形态识别：17 位数字 + 末位数字或 X。
     * 完整身份证校验还应包含出生日期合法性、地区码、校验位算法等，但审计预览脱敏不应该依赖过重校验，
     * 因为用户传入的 SQL 条件可能包含历史脏数据或被截断的异常数据。
     */
    private static final Pattern IDENTITY_NUMBER_PATTERN = Pattern.compile(
            "(?<![A-Za-z0-9])\\d{17}[\\dXx](?![A-Za-z0-9])"
    );

    /**
     * 常见凭据字段赋值识别规则。
     *
     * 这条规则用于捕获 password='xxx'、token=\"xxx\"、api_key=xxx 这类条件。
     * 它会保留字段名，替换字段值，这样审计人员仍能知道 SQL 涉及“凭据类字段”，
     * 但不会看到实际凭据内容。
     */
    private static final Pattern CREDENTIAL_ASSIGNMENT_PATTERN = Pattern.compile(
            "(?i)\\b(password|passwd|pwd|token|access_token|refresh_token|secret|api_key|apikey|authorization)\\b\\s*=\\s*('(?:''|[^'])*'|\"(?:\"\"|[^\"])*\"|[^\\s,)]+)"
    );

    /**
     * Bearer Token 识别规则。
     *
     * 访问令牌经常出现在网关日志、接口调用日志或第三方回调日志里。
     * 如果质量扫描或异常诊断 SQL 查询这些日志表，where 条件可能会携带 Bearer token 片段，
     * 因此在审计预览里要优先遮蔽。
     */
    private static final Pattern BEARER_TOKEN_PATTERN = Pattern.compile(
            "(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]+"
    );

    /**
     * 单引号字符串字面量识别规则。
     *
     * SQL 标准中单引号内可以通过两个连续单引号表示一个真实单引号，例如 'Tom''s note'。
     * 这里用 (?:''|[^'])* 支持这种常见转义形态，避免在长文本中途错误截断。
     */
    private static final Pattern SINGLE_QUOTED_LITERAL_PATTERN = Pattern.compile("'((?:''|[^'])*)'");

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
        syncPermissionEvaluator.assertAllowed(request.getActorRole(), SyncPermissionResource.DATASOURCE_METADATA,
                SyncPermissionAction.VIEW_STRUCTURE);
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
     * 执行受控只读 SQL。
     *
     * 这个方法是 datasource-management 从“连接配置管理”走向“安全数据访问代理”的关键一步。
     * 它会把一次查询拆成几个清晰阶段：
     * 1. 校验模块开关和数据源生命周期，避免停用或删除的数据源继续被使用；
     * 2. 校验操作者角色，确保只有运营、管理员或服务账号能执行读取真实数据的动作；
     * 3. 校验 SQL 形态，只接受单条 SELECT，并拒绝注释、多语句、DDL、DML 和存储过程；
     * 4. 应用服务端行数和超时边界，调用方不能通过请求参数突破硬上限；
     * 5. 使用只读 JDBC 连接执行，并把结果转成稳定的 JSON 友好结构。
     *
     * 当前实现仍是同步短查询模型。
     * 如果后续要支持千万级扫描、分片并发、断点续跑或长时间统计，应通过 task-management
     * 创建异步执行任务，而不是让 HTTP 接口直接承担重负载。
     */
    @Override
    public ReadOnlySqlExecutionResult executeReadOnlySql(Long id, ReadOnlySqlExecutionRequest request) {
        if (!Boolean.TRUE.equals(readOnlySqlExecutionProperties.getEnabled())) {
            throw new IllegalStateException("受控只读 SQL 执行能力未启用，请检查 datasmart.datasource.read-only-sql.enabled 配置");
        }

        DataSourceConfig config = getRequiredDataSource(id);
        LocalDateTime executedAt = LocalDateTime.now();
        long startTime = System.currentTimeMillis();
        DataSourceType type = null;
        String safeSql = request.getSql();
        int maxRows = 0;
        int queryTimeoutSeconds = 0;
        try {
            ensureActive(config);
            type = DataSourceType.fromValue(config.getType());
            if (!type.isCanRead()) {
                throw new IllegalStateException("当前数据源类型不支持读取: " + type.name());
            }
            if (request.getSql() == null || request.getSql().isBlank()) {
                throw new IllegalArgumentException("SQL 不能为空");
            }
            if (request.getActorRole() == null || request.getActorRole().isBlank()) {
                throw new IllegalArgumentException("操作者角色不能为空，请通过请求体 actorRole 或 X-DataSmart-Actor-Role Header 传入");
            }

            syncPermissionEvaluator.assertAllowed(request.getActorRole(),
                    SyncPermissionResource.DATASOURCE_READONLY_QUERY,
                    SyncPermissionAction.EXECUTE_READ_ONLY_QUERY);

            safeSql = validateAndNormalizeReadOnlySql(request.getSql());
            maxRows = applyPositiveBoundedValue(
                    request.getMaxRows(),
                    readOnlySqlExecutionProperties.getDefaultMaxRows(),
                    readOnlySqlExecutionProperties.getAbsoluteMaxRows());
            queryTimeoutSeconds = applyPositiveBoundedValue(
                    request.getQueryTimeoutSeconds(),
                    readOnlySqlExecutionProperties.getDefaultQueryTimeoutSeconds(),
                    readOnlySqlExecutionProperties.getAbsoluteQueryTimeoutSeconds());
            String boundedSql = buildReadOnlyBoundedSql(type, safeSql, maxRows);

            List<String> warnings = new ArrayList<>();
            warnings.add("当前接口仅用于平台内部短查询、质量扫描统计和异常样本预览，不应用作大规模数据导出通道");
            warnings.add("服务端已对 SQL 二次包裹并强制应用最大返回行数: " + maxRows);
            warnings.add("结果值已统一转换为字符串或 null，避免不同 JDBC 驱动对象影响跨服务 JSON 契约");

            try (Connection connection = openConnection(config);
                 PreparedStatement preparedStatement = connection.prepareStatement(boundedSql)) {
                connection.setReadOnly(true);
                preparedStatement.setQueryTimeout(queryTimeoutSeconds);
                preparedStatement.setMaxRows(maxRows);

                List<String> columns = new ArrayList<>();
                List<Map<String, Object>> rows = new ArrayList<>();
                try (ResultSet resultSet = preparedStatement.executeQuery()) {
                    ResultSetMetaData metaData = resultSet.getMetaData();
                    int columnCount = metaData.getColumnCount();
                    for (int columnIndex = 1; columnIndex <= columnCount; columnIndex++) {
                        columns.add(metaData.getColumnLabel(columnIndex));
                    }

                    while (resultSet.next() && rows.size() < maxRows) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (int columnIndex = 1; columnIndex <= columnCount; columnIndex++) {
                            row.put(columns.get(columnIndex - 1), normalizeReadOnlySqlCellValue(resultSet.getObject(columnIndex)));
                        }
                        rows.add(row);
                    }

                    long durationMs = System.currentTimeMillis() - startTime;
                    recordReadOnlySqlExecutionAudit(config, type, request, safeSql, maxRows, queryTimeoutSeconds,
                            rows.size(), columnCount, durationMs, "SUCCESS", null, executedAt);

                    return new ReadOnlySqlExecutionResult(
                            config.getId(),
                            config.getName(),
                            type.name(),
                            request.getPurpose(),
                            true,
                            rows.size(),
                            columnCount,
                            maxRows,
                            queryTimeoutSeconds,
                            durationMs,
                            columns,
                            rows,
                            warnings,
                            LocalDateTime.now(),
                            "受控只读 SQL 执行成功"
                    );
                }
            }
        } catch (ClassNotFoundException | SQLException exception) {
            String message = "受控只读 SQL 执行失败: " + truncateMessage(exception.getMessage());
            recordReadOnlySqlExecutionAudit(config, type, request, safeSql, maxRows, queryTimeoutSeconds,
                    0, 0, System.currentTimeMillis() - startTime, "FAILED", message, executedAt);
            throw new IllegalStateException(message, exception);
        } catch (RuntimeException exception) {
            String message = truncateMessage(exception.getMessage());
            recordReadOnlySqlExecutionAudit(config, type, request, safeSql, maxRows, queryTimeoutSeconds,
                    0, 0, System.currentTimeMillis() - startTime, "FAILED", message, executedAt);
            throw exception;
        }
    }

    /**
     * 记录受控只读 SQL 执行审计。
     *
     * 审计记录采用 best-effort 策略：如果审计表暂未迁移或数据库短暂异常，
     * 不能反过来阻断已经完成的只读查询结果返回；但会写 warn 日志提示运维关注。
     *
     * 生产环境中，如果客户要求“无审计不访问”，可以把这里改成 fail-close 策略：
     * 即审计写入失败时直接让查询失败。
     */
    private void recordReadOnlySqlExecutionAudit(DataSourceConfig config,
                                                 DataSourceType dataSourceType,
                                                 ReadOnlySqlExecutionRequest request,
                                                 String sql,
                                                 Integer appliedMaxRows,
                                                 Integer appliedQueryTimeoutSeconds,
                                                 Integer returnedRowCount,
                                                 Integer columnCount,
                                                 Long durationMs,
                                                 String executionStatus,
                                                 String failureMessage,
                                                 LocalDateTime executedAt) {
        try {
            DataSourceReadOnlySqlExecutionAudit audit = new DataSourceReadOnlySqlExecutionAudit();
            audit.setDatasourceId(config.getId());
            audit.setDatasourceName(config.getName());
            audit.setDatasourceType(dataSourceType == null ? config.getType() : dataSourceType.name());
            audit.setPurpose(request.getPurpose());
            audit.setActorTenantId(request.getActorTenantId());
            audit.setActorId(request.getActorId());
            audit.setActorRole(normalizeUpper(request.getActorRole()));
            audit.setActorType(normalizeUpper(request.getActorType()));
            audit.setSourceService(request.getSourceService());
            audit.setTraceId(request.getTraceId());

            /*
             * 指纹与预览采用两条不同策略：
             * 1. sqlFingerprint 基于原始 SQL 计算，保证同一 SQL 在多次执行、多次脱敏配置调整后仍能聚合到同一指纹。
             * 2. sqlPreview 基于脱敏后的 SQL 写入，满足人工排查时“能看结构、不能看敏感值”的合规边界。
             *
             * 这样做比直接把脱敏 SQL 计算指纹更稳定，也比保存完整 SQL 更安全。
             */
            audit.setSqlFingerprint(sha256Hex(sql));
            audit.setSqlPreview(truncate(maskReadOnlySqlAuditPreview(sql), 1000));
            audit.setRequestedMaxRows(request.getMaxRows());
            audit.setAppliedMaxRows(appliedMaxRows);
            audit.setRequestedQueryTimeoutSeconds(request.getQueryTimeoutSeconds());
            audit.setAppliedQueryTimeoutSeconds(appliedQueryTimeoutSeconds);
            audit.setReturnedRowCount(returnedRowCount);
            audit.setColumnCount(columnCount);
            audit.setDurationMs(durationMs);
            audit.setExecutionStatus(executionStatus);
            audit.setFailureMessage(truncate(failureMessage, 1000));
            audit.setExecutedAt(executedAt);
            audit.setCreateTime(LocalDateTime.now());
            readOnlySqlExecutionAuditMapper.insert(audit);
        } catch (Exception auditException) {
            log.warn("记录受控只读 SQL 执行审计失败，datasourceId={}, purpose={}, status={}",
                    config.getId(),
                    request.getPurpose(), executionStatus, auditException);
        }
    }

    /**
     * 分页查询受控只读 SQL 执行审计。
     *
     * 该方法把审计从“只落库”推进到“可运营检索”。
     * 当前查询权限暂时复用本地权限策略中的 `SYNC_PERMISSION_POLICY + VIEW_POLICY`，
     * 因为审计记录本质上属于治理后台能力，不应开放给普通项目负责人或普通用户。
     *
     * 后续更合理的做法是新增独立的审计资源与动作，例如 `DATASOURCE_AUDIT + VIEW_AUDIT`，
     * 并由 permission-admin 统一下发角色、租户和数据范围策略。
     */
    @Override
    public IPage<DataSourceReadOnlySqlExecutionAudit> pageReadOnlySqlExecutionAudits(
            Integer current,
            Integer size,
            Long datasourceId,
            String purpose,
            String actorRole,
            Long actorTenantId,
            String executionStatus,
            String sqlFingerprint,
            LocalDateTime startTime,
            LocalDateTime endTime,
            String queryActorRole) {
        if (queryActorRole == null || queryActorRole.isBlank()) {
            throw new IllegalArgumentException("查询审计必须提供 queryActorRole 或 X-DataSmart-Actor-Role Header");
        }
        syncPermissionEvaluator.assertAllowed(queryActorRole,
                SyncPermissionResource.SYNC_PERMISSION_POLICY,
                SyncPermissionAction.VIEW_POLICY);

        LambdaQueryWrapper<DataSourceReadOnlySqlExecutionAudit> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(datasourceId != null, DataSourceReadOnlySqlExecutionAudit::getDatasourceId, datasourceId)
                .eq(hasText(purpose), DataSourceReadOnlySqlExecutionAudit::getPurpose, normalizeUpper(purpose))
                .eq(hasText(actorRole), DataSourceReadOnlySqlExecutionAudit::getActorRole, normalizeUpper(actorRole))
                .eq(actorTenantId != null, DataSourceReadOnlySqlExecutionAudit::getActorTenantId, actorTenantId)
                .eq(hasText(executionStatus), DataSourceReadOnlySqlExecutionAudit::getExecutionStatus, normalizeUpper(executionStatus))
                .eq(hasText(sqlFingerprint), DataSourceReadOnlySqlExecutionAudit::getSqlFingerprint, sqlFingerprint)
                .ge(startTime != null, DataSourceReadOnlySqlExecutionAudit::getExecutedAt, startTime)
                .le(endTime != null, DataSourceReadOnlySqlExecutionAudit::getExecutedAt, endTime)
                .orderByDesc(DataSourceReadOnlySqlExecutionAudit::getExecutedAt);
        return readOnlySqlExecutionAuditMapper.selectPage(new Page<>(safePage(current), safeSize(size)), wrapper);
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
     * 确保数据源处于启用状态。
     *
     * 元数据发现目前只禁止已删除数据源，而只读 SQL 执行会真正读取业务库内容，
     * 所以这里使用更严格的 ACTIVE 校验：
     * - INACTIVE 通常表示管理员临时停用，可能是安全、性能、凭据或业务窗口原因；
     * - DELETED 表示逻辑删除，任何执行型能力都不应继续使用；
     * - 只有 ACTIVE 才允许被质量扫描、字段画像或诊断查询消费。
     */
    private void ensureActive(DataSourceConfig config) {
        ensureNotDeleted(config);
        if (!DataSourceStatus.ACTIVE.equals(config.getStatus())) {
            throw new IllegalStateException("数据源未启用，不能执行只读 SQL: " + config.getId());
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
     * 应用正整数边界限制。
     *
     * 与元数据探查不同，SQL 执行的 maxRows 和 queryTimeoutSeconds 如果传入 0 或负数，
     * 可能导致驱动行为不一致甚至绕过限制，因此这里统一压到最小值 1。
     */
    private int applyPositiveBoundedValue(Integer requestedValue, Integer defaultValue, Integer absoluteMaxValue) {
        int value = requestedValue == null ? defaultValue : requestedValue;
        return Math.max(1, Math.min(value, absoluteMaxValue));
    }

    /**
     * 校验并归一化只读 SQL。
     *
     * 当前采用“保守字符串策略”：
     * - 必须以 SELECT 开头；
     * - 不允许分号，避免多语句；
     * - 不允许行注释和块注释，避免通过注释隐藏后半段语句或绕过人工审查；
     * - 不允许 DDL、DML、事务、权限、存储过程等危险关键字。
     *
     * 这套策略不追求覆盖所有 SQL 方言特性，而是服务于当前阶段的商业安全底线。
     * 后续如果要支持 WITH、窗口函数、CTE 或更复杂统计，应优先接入 SQL Parser，并按 AST 判断真正的语句类型。
     */
    private String validateAndNormalizeReadOnlySql(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("SQL 不能为空");
        }
        String normalizedSql = sql.trim();
        String lowerCaseSql = normalizedSql.toLowerCase();
        if (!lowerCaseSql.startsWith("select ")) {
            throw new IllegalArgumentException("当前只允许执行单条 SELECT 查询");
        }
        if (normalizedSql.contains(";")) {
            throw new IllegalArgumentException("只读 SQL 不允许包含分号或多语句");
        }
        if (normalizedSql.contains("--") || normalizedSql.contains("/*") || normalizedSql.contains("*/")) {
            throw new IllegalArgumentException("只读 SQL 不允许包含注释片段");
        }
        if (FORBIDDEN_SQL_KEYWORD_PATTERN.matcher(normalizedSql).find()) {
            throw new IllegalArgumentException("只读 SQL 包含高风险关键字，请改为纯 SELECT 查询");
        }
        return normalizedSql;
    }

    /**
     * 构建带服务端行数上限的 SQL。
     *
     * 这里不信任调用方自己在 SQL 中写 LIMIT/TOP，因为调用方可能忘记写、写得过大，或不同方言下行为不一致。
     * 因此服务端把原始 SELECT 包裹成子查询，再按数据源方言附加最终上限：
     * - MySQL/PostgreSQL 使用 `LIMIT n`；
     * - SQL Server 使用 `SELECT TOP (n)`。
     */
    private String buildReadOnlyBoundedSql(DataSourceType dataSourceType, String sql, int maxRows) {
        if (dataSourceType == DataSourceType.SQLSERVER) {
            return "SELECT TOP (" + maxRows + ") * FROM (" + sql + ") datasmart_safe_query";
        }
        return "SELECT * FROM (" + sql + ") datasmart_safe_query LIMIT " + maxRows;
    }

    /**
     * 归一化单元格值。
     *
     * JDBC 驱动返回的对象类型非常多，例如 BigDecimal、Timestamp、byte[]、Blob、Clob 等。
     * 当前接口的第一目标是跨模块契约稳定，所以先把非空值统一转成字符串；
     * 对二进制数据只返回长度提示，避免把大对象或不可读字节直接塞进 JSON 响应。
     */
    private Object normalizeReadOnlySqlCellValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof byte[] bytes) {
            return "[binary:" + bytes.length + " bytes]";
        }
        return String.valueOf(value);
    }

    /**
     * 计算 SQL 指纹。
     *
     * 这里使用 SHA-256，是因为它足够稳定、冲突概率极低，适合做审计检索和同类访问聚合。
     * 需要注意：指纹不是加密脱敏，不能从指纹还原 SQL；但如果同一 SQL 重复出现，指纹会相同。
     */
    private String sha256Hex(String value) {
        if (value == null) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte item : bytes) {
                builder.append(String.format("%02x", item));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 JDK 不支持 SHA-256 摘要算法", exception);
        }
    }

    /**
     * 脱敏只读 SQL 审计预览。
     *
     * 这个方法只处理“写入审计表的预览文本”，不会改变真正被执行的 SQL。
     * 原因是：
     * - 执行 SQL 必须保持调用方原始语义，否则会导致查询结果变化；
     * - 审计预览只用于排查和合规回溯，保留结构即可，不需要保留敏感字面量；
     * - 脱敏失败不应该影响已经完成的只读查询，因此规则应尽量简单、确定、无外部依赖。
     *
     * 当前采用正则规则覆盖最常见的敏感模式。它不是完整 SQL Parser，也不是最终合规脱敏中心；
     * 后续商业化增强时，应把字段分类分级、策略审批、脱敏模板和样本预览授权统一收敛到 compliance-masking 模块。
     */
    private String maskReadOnlySqlAuditPreview(String sql) {
        if (sql == null || !Boolean.TRUE.equals(readOnlySqlAuditMaskingProperties.getEnabled())) {
            return sql;
        }

        String maskedSql = sql;
        if (Boolean.TRUE.equals(readOnlySqlAuditMaskingProperties.getMaskCredentialAssignments())) {
            maskedSql = maskCredentialAssignments(maskedSql);
        }
        if (Boolean.TRUE.equals(readOnlySqlAuditMaskingProperties.getMaskBearerToken())) {
            maskedSql = BEARER_TOKEN_PATTERN.matcher(maskedSql).replaceAll("[MASKED_BEARER_TOKEN]");
        }
        if (Boolean.TRUE.equals(readOnlySqlAuditMaskingProperties.getMaskEmail())) {
            maskedSql = EMAIL_PATTERN.matcher(maskedSql).replaceAll("[MASKED_EMAIL]");
        }
        if (Boolean.TRUE.equals(readOnlySqlAuditMaskingProperties.getMaskPhone())) {
            maskedSql = PHONE_PATTERN.matcher(maskedSql).replaceAll("[MASKED_PHONE]");
        }
        if (Boolean.TRUE.equals(readOnlySqlAuditMaskingProperties.getMaskIdentityNumber())) {
            maskedSql = IDENTITY_NUMBER_PATTERN.matcher(maskedSql).replaceAll("[MASKED_IDENTITY_NUMBER]");
        }
        if (Boolean.TRUE.equals(readOnlySqlAuditMaskingProperties.getMaskQuotedLongText())) {
            maskedSql = maskLongQuotedLiterals(maskedSql);
        }
        return maskedSql;
    }

    /**
     * 遮蔽凭据字段赋值。
     *
     * 这里使用 Matcher + appendReplacement，而不是简单 replaceAll("$1 = ...")，
     * 是为了避免原始 SQL 里出现美元符号、反斜杠等特殊字符时触发 Java 正则替换语义，
     * 也便于后续保留更多上下文，例如原始等号两侧空格、字段别名或 JSON 路径。
     */
    private String maskCredentialAssignments(String sql) {
        Matcher matcher = CREDENTIAL_ASSIGNMENT_PATTERN.matcher(sql);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String replacement = matcher.group(1) + " = [MASKED_CREDENTIAL]";
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    /**
     * 遮蔽过长的单引号字符串字面量。
     *
     * 为什么要做这条“长度兜底”：
     * - 敏感信息并不总是长得像手机号、邮箱、身份证或 token；
     * - 很多客户系统会把地址、备注、证件扫描结果、cookie、外部流水、错误堆栈写成普通字符串；
     * - 如果 SQL 条件里包含这些长文本，直接落审计会违反数据最小化原则。
     *
     * 替换结果保留长度，例如 '[MASKED_LITERAL:128]'，这样排查人员仍可判断是否出现异常长条件，
     * 但不能看到原始内容。
     */
    private String maskLongQuotedLiterals(String sql) {
        Integer configuredThreshold = readOnlySqlAuditMaskingProperties.getMaxQuotedLiteralPreviewLength();
        int threshold = Math.max(1, configuredThreshold == null ? 24 : configuredThreshold);
        Matcher matcher = SINGLE_QUOTED_LITERAL_PATTERN.matcher(sql);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String literal = matcher.group(1);
            if (literal.length() <= threshold) {
                matcher.appendReplacement(buffer, Matcher.quoteReplacement(matcher.group()));
                continue;
            }
            String replacement = "'[MASKED_LITERAL:" + literal.length() + "]'";
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    /**
     * 截断字符串。
     *
     * 审计字段需要兼顾排查价值和敏感数据最小化原则。
     * 因此 SQL 预览、失败原因这类文本都只保留有限长度，完整 SQL 不进入审计表。
     */
    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    /**
     * 判断字符串是否有真实内容。
     */
    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * 归一化编码类字符串。
     */
    private String normalizeUpper(String value) {
        return hasText(value) ? value.trim().toUpperCase() : value;
    }

    /**
     * 保护分页页码，避免调用方传入 0 或负数。
     */
    private long safePage(Integer current) {
        return current == null || current <= 0 ? 1L : current.longValue();
    }

    /**
     * 保护分页大小。
     *
     * 审计查询不应该一次拉出大量记录；如果后续需要导出，应设计异步导出任务和审批流程。
     */
    private long safeSize(Integer size) {
        if (size == null || size <= 0) {
            return 10L;
        }
        return Math.min(size, 200);
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
