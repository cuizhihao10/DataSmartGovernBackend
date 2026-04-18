package com.czh.datasmart.govern.datasource.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * @Author : Cui
 * @Date: 2026/4/18 22:51
 * @Description DataSmart Govern Backend - MetadataDiscoveryProperties.java
 * @Version:1.0.0
 *
 * 元数据发现配置属性。
 * 这一组配置的目标，是把“元数据发现的性能保护策略”从业务代码里抽离出来。
 *
 * 当前阶段先把最关键的控制项做成配置：
 * - 默认和绝对上限的表数量；
 * - 默认和绝对上限的字段数量；
 * - 默认和绝对上限的样本行数量；
 * - JDBC 查询超时；
 * - 简单缓存的有效期。
 *
 * 这样做的好处是：
 * 1. 开发环境可以开得松一些，便于学习和联调；
 * 2. 生产环境可以收得更严，避免一次误探查拖垮数据库；
 * 3. 后续如果要改成异步扫描或专门元数据任务，也可以保留这套边界作为基础策略。
 */
@Data
@Component
@ConfigurationProperties(prefix = "datasmart.datasource.metadata-discovery")
public class MetadataDiscoveryProperties {

    /**
     * 默认最大返回表数量。
     */
    private Integer defaultMaxTables = 20;

    /**
     * 绝对最大返回表数量。
     * 即便调用方显式传入更大的值，也会被压回这个上限。
     */
    private Integer absoluteMaxTables = 100;

    /**
     * 默认每表最大字段数。
     */
    private Integer defaultMaxColumnsPerTable = 50;

    /**
     * 绝对每表最大字段数。
     */
    private Integer absoluteMaxColumnsPerTable = 200;

    /**
     * 默认样本行数。
     */
    private Integer defaultSampleRowLimit = 5;

    /**
     * 绝对样本行上限。
     */
    private Integer absoluteSampleRowLimit = 20;

    /**
     * JDBC 查询超时秒数。
     */
    private Integer jdbcQueryTimeoutSeconds = 5;

    /**
     * 探查结果缓存有效期秒数。
     */
    private Integer cacheTtlSeconds = 60;
}
