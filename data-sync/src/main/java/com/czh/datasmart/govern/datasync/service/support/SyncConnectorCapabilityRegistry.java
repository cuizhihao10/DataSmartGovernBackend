/**
 * @Author : Cui
 * @Date: 2026/06/28 23:28
 * @Description DataSmart Govern Backend - SyncConnectorCapabilityRegistry.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import com.czh.datasmart.govern.datasync.controller.dto.SyncConnectorCapabilityView;
import com.czh.datasmart.govern.datasync.controller.dto.SyncConnectorCompatibilityView;
import com.czh.datasmart.govern.datasync.support.SyncConnectorType;
import com.czh.datasmart.govern.datasync.support.SyncMode;
import com.czh.datasmart.govern.datasync.support.SyncTransferChannel;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 数据同步连接器能力注册表。
 *
 * <p>当前实现是代码内置基线，而不是数据库表，是为了先把产品能力边界稳定下来：
 * 1. 模板创建、Agent 规划、前端表单和运营台都可以查询同一份 connector capability；
 * 2. 后续接真实 worker 时，只需要把 worker 支持情况同步到这里或替换为配置中心实现；
 * 3. 能力矩阵不包含连接实例和密钥，因此可以安全暴露给普通诊断接口。</p>
 *
 * <p>为什么不直接在 `SyncTemplateValidationSupport` 里写 if/else：
 * 连接器能力会影响模板校验、执行器调度、并发上限、checkpoint 策略、回放/补数策略和前端表单。
 * 如果每个入口都自己写判断，后续 MySQL、PostgreSQL、Kafka、对象存储会产生大量重复逻辑。
 * 独立注册表能让这些入口共享同一套低敏、可测试、可演进的能力事实。</p>
 */
@Component
public class SyncConnectorCapabilityRegistry {

    private static final String PAYLOAD_POLICY = "LOW_SENSITIVE_CONNECTOR_CAPABILITY_METADATA_ONLY";

    private final Map<SyncConnectorType, ConnectorCapabilityProfile> profiles;

    /**
     * 初始化默认能力矩阵。
     *
     * <p>这里优先覆盖产品文档中列出的连接器家族。`PRIMARY` 不表示 worker 已经全部实现，
     * 而是表示产品闭环优先级更高；真实执行前仍要看任务配置、权限、连接测试和 worker 发布状态。</p>
     */
    public SyncConnectorCapabilityRegistry() {
        this.profiles = defaultProfiles();
    }

    /**
     * 查询全部连接器能力。
     *
     * <p>该方法用于前端表单、Agent 规划和管理台展示。返回值不分页是因为当前连接器类型数量很小；
     * 如果未来进入插件市场并允许租户安装自定义连接器，再升级为分页和按租户过滤。</p>
     */
    public List<SyncConnectorCapabilityView> listCapabilities() {
        return profiles.values().stream()
                .map(ConnectorCapabilityProfile::toView)
                .toList();
    }

    /**
     * 查询单个连接器能力。
     *
     * @param connectorType 连接器类型字符串，大小写不敏感。
     * @return 低敏连接器能力视图。
     */
    public SyncConnectorCapabilityView getCapability(String connectorType) {
        return profile(resolveConnectorType(connectorType)).toView();
    }

