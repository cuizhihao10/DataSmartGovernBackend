package com.czh.datasmart.govern.datasource.support;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * @Author : Cui
 * @Date: 2026/4/18 21:55
 * @Description DataSmart Govern Backend - DataSourceType.java
 * @Version:1.0.0
 *
 * 数据源类型枚举。
 *
 * <p>这个枚举不只负责把页面传入的类型映射为 JDBC Driver，也作为连接器类型校验的单一事实源。
 * 创建、编辑和临时连接测试都必须使用这里的 JDBC URL 前缀与数据库产品名规则，避免出现“页面选择 PostgreSQL，
 * 实际 URL 却连接到 MySQL”的脏配置。</p>
 */
public enum DataSourceType {
    MYSQL("MySQL", "jdbc:mysql:", "jdbc:mysql://host:3306/database",
            "com.mysql.cj.jdbc.Driver", List.of("mysql", "mariadb"), true, true, true, true,
            false, true, true, true, true, true),
    POSTGRESQL("PostgreSQL", "jdbc:postgresql:", "jdbc:postgresql://host:5432/database",
            "org.postgresql.Driver", List.of("postgresql"), true, true, true, true,
            false, true, true, true, true, true),
    SQLSERVER("SQL Server", "jdbc:sqlserver:", "jdbc:sqlserver://host:1433;databaseName=database",
            "com.microsoft.sqlserver.jdbc.SQLServerDriver", List.of("microsoft sql server", "sql server"), true, true, true, true,
            false, true, true, true, true, true);

    /**
     * 面向用户展示的连接器名称。
     */
    private final String displayName;

    /**
     * 该连接器允许的 JDBC URL 前缀。前缀校验发生在真正建连之前，避免 DriverManager 按 URL 选择到别的驱动。
     */
    private final String jdbcUrlPrefix;

    /**
     * 面向错误提示的低敏 JDBC URL 示例，不包含真实主机、账号或密码。
     */
    private final String jdbcUrlExample;

    /**
     * 对应 JDBC 驱动类名。
     */
    private final String driverClassName;

    /**
     * DatabaseMetaData 返回的产品名关键字，用于建连后做二次兜底校验。
     */
    private final List<String> databaseProductNameTokens;

    /**
     * 是否支持读取。
     */
    private final boolean canRead;

    /**
     * 是否支持写入。
     */
    private final boolean canWrite;

    /**
     * 是否支持全量同步。
     */
    private final boolean supportsFullSync;

    /**
     * 是否支持增量同步。
     */
    private final boolean supportsIncrementalSync;

    /**
     * 是否支持流式或 CDC 场景。
     */
    private final boolean supportsStreaming;

    /**
     * 是否支持模式、表、字段发现。
     */
    private final boolean supportsSchemaDiscovery;

    /**
     * 是否支持字段映射。
     */
    private final boolean supportsFieldMapping;

    /**
     * 是否支持检查点恢复。
     */
    private final boolean supportsCheckpointResume;

    /**
     * 是否支持预览采样。
     */
    private final boolean supportsPreviewSampling;

    /**
     * 是否支持分区并行。
     */
    private final boolean supportsPartitionParallelism;

    DataSourceType(String displayName,
                   String jdbcUrlPrefix,
                   String jdbcUrlExample,
                   String driverClassName,
                   List<String> databaseProductNameTokens,
                   boolean canRead,
                   boolean canWrite,
                   boolean supportsFullSync,
                   boolean supportsIncrementalSync,
                   boolean supportsStreaming,
                   boolean supportsSchemaDiscovery,
                   boolean supportsFieldMapping,
                   boolean supportsCheckpointResume,
                   boolean supportsPreviewSampling,
                   boolean supportsPartitionParallelism) {
        this.displayName = displayName;
        this.jdbcUrlPrefix = jdbcUrlPrefix;
        this.jdbcUrlExample = jdbcUrlExample;
        this.driverClassName = driverClassName;
        this.databaseProductNameTokens = databaseProductNameTokens;
        this.canRead = canRead;
        this.canWrite = canWrite;
        this.supportsFullSync = supportsFullSync;
        this.supportsIncrementalSync = supportsIncrementalSync;
        this.supportsStreaming = supportsStreaming;
        this.supportsSchemaDiscovery = supportsSchemaDiscovery;
        this.supportsFieldMapping = supportsFieldMapping;
        this.supportsCheckpointResume = supportsCheckpointResume;
        this.supportsPreviewSampling = supportsPreviewSampling;
        this.supportsPartitionParallelism = supportsPartitionParallelism;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getJdbcUrlPrefix() {
        return jdbcUrlPrefix;
    }

    public String getJdbcUrlExample() {
        return jdbcUrlExample;
    }

    public String getDriverClassName() {
        return driverClassName;
    }

    public List<String> getDatabaseProductNameTokens() {
        return databaseProductNameTokens;
    }

    public boolean isCanRead() {
        return canRead;
    }

    public boolean isCanWrite() {
        return canWrite;
    }

    public boolean isSupportsFullSync() {
        return supportsFullSync;
    }

    public boolean isSupportsIncrementalSync() {
        return supportsIncrementalSync;
    }

    public boolean isSupportsStreaming() {
        return supportsStreaming;
    }

    public boolean isSupportsSchemaDiscovery() {
        return supportsSchemaDiscovery;
    }

    public boolean isSupportsFieldMapping() {
        return supportsFieldMapping;
    }

    public boolean isSupportsCheckpointResume() {
        return supportsCheckpointResume;
    }

    public boolean isSupportsPreviewSampling() {
        return supportsPreviewSampling;
    }

    public boolean isSupportsPartitionParallelism() {
        return supportsPartitionParallelism;
    }

    /**
     * 判断 JDBC URL 是否符合当前连接器类型。
     */
    public boolean matchesJdbcUrl(String jdbcUrl) {
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            return false;
        }
        return jdbcUrl.trim().toLowerCase(Locale.ROOT).startsWith(jdbcUrlPrefix);
    }

    /**
     * 判断真实连接返回的数据库产品名是否符合当前连接器类型。
     */
    public boolean matchesDatabaseProductName(String productName) {
        if (productName == null || productName.isBlank()) {
            return false;
        }
        String normalizedProductName = productName.toLowerCase(Locale.ROOT);
        return databaseProductNameTokens.stream().anyMatch(normalizedProductName::contains);
    }

    /**
     * 给错误响应生成低敏、可操作的 URL 格式提示。
     */
    public String describeExpectedJdbcUrl() {
        return jdbcUrlExample + "，前缀必须是 " + jdbcUrlPrefix;
    }

    /**
     * 将外部输入归一化为标准数据源类型。
     */
    public static DataSourceType fromValue(String value) {
        String normalizedValue = normalizeTypeValue(value);
        return Arrays.stream(values())
                .filter(item -> item.name().equals(normalizedValue)
                        || normalizeTypeValue(item.displayName).equals(normalizedValue))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("不支持的数据源类型: " + value));
    }

    private static String normalizeTypeValue(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("数据源类型不能为空");
        }
        return value.trim()
                .replace(" ", "")
                .replace("-", "")
                .replace("_", "")
                .toUpperCase(Locale.ROOT);
    }
}
