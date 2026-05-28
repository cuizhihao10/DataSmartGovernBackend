/**
 * @Author : Cui
 * @Date: 2026/05/28 23:58
 * @Description DataSmart Govern Backend - AgentRuntimeJdbcConnectionManager.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.persistence;

import com.czh.datasmart.govern.agent.config.AgentRuntimePersistenceProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * Agent Runtime 专用 JDBC 连接与轻量事务管理器。
 *
 * <p>这个类不是为了替代 Spring 事务体系，而是当前 agent-runtime 手写 JDBC 阶段的过渡性事务边界。
 * 4.17 与 4.18 已经分别实现了 MySQL 审计仓储和 MySQL outbox 仓储；如果它们各自调用
 * {@link DataSource#getConnection()}，就算两个仓储都写 MySQL，也仍然是两个独立连接、两个独立提交，
 * 不能解决“审计状态提交成功但 outbox 写入失败”的双写问题。</p>
 *
 * <p>这里通过 {@link ThreadLocal} 保存当前线程正在使用的事务连接，让同一条服务调用链上的
 * audit store 与 outbox store 能复用同一个 JDBC Connection。工具审计状态机当前是同步调用，
 * 因此 ThreadLocal 足够覆盖“保存状态 -> 发布事件 -> outbox sink append”的链路。
 * 后续如果引入异步线程池、Reactor、分布式事务或 MyBatis/Spring Transaction，应把这里替换成更标准的事务设施。</p>
 *
 * <p>边界说明：
 * 1. 只管理 agent-runtime 自己的 JDBC 连接，不跨 datasource-management、task-management 等其他微服务；
 * 2. 只适合单 JVM 内同步调用链，不要把事务连接传给异步线程；
 * 3. 只有 {@code audit-store=mysql + outbox-store=mysql + database-enabled=true} 同时满足时，
 *    才代表“工具审计状态与 outbox 事件可以同事务提交”。</p>
 */
@Component
@ConditionalOnBean(name = "agentRuntimeJdbcDataSource")
public class AgentRuntimeJdbcConnectionManager {

    /**
     * Agent Runtime 专用连接池。
     *
     * <p>该连接池由 {@code AgentRuntimeJdbcPersistenceConfiguration} 条件化创建，
     * 默认 memory 模式不会注册该 Bean，因此本类也不会出现在本地轻量启动路径中。</p>
     */
    private final DataSource dataSource;

    /**
     * 当前持久化策略。
     *
     * <p>连接管理器需要知道 audit/outbox 是否都已经切到 MySQL，避免服务层在只有一边是数据库时
     * 误以为已经获得了完整事务 outbox 语义。</p>
     */
    private final AgentRuntimePersistenceProperties properties;

    /**
     * 当前线程绑定的事务连接。
     *
     * <p>audit store 与 outbox store 的调用发生在同一个请求线程内时，会从这里读取同一条 Connection。
     * 事务结束后必须 remove，防止连接引用泄漏到线程池复用后的下一次请求。</p>
     */
    private final ThreadLocal<Connection> transactionConnection = new ThreadLocal<>();

    public AgentRuntimeJdbcConnectionManager(@Qualifier("agentRuntimeJdbcDataSource") DataSource dataSource,
                                             AgentRuntimePersistenceProperties properties) {
        this.dataSource = dataSource;
        this.properties = properties;
    }

    /**
     * 在当前事务连接或一条临时连接上执行 JDBC 操作。
     *
     * <p>仓储查询、单条写入、状态更新都应通过该方法获取连接。
     * 如果外层已经调用 {@link #executeInTransaction(SqlConnectionCallback)}，这里会复用同一条连接；
     * 如果没有外层事务，则临时获取一条连接并在操作结束后关闭，保持与普通 JDBC 使用方式一致。</p>
     *
     * @param callback 需要使用 JDBC Connection 执行的逻辑。
     * @return callback 返回值。
     */
    public <T> T executeWithConnection(SqlConnectionCallback<T> callback) {
        Connection currentConnection = transactionConnection.get();
        if (currentConnection != null) {
            return invoke(callback, currentConnection);
        }
        try (Connection connection = dataSource.getConnection()) {
            return invoke(callback, connection);
        } catch (SQLException exception) {
            throw new IllegalStateException("获取 Agent Runtime JDBC 连接失败", exception);
        }
    }

    /**
     * 使用同一条 JDBC Connection 执行一个事务。
     *
     * <p>如果当前线程已经处在事务中，则直接复用外层事务，避免嵌套事务把内层提前 commit。
     * 如果当前线程没有事务，则创建新连接、关闭 autoCommit、执行 callback、成功 commit、失败 rollback。
     * 这正好覆盖工具状态变更链路中的关键步骤：保存 audit 状态、发布状态事件、outbox sink 追加待投递记录。</p>
     *
     * @param callback 需要在事务内执行的逻辑。
     * @return callback 返回值。
     */
    public <T> T executeInTransaction(SqlConnectionCallback<T> callback) {
        Connection existingConnection = transactionConnection.get();
        if (existingConnection != null) {
            return invoke(callback, existingConnection);
        }
        try (Connection connection = dataSource.getConnection()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            transactionConnection.set(connection);
            try {
                T result = invoke(callback, connection);
                connection.commit();
                return result;
            } catch (RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            } finally {
                transactionConnection.remove();
                connection.setAutoCommit(originalAutoCommit);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("执行 Agent Runtime JDBC 事务失败", exception);
        }
    }

    /**
     * 判断当前配置是否具备“审计状态 + outbox 事件”同事务提交条件。
     *
     * <p>只有 audit 与 outbox 都是 MySQL，并且数据库总开关开启时，服务层才应该把事件 outbox 失败视为
     * 可以通过 rollback 恢复的必达失败。否则其中一边仍是 memory，事务只能覆盖部分事实，不能假装已经完全原子。</p>
     */
    public boolean isStateAndOutboxTransactionAvailable() {
        return properties.isStateAndOutboxMysqlEnabled();
    }

    private <T> T invoke(SqlConnectionCallback<T> callback, Connection connection) {
        try {
            return callback.doWithConnection(connection);
        } catch (SQLException exception) {
            throw new IllegalStateException("执行 Agent Runtime JDBC 操作失败", exception);
        }
    }

    private void rollbackQuietly(Connection connection, RuntimeException originalException) {
        try {
            connection.rollback();
        } catch (SQLException rollbackException) {
            originalException.addSuppressed(rollbackException);
        }
    }

    /**
     * JDBC 回调接口。
     *
     * <p>允许仓储代码保留原生 SQLException 抛出方式，由连接管理器统一包装成运行时异常。
     * 这样上层服务不需要暴露 JDBC checked exception，也能在事务失败时统一回滚。</p>
     */
    @FunctionalInterface
    public interface SqlConnectionCallback<T> {

        T doWithConnection(Connection connection) throws SQLException;
    }
}
