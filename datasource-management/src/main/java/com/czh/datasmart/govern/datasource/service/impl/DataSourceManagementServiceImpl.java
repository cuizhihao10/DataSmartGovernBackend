package com.czh.datasmart.govern.datasource.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import com.czh.datasmart.govern.datasource.controller.dto.MetadataDiscoveryRequest;
import com.czh.datasmart.govern.datasource.controller.dto.ReadOnlySqlExecutionRequest;
import com.czh.datasmart.govern.datasource.controller.dto.ReadOnlySqlExecutionResult;
import com.czh.datasmart.govern.datasource.entity.ConnectorCapabilityProfile;
import com.czh.datasmart.govern.datasource.entity.DataSourceCapabilityProfile;
import com.czh.datasmart.govern.datasource.entity.DataSourceConfig;
import com.czh.datasmart.govern.datasource.entity.DataSourceConnectionTestResult;
import com.czh.datasmart.govern.datasource.entity.DataSourceMetadataDiscoveryResult;
import com.czh.datasmart.govern.datasource.entity.DataSourceReadOnlySqlExecutionAudit;
import com.czh.datasmart.govern.datasource.mapper.DataSourceConfigMapper;
import com.czh.datasmart.govern.datasource.service.DataSourceManagementService;
import com.czh.datasmart.govern.datasource.service.support.ConnectorCapabilityRegistry;
import com.czh.datasmart.govern.datasource.service.support.DataSourceMetadataDiscoverySupport;
import com.czh.datasmart.govern.datasource.service.support.DataSourceReadOnlySqlSupport;
import com.czh.datasmart.govern.datasource.service.support.DatasourceProjectVisibility;
import com.czh.datasmart.govern.datasource.support.ConnectionTestStatus;
import com.czh.datasmart.govern.datasource.support.DataSourceStatus;
import com.czh.datasmart.govern.datasource.support.DataSourceType;
import com.czh.datasmart.govern.datasource.support.DataSourceUsagePurpose;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

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
     * 受控只读 SQL 执行支持组件。
     * 它承载 SQL 安全校验、执行、审计、脱敏和审计分页，避免主服务继续承担高敏感访问细节。
     */
    private final DataSourceReadOnlySqlSupport dataSourceReadOnlySqlSupport;

    /**
     * 元数据发现支持组件。
     * 主服务只保留“找到数据源配置并进入业务支撑组件”的编排职责，
     * 具体 JDBC 元数据读取、缓存、字段/索引/样例预览组装都交给该组件。
     */
    private final DataSourceMetadataDiscoverySupport dataSourceMetadataDiscoverySupport;

    /**
     * 连接器能力注册表。
     * 数据源详情接口仍根据具体数据源返回能力画像，但能力规则本身统一来自注册表，避免 DataSourceType、模板校验和前端向导各维护一套规则。
     */
    private final ConnectorCapabilityRegistry connectorCapabilityRegistry;

    /**
     * 创建数据源。
     * 这里会把 type 解析成 driverClassName 并固化到数据库中，
     * 后面执行连接测试或元数据采集时就不需要每次重新推导。
     */
    @Override
    @Transactional
    public DataSourceConfig createDataSource(Long tenantId, Long projectId, Long workspaceId,
                                             String name, String type, String jdbcUrl, String username,
                                             String password, String description, String usagePurpose) {
        ensureNameNotDuplicated(name, tenantId, projectId, null);
        DataSourceType dataSourceType = DataSourceType.fromValue(type);
        DataSourceUsagePurpose normalizedPurpose = DataSourceUsagePurpose.fromValue(usagePurpose);
        ensureJdbcUrlMatchesDeclaredTypeForSave(dataSourceType, jdbcUrl);

        DataSourceConfig config = new DataSourceConfig();
        config.setTenantId(tenantId);
        config.setProjectId(projectId);
        config.setWorkspaceId(workspaceId);
        config.setName(name);
        config.setType(dataSourceType.name());
        config.setUsagePurpose(normalizedPurpose.name());
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(username);
        config.setPassword(password);
        config.setDriverClassName(dataSourceType.getDriverClassName());
        config.setDescription(description);
        config.setStatus(DataSourceStatus.ACTIVE);
        LocalDateTime testedAt = LocalDateTime.now();
        config.setCreateTime(testedAt);
        config.setUpdateTime(testedAt);
        try {
            DataSourceConnectionTestResult result = runConnectionProbe(config, testedAt);
            applyConnectionTestResult(config, result, testedAt);
        } catch (DataSourceTypeMismatchException exception) {
            throw new IllegalArgumentException(exception.getMessage(), exception);
        } catch (ClassNotFoundException | SQLException exception) {
            applyConnectionTestFailure(config, exception, testedAt);
        }
        persistNewDataSource(config);

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
                                             String password, String description, String usagePurpose) {
        DataSourceConfig config = getRequiredDataSource(id);
        ensureNotDeleted(config);
        ensureNameNotDuplicated(name, config.getTenantId(), config.getProjectId(), id);
        DataSourceUsagePurpose normalizedPurpose = usagePurpose == null || usagePurpose.isBlank()
                ? DataSourceUsagePurpose.fromPersistedValueOrDefault(config.getUsagePurpose())
                : DataSourceUsagePurpose.fromValue(usagePurpose);
        DataSourceType dataSourceType = DataSourceType.fromValue(config.getType());
        ensureJdbcUrlMatchesDeclaredTypeForSave(dataSourceType, jdbcUrl);

        config.setName(name);
        config.setUsagePurpose(normalizedPurpose.name());
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(username);
        /*
         * 编辑数据源时密码为空表示“不轮换凭据”。
         *
         * 这是连接配置管理里的重要安全/体验边界：后端不应该为了改名称、用途或描述，要求前端重新展示或回传旧密码；
         * 旧密码也不应该因为列表接口被低敏化后丢失而被误写成空值。只有用户在编辑弹窗里明确填写新密码时，
         * 才把它视为一次凭据更新。
         */
        if (password != null && !password.isBlank()) {
            config.setPassword(password);
        }
        config.setDescription(description);
        LocalDateTime testedAt = LocalDateTime.now();
        try {
            DataSourceConnectionTestResult result = runConnectionProbe(config, testedAt);
            applyConnectionTestResult(config, result, testedAt);
        } catch (DataSourceTypeMismatchException exception) {
            throw new IllegalArgumentException(exception.getMessage(), exception);
        } catch (ClassNotFoundException | SQLException exception) {
            applyConnectionTestFailure(config, exception, testedAt);
        }
        persistUpdatedDataSource(config);

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
            DataSourceConnectionTestResult result = runConnectionProbe(config, testedAt);

            applyConnectionTestResult(config, result, testedAt);
            updateById(config);

            log.info("数据源连接测试成功，datasourceId={}", id);
            return result;
        } catch (ClassNotFoundException | SQLException exception) {
            String message = applyConnectionTestFailure(config, exception, testedAt);
            updateById(config);

            log.warn("数据源连接测试失败，datasourceId={}, reason={}", id, message);
            return new DataSourceConnectionTestResult(id, ConnectionTestStatus.FAILED, message, testedAt);
        }
    }

    /**
     * 测试尚未保存的数据源连接参数。
     *
     * <p>新建页面调用这个方法时，用户只是想确认“这组 URL、账号和连接密码能不能连通”。
     * 因此这里会构造一个只存在于内存中的 DataSourceConfig，复用正式连接测试的 JDBC 探测逻辑，
     * 但不会执行 save/update，也不会留下 lastTestStatus 之类的持久状态。</p>
     */
    @Override
    public DataSourceConnectionTestResult testConnection(String type, String jdbcUrl, String username, String password) {
        LocalDateTime testedAt = LocalDateTime.now();
        DataSourceConfig config = null;
        try {
            config = buildTransientConnectionTestConfig(type, jdbcUrl, username, password);
            DataSourceConnectionTestResult result = runConnectionProbe(config, testedAt);
            log.info("临时数据源连接测试成功，type={}", config.getType());
            return result;
        } catch (IllegalArgumentException | ClassNotFoundException | SQLException exception) {
            String message = truncateMessage(exception.getMessage());
            log.warn("临时数据源连接测试失败，type={}, reason={}", config == null ? type : config.getType(), message);
            return new DataSourceConnectionTestResult(0L, ConnectionTestStatus.FAILED, message, testedAt);
        }
    }

    /**
     * 测试编辑表单里的连接参数。
     *
     * <p>和 {@link #testConnection(Long)} 不同，这里不会直接使用数据库里整条旧配置，也不会把测试结果写回主表。
     * 它会把用户当前正在编辑的 JDBC URL、用户名和可选连接密码合并成一条临时配置：
     * 1. type 和 driverClassName 沿用已保存数据源，避免编辑时改变连接器类型；
     * 2. password 为空时沿用已保存密码，保持“留空不轮换凭据”的页面语义；
     * 3. 测试结果只返回给页面，用于保存前确认当前表单是否可用。</p>
     */
    @Override
    public DataSourceConnectionTestResult testConnection(Long id, String jdbcUrl, String username, String password) {
        DataSourceConfig savedConfig = getRequiredDataSource(id);
        ensureNotDeleted(savedConfig);
        LocalDateTime testedAt = LocalDateTime.now();
        try {
            DataSourceConfig probeConfig = buildEditingConnectionTestConfig(savedConfig, jdbcUrl, username, password);
            DataSourceConnectionTestResult result = runConnectionProbe(probeConfig, testedAt);
            log.info("编辑态数据源连接测试成功，datasourceId={}", id);
            return result;
        } catch (IllegalArgumentException | ClassNotFoundException | SQLException exception) {
            String message = truncateMessage(exception.getMessage());
            log.warn("编辑态数据源连接测试失败，datasourceId={}, reason={}", id, message);
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
        ConnectorCapabilityProfile capability = connectorCapabilityRegistry.getProfile(type.name());
        return new DataSourceCapabilityProfile(
                config.getId(),
                config.getName(),
                type.name(),
                capability.isCanRead(),
                capability.isCanWrite(),
                capability.getSupportedSyncModes().contains("FULL"),
                capability.getSupportedSyncModes().contains("INCREMENTAL_TIME")
                        || capability.getSupportedSyncModes().contains("INCREMENTAL_ID"),
                capability.getSupportedSyncModes().contains("STREAMING")
                        || capability.getSupportedSyncModes().contains("CDC"),
                capability.isSupportsSchemaDiscovery(),
                capability.isSupportsFieldMapping(),
                capability.isSupportsCheckpointResume(),
                capability.isSupportsPreviewSampling(),
                capability.isSupportsPartitionParallelism(),
                capability.getSupportedSyncModes(),
                capability.getSupportedWriteStrategies(),
                capability.getPerformanceRecommendations(),
                capability.getProductionLimitations(),
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
        return dataSourceMetadataDiscoverySupport.discoverMetadata(config, request);
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
        DataSourceConfig config = getRequiredDataSource(id);
        return dataSourceReadOnlySqlSupport.executeReadOnlySql(config, request);
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
            DatasourceProjectVisibility visibility,
            String executionStatus,
            String sqlFingerprint,
            LocalDateTime startTime,
            LocalDateTime endTime,
            String queryActorRole) {
        return dataSourceReadOnlySqlSupport.pageReadOnlySqlExecutionAudits(
                current,
                size,
                datasourceId,
                purpose,
                actorRole,
                actorTenantId,
                visibility,
                executionStatus,
                sqlFingerprint,
                startTime,
                endTime,
                queryActorRole
        );
    }

    /**
     * 构造临时连接测试配置。
     *
     * <p>临时测试不需要 tenant/project/name/usagePurpose，因为它不参与权限资源落库，也不会进入任务选择器。
     * 但它必须和正式数据源一样完成 type 归一化和 driverClassName 推导，确保两条测试路径使用同一套连接器规则。</p>
     */
    private DataSourceConfig buildTransientConnectionTestConfig(String type, String jdbcUrl, String username, String password) {
        DataSourceType dataSourceType = DataSourceType.fromValue(type);
        DataSourceConfig config = new DataSourceConfig();
        config.setId(0L);
        config.setType(dataSourceType.name());
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(username);
        config.setPassword(password);
        config.setDriverClassName(dataSourceType.getDriverClassName());
        return config;
    }

    /**
     * 基于已保存数据源和编辑表单构造临时测试配置。
     */
    private DataSourceConfig buildEditingConnectionTestConfig(DataSourceConfig savedConfig,
                                                              String jdbcUrl,
                                                              String username,
                                                              String password) {
        DataSourceType dataSourceType = DataSourceType.fromValue(savedConfig.getType());
        DataSourceConfig config = new DataSourceConfig();
        config.setId(savedConfig.getId());
        config.setType(dataSourceType.name());
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(username);
        config.setPassword(hasText(password) ? password : savedConfig.getPassword());
        config.setDriverClassName(dataSourceType.getDriverClassName());
        return config;
    }

    /**
     * 执行一次真实 JDBC 连接探测。
     *
     * <p>这个方法只负责打开连接、校验 connection.isValid，并组装轻量元数据诊断结果。
     * 是否把结果写回数据源主表由调用方决定：已保存数据源会回写 lastTestStatus，临时测试则只把结果返回给页面。</p>
     */
    private DataSourceConnectionTestResult runConnectionProbe(DataSourceConfig config,
                                                              LocalDateTime testedAt)
            throws SQLException, ClassNotFoundException {
        DataSourceType dataSourceType = DataSourceType.fromValue(config.getType());
        ensureJdbcUrlMatchesDeclaredTypeForProbe(dataSourceType, config.getJdbcUrl());
        try (Connection connection = openConnection(config)) {
            boolean valid = connection.isValid(5);
            if (!valid) {
                throw new SQLException("connection validation failed");
            }
            return buildConnectionTestSuccessResult(config, connection, testedAt);
        }
    }

    /**
     * 构造连接测试成功响应，并顺带执行一次轻量元数据探测。
     *
     * <p>为什么“连接测试成功”还要探测元数据：</p>
     * <p>JDBC 连接成功只证明网络、端口、账号、密码和驱动基本可用；数据同步创建任务还需要能读取库表结构。
     * MySQL 尤其容易出现“能连上 MySQL Server，但 JDBC URL 没有指定 database/catalog，或者账号没有目标库表权限”的情况。
     * 如果前端只显示一个绿色成功，用户就会误以为后续一定能选到表。</p>
     *
     * <p>这里的元数据探测故意保持“轻量”：
     * 1. 只读取产品名、驱动名、当前 catalog/schema；
     * 2. 只统计少量 TABLE 类型对象，用于判断是否至少能发现表；
     * 3. 不读取字段、不采样业务行，不扩大敏感数据接触面；
     * 4. 探测异常不推翻 JDBC 连通性成功，但会写入 warnings，让前端把风险展示出来。</p>
     */
    private DataSourceConnectionTestResult buildConnectionTestSuccessResult(DataSourceConfig config,
                                                                            Connection connection,
                                                                            LocalDateTime testedAt) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        DataSourceType dataSourceType = DataSourceType.fromValue(config.getType());
        String productName = metaData.getDatabaseProductName();
        ensureDatabaseProductMatchesDeclaredType(dataSourceType, productName);
        List<String> warnings = new ArrayList<>();
        String currentCatalog = safeConnectionCatalog(connection, warnings);
        String currentSchema = safeConnectionSchema(connection, warnings);
        Integer discoveredTableCount = null;
        boolean metadataDiscoverable = false;
        try {
            discoveredTableCount = countDiscoverableTables(metaData, dataSourceType, currentCatalog, currentSchema);
            metadataDiscoverable = discoveredTableCount > 0;
            appendMetadataProbeWarnings(config, dataSourceType, currentCatalog, currentSchema, discoveredTableCount, warnings);
        } catch (SQLException exception) {
            warnings.add("JDBC 已连通，但轻量元数据探测失败，通常是账号缺少元数据权限、catalog/schema 不正确或数据库拒绝 DatabaseMetaData 查询："
                    + truncateMessage(exception.getMessage()));
        }

        String message = metadataDiscoverable
                ? "连接测试成功，且可发现 " + discoveredTableCount + " 张用户表"
                : "连接测试成功，但未发现可用于任务配置的用户表，请检查 database/catalog、schema、表权限或筛选条件";
        DataSourceConnectionTestResult result =
                new DataSourceConnectionTestResult(config.getId(), ConnectionTestStatus.SUCCESS, message, testedAt);
        result.setProductName(productName);
        result.setProductVersion(metaData.getDatabaseProductVersion());
        result.setDriverName(metaData.getDriverName());
        result.setCurrentCatalog(currentCatalog);
        result.setCurrentSchema(currentSchema);
        result.setMetadataDiscoverable(metadataDiscoverable);
        result.setDiscoveredTableCount(discoveredTableCount);
        result.setWarnings(warnings);
        return result;
    }

    /**
     * 统计连接当前上下文下可发现的用户表数量。
     *
     * <p>这里要特别照顾 MySQL：在 JDBC 语义里 MySQL 的 database 对应 catalog，而不是 PostgreSQL 风格 schema。
     * 因此 MySQL 使用 {@code connection.getCatalog()} 作为 getTables 的第一个参数，并把 schemaPattern 置空；
     * PostgreSQL/SQL Server 则优先使用当前 schema。这个差异正是很多“能连上但查不到表”的根因。</p>
     */
    private int countDiscoverableTables(DatabaseMetaData metaData,
                                        DataSourceType dataSourceType,
                                        String currentCatalog,
                                        String currentSchema) throws SQLException {
        String catalogPattern = dataSourceType == DataSourceType.MYSQL ? blankToNull(currentCatalog) : null;
        String schemaPattern = dataSourceType == DataSourceType.MYSQL ? null : blankToNull(currentSchema);
        int count = 0;
        try (ResultSet tables = metaData.getTables(catalogPattern, schemaPattern, "%", new String[]{"TABLE"})) {
            while (tables.next()) {
                count++;
                if (count >= 200) {
                    return count;
                }
            }
        }
        return count;
    }

    /**
     * 根据轻量探测结果生成面向用户的诊断提示。
     */
    private void appendMetadataProbeWarnings(DataSourceConfig config,
                                             DataSourceType dataSourceType,
                                             String currentCatalog,
                                             String currentSchema,
                                             Integer discoveredTableCount,
                                             List<String> warnings) {
        if (dataSourceType == DataSourceType.MYSQL && !hasText(currentCatalog)) {
            warnings.add("MySQL 连接当前没有选中 database/catalog。请确认 JDBC URL 形如 jdbc:mysql://host:3306/database，否则任务创建时无法稳定列出业务表。");
        }
        if (dataSourceType != DataSourceType.MYSQL && !hasText(currentSchema)) {
            warnings.add("当前连接未返回默认 schema。PostgreSQL/SQL Server 场景下，请确认 search_path、schema 权限或连接用户默认 schema。");
        }
        if (discoveredTableCount != null && discoveredTableCount == 0) {
            warnings.add("JDBC URL 已连通，但当前账号在当前 catalog/schema 下没有发现 TABLE 类型对象。请检查表是否存在、账号是否拥有元数据读取权限，或创建任务时是否误用了 schema 筛选。");
        }
        if (config.getJdbcUrl() != null && config.getJdbcUrl().contains("localhost")) {
            warnings.add("当前 JDBC URL 包含 localhost。如果服务运行在 Docker 容器内，localhost 指向容器自身，生产/Compose 环境建议使用服务名或内网域名。");
        }
    }

    /**
     * 创建或编辑保存前的硬校验。
     *
     * <p>类型与 JDBC URL 前缀不一致属于配置语义错误，而不是普通网络不可达。这里必须阻断保存，
     * 否则平台会留下“标记为 PostgreSQL 的 MySQL 数据源”这类后续任务无法可靠解释的脏事实。</p>
     */
    private void ensureJdbcUrlMatchesDeclaredTypeForSave(DataSourceType dataSourceType, String jdbcUrl) {
        if (!dataSourceType.matchesJdbcUrl(jdbcUrl)) {
            throw new IllegalArgumentException(buildJdbcUrlMismatchMessage(dataSourceType, jdbcUrl));
        }
    }

    /**
     * 探测时的软校验。
     *
     * <p>手动测试已有数据源时不能直接抛业务异常中断页面诊断，而是要返回 FAILED 并回写最近测试状态。</p>
     */
    private void ensureJdbcUrlMatchesDeclaredTypeForProbe(DataSourceType dataSourceType, String jdbcUrl)
            throws DataSourceTypeMismatchException {
        if (!dataSourceType.matchesJdbcUrl(jdbcUrl)) {
            throw new DataSourceTypeMismatchException(buildJdbcUrlMismatchMessage(dataSourceType, jdbcUrl));
        }
    }

    /**
     * 建连后的二次兜底校验。
     *
     * <p>URL 前缀是第一道防线，真实数据库产品名是第二道防线。这样即使未来接入代理、网关或特殊驱动，
     * 也不会把实际连到 MySQL 的连接误标记成 PostgreSQL。</p>
     */
    private void ensureDatabaseProductMatchesDeclaredType(DataSourceType dataSourceType, String productName)
            throws DataSourceTypeMismatchException {
        if (!dataSourceType.matchesDatabaseProductName(productName)) {
            throw new DataSourceTypeMismatchException("数据源类型与真实数据库产品不一致：选择的是 "
                    + dataSourceType.getDisplayName() + "，但 JDBC 返回的数据库产品是 "
                    + (hasText(productName) ? productName : "未知") + "。请修正数据源类型或 JDBC URL。");
        }
    }

    /**
     * 把成功探测结果写回数据源主记录。
     */
    private void applyConnectionTestResult(DataSourceConfig config,
                                           DataSourceConnectionTestResult result,
                                           LocalDateTime testedAt) {
        config.setLastTestStatus(result.getTestStatus());
        config.setLastTestMessage(truncateMessage(result.getMessage()));
        config.setLastTestTime(testedAt);
        config.setUpdateTime(testedAt);
    }

    /**
     * 把失败探测结果写回数据源主记录，并返回低敏错误摘要供日志和接口响应复用。
     */
    private String applyConnectionTestFailure(DataSourceConfig config,
                                              Exception exception,
                                              LocalDateTime testedAt) {
        String message = truncateMessage(exception.getMessage());
        config.setLastTestStatus(ConnectionTestStatus.FAILED);
        config.setLastTestMessage(message);
        config.setLastTestTime(testedAt);
        config.setUpdateTime(testedAt);
        return message;
    }

    private String buildJdbcUrlMismatchMessage(DataSourceType dataSourceType, String jdbcUrl) {
        return "数据源类型与 JDBC URL 不一致：选择的是 " + dataSourceType.getDisplayName()
                + "，当前 URL 前缀是 " + extractJdbcUrlPrefix(jdbcUrl)
                + "。正确格式示例：" + dataSourceType.describeExpectedJdbcUrl()
                + "。请修正类型或 JDBC URL 后再测试/保存。";
    }

    private String extractJdbcUrlPrefix(String jdbcUrl) {
        if (!hasText(jdbcUrl)) {
            return "空";
        }
        String trimmed = jdbcUrl.trim();
        int firstColon = trimmed.indexOf(':');
        if (firstColon < 0) {
            return "无法识别";
        }
        int secondColon = trimmed.indexOf(':', firstColon + 1);
        if (secondColon > firstColon) {
            return trimmed.substring(0, secondColon + 1);
        }
        return trimmed.length() > 32 ? trimmed.substring(0, 32) + "..." : trimmed;
    }

    private String safeConnectionCatalog(Connection connection, List<String> warnings) {
        try {
            return connection.getCatalog();
        } catch (SQLException exception) {
            warnings.add("读取当前 catalog 失败：" + truncateMessage(exception.getMessage()));
            return null;
        }
    }

    private String safeConnectionSchema(Connection connection, List<String> warnings) {
        try {
            return connection.getSchema();
        } catch (SQLException exception) {
            warnings.add("读取当前 schema 失败：" + truncateMessage(exception.getMessage()));
            return null;
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
    private void ensureNameNotDuplicated(String name, Long tenantId, Long projectId, Long currentId) {
        LambdaQueryWrapper<DataSourceConfig> wrapper = new LambdaQueryWrapper<DataSourceConfig>()
                .eq(DataSourceConfig::getTenantId, tenantId)
                .eq(DataSourceConfig::getProjectId, projectId)
                .eq(DataSourceConfig::getName, name)
                .ne(currentId != null, DataSourceConfig::getId, currentId)
                .ne(DataSourceConfig::getStatus, DataSourceStatus.DELETED);
        if (count(wrapper) > 0) {
            throw duplicateDataSourceName(name);
        }
    }

    /**
     * 保存新数据源时把数据库唯一键冲突翻译为业务错误。
     *
     * <p>保存前的名称查询用于提前给用户友好提示；这里的 catch 是并发兜底：
     * 如果两个窗口同时提交同一个租户、项目、名称，数据库唯一索引仍然是最终裁判，
     * 但不能把底层约束名和 SQL 堆栈暴露成“内部异常”。</p>
     */
    private void persistNewDataSource(DataSourceConfig config) {
        try {
            save(config);
        } catch (DuplicateKeyException exception) {
            throw duplicateDataSourceName(config.getName());
        }
    }

    /**
     * 更新数据源名称时同样需要兜住数据库唯一键冲突。
     */
    private void persistUpdatedDataSource(DataSourceConfig config) {
        try {
            updateById(config);
        } catch (DuplicateKeyException exception) {
            throw duplicateDataSourceName(config.getName());
        }
    }

    private PlatformBusinessException duplicateDataSourceName(String name) {
        return new PlatformBusinessException(
                PlatformErrorCode.DUPLICATE_OPERATION,
                "当前项目下已存在同名数据源，请修改名称后再保存: " + name);
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

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
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
     * 数据源类型一致性失败。
     *
     * <p>继承 SQLException 是为了让探测链路把它当作一次连接测试失败来回写 FAILED；
     * 创建和编辑保存入口会单独捕获并转成 IllegalArgumentException，阻断脏配置落库。</p>
     */
    private static class DataSourceTypeMismatchException extends SQLException {

        DataSourceTypeMismatchException(String message) {
            super(message);
        }
    }

}
