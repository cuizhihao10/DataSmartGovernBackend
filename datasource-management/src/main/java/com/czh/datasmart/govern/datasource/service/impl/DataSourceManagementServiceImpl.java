package com.czh.datasmart.govern.datasource.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.czh.datasmart.govern.datasource.entity.DataSourceConfig;
import com.czh.datasmart.govern.datasource.entity.DataSourceConnectionTestResult;
import com.czh.datasmart.govern.datasource.mapper.DataSourceConfigMapper;
import com.czh.datasmart.govern.datasource.service.DataSourceManagementService;
import com.czh.datasmart.govern.datasource.support.ConnectionTestStatus;
import com.czh.datasmart.govern.datasource.support.DataSourceStatus;
import com.czh.datasmart.govern.datasource.support.DataSourceType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.NoSuchElementException;

/**
 * 数据源管理服务实现。
 * <p>
 * 当前这个模块的第一阶段目标，不是做复杂的元数据采集，而是先把“外部数据源登记与可用性管理”做好。
 * 因此这里的核心职责主要有三类：
 * 1. 维护数据源配置主记录。
 * 2. 管理数据源启用、停用、删除等生命周期状态。
 * 3. 通过 JDBC 做最基础的连通性测试，验证配置是否真的可用。
 * <p>
 * 这也是后续很多功能的前置条件：
 * - 如果连通性不可靠，元数据采集就无从谈起。
 * - 如果没有启停管理，其他模块很难判断某个数据源是否允许被使用。
 */
@Slf4j
@Service
public class DataSourceManagementServiceImpl extends ServiceImpl<DataSourceConfigMapper, DataSourceConfig>
        implements DataSourceManagementService {

    /**
     * 创建数据源。
     * <p>
     * 这里会把 type 解析成 driverClassName 并固化到数据库中。
     * 好处是后面真正执行连接测试或元数据采集时，不需要每次再做推导。
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

        log.info("Created datasource config: {}", config.getId());
        return config;
    }

    /**
     * 更新数据源配置。
     * <p>
     * 这里不允许修改 type，目的是避免“逻辑上一条数据源记录”的语义发生根本改变。
     * 如果真要从 MySQL 改成 PostgreSQL，更合理的方式通常是新建一条数据源。
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

        log.info("Updated datasource config: {}", id);
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
        log.info("Enabled datasource config: {}", id);
        return config;
    }

    /**
     * 停用数据源。
     * <p>
     * 停用并不会删除配置，而是表达“这条配置暂时不允许被其他业务使用”。
     * 这在运维窗口、网络故障或权限调整期间非常常见。
     */
    @Override
    @Transactional
    public DataSourceConfig disableDataSource(Long id) {
        DataSourceConfig config = getRequiredDataSource(id);
        ensureNotDeleted(config);
        config.setStatus(DataSourceStatus.INACTIVE);
        config.setUpdateTime(LocalDateTime.now());
        updateById(config);
        log.info("Disabled datasource config: {}", id);
        return config;
    }

    /**
     * 逻辑删除数据源。
     * <p>
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
        log.info("Deleted datasource config logically: {}", id);
        return config;
    }

    /**
     * 测试数据源连接。
     * <p>
     * 这里使用最基础的 JDBC 连接测试逻辑：
     * 1. 根据数据库类型加载驱动类。
     * 2. 通过 DriverManager 尝试建立连接。
     * 3. 如果能在超时时间内拿到连接并通过 isValid，则视为成功。
     * <p>
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
            Class.forName(config.getDriverClassName());
            DriverManager.setLoginTimeout(5);
            try (Connection connection = DriverManager.getConnection(
                    config.getJdbcUrl(), config.getUsername(), config.getPassword())) {
                boolean valid = connection.isValid(5);
                if (!valid) {
                    throw new SQLException("connection validation failed");
                }
            }

            config.setLastTestStatus(ConnectionTestStatus.SUCCESS);
            config.setLastTestMessage("connection test succeeded");
            config.setLastTestTime(testedAt);
            config.setUpdateTime(testedAt);
            updateById(config);

            log.info("Datasource connection test succeeded: {}", id);
            return new DataSourceConnectionTestResult(id, ConnectionTestStatus.SUCCESS,
                    "connection test succeeded", testedAt);
        } catch (ClassNotFoundException | SQLException exception) {
            config.setLastTestStatus(ConnectionTestStatus.FAILED);
            config.setLastTestMessage(truncateMessage(exception.getMessage()));
            config.setLastTestTime(testedAt);
            config.setUpdateTime(testedAt);
            updateById(config);

            log.warn("Datasource connection test failed, datasourceId={}, reason={}", id, exception.getMessage());
            return new DataSourceConnectionTestResult(id, ConnectionTestStatus.FAILED,
                    truncateMessage(exception.getMessage()), testedAt);
        }
    }

    /**
     * 获取必存在的数据源。
     */
    private DataSourceConfig getRequiredDataSource(Long id) {
        DataSourceConfig config = getById(id);
        if (config == null) {
            throw new NoSuchElementException("Datasource not found: " + id);
        }
        return config;
    }

    /**
     * 逻辑删除后的数据源不允许再继续参与常规业务操作。
     */
    private void ensureNotDeleted(DataSourceConfig config) {
        if (DataSourceStatus.DELETED.equals(config.getStatus())) {
            throw new IllegalStateException("Datasource has been deleted: " + config.getId());
        }
    }

    /**
     * 名称去重校验。
     * <p>
     * 在数据源管理里，名称通常会被用于下拉选择、运维识别和人工排查，
     * 因此重复名称会明显增加误操作风险。
     */
    private void ensureNameNotDuplicated(String name, Long currentId) {
        LambdaQueryWrapper<DataSourceConfig> wrapper = new LambdaQueryWrapper<DataSourceConfig>()
                .eq(DataSourceConfig::getName, name)
                .ne(currentId != null, DataSourceConfig::getId, currentId)
                .ne(DataSourceConfig::getStatus, DataSourceStatus.DELETED);
        if (count(wrapper) > 0) {
            throw new IllegalArgumentException("datasource name already exists: " + name);
        }
    }

    /**
     * 避免把异常信息原样无限制写入数据库字段。
     */
    private String truncateMessage(String message) {
        if (message == null || message.isBlank()) {
            return "unknown connection error";
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }
}
