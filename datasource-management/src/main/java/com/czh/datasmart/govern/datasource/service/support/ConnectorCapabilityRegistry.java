package com.czh.datasmart.govern.datasource.service.support;

import com.czh.datasmart.govern.datasource.entity.ConnectorCapabilityProfile;
import com.czh.datasmart.govern.datasource.entity.DataSourceConfig;
import com.czh.datasmart.govern.datasource.entity.SyncConnectorCapabilityAssessment;
import com.czh.datasmart.govern.datasource.entity.SyncTemplate;
import com.czh.datasmart.govern.datasource.support.ConnectorType;
import com.czh.datasmart.govern.datasource.support.SyncMode;
import com.czh.datasmart.govern.datasource.support.SyncWriteStrategy;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * @Author : Cui
 * @Date: 2026/06/20 02:00
 * @Description DataSmart Govern Backend - ConnectorCapabilityRegistry.java
 * @Version:1.0.0
 *
 * 连接器能力注册表。
 *
 * <p>该组件是 datasource-management 向“真实商业化数据同步产品”收敛的关键控制点之一。
 * 它把连接器能力从散落的枚举布尔值、模板 if/else 和前端硬编码中收拢到一个可查询、可测试、可复用的位置。</p>
 *
 * <p>设计边界：</p>
 * <p>1. 当前注册表不连接外部系统，也不执行数据搬运，只做低敏能力声明和模板兼容性判断；</p>
 * <p>2. 已有 JDBC 三类连接器处于 CONTROL_PLANE_SUPPORTED_EXECUTOR_PENDING，表示控制面可建模、可校验，
 * 但真实批量抽取/写入执行器仍需后续接入；</p>
 * <p>3. Kafka、文件、对象存储、REST API 等连接器先以 ROADMAP_RESERVED 进入画像，便于产品路线和前端向导提前对齐，
 * 但模板创建时如果引用当前 DataSourceType 尚不支持的类型，仍会在数据源创建阶段被拦住。</p>
 */
@Component
public class ConnectorCapabilityRegistry {

    /**
     * 标准连接器画像表。
     * key 使用 ConnectorType.name()，业务读取时通过 normalizeConnectorType 做别名归一。
     */
    private final Map<String, ConnectorCapabilityProfile> profiles = new LinkedHashMap<>();

    public ConnectorCapabilityRegistry() {
        registerRelationalJdbcProfiles();
        registerRoadmapProfiles();
    }

    /**
     * 查询全部连接器能力画像。
     *
     * <p>返回值按连接器类型排序，保证前端、测试和文档生成时顺序稳定。</p>
     */
    public List<ConnectorCapabilityProfile> listProfiles() {
        return profiles.values().stream()
                .sorted(Comparator.comparing(ConnectorCapabilityProfile::getConnectorType))
                .toList();
    }

    /**
     * 按连接器类型查询能力画像。
     *
     * <p>这里兼容 SQLSERVER 和 SQL_SERVER 两种写法，是为了平滑当前 DataSourceType 的历史命名与
     * ConnectorType 的产品级命名差异。</p>
     */
    public ConnectorCapabilityProfile getProfile(String connectorType) {
        String normalizedType = normalizeConnectorType(connectorType);
        ConnectorCapabilityProfile profile = profiles.get(normalizedType);
        if (profile == null) {
            throw new IllegalArgumentException("未维护连接器能力画像: " + connectorType);
        }
        return profile;
    }

    /**
     * 根据已登记数据源查询能力画像。
     */
    public ConnectorCapabilityProfile getProfile(DataSourceConfig datasource) {
        if (datasource == null) {
            throw new IllegalArgumentException("数据源不能为空");
        }
        return getProfile(datasource.getType());
    }

    /**
     * 针对已持久化模板执行连接器兼容性评估。
     */
    public SyncConnectorCapabilityAssessment assessTemplateCompatibility(SyncTemplate template,
                                                                         DataSourceConfig source,
                                                                         DataSourceConfig target) {
        return assessTemplateCompatibility(
                source,
                target,
                SyncMode.fromValue(template.getSyncMode()),
                SyncWriteStrategy.fromValue(template.getWriteStrategy()),
                template.getPartitionConfig()
        );
    }

