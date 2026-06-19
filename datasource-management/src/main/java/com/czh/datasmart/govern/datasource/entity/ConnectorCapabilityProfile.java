package com.czh.datasmart.govern.datasource.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * @Author : Cui
 * @Date: 2026/06/20 02:00
 * @Description DataSmart Govern Backend - ConnectorCapabilityProfile.java
 * @Version:1.0.0
 *
 * 连接器能力画像。
 *
 * <p>该对象不是数据库持久化实体，而是 datasource-management 暴露给前端、模板校验、调度器和未来执行器的
 * “连接器能力契约”。它回答的是：某一类连接器在平台产品语义上支持哪些同步模式、写入策略、性能特征和生产边界。</p>
 *
 * <p>为什么要单独建模连接器能力：</p>
 * <p>1. 数据同步不是只有 MySQL 到 MySQL，商业化产品必须提前容纳 PostgreSQL、SQL Server、Oracle、Kafka、
 * 文件、对象存储、API 等不同连接器族；</p>
 * <p>2. 模板创建时如果不校验能力，用户可能配置出“JDBC 数据源做 STREAMING”或“PostgreSQL 目标端使用 MySQL REPLACE”
 * 这类执行期才会失败的方案；</p>
 * <p>3. 前端向导需要根据能力画像决定是否展示 CDC、分区并行、字段映射、检查点恢复等高级配置；</p>
 * <p>4. 后续真实执行器、任务调度、队列限流和成本预估都可以复用同一份能力契约，避免规则散落在多个服务里。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConnectorCapabilityProfile {

    /**
     * 平台内部标准连接器类型。
     * 例如 MYSQL、POSTGRESQL、SQL_SERVER、KAFKA、OBJECT_STORAGE。
     */
    private String connectorType;

    /**
     * 连接器家族。
     * 该字段用于把连接器按技术范式归类，例如 RELATIONAL_JDBC、MESSAGE_STREAM、FILE_LAKE、HTTP_API。
     */
    private String connectorFamily;

    /**
     * 当前实现阶段。
     * 这里不只表达“理论上能不能做”，也诚实说明当前仓库是否已经具备真实执行器能力。
     */
    private String implementationStage;

    /**
     * 是否支持作为源端读取数据。
     */
    private boolean canRead;

    /**
     * 是否支持作为目标端写入数据。
     */
    private boolean canWrite;

    /**
     * 是否支持读取或推断 schema、表、字段、索引等结构信息。
     */
    private boolean supportsSchemaDiscovery;

    /**
     * 是否支持字段映射。
     * 字段映射是异构同步非常核心的能力，影响类型转换、字段重命名、必填字段校验和目标端写入。
     */
    private boolean supportsFieldMapping;

    /**
     * 是否支持检查点恢复。
     * 增量、CDC、回放、补数和失败重试都依赖检查点，否则只能粗暴全量重跑。
     */
    private boolean supportsCheckpointResume;

    /**
     * 是否支持预览采样。
     * 采样可用于模板向导、字段映射辅助和质量规则预检查，但也需要配合权限和脱敏策略。
     */
    private boolean supportsPreviewSampling;

    /**
     * 是否具备分区并行的产品可行性。
     * 这并不代表当前执行器已经实现并行拉取，而是表示该连接器适合进一步发展分区并发策略。
     */
    private boolean supportsPartitionParallelism;

    /**
     * 支持的同步模式列表。
     * 这里使用字符串而不是枚举字段，是为了让 API 响应稳定且便于前端直接渲染。
     */
    private List<String> supportedSyncModes;

    /**
     * 支持的写入策略列表。
     * 目标端连接器需要根据这里判断 APPEND、UPSERT、REPLACE、OVERWRITE 等语义是否成立。
     */
    private List<String> supportedWriteStrategies;

    /**
     * 一致性说明。
     * 用于解释该连接器在事务、幂等、顺序、至少一次/至多一次等方面的边界。
     */
    private List<String> consistencyNotes;

    /**
     * 性能建议。
     * 用于提示批量大小、分区并行、背压、索引、水位线等性能优化方向。
     */
    private List<String> performanceRecommendations;

    /**
     * 生产化限制。
     * 用于明确当前阶段还缺少哪些能力，例如真实执行器、CDC 位点、对象存储凭证托管等。
     */
    private List<String> productionLimitations;

    /**
     * 推荐下一步建设能力。
     * 该字段用于产品路线收敛：只记录能推动闭环的下一步，不把局部能力无限发散。
     */
    private List<String> recommendedNextCapabilities;

    /**
     * 画像生成时间。
     */
    private LocalDateTime generatedAt;
}
