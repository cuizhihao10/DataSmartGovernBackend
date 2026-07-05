/**
 * @Author : Cui
 * @Date: 2026/07/05 14:22
 * @Description DataSmart Govern Backend - SyncOfflineJobPlanClassificationSupport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.datasync.entity.SyncTemplate;
import com.czh.datasmart.govern.datasync.support.SyncMode;

import java.util.List;
import java.util.Locale;

/**
 * 离线作业计划分类辅助类。
 *
 * <p>这个类只负责把模板中的低敏枚举事实翻译成 runner 更容易消费的分类编码，例如 Reader 家族、Writer 家族、
 * 模式族、分片策略和调度语义。它不访问数据库、不解析 SQL、不做权限校验，也不判断当前能不能执行。</p>
 *
 * <p>为什么从 {@link SyncOfflineJobPlanSupport} 中拆出来：</p>
 * <p>1. 离线作业计划的主流程应该聚焦“安全边界、审批、checkpoint、fail-closed”；</p>
 * <p>2. Reader/Writer/模式族这类分类规则会随着连接器扩展独立增长；</p>
 * <p>3. 独立 helper 能让单个文件保持可读，后续新增 ClickHouse、Oracle、S3、REST API 等连接器时不必改动主编排逻辑。</p>
 */
final class SyncOfflineJobPlanClassificationSupport {

    private SyncOfflineJobPlanClassificationSupport() {
    }

    /**
     * 根据源端 connector type 推导 Reader 家族。
     *
     * <p>这里返回的是低敏执行分类，不是具体 DataX 插件类名，也不是连接配置。
     * 真正执行时，runner 会根据 datasourceId 从 datasource-management/密钥系统获取受控连接信息。</p>
     */
    static String readerFamily(String connectorType) {
        String normalized = normalize(connectorType);
        if (normalized == null) {
            return "UNKNOWN_READER";
        }
        return switch (normalized) {
            case "MYSQL", "POSTGRESQL", "SQL_SERVER", "ORACLE", "CLICKHOUSE" -> "JDBC_READER";
            case "HIVE" -> "HIVE_READER";
            case "MONGODB" -> "MONGODB_READER";
            case "KAFKA" -> "KAFKA_READER";
            case "FILE" -> "FILE_READER";
            case "OBJECT_STORAGE" -> "OBJECT_STORAGE_READER";
            case "REST_API" -> "REST_API_READER";
            default -> "CUSTOM_CONNECTOR_READER";
        };
    }

    /**
     * 根据目标端 connector type 推导 Writer 家族。
     *
     * <p>Writer 家族只描述“写入目标属于哪类运行时能力”，不描述表名、bucket、topic、文件路径、账号或密钥。</p>
     */
    static String writerFamily(String connectorType) {
        String normalized = normalize(connectorType);
        if (normalized == null) {
            return "UNKNOWN_WRITER";
        }
        return switch (normalized) {
            case "MYSQL", "POSTGRESQL", "SQL_SERVER", "ORACLE", "CLICKHOUSE" -> "JDBC_WRITER";
            case "HIVE" -> "HIVE_WRITER";
            case "MONGODB" -> "MONGODB_WRITER";
            case "KAFKA" -> "KAFKA_WRITER";
            case "FILE" -> "FILE_WRITER";
            case "OBJECT_STORAGE" -> "OBJECT_STORAGE_WRITER";
            case "REST_API" -> "REST_API_WRITER";
            default -> "CUSTOM_CONNECTOR_WRITER";
        };
    }

    /**
     * 将细粒度 syncMode 归入 runner 模式族。
     *
     * <p>模式族用于未来离线 runner 选择执行模板。比如 FULL 与 SCHEDULED_BATCH 都属于 OFFLINE 通道，
     * 但 FULL 是全量扫描，SCHEDULED_BATCH 是定时窗口作业，两者的调度、checkpoint 和运行报告语义不同。</p>
     */
    static String modeFamily(SyncMode syncMode) {
        if (syncMode == null) {
            return "UNKNOWN_MODE";
        }
        return switch (syncMode) {
            case FULL -> "FULL_OBJECT_SCAN";
            case INCREMENTAL_TIME -> "INCREMENTAL_TIME_WINDOW";
            case INCREMENTAL_ID -> "INCREMENTAL_ID_RANGE";
            case CDC_STREAMING -> "REALTIME_CDC_STREAM";
            case SCHEDULED_BATCH -> "SCHEDULED_BATCH_WINDOW";
            case ONE_TIME_MIGRATION -> "ONE_TIME_MIGRATION";
            case REPLAY -> "CHECKPOINT_REPLAY";
            case BACKFILL -> "RANGE_BACKFILL";
            case OFFLINE_IMPORT -> "OFFLINE_IMPORT_ARTIFACT";
            case OFFLINE_EXPORT -> "OFFLINE_EXPORT_ARTIFACT";
            case CUSTOM_SQL_QUERY -> "CUSTOM_SQL_RESULT_SET";
        };
    }