    /**
     * 针对创建/更新请求执行连接器兼容性评估。
     *
     * <p>该方法不读取数据库，调用方负责提前加载 source/target 数据源并校验其生命周期。
     * 这样注册表保持纯规则组件，便于单元测试，也避免和 Mapper、事务边界产生耦合。</p>
     */
    public SyncConnectorCapabilityAssessment assessTemplateCompatibility(DataSourceConfig source,
                                                                         DataSourceConfig target,
                                                                         SyncMode syncMode,
                                                                         SyncWriteStrategy writeStrategy,
                                                                         String partitionConfig) {
        ConnectorCapabilityProfile sourceProfile = getProfile(source);
        ConnectorCapabilityProfile targetProfile = getProfile(target);
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<String> performanceRecommendations = new ArrayList<>();
        List<String> recommendedNextCapabilities = new ArrayList<>();

        collectReadWriteErrors(sourceProfile, targetProfile, errors);
        collectModeErrors(sourceProfile, targetProfile, syncMode, errors, warnings);
        collectWriteStrategyErrors(targetProfile, writeStrategy, errors, warnings);
        collectCheckpointRequirements(sourceProfile, syncMode, errors, warnings);
        collectPartitionRecommendations(sourceProfile, targetProfile, partitionConfig, warnings, performanceRecommendations);
        collectProductionBoundary(sourceProfile, targetProfile, warnings, recommendedNextCapabilities);

        performanceRecommendations.addAll(sourceProfile.getPerformanceRecommendations());
        performanceRecommendations.addAll(targetProfile.getPerformanceRecommendations());
        recommendedNextCapabilities.addAll(sourceProfile.getRecommendedNextCapabilities());
        recommendedNextCapabilities.addAll(targetProfile.getRecommendedNextCapabilities());

        return new SyncConnectorCapabilityAssessment(
                errors.isEmpty(),
                sourceProfile.getConnectorType(),
                targetProfile.getConnectorType(),
                syncMode.name(),
                writeStrategy.name(),
                errors,
                warnings,
                performanceRecommendations.stream().distinct().toList(),
                recommendedNextCapabilities.stream().distinct().toList(),
                LocalDateTime.now()
        );
    }

    /**
     * 将阻断错误转换为异常。
     *
     * <p>模板创建和更新属于强一致入口，不能只把错误写入 warnings 后继续落库。
     * 如果允许明显不兼容的模板入库，问题会被推迟到执行器认领后才暴露，最终造成队列污染和运维误判。</p>
     */
    public void assertTemplateCompatible(DataSourceConfig source,
                                         DataSourceConfig target,
                                         SyncMode syncMode,
                                         SyncWriteStrategy writeStrategy,
                                         String partitionConfig) {
        SyncConnectorCapabilityAssessment assessment = assessTemplateCompatibility(
                source, target, syncMode, writeStrategy, partitionConfig);
        if (!assessment.isPassed()) {
            throw new IllegalArgumentException("同步模板连接器能力不兼容: " + String.join("；", assessment.getErrors()));
        }
    }

    private void collectReadWriteErrors(ConnectorCapabilityProfile sourceProfile,
                                        ConnectorCapabilityProfile targetProfile,
                                        List<String> errors) {
        if (!sourceProfile.isCanRead()) {
            errors.add("源连接器不支持读取: " + sourceProfile.getConnectorType());
        }
        if (!targetProfile.isCanWrite()) {
            errors.add("目标连接器不支持写入: " + targetProfile.getConnectorType());
        }
    }

    private void collectModeErrors(ConnectorCapabilityProfile sourceProfile,
                                   ConnectorCapabilityProfile targetProfile,
                                   SyncMode syncMode,
                                   List<String> errors,
                                   List<String> warnings) {
        if (!sourceProfile.getSupportedSyncModes().contains(syncMode.name())) {
            errors.add("源连接器 " + sourceProfile.getConnectorType() + " 不支持同步模式 " + syncMode.name());
        }
        if ((syncMode == SyncMode.STREAMING || syncMode == SyncMode.CDC)
                && !targetProfile.getSupportedSyncModes().contains(syncMode.name())) {
            warnings.add("目标连接器 " + targetProfile.getConnectorType()
                    + " 未声明原生流式/CDC 写入能力，后续执行器需要采用微批、缓冲队列或专用 sink 适配");
        }
        if (syncMode == SyncMode.OFFLINE_IMPORT
                && !targetProfile.getSupportedSyncModes().contains(SyncMode.OFFLINE_IMPORT.name())) {
            warnings.add("目标连接器未声明离线导入能力，批量导入可能只能退化为常规批量写入");
        }
        if (syncMode == SyncMode.OFFLINE_EXPORT
                && !targetProfile.getSupportedSyncModes().contains(SyncMode.OFFLINE_EXPORT.name())) {
            warnings.add("目标连接器未声明离线导出落地能力，后续需要补对象存储、文件或湖仓 sink");
        }
    }