    /**
     * 判断源端、目标端和同步模式是否兼容。
     *
     * <p>兼容性判断分三层：</p>
     * <ul>
     *     <li>源端必须可读，目标端必须可写；</li>
     *     <li>源端和目标端都必须支持该同步模式涉及的基础能力；</li>
     *     <li>按同步模式返回一致性、checkpoint、重试模式和治理提示，帮助模板校验或 Agent 规划给用户解释原因。</li>
     * </ul>
     */
    public SyncConnectorCompatibilityView checkCompatibility(String sourceConnectorType,
                                                             String targetConnectorType,
                                                             String syncMode) {
        ConnectorCapabilityProfile source = profile(resolveConnectorType(sourceConnectorType));
        ConnectorCapabilityProfile target = profile(resolveConnectorType(targetConnectorType));
        SyncMode mode = resolveMode(syncMode);

        List<String> issueCodes = new ArrayList<>();
        List<String> recommendedActions = new ArrayList<>();
        if (!source.canRead()) {
            issueCodes.add("SOURCE_CONNECTOR_NOT_READABLE");
        }
        if (!target.canWrite()) {
            issueCodes.add("TARGET_CONNECTOR_NOT_WRITABLE");
        }
        if (!source.supportedModes().contains(mode)) {
            issueCodes.add("SOURCE_MODE_UNSUPPORTED");
        }
        if (!target.supportedModes().contains(mode)) {
            issueCodes.add("TARGET_MODE_UNSUPPORTED");
        }
        if (requiresCheckpoint(mode) && (!source.supportsCheckpointResume() || !target.supportsCheckpointResume())) {
            issueCodes.add("CHECKPOINT_RESUME_NOT_FULLY_SUPPORTED");
            recommendedActions.add("为该模式补充 checkpoint 策略，或降级为 FULL/ONE_TIME_MIGRATION 等有界模式。");
        }
        if (mode == SyncMode.CDC_STREAMING && (!source.supportsStreaming() || !target.supportsStreaming())) {
            issueCodes.add("STREAMING_CAPABILITY_REQUIRED");
        }
        if (mode == SyncMode.OFFLINE_EXPORT && target.connectorType() != SyncConnectorType.FILE
                && target.connectorType() != SyncConnectorType.OBJECT_STORAGE) {
            issueCodes.add("EXPORT_TARGET_SHOULD_BE_FILE_OR_OBJECT_STORAGE");
        }
        if (issueCodes.isEmpty()) {
            recommendedActions.add("当前组合通过能力矩阵预检，后续仍需执行连接测试、权限校验、模板字段映射校验和任务状态机校验。");
        } else {
            recommendedActions.add("请调整连接器类型、同步模式或补充 worker 支持后再创建生产任务。");
        }
        SyncTransferChannel transferChannel = SyncTransferChannelSupport.resolve(mode);
        List<String> performanceNotes = new ArrayList<>();
        performanceNotes.add(SyncTransferChannelSupport.explanation(transferChannel));
        performanceNotes.addAll(mergeNotes(source.performanceNotes(), target.performanceNotes()));

        return new SyncConnectorCompatibilityView(
                source.connectorType().name(),
                target.connectorType().name(),
                mode.name(),
                transferChannel == null ? null : transferChannel.name(),
                SyncTransferChannelSupport.referenceRuntime(transferChannel),
                issueCodes.isEmpty(),
                consistencyGoal(mode),
                requiresCheckpoint(mode),
                retryPattern(mode),
                List.copyOf(issueCodes),
                List.copyOf(recommendedActions),
                PAYLOAD_POLICY,
                List.copyOf(performanceNotes),
                mergeNotes(source.safetyNotes(), target.safetyNotes())
        );
    }

