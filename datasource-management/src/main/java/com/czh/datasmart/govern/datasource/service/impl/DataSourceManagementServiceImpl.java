package com.czh.datasmart.govern.datasource.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.czh.datasmart.govern.datasource.controller.dto.MetadataDiscoveryRequest;
import com.czh.datasmart.govern.datasource.controller.dto.ReadOnlySqlExecutionRequest;
import com.czh.datasmart.govern.datasource.controller.dto.ReadOnlySqlExecutionResult;
import com.czh.datasmart.govern.datasource.entity.DataSourceCapabilityProfile;
import com.czh.datasmart.govern.datasource.entity.DataSourceConfig;
import com.czh.datasmart.govern.datasource.entity.DataSourceConnectionTestResult;
import com.czh.datasmart.govern.datasource.entity.DataSourceMetadataDiscoveryResult;
import com.czh.datasmart.govern.datasource.entity.DataSourceReadOnlySqlExecutionAudit;
import com.czh.datasmart.govern.datasource.mapper.DataSourceConfigMapper;
import com.czh.datasmart.govern.datasource.service.DataSourceManagementService;
import com.czh.datasmart.govern.datasource.service.support.DataSourceMetadataDiscoverySupport;
import com.czh.datasmart.govern.datasource.service.support.DataSourceReadOnlySqlSupport;
import com.czh.datasmart.govern.datasource.service.support.DatasourceProjectVisibility;
import com.czh.datasmart.govern.datasource.support.ConnectionTestStatus;
import com.czh.datasmart.govern.datasource.support.DataSourceStatus;
import com.czh.datasmart.govern.datasource.support.DataSourceType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.LocalDateTime;
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
     * 创建数据源。
     * 这里会把 type 解析成 driverClassName 并固化到数据库中，
     * 后面执行连接测试或元数据采集时就不需要每次重新推导。
     */
    @Override
    @Transactional
    public DataSourceConfig createDataSource(Long tenantId, Long projectId, Long workspaceId,
                                             String name, String type, String jdbcUrl, String username,
                                             String password, String description) {
        ensureNameNotDuplicated(name, tenantId, projectId, null);
        DataSourceType dataSourceType = DataSourceType.fromValue(type);

        DataSourceConfig config = new DataSourceConfig();
        config.setTenantId(tenantId);
        config.setProjectId(projectId);
        config.setWorkspaceId(workspaceId);
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
        ensureNameNotDuplicated(name, config.getTenantId(), config.getProjectId(), id);

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

}