    private void collectWriteStrategyErrors(ConnectorCapabilityProfile targetProfile,
                                            SyncWriteStrategy writeStrategy,
                                            List<String> errors,
                                            List<String> warnings) {
        if (!targetProfile.getSupportedWriteStrategies().contains(writeStrategy.name())) {
            errors.add("目标连接器 " + targetProfile.getConnectorType()
                    + " 不支持写入策略 " + writeStrategy.name());
        }
        if (writeStrategy.isDestructiveRewrite()) {
            warnings.add("OVERWRITE 属于覆盖式写入，生产执行前应补影响行数预估、审批、备份和回滚策略");
        }
    }

    private void collectCheckpointRequirements(ConnectorCapabilityProfile sourceProfile,
                                               SyncMode syncMode,
                                               List<String> errors,
                                               List<String> warnings) {
        if (!requiresCheckpoint(syncMode)) {
            return;
        }
        if (!sourceProfile.isSupportsCheckpointResume()) {
            errors.add("同步模式 " + syncMode.name() + " 需要检查点恢复，但源连接器不支持检查点: "
                    + sourceProfile.getConnectorType());
        }
        if (syncMode == SyncMode.CDC || syncMode == SyncMode.STREAMING) {
            warnings.add("流式/CDC 场景需要补充位点持久化、事件顺序、重复消息去重、背压和 exactly-once/at-least-once 语义说明");
        }
    }

    private void collectPartitionRecommendations(ConnectorCapabilityProfile sourceProfile,
                                                 ConnectorCapabilityProfile targetProfile,
                                                 String partitionConfig,
                                                 List<String> warnings,
                                                 List<String> performanceRecommendations) {
        if (partitionConfig == null || partitionConfig.isBlank()) {
            performanceRecommendations.add("大表全量或补数场景建议配置 partitionConfig，以便后续执行器支持分区并行和断点恢复");
            return;
        }
        if (!sourceProfile.isSupportsPartitionParallelism()) {
            warnings.add("源连接器未声明分区并行能力，partitionConfig 可能只能作为逻辑过滤条件使用");
        }
        if (!targetProfile.isSupportsPartitionParallelism()) {
            performanceRecommendations.add("目标连接器未声明分区并行写入能力，建议执行器侧补批量写入、背压和幂等冲突处理");
        }
    }

    private void collectProductionBoundary(ConnectorCapabilityProfile sourceProfile,
                                           ConnectorCapabilityProfile targetProfile,
                                           List<String> warnings,
                                           List<String> recommendedNextCapabilities) {
        if (sourceProfile.getImplementationStage().contains("PENDING")
                || targetProfile.getImplementationStage().contains("PENDING")) {
            warnings.add("当前连接器组合已具备控制面建模能力，但真实数据搬运执行器仍需接入后才能形成端到端生产闭环");
            recommendedNextCapabilities.add("优先接入批量读取、批量写入、检查点提交和执行器回执，形成真实同步闭环");
        }
        if (sourceProfile.getImplementationStage().contains("ROADMAP")
                || targetProfile.getImplementationStage().contains("ROADMAP")) {
            warnings.add("当前连接器属于路线预留画像，尚不应直接承诺生产执行能力");
        }
    }

    private boolean requiresCheckpoint(SyncMode syncMode) {
        return syncMode == SyncMode.INCREMENTAL_TIME
                || syncMode == SyncMode.INCREMENTAL_ID
                || syncMode == SyncMode.STREAMING
                || syncMode == SyncMode.CDC
                || syncMode == SyncMode.REPLAY
                || syncMode == SyncMode.BACKFILL;
    }

