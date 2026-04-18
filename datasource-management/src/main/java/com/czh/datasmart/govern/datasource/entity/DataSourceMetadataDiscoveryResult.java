package com.czh.datasmart.govern.datasource.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * @Author : Cui
 * @Date: 2026/4/18 22:42
 * @Description DataSmart Govern Backend - DataSourceMetadataDiscoveryResult.java
 * @Version:1.0.0
 *
 * 数据源元数据发现结果。
 * 它描述的是“一次探查动作返回了什么”，而不是长期存储的元数据仓库。
 *
 * 之所以先做成即时发现结果，而不是马上落库成复杂元数据库表，是因为：
 * 1. 当前更需要先打通真实探查链路；
 * 2. 未来元数据采集、血缘和资产目录可能会形成独立模块；
 * 3. 先把接口形态和抽象沉淀下来，能更快支撑模板创建与同步配置。
 *
 * 本轮新增了一些与性能控制相关的返回字段，
 * 让调用方能够知道这次结果是否来自缓存、是否启用了限制、是否存在截断。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DataSourceMetadataDiscoveryResult {

    /**
     * 数据源主键。
     */
    private Long datasourceId;

    /**
     * 数据源名称。
     */
    private String datasourceName;

    /**
     * 数据源类型。
     */
    private String datasourceType;

    /**
     * 数据库产品名称。
     */
    private String productName;

    /**
     * 数据库产品版本。
     */
    private String productVersion;

    /**
     * JDBC 驱动名称。
     */
    private String driverName;

    /**
     * 探查时使用的 catalog 过滤条件。
     */
    private String catalog;

    /**
     * 探查时使用的 schema 模式过滤条件。
     */
    private String schemaPattern;

    /**
     * 探查时使用的表名过滤条件。
     */
    private String tableNamePattern;

    /**
     * 实际发现的表数量。
     */
    private Integer tableCount;

    /**
     * 本次请求实际采用的最大表数量限制。
     */
    private Integer appliedMaxTables;

    /**
     * 本次请求实际采用的每表最大字段数限制。
     */
    private Integer appliedMaxColumnsPerTable;

    /**
     * 本次请求实际采用的样本行限制。
     */
    private Integer appliedSampleRowLimit;

    /**
     * 是否命中缓存。
     */
    private Boolean cacheHit;

    /**
     * 本次探查耗时，单位毫秒。
     * 这个字段可以帮助我们后续评估元数据接口是否需要进一步异步化或拆后台任务。
     */
    private Long discoveryDurationMs;

    /**
     * 探查时间。
     */
    private LocalDateTime discoveredAt;

    /**
     * 表摘要清单。
     */
    private List<TableMetadataSummary> tables;

    /**
     * 告警或提示信息。
     * 例如只返回了前 N 张表、未包含系统表、当前连接器暂不支持某类对象等。
     */
    private List<String> warnings;
}
