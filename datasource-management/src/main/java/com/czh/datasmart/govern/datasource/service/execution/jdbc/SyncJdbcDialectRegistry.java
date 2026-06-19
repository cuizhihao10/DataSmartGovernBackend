/**
 * @Author : Cui
 * @Date: 2026/06/20 03:05
 * @Description DataSmart Govern Backend - SyncJdbcDialectRegistry.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.service.execution.jdbc;

import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/**
 * JDBC 方言注册表。
 *
 * <p>真实数据同步执行器不能把连接器类型写死在 if/else 里。
 * 注册表的职责是把外部连接器类型归一化，然后找到对应方言组件。
 * 这样后续新增 Oracle、ClickHouse、Hive 或 lakehouse 方言时，只需要新增一个 dialect 实现，
 * 不需要改动 worker 主流程。</p>
 *
 * <p>当前注册表只处理关系型 JDBC 方言。
 * Kafka、MongoDB、对象存储、REST API 等连接器后续应进入各自 connector family 的执行器，
 * 不应该强行塞进 JDBC dialect。</p>
 */
@Component
public class SyncJdbcDialectRegistry {

    /**
     * Spring 注入的方言集合。
     * 通过构造函数注入可以在单元测试中直接传入自定义方言列表，也便于后续插件化替换。
     */
    private final List<SyncJdbcDialect> dialects;

    public SyncJdbcDialectRegistry(List<SyncJdbcDialect> dialects) {
        this.dialects = dialects.stream()
                .sorted(Comparator.comparing(SyncJdbcDialect::connectorType))
                .toList();
    }

    /**
     * 获取指定连接器类型的方言。
     *
     * @param connectorType 数据源或执行计划中的连接器类型，允许 MYSQL、POSTGRESQL、SQL_SERVER、SQLSERVER、MSSQL 等常见写法。
     * @return 对应 JDBC 方言。
     */
    public SyncJdbcDialect getRequiredDialect(String connectorType) {
        String normalized = normalizeConnectorType(connectorType);
        return dialects.stream()
                .filter(dialect -> dialect.supportsConnector(normalized))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("当前 JDBC 执行层暂不支持连接器类型: " + connectorType));
    }

    /**
     * 列出当前已注册的 JDBC 方言类型。
     * 该方法主要用于测试、诊断和后续只读运维接口。
     */
    public List<String> supportedConnectorTypes() {
        return dialects.stream()
                .map(SyncJdbcDialect::connectorType)
                .toList();
    }

    /**
     * 连接器类型归一化。
     *
     * <p>产品接口和历史数据中可能出现 SQLSERVER、SQL_SERVER、MSSQL、PGSQL、POSTGRES 等不同写法。
     * 方言注册表统一在入口处理这些别名，避免每个调用方都维护一份兼容逻辑。</p>
     */
    private String normalizeConnectorType(String connectorType) {
        if (connectorType == null || connectorType.isBlank()) {
            throw new IllegalArgumentException("connectorType 不能为空");
        }
        String normalized = connectorType.trim().toUpperCase().replace('-', '_');
        return switch (normalized) {
            case "SQLSERVER", "MSSQL" -> "SQL_SERVER";
            case "POSTGRES", "PGSQL" -> "POSTGRESQL";
            default -> normalized;
        };
    }
}