    private void registerRelationalJdbcProfiles() {
        profiles.put(ConnectorType.MYSQL.name(), profile(
                ConnectorType.MYSQL.name(),
                "RELATIONAL_JDBC",
                "CONTROL_PLANE_SUPPORTED_EXECUTOR_PENDING",
                true,
                true,
                true,
                true,
                true,
                true,
                true,
                modes(SyncMode.FULL, SyncMode.INCREMENTAL_TIME, SyncMode.INCREMENTAL_ID,
                        SyncMode.SCHEDULED_BATCH, SyncMode.REPLAY, SyncMode.BACKFILL),
                strategies(SyncWriteStrategy.APPEND, SyncWriteStrategy.UPSERT, SyncWriteStrategy.INSERT_IGNORE,
                        SyncWriteStrategy.REPLACE, SyncWriteStrategy.OVERWRITE),
                List.of("JDBC 批处理适合先实现 at-least-once + 幂等写入，再逐步增强 exactly-once 语义"),
                List.of("大表同步建议使用主键或时间字段分区，并为增量字段建立索引"),
                List.of("当前尚未实现真实 MySQL 批量抽取、写入 SQL 生成和 binlog CDC 位点"),
                List.of("实现 JDBC batch reader/writer", "实现 MySQL checkpoint 提交", "评估 binlog CDC connector")
        ));
        profiles.put(ConnectorType.POSTGRESQL.name(), profile(
                ConnectorType.POSTGRESQL.name(),
                "RELATIONAL_JDBC",
                "CONTROL_PLANE_SUPPORTED_EXECUTOR_PENDING",
                true,
                true,
                true,
                true,
                true,
                true,
                true,
                modes(SyncMode.FULL, SyncMode.INCREMENTAL_TIME, SyncMode.INCREMENTAL_ID,
                        SyncMode.SCHEDULED_BATCH, SyncMode.REPLAY, SyncMode.BACKFILL),
                strategies(SyncWriteStrategy.APPEND, SyncWriteStrategy.UPSERT, SyncWriteStrategy.INSERT_IGNORE,
                        SyncWriteStrategy.OVERWRITE),
                List.of("PostgreSQL 更推荐基于 ON CONFLICT 做 UPSERT/忽略冲突，REPLACE 不是原生语义"),
                List.of("大表同步建议使用游标、批量提交、合理 fetchSize 和增量字段索引"),
                List.of("当前尚未实现真实 PostgreSQL 批量抽取、写入 SQL 生成和 logical decoding CDC 位点"),
                List.of("实现 PostgreSQL batch reader/writer", "实现 ON CONFLICT 写入策略", "评估 logical decoding CDC connector")
        ));
        profiles.put(ConnectorType.SQL_SERVER.name(), profile(
                ConnectorType.SQL_SERVER.name(),
                "RELATIONAL_JDBC",
                "CONTROL_PLANE_SUPPORTED_EXECUTOR_PENDING",
                true,
                true,
                true,
                true,
                true,
                true,
                true,
                modes(SyncMode.FULL, SyncMode.INCREMENTAL_TIME, SyncMode.INCREMENTAL_ID,
                        SyncMode.SCHEDULED_BATCH, SyncMode.REPLAY, SyncMode.BACKFILL),
                strategies(SyncWriteStrategy.APPEND, SyncWriteStrategy.UPSERT, SyncWriteStrategy.OVERWRITE),
                List.of("SQL Server UPSERT 通常需要 MERGE 或事务内条件写入，必须重点处理并发冲突"),
                List.of("大表同步建议使用时间水位、分页键和批量写入，并关注锁等待与事务日志压力"),
                List.of("当前尚未实现真实 SQL Server 批量抽取、MERGE 写入和 CDC/Change Tracking 位点"),
                List.of("实现 SQL Server batch reader/writer", "补 MERGE 幂等写入", "评估 Change Tracking/CDC connector")
        ));
    }

