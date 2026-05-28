/**
 * @Author : Cui
 * @Date: 2026/05/28 23:59
 * @Description DataSmart Govern Backend - AgentRuntimeJdbcConnectionManagerTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.persistence;

import com.czh.datasmart.govern.agent.config.AgentRuntimePersistenceProperties;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Agent Runtime JDBC 连接管理器测试。
 *
 * <p>这组测试保护 4.19 的事务 outbox 基础：audit store 与 outbox store 必须能在同一个服务调用线程内
 * 复用同一条 JDBC Connection，否则“同事务提交”就只停留在注释里。这里使用 Mockito 模拟 DataSource/Connection，
 * 不连接真实 MySQL，确保默认单测仍然轻量。</p>
 */
class AgentRuntimeJdbcConnectionManagerTest {

    @Test
    void transactionShouldReuseSameConnectionForNestedJdbcOperations() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        AgentRuntimeJdbcConnectionManager manager = new AgentRuntimeJdbcConnectionManager(dataSource, mysqlProperties());

        manager.executeInTransaction(transactionConnection -> {
            manager.executeWithConnection(nestedConnection -> {
                assertSame(transactionConnection, nestedConnection);
                return null;
            });
            return null;
        });

        verify(dataSource, times(1)).getConnection();
        verify(connection).setAutoCommit(false);
        verify(connection).commit();
        verify(connection).setAutoCommit(true);
        verify(connection).close();
    }

    @Test
    void transactionShouldRollbackWhenCallbackFails() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        AgentRuntimeJdbcConnectionManager manager = new AgentRuntimeJdbcConnectionManager(dataSource, mysqlProperties());

        IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
                manager.executeInTransaction(transactionConnection -> {
                    throw new IllegalStateException("模拟 outbox 写入失败，需要回滚审计状态");
                })
        );

        assertTrue(exception.getMessage().contains("模拟 outbox 写入失败"));
        verify(connection).rollback();
        verify(connection).setAutoCommit(true);
        verify(connection).close();
    }

    private AgentRuntimePersistenceProperties mysqlProperties() {
        AgentRuntimePersistenceProperties properties = new AgentRuntimePersistenceProperties();
        properties.setDatabaseEnabled(true);
        properties.setAuditStore("mysql");
        properties.setOutboxStore("mysql");
        return properties;
    }
}
