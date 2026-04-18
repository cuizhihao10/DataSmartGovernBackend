package com.czh.datasmart.govern.datasource.support;

import java.util.Arrays;

/**
 * @Author : Cui
 * @Date: 2026/4/18 21:55
 * @Description DataSmart Govern Backend - DataSourceType.java
 * @Version:1.0.0
 *
 * 数据源类型枚举。
 * 当前先支持 MySQL、PostgreSQL、SQL Server 三类 JDBC 数据源，
 * 因为它们是最常见的结构化数据源，也是当前 pom 已具备驱动支持的第一批对象。
 *
 * 这个枚举现在不只负责“把 type 映射成驱动类名”，还开始承载第一版连接器能力画像。
 * 这样做的目的，是把“当前这个数据源理论上支持哪些平台能力”沉淀到统一入口，便于：
 * 1. 前端在创建同步模板时做能力提示；
 * 2. 后端在未来做模板校验、字段映射、增量模式限制时直接复用；
 * 3. 逐步从“只是能连数据库”演进到“知道连接器能做什么”的产品形态。
 */
public enum DataSourceType {
    MYSQL("com.mysql.cj.jdbc.Driver", true, true, true, true,
            false, true, true, true, true, true),
    POSTGRESQL("org.postgresql.Driver", true, true, true, true,
            false, true, true, true, true, true),
    SQLSERVER("com.microsoft.sqlserver.jdbc.SQLServerDriver", true, true, true, true,
            false, true, true, true, true, true);

    /**
     * 对应 JDBC 驱动类名。
     */
    private final String driverClassName;

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
     * 这里表达的是“具备实现增量抽取的基础”，而不是当前仓库已经把所有增量逻辑都写完。
     */
    private final boolean supportsIncrementalSync;

    /**
     * 是否支持流式或 CDC 场景。
     * 对纯 JDBC 连接来说，这里先保守标记为 false，后续如果接 binlog、logical decoding 或 CDC 组件再独立建模。
     */
    private final boolean supportsStreaming;

    /**
     * 是否支持模式/表/字段发现。
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
     * 对关系型数据库来说，这里先给出产品层面的可实现判断，后续真正的并发策略还要结合表结构、索引和分片键决定。
     */
    private final boolean supportsPartitionParallelism;

    DataSourceType(String driverClassName,
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
        this.driverClassName = driverClassName;
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

    public String getDriverClassName() {
        return driverClassName;
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
     * 将外部输入归一化为标准数据源类型。
     */
    public static DataSourceType fromValue(String value) {
        return Arrays.stream(values())
                .filter(item -> item.name().equalsIgnoreCase(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("不支持的数据源类型: " + value));
    }
}