    private void registerRoadmapProfiles() {
        profiles.put(ConnectorType.ORACLE.name(), roadmapRelational(ConnectorType.ORACLE.name()));
        profiles.put(ConnectorType.MONGODB.name(), profile(
                ConnectorType.MONGODB.name(), "DOCUMENT_DATABASE", "ROADMAP_RESERVED", true, true,
                false, true, true, false, true,
                modes(SyncMode.FULL, SyncMode.INCREMENTAL_TIME, SyncMode.INCREMENTAL_ID, SyncMode.SCHEDULED_BATCH, SyncMode.BACKFILL),
                strategies(SyncWriteStrategy.APPEND, SyncWriteStrategy.UPSERT, SyncWriteStrategy.REPLACE),
                List.of("文档模型需要显式处理 schema drift、嵌套字段映射和幂等键"),
                List.of("建议按 _id、更新时间或分片键做增量与分区"),
                List.of("当前未实现 MongoDB 连接配置、采样、schema 推断和批量写入"),
                List.of("补 MongoDB connector SPI", "补文档字段映射和 schema drift 检测")
        ));
        profiles.put(ConnectorType.KAFKA.name(), profile(
                ConnectorType.KAFKA.name(), "MESSAGE_STREAM", "ROADMAP_RESERVED", true, true,
                false, false, true, false, true,
                modes(SyncMode.STREAMING, SyncMode.CDC, SyncMode.REPLAY),
                strategies(SyncWriteStrategy.APPEND),
                List.of("Kafka 更接近事件流语义，需要明确 offset、消费者组、消息顺序和重复投递处理"),
                List.of("建议按 topic/partition 维度保存 checkpoint，并引入背压和重放窗口"),
                List.of("当前未实现 Kafka connector、offset store、序列化协议和 dead-letter queue"),
                List.of("补 Kafka source/sink connector", "补 offset checkpoint", "补 DLQ 与重放策略")
        ));
        profiles.put(ConnectorType.HIVE.name(), roadmapLakehouse(ConnectorType.HIVE.name()));
        profiles.put(ConnectorType.CLICKHOUSE.name(), roadmapAnalytical(ConnectorType.CLICKHOUSE.name()));
        profiles.put(ConnectorType.FILE.name(), roadmapFileLike(ConnectorType.FILE.name(), "FILE_LAKE"));
        profiles.put(ConnectorType.OBJECT_STORAGE.name(), roadmapFileLike(ConnectorType.OBJECT_STORAGE.name(), "OBJECT_STORAGE"));
        profiles.put(ConnectorType.REST_API.name(), profile(
                ConnectorType.REST_API.name(), "HTTP_API", "ROADMAP_RESERVED", true, true,
                false, false, true, false, false,
                modes(SyncMode.FULL, SyncMode.INCREMENTAL_TIME, SyncMode.SCHEDULED_BATCH, SyncMode.REPLAY),
                strategies(SyncWriteStrategy.APPEND, SyncWriteStrategy.UPSERT),
                List.of("API 同步必须显式处理分页、限流、签名、重试幂等和响应 schema 漂移"),
                List.of("建议以 cursor/pageToken 作为 checkpoint，并设置请求级限流和熔断"),
                List.of("当前未实现 REST connector、凭证托管、分页协议和限流重试"),
                List.of("补 REST connector SPI", "补分页/checkpoint 适配", "补 API 凭证和限流治理")
        ));
    }

    private ConnectorCapabilityProfile roadmapRelational(String connectorType) {
        return profile(connectorType, "RELATIONAL_JDBC", "ROADMAP_RESERVED", true, true,
                true, true, true, true, true,
                modes(SyncMode.FULL, SyncMode.INCREMENTAL_TIME, SyncMode.INCREMENTAL_ID,
                        SyncMode.SCHEDULED_BATCH, SyncMode.REPLAY, SyncMode.BACKFILL),
                strategies(SyncWriteStrategy.APPEND, SyncWriteStrategy.UPSERT, SyncWriteStrategy.OVERWRITE),
                List.of("关系型路线连接器需要根据方言差异处理分页、锁、事务和冲突写入"),
                List.of("建议优先完成 JDBC connector SPI，再接入具体方言优化"),
                List.of("当前未接入驱动、元数据探查和真实执行器"),
                List.of("补通用 JDBC connector SPI", "补方言级 SQL 生成", "补连接池与限流")
        );
    }

