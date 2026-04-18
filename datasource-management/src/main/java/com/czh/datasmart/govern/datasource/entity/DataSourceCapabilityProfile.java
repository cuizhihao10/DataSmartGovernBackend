package com.czh.datasmart.govern.datasource.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * @Author : Cui
 * @Date: 2026/4/18 22:42
 * @Description DataSmart Govern Backend - DataSourceCapabilityProfile.java
 * @Version:1.0.0
 *
 * 数据源能力画像结果。
 * 这不是数据库持久化实体，而是管理接口返回给前端和上层服务的“连接器能力快照”。
 *
 * 设计这个对象的原因是：
 * 1. 让“当前数据源支持什么能力”成为显式 API，而不是隐含在代码分支里；
 * 2. 后续模板校验、同步模式限制、字段映射 UI 提示都可以直接消费这份能力画像；
 * 3. 为以后扩展 PostgreSQL、Oracle、ClickHouse、文件、对象存储、API 连接器保留统一返回模型。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DataSourceCapabilityProfile {

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
     * 是否支持读取。
     */
    private boolean canRead;

    /**
     * 是否支持写入。
     */
    private boolean canWrite;

    /**
     * 是否支持全量同步。
     */
    private boolean supportsFullSync;

    /**
     * 是否支持增量同步。
     */
    private boolean supportsIncrementalSync;

    /**
     * 是否支持流式处理或 CDC。
     * 对当前 JDBC 型实现来说通常为 false，但保留字段是为了让统一 API 不需要未来再改结构。
     */
    private boolean supportsStreaming;

    /**
     * 是否支持元数据发现。
     */
    private boolean supportsSchemaDiscovery;

    /**
     * 是否支持字段映射。
     */
    private boolean supportsFieldMapping;

    /**
     * 是否支持检查点恢复。
     */
    private boolean supportsCheckpointResume;

    /**
     * 是否支持预览采样。
     */
    private boolean supportsPreviewSampling;

    /**
     * 是否支持分区并行。
     */
    private boolean supportsPartitionParallelism;

    /**
     * 画像生成时间。
     */
    private LocalDateTime generatedAt;
}