    /**
     * 推导离线作业的分片策略摘要。
     *
     * <p>这里只返回策略分类，不返回 partitionConfig 原文。原因是分区配置可能包含字段名、时间窗口、对象清单、
     * 文件前缀或业务条件，普通规划响应不应该直接暴露。</p>
     */
    static String shardStrategy(SyncMode syncMode, SyncTemplateScopeContract scopeContract, SyncTemplate template) {
        if (scopeContract.multiObjectScope()) {
            return scopeContract.selectedObjectCount() > 0
                    ? "OBJECT_LEVEL_FAN_OUT_EXPLICIT_MAPPINGS"
                    : "OBJECT_LEVEL_FAN_OUT_BY_DISCOVERY_POLICY";
        }
        if (hasText(template.getPartitionConfig())) {
            return "EXPLICIT_PARTITION_CONFIG";
        }
        if (syncMode == SyncMode.CUSTOM_SQL_QUERY) {
            return "CUSTOM_SQL_SINGLE_RESULT_SET_OR_STATEMENT_REF_SHARD";
        }
        if (syncMode == SyncMode.INCREMENTAL_TIME) {
            return "TIME_WINDOW_SHARD";
        }
        if (syncMode == SyncMode.INCREMENTAL_ID) {
            return "ID_RANGE_SHARD";
        }
        if (syncMode == SyncMode.SCHEDULED_BATCH) {
            return "SCHEDULED_WINDOW_SHARD";
        }
        if (syncMode == SyncMode.REPLAY || syncMode == SyncMode.BACKFILL) {
            return "CHECKPOINT_OR_RANGE_SHARD";
        }
        if (syncMode == SyncMode.OFFLINE_IMPORT || syncMode == SyncMode.OFFLINE_EXPORT) {
            return "ARTIFACT_CHUNK_SHARD";
        }
        if (syncMode == SyncMode.CDC_STREAMING) {
            return "STREAM_PARTITION_SHARD";
        }
        return "SINGLE_OBJECT_PAGE_OR_PK_RANGE_SHARD";
    }

    /**
     * 推导调度语义。
     *
     * <p>这里特别区分“定时全量”和“SCHEDULED_BATCH”：定时全量不是新的 syncMode，
     * 而是 FULL 模板在创建任务时配置 scheduleConfig；SCHEDULED_BATCH 才表示按批处理窗口周期运行。</p>
     */
    static String scheduleSemantics(SyncMode syncMode, List<String> recommendedActions) {
        if (syncMode == SyncMode.FULL) {
            recommendedActions.add("如果需要定时全量，不新增 syncMode；请用 FULL 模板创建任务，并在任务层配置 scheduleConfig");
            return "MANUAL_FULL_OR_TASK_LEVEL_SCHEDULED_FULL";
        }
        if (syncMode == SyncMode.SCHEDULED_BATCH) {
            recommendedActions.add("SCHEDULED_BATCH 必须在创建任务时提供 scheduleConfig，并声明批处理窗口、重试和超时策略");
            return "TASK_LEVEL_SCHEDULE_REQUIRED_FOR_BATCH_WINDOW";
        }
        if (syncMode == SyncMode.CUSTOM_SQL_QUERY) {
            return "MANUAL_OR_SCHEDULED_QUERY_RESULT_JOB_REQUIRES_APPROVAL";
        }
        if (syncMode == SyncMode.REPLAY || syncMode == SyncMode.BACKFILL) {
            return "OPERATOR_TRIGGERED_RECOVERY_WINDOW";
        }
        if (syncMode == SyncMode.OFFLINE_IMPORT || syncMode == SyncMode.OFFLINE_EXPORT) {
            return "ARTIFACT_BOUNDED_JOB_CAN_BE_MANUAL_OR_SCHEDULED";
        }
        return "MANUAL_OR_TASK_LEVEL_SCHEDULED_JOB";
    }

    private static String normalize(String value) {
        return hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : null;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