    private ConnectorCapabilityProfile roadmapLakehouse(String connectorType) {
        return profile(connectorType, "LAKEHOUSE", "ROADMAP_RESERVED", true, true,
                true, true, true, false, true,
                modes(SyncMode.FULL, SyncMode.SCHEDULED_BATCH, SyncMode.OFFLINE_IMPORT,
                        SyncMode.OFFLINE_EXPORT, SyncMode.BACKFILL),
                strategies(SyncWriteStrategy.APPEND, SyncWriteStrategy.OVERWRITE),
                List.of("湖仓同步需要关注分区、文件小文件问题、元数据提交和 schema evolution"),
                List.of("建议按分区批量提交，并把文件生成与元数据提交拆成可恢复步骤"),
                List.of("当前未实现湖仓 connector、分区提交和对象存储中间层"),
                List.of("补湖仓 source/sink connector", "补分区提交", "补小文件治理")
        );
    }

    private ConnectorCapabilityProfile roadmapAnalytical(String connectorType) {
        return profile(connectorType, "ANALYTICAL_DATABASE", "ROADMAP_RESERVED", true, true,
                true, true, true, true, true,
                modes(SyncMode.FULL, SyncMode.INCREMENTAL_TIME, SyncMode.INCREMENTAL_ID,
                        SyncMode.SCHEDULED_BATCH, SyncMode.BACKFILL),
                strategies(SyncWriteStrategy.APPEND, SyncWriteStrategy.OVERWRITE),
                List.of("分析型数据库更适合批量写入和幂等分区覆盖，不适合频繁小事务 UPSERT"),
                List.of("建议优先使用批量导入、分区覆盖和异步写入回执"),
                List.of("当前未实现 ClickHouse connector、批量导入和分区替换"),
                List.of("补 ClickHouse batch writer", "补分区级幂等覆盖", "补写入回执")
        );
    }

    private ConnectorCapabilityProfile roadmapFileLike(String connectorType, String family) {
        return profile(connectorType, family, "ROADMAP_RESERVED", true, true,
                false, true, true, true, true,
                modes(SyncMode.FULL, SyncMode.SCHEDULED_BATCH, SyncMode.OFFLINE_IMPORT,
                        SyncMode.OFFLINE_EXPORT, SyncMode.REPLAY, SyncMode.BACKFILL),
                strategies(SyncWriteStrategy.APPEND, SyncWriteStrategy.OVERWRITE),
                List.of("文件/对象存储同步需要处理文件清单、分片、校验和、原子提交和失败清理"),
                List.of("建议使用 manifest + checksum + 临时目录提交协议，避免半成品被下游读取"),
                List.of("当前未实现文件扫描、对象存储凭证托管、manifest 和校验和"),
                List.of("补文件/对象存储 connector", "补 manifest 提交协议", "补 checksum 与失败清理")
        );
    }

    private ConnectorCapabilityProfile profile(String connectorType,
                                               String connectorFamily,
                                               String implementationStage,
                                               boolean canRead,
                                               boolean canWrite,
                                               boolean supportsSchemaDiscovery,
                                               boolean supportsFieldMapping,
                                               boolean supportsCheckpointResume,
                                               boolean supportsPreviewSampling,
                                               boolean supportsPartitionParallelism,
                                               List<String> supportedSyncModes,
                                               List<String> supportedWriteStrategies,
                                               List<String> consistencyNotes,
                                               List<String> performanceRecommendations,
                                               List<String> productionLimitations,
                                               List<String> recommendedNextCapabilities) {
        return new ConnectorCapabilityProfile(
                connectorType,
                connectorFamily,
                implementationStage,
                canRead,
                canWrite,
                supportsSchemaDiscovery,
                supportsFieldMapping,
                supportsCheckpointResume,
                supportsPreviewSampling,
                supportsPartitionParallelism,
                supportedSyncModes,
                supportedWriteStrategies,
                consistencyNotes,
                performanceRecommendations,
                productionLimitations,
                recommendedNextCapabilities,
                LocalDateTime.now()
        );
    }

    private List<String> modes(SyncMode... modes) {
        return List.of(modes).stream().map(SyncMode::name).toList();
    }

    private List<String> strategies(SyncWriteStrategy... strategies) {
        return List.of(strategies).stream().map(SyncWriteStrategy::name).toList();
    }

    private String normalizeConnectorType(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("连接器类型不能为空");
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        if ("SQLSERVER".equals(normalized) || "MSSQL".equals(normalized) || "SQL_SERVER".equals(normalized)) {
            return ConnectorType.SQL_SERVER.name();
        }
        return ConnectorType.fromValue(normalized).name();
    }
}
