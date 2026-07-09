/**
 * @Author : Cui
 * @Date: 2026/06/20 03:42
 * @Description DataSmart Govern Backend - DriverManagerSyncJdbcConnectionProvider.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.service.execution.jdbc;

import com.czh.datasmart.govern.datasource.entity.DataSourceConfig;
import com.czh.datasmart.govern.datasource.mapper.DataSourceConfigMapper;
import com.czh.datasmart.govern.datasource.service.support.DataSourceCredentialCipherSupport;
import com.czh.datasmart.govern.datasource.support.DataSourceStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * 基于 DriverManager 的同步执行 JDBC 连接提供者。
 *
 * <p>当前项目已有连接测试、元数据发现、只读 SQL 能力都使用 DriverManager。
 * 本阶段为了快速闭合执行链路，沿用同一风格，避免在真实 reader/writer 尚未稳定前引入连接池生命周期复杂度。
 * 但这里已经通过 `SyncJdbcConnectionProvider` 接口隔离了实现，后续可以替换成 HikariCP、租户级连接池、
 * 密钥管理服务或连接代理，而不需要改动 reader/writer 主流程。</p>
 */
@Component
@RequiredArgsConstructor
public class DriverManagerSyncJdbcConnectionProvider implements SyncJdbcConnectionProvider {

    /**
     * 数据源配置 Mapper。
     * 连接提供者只在内部读取 jdbcUrl/username/password，不能把这些字段返回给 API 或事件。
     */
    private final DataSourceConfigMapper dataSourceConfigMapper;

    /**
     * 数据源凭据解密组件。
     *
     * <p>同步执行器是最需要真实密码的链路，但它也不应该理解“密码字段如何加密存储”。
     * 这里保持 provider 只负责打开连接，密钥版本、历史明文兼容和未来 KMS 替换都由独立组件承担。</p>
     */
    private final DataSourceCredentialCipherSupport credentialCipherSupport;

    @Override
    public Connection openConnection(Long datasourceId, boolean readOnly) throws SQLException, ClassNotFoundException {
        DataSourceConfig datasource = dataSourceConfigMapper.selectById(datasourceId);
        if (datasource == null) {
            throw new IllegalArgumentException("同步执行所需数据源不存在: " + datasourceId);
        }
        ensureActive(datasource);
        if (datasource.getDriverClassName() == null || datasource.getDriverClassName().isBlank()) {
            throw new IllegalStateException("数据源缺少 driverClassName，不能打开 JDBC 连接: " + datasourceId);
        }
        Class.forName(datasource.getDriverClassName());
        DriverManager.setLoginTimeout(5);
        String connectionPassword = credentialCipherSupport.decryptForUse(datasource.getPassword());
        Connection connection = DriverManager.getConnection(datasource.getJdbcUrl(), datasource.getUsername(), connectionPassword);
        connection.setReadOnly(readOnly);
        return connection;
    }

    /**
     * 校验数据源处于可执行状态。
     */
    private void ensureActive(DataSourceConfig datasource) {
        if (DataSourceStatus.DELETED.equals(datasource.getStatus())) {
            throw new IllegalStateException("数据源已删除，不能参与同步执行: " + datasource.getId());
        }
        if (!DataSourceStatus.ACTIVE.equals(datasource.getStatus())) {
            throw new IllegalStateException("数据源未启用，不能参与同步执行: " + datasource.getId());
        }
    }
}
