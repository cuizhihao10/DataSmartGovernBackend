/**
 * @Author : Cui
 * @Date: 2026/05/28 19:32
 * @Description DataSmart Govern Backend - AgentRuntimeJdbcPersistenceConfiguration.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Agent Runtime JDBC 持久化配置。
 *
 * <p>该配置只在同时满足以下条件时生效：
 * 1. {@code datasmart.agent-runtime.persistence.database-enabled=true}；
 * 2. 工具审计、工具事件 outbox 或异步命令 outbox 至少一个明确选择 MySQL 仓储。
 *
 * <p>这样设计是为了避免一个很常见的商业化项目坑：只要把 JDBC/MyBatis 依赖加入模块，Spring Boot 就可能在默认本地环境
 * 尝试自动创建数据源，导致没有启动 MySQL 的开发者无法运行任何单元测试或应用。这里采用独立 HikariDataSource Bean，
 * 且通过条件表达式显式收口，默认 memory 模式不会创建连接池。</p>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression(
        "'${datasmart.agent-runtime.persistence.database-enabled:false}'.equalsIgnoreCase('true') "
                + "&& ('${datasmart.agent-runtime.persistence.audit-store:memory}'.equalsIgnoreCase('mysql') "
                + "|| '${datasmart.agent-runtime.persistence.outbox-store:memory}'.equalsIgnoreCase('mysql') "
                + "|| '${datasmart.agent-runtime.async-task-commands.outbox.store:memory}'.equalsIgnoreCase('mysql'))"
)
public class AgentRuntimeJdbcPersistenceConfiguration {

    /**
     * 创建 Agent Runtime 专用 JDBC 连接池。
     *
     * <p>当前连接池主要服务工具执行审计仓储、工具事件 outbox 和异步命令 outbox。
     * 这三个能力都属于 agent-runtime 控制面事实库，不复用业务模块自己的 DataSource，
     * 可以避免 task-management、datasource-management 的连接池参数或事务策略反向影响 Agent Runtime。
     * 生产环境如果要区分审计写入库、查询只读库和 outbox 投递库，可以在这里继续拆分多个 DataSource。</p>
     *
     * @param properties Agent Runtime 持久化配置。
     * @return Hikari 连接池，Spring 关闭应用时会调用 close 释放连接。
     */
    @Bean(name = "agentRuntimeJdbcDataSource", destroyMethod = "close")
    public HikariDataSource agentRuntimeJdbcDataSource(AgentRuntimePersistenceProperties properties) {
        AgentRuntimePersistenceProperties.Jdbc jdbc = properties.getJdbc();
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(requireText(jdbc.getUrl(), "datasmart.agent-runtime.persistence.jdbc.url"));
        config.setUsername(requireText(jdbc.getUsername(), "datasmart.agent-runtime.persistence.jdbc.username"));
        config.setPassword(jdbc.getPassword() == null ? "" : jdbc.getPassword());
        config.setDriverClassName(requireText(jdbc.getDriverClassName(), "datasmart.agent-runtime.persistence.jdbc.driver-class-name"));
        config.setPoolName(requireText(jdbc.getPoolName(), "datasmart.agent-runtime.persistence.jdbc.pool-name"));
        config.setMinimumIdle(Math.max(0, jdbc.getMinimumIdle()));
        config.setMaximumPoolSize(Math.max(1, jdbc.getMaximumPoolSize()));
        config.setConnectionTimeout(Math.max(1000, jdbc.getConnectionTimeoutMs()));
        config.setIdleTimeout(Math.max(10000, jdbc.getIdleTimeoutMs()));
        config.setMaxLifetime(Math.max(30000, jdbc.getMaxLifetimeMs()));
        return new HikariDataSource(config);
    }

    private String requireText(String value, String propertyName) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("启用 Agent Runtime MySQL 持久化时必须配置 " + propertyName);
        }
        return value.trim();
    }
}