    private ConnectorCapabilityProfile profile(SyncConnectorType connectorType) {
        ConnectorCapabilityProfile profile = profiles.get(connectorType);
        if (profile == null) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR,
                    "未配置连接器能力矩阵: " + connectorType);
        }
        return profile;
    }

    private SyncConnectorType resolveConnectorType(String connectorType) {
        if (connectorType == null || connectorType.isBlank()) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR, "连接器类型不能为空");
        }
        try {
            return SyncConnectorType.valueOf(connectorType.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR,
                    "不支持的连接器类型: " + connectorType);
        }
    }

    private SyncMode resolveMode(String syncMode) {
        if (syncMode == null || syncMode.isBlank()) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR, "同步模式不能为空");
        }
        try {
            return SyncMode.valueOf(syncMode.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR,
                    "不支持的同步模式: " + syncMode);
        }
    }

    private boolean requiresCheckpoint(SyncMode mode) {
        return mode == SyncMode.INCREMENTAL_TIME
                || mode == SyncMode.INCREMENTAL_ID
                || mode == SyncMode.CDC_STREAMING
                || mode == SyncMode.REPLAY
                || mode == SyncMode.BACKFILL;
    }

    private String consistencyGoal(SyncMode mode) {
        return switch (mode) {
            case FULL, ONE_TIME_MIGRATION, OFFLINE_EXPORT -> "SNAPSHOT_BOUNDED";
            case INCREMENTAL_TIME, INCREMENTAL_ID, SCHEDULED_BATCH, BACKFILL, REPLAY -> "AT_LEAST_ONCE_DEDUP_AWARE";
            case CDC_STREAMING -> "LOW_LATENCY_EVENTUAL_CONSISTENCY";
            case OFFLINE_IMPORT -> "FILE_BOUNDED_WITH_ARTIFACT_INTEGRITY";
            case CUSTOM_SQL_QUERY -> "QUERY_BOUNDED_READ_ONLY";
        };
    }

    private String retryPattern(SyncMode mode) {
        return switch (mode) {
            case FULL, ONE_TIME_MIGRATION -> "SEGMENT_RETRY";
            case INCREMENTAL_TIME -> "WINDOW_RETRY";
            case INCREMENTAL_ID -> "RANGE_RETRY";
            case CDC_STREAMING -> "OFFSET_RECOVERY";
            case SCHEDULED_BATCH -> "RUN_LEVEL_RETRY";
            case REPLAY -> "CHECKPOINT_REPLAY";
            case BACKFILL -> "RANGE_BACKFILL_RETRY";
            case OFFLINE_IMPORT, OFFLINE_EXPORT -> "ARTIFACT_STAGE_RETRY";
            case CUSTOM_SQL_QUERY -> "QUERY_RESULT_RETRY_WITH_APPROVAL";
        };
    }

    private List<String> mergeNotes(List<String> sourceNotes, List<String> targetNotes) {
        List<String> merged = new ArrayList<>();
        merged.addAll(sourceNotes);
        for (String note : targetNotes) {
            if (!merged.contains(note)) {
                merged.add(note);
            }
        }
        return List.copyOf(merged);
    }

    private Map<SyncConnectorType, ConnectorCapabilityProfile> defaultProfiles() {
        Map<SyncConnectorType, ConnectorCapabilityProfile> map = new EnumMap<>(SyncConnectorType.class);
        Set<SyncMode> relationalModes = Set.of(
                SyncMode.FULL, SyncMode.INCREMENTAL_TIME, SyncMode.INCREMENTAL_ID,
                SyncMode.CDC_STREAMING, SyncMode.SCHEDULED_BATCH, SyncMode.ONE_TIME_MIGRATION,
                SyncMode.REPLAY, SyncMode.BACKFILL, SyncMode.CUSTOM_SQL_QUERY
        );
        register(map, profile(SyncConnectorType.MYSQL, "MySQL", "PRIMARY", relationalModes,
                List.of("PAGE_CURSOR", "TIME_WINDOW", "ID_RANGE", "BINLOG_POSITION"),
                List.of("大表建议按主键或时间字段分片；CDC 场景后续应接 binlog 位点和 worker 并发配额。"),
                List.of("连接串和账号必须留在 datasource-management 或密钥系统；预览样本必须脱敏。")));
        register(map, profile(SyncConnectorType.POSTGRESQL, "PostgreSQL", "PRIMARY", relationalModes,
                List.of("PAGE_CURSOR", "TIME_WINDOW", "ID_RANGE", "WAL_POSITION"),
                List.of("PostgreSQL 应与 MySQL 同级设计，避免产品只适配单一数据库。"),
                List.of("WAL/逻辑复制权限通常较高，启用前应经过管理员审批。")));
        register(map, profile(SyncConnectorType.SQL_SERVER, "SQL Server", "PREPARED", relationalModes,
                List.of("PAGE_CURSOR", "TIME_WINDOW", "ID_RANGE", "CDC_POSITION"),
                List.of("企业库通常需要更严格的连接池、锁等待和批量写入限制。"),
                List.of("生产连接必须绑定服务账号和最小权限。")));
        register(map, profile(SyncConnectorType.ORACLE, "Oracle", "PREPARED", relationalModes,
                List.of("PAGE_CURSOR", "TIME_WINDOW", "ID_RANGE", "LOG_POSITION"),
                List.of("Oracle 大表同步需要关注 undo、归档日志和分区裁剪。"),
                List.of("归档日志/CDC 权限敏感，必须审计。")));
        register(map, profile(SyncConnectorType.MONGODB, "MongoDB", "PREPARED",
                Set.of(SyncMode.FULL, SyncMode.INCREMENTAL_TIME, SyncMode.CDC_STREAMING,
                        SyncMode.SCHEDULED_BATCH, SyncMode.ONE_TIME_MIGRATION, SyncMode.REPLAY, SyncMode.BACKFILL),
                List.of("DOCUMENT_CURSOR", "TIME_WINDOW", "CHANGE_STREAM_RESUME_TOKEN"),
                List.of("文档库需要处理 schema drift、嵌套字段映射和单文档大小限制。"),
                List.of("字段预览必须隐藏敏感嵌套属性。")));
        register(map, profile(SyncConnectorType.KAFKA, "Kafka", "PRIMARY",
                Set.of(SyncMode.CDC_STREAMING, SyncMode.REPLAY, SyncMode.BACKFILL, SyncMode.SCHEDULED_BATCH),
                List.of("TOPIC_PARTITION_OFFSET", "CONSUMER_GROUP_OFFSET"),
                List.of("Kafka 不适合传统全表 FULL，同步语义应围绕 offset、分区和消费组。"),
                List.of("消息体样本不能直接进入诊断响应，schema 信息应走低敏摘要。")));
        register(map, profile(SyncConnectorType.HIVE, "Hive", "PREPARED",
                Set.of(SyncMode.FULL, SyncMode.INCREMENTAL_TIME, SyncMode.SCHEDULED_BATCH,
                        SyncMode.ONE_TIME_MIGRATION, SyncMode.REPLAY, SyncMode.BACKFILL),
                List.of("PARTITION_CURSOR", "BATCH_WINDOW"),
                List.of("Hive 更适合批量窗口同步，不适合低延迟流式任务。"),
                List.of("分区路径和表位置可能暴露存储结构，诊断中只返回分区策略摘要。")));
        register(map, profile(SyncConnectorType.CLICKHOUSE, "ClickHouse", "PREPARED",
                Set.of(SyncMode.FULL, SyncMode.INCREMENTAL_TIME, SyncMode.INCREMENTAL_ID,
                        SyncMode.SCHEDULED_BATCH, SyncMode.ONE_TIME_MIGRATION, SyncMode.REPLAY, SyncMode.BACKFILL),
                List.of("PARTITION_CURSOR", "TIME_WINDOW", "ID_RANGE"),
                List.of("ClickHouse 写入建议按批次合并，避免过小批次影响 merge。"),
                List.of("分析侧导入仍需遵守租户和项目边界。")));
        register(map, profile(SyncConnectorType.FILE, "File", "PRIMARY",
                Set.of(SyncMode.FULL, SyncMode.ONE_TIME_MIGRATION, SyncMode.OFFLINE_IMPORT, SyncMode.OFFLINE_EXPORT),
                List.of("FILE_CHUNK", "PACKAGE_STAGE"),
                List.of("大文件需要分片、格式探测和导入阶段状态。"),
                List.of("文件名、路径和样本内容都可能敏感，API 只能返回 artifactReference 或摘要。")));
        register(map, profile(SyncConnectorType.OBJECT_STORAGE, "Object Storage", "PRIMARY",
                Set.of(SyncMode.FULL, SyncMode.SCHEDULED_BATCH, SyncMode.ONE_TIME_MIGRATION,
                        SyncMode.OFFLINE_IMPORT, SyncMode.OFFLINE_EXPORT, SyncMode.REPLAY, SyncMode.BACKFILL),
                List.of("OBJECT_MANIFEST", "OBJECT_PREFIX_CURSOR", "PACKAGE_STAGE"),
                List.of("对象存储适合离线导入导出和 lake-style 批同步，需要 manifest 去重。"),
                List.of("不得返回 bucket/key、签名 URL 或 bearer token，统一使用低敏对象引用。")));
        register(map, profile(SyncConnectorType.REST_API, "REST API", "PREPARED",
                Set.of(SyncMode.FULL, SyncMode.INCREMENTAL_TIME, SyncMode.SCHEDULED_BATCH,
                        SyncMode.ONE_TIME_MIGRATION, SyncMode.REPLAY, SyncMode.BACKFILL),
                List.of("PAGE_TOKEN", "CURSOR_TOKEN", "TIME_WINDOW"),
                List.of("REST API 需要严格 rate limit、分页游标、重试退避和上游错误映射。"),
                List.of("API token、完整 URL、请求体和响应样本不能进入模板诊断。")));
        return Map.copyOf(map);
    }

    private void register(Map<SyncConnectorType, ConnectorCapabilityProfile> map, ConnectorCapabilityProfile profile) {
        map.put(profile.connectorType(), profile);
    }

    private ConnectorCapabilityProfile profile(SyncConnectorType type,
                                               String displayName,
                                               String supportLevel,
                                               Set<SyncMode> supportedModes,
                                               List<String> checkpointTypes,
                                               List<String> performanceNotes,
                                               List<String> safetyNotes) {
        boolean streaming = supportedModes.contains(SyncMode.CDC_STREAMING);
        boolean incremental = supportedModes.contains(SyncMode.INCREMENTAL_TIME)
                || supportedModes.contains(SyncMode.INCREMENTAL_ID)
                || streaming;
        boolean full = supportedModes.contains(SyncMode.FULL);
        return new ConnectorCapabilityProfile(
                type,
                displayName,
                supportLevel,
                true,
                type != SyncConnectorType.REST_API || supportLevel.equals("PRIMARY"),
                type != SyncConnectorType.REST_API,
                type != SyncConnectorType.REST_API,
                true,
                true,
                full,
                incremental,
                streaming,
                !checkpointTypes.isEmpty(),
                type != SyncConnectorType.REST_API && type != SyncConnectorType.FILE,
                type != SyncConnectorType.KAFKA,
                type != SyncConnectorType.KAFKA,
                true,
                true,
                supportedModes,
                checkpointTypes,
                performanceNotes,
                safetyNotes
        );
    }

    private record ConnectorCapabilityProfile(
            SyncConnectorType connectorType,
            String displayName,
            String supportLevel,
            boolean canRead,
            boolean canWrite,
            boolean supportsMetadataDiscovery,
            boolean supportsSchemaDiscovery,
            boolean supportsFieldSampling,
            boolean supportsPreview,
            boolean supportsFullSync,
            boolean supportsIncrementalSync,
            boolean supportsStreaming,
            boolean supportsCheckpointResume,
            boolean supportsPartitionParallelism,
            boolean supportsFieldMapping,
            boolean supportsTransformationHook,
            boolean supportsDataValidation,
            boolean supportsAdminThrottling,
            Set<SyncMode> supportedModes,
            List<String> recommendedCheckpointTypes,
            List<String> performanceNotes,
            List<String> safetyNotes
    ) {

        private SyncConnectorCapabilityView toView() {
            return new SyncConnectorCapabilityView(
                    connectorType.name(),
                    displayName,
                    supportLevel,
                    canRead,
                    canWrite,
                    supportsMetadataDiscovery,
                    supportsSchemaDiscovery,
                    supportsFieldSampling,
                    supportsPreview,
                    supportsFullSync,
                    supportsIncrementalSync,
                    supportsStreaming,
                    supportsCheckpointResume,
                    supportsPartitionParallelism,
                    supportsFieldMapping,
                    supportsTransformationHook,
                    supportsDataValidation,
                    supportsAdminThrottling,
                    supportedModes.stream().map(Enum::name).sorted().toList(),
                    recommendedCheckpointTypes,
                    performanceNotes,
                    safetyNotes
            );
        }
    }
}
