/**
 * @Author : Cui
 * @Date: 2026/07/05 16:10
 * @Description DataSmart Govern Backend - SyncDataXExecutionContractPolicySupport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * DataX-style 执行拓扑的策略分类辅助类。
 *
 * <p>{@link SyncDataXExecutionContractSupport} 负责把各种合同对象组装成最终拓扑；本类只负责“给事实命名”和
 * “做低敏数量估算”。拆分它的目的不是为了机械增加文件，而是避免主生成器继续膨胀成一个包含所有 if/else 的大类。</p>
 *
 * <p>本类保持无状态、无 Spring Bean、无数据库访问。所有方法都只基于低敏枚举和摘要字段做判断，
 * 不读取 SQL、对象映射正文、字段映射正文、过滤条件正文、凭据或行样本。</p>
 */
final class SyncDataXExecutionContractPolicySupport {

    private SyncDataXExecutionContractPolicySupport() {
    }

    static List<String> requiredCapabilities(SyncOfflineRunnerShardPlan shardPlan,
                                             SyncOfflineRunnerExecutionReport reportContract,
                                             boolean dedicatedRunnerRequired,
                                             boolean checkpointRequired) {
        List<String> capabilities = new ArrayList<>();
        capabilities.add("DATAX_JOB_TASKGROUP_CHANNEL_TOPOLOGY");
        capabilities.add("READER_WRITER_PLUGIN_SELECTION");
        capabilities.add("LOW_SENSITIVE_RUNTIME_SAFETY_POLICY");
        capabilities.addAll(shardPlan.requiredRunnerCapabilities());
        if (reportContract != null && reportContract.publishProgressRequired()) {
            capabilities.add("STRUCTURED_PROGRESS_REPORT_CALLBACK");
        }
        if (dedicatedRunnerRequired) {
            capabilities.add("DEDICATED_RUNNER_ADAPTER");
        }
        if (checkpointRequired) {
            capabilities.add("DURABLE_CHECKPOINT_HANDOFF");
        }
        return distinct(capabilities);
    }

    static String topologyStatus(boolean offlineChannel,
                                 boolean approvalRequired,
                                 boolean minimalBridgeCompatible,
                                 boolean dedicatedRunnerRequired,
                                 boolean checkpointRequired) {
        if (!offlineChannel) {
            return "NOT_OFFLINE_TOPOLOGY_USE_REALTIME_PIPELINE";
        }
        if (approvalRequired) {
            return "WAITING_APPROVAL_BEFORE_DATAX_TOPOLOGY_DISPATCH";
        }
        if (minimalBridgeCompatible) {
            return "MINIMAL_SINGLE_CHANNEL_RUN_ONCE_TOPOLOGY";
        }
        if (checkpointRequired) {
            return "DATAX_TOPOLOGY_REQUIRES_CHECKPOINT_HANDOFF";
        }
        if (dedicatedRunnerRequired) {
            return "DEDICATED_DATAX_STYLE_TOPOLOGY_REQUIRED";
        }
        return "DATAX_TOPOLOGY_MODELED_WAITING_RUNNER_POLICY";
    }

    static String jobExecutionMode(boolean offlineChannel,
                                   boolean minimalBridgeCompatible,
                                   boolean dedicatedRunnerRequired) {
        if (!offlineChannel) {
            return "REALTIME_PIPELINE_NOT_ACCEPTED_BY_DATAX_OFFLINE_TOPOLOGY";
        }
        if (minimalBridgeCompatible) {
            return "IN_PROCESS_JAVA_RUN_ONCE_BRIDGE";
        }
        if (dedicatedRunnerRequired) {
            return "DEDICATED_RUNNER_ASYNC_DISPATCH_AND_CALLBACK";
        }
        return "RUNNER_POLICY_NOT_SELECTED";
    }

    static String jobKind(String shardKind) {
        if ("OBJECT_FAN_OUT_EXPLICIT".equals(shardKind) || "OBJECT_FAN_OUT_DISCOVERY".equals(shardKind)) {
            return "MULTI_OBJECT_FAN_OUT_JOB";
        }
        if ("SCHEMA_OR_DATABASE_DISCOVERY_FAN_OUT".equals(shardKind)) {
            return "SCHEMA_OR_DATABASE_MIGRATION_JOB";
        }
        if ("CUSTOM_SQL_RESULT_SET".equals(shardKind)) {
            return "CUSTOM_SQL_RESULT_SET_JOB";
        }
        if ("SCHEDULED_WINDOW".equals(shardKind)) {
            return "SCHEDULED_BATCH_WINDOW_JOB";
        }
        if ("CHECKPOINT_RANGE".equals(shardKind)) {
            return "INCREMENTAL_CHECKPOINT_RANGE_JOB";
        }
        if ("RECOVERY_RANGE".equals(shardKind)) {
            return "RECOVERY_OR_BACKFILL_RANGE_JOB";
        }
        if ("ARTIFACT_CHUNK".equals(shardKind)) {
            return "OFFLINE_IMPORT_EXPORT_ARTIFACT_JOB";
        }
        return "SINGLE_OBJECT_JOB";
    }

    static String objectReadPolicy(String shardKind) {
        if ("CUSTOM_SQL_RESULT_SET".equals(shardKind)) {
            return "MANAGED_STATEMENT_RESULT_SET_READ";
        }
        if (shardKind.contains("OBJECT_FAN_OUT")) {
            return "OBJECT_LEVEL_FAN_OUT_READ";
        }
        if ("SCHEMA_OR_DATABASE_DISCOVERY_FAN_OUT".equals(shardKind)) {
            return "RUNTIME_DISCOVERY_OBJECT_READ";
        }
        if ("SCHEDULED_WINDOW".equals(shardKind)) {
            return "SCHEDULED_WINDOW_READ";
        }
        if ("CHECKPOINT_RANGE".equals(shardKind)) {
            return "CHECKPOINT_RANGE_READ";
        }
        if ("RECOVERY_RANGE".equals(shardKind)) {
            return "RECOVERY_OR_BACKFILL_RANGE_READ";
        }
        if ("ARTIFACT_CHUNK".equals(shardKind)) {
            return "ARTIFACT_CHUNK_READ";
        }
        return "BOUNDED_SINGLE_OBJECT_READ";
    }

    static String objectWritePolicy(String scopeType) {
        String scope = normalize(scopeType);
        if ("OBJECT_LIST".equals(scope)) {
            return "OBJECT_LEVEL_TARGET_FAN_OUT_WRITE";
        }
        if ("SCHEMA_FULL".equals(scope) || "DATABASE_FULL".equals(scope)) {
            return "RUNTIME_DISCOVERY_TARGET_OBJECT_WRITE";
        }
        if ("CUSTOM_SQL_QUERY".equals(scope)) {
            return "CUSTOM_SQL_RESULT_TARGET_OBJECT_WRITE";
        }
        return "SINGLE_TARGET_OBJECT_WRITE";
    }

    static String splitPolicy(String shardKind, boolean partitionDeclared, boolean minimalBridgeCompatible) {
        if (partitionDeclared) {
            return "PARTITION_CONFIG_DECLARED_RUNNER_MUST_VALIDATE";
        }
        if (minimalBridgeCompatible) {
            return "NO_PARALLEL_SPLIT_SINGLE_OBJECT_BATCH_LOOP";
        }
        if (shardKind.contains("OBJECT_FAN_OUT")) {
            return "OBJECT_LEVEL_SPLIT";
        }
        if ("SCHEMA_OR_DATABASE_DISCOVERY_FAN_OUT".equals(shardKind)) {
            return "RUNNER_DISCOVERS_OBJECT_SPLITS";
        }
        if ("CHECKPOINT_RANGE".equals(shardKind) || "RECOVERY_RANGE".equals(shardKind)) {
            return "CHECKPOINT_OR_RANGE_SPLIT";
        }
        if ("ARTIFACT_CHUNK".equals(shardKind)) {
            return "ARTIFACT_CHUNK_SPLIT";
        }
        return "DEDICATED_RUNNER_SPLIT_POLICY_REQUIRED";
    }

    static String transformerPolicy(boolean fieldMappingDeclared,
                                    boolean fieldRenameRequired,
                                    boolean dedicatedRunnerRequired) {
        if (!fieldMappingDeclared) {
            return "FIELD_MAPPING_REQUIRED_BEFORE_EXECUTION";
        }
        if (fieldRenameRequired) {
            return "ROW_KEY_ALIGNMENT_OR_TRANSFORM_CHAIN_REQUIRED";
        }
        if (dedicatedRunnerRequired) {
            return "DEDICATED_RUNNER_TRANSFORM_CHAIN_AVAILABLE_FOR_TYPE_AND_MASKING_RULES";
        }
        return "DIRECT_FIELD_PROJECTION_NO_COMPLEX_TRANSFORM";
    }

    static String idempotencyPolicy(String writeStrategy, boolean dedicatedRunnerRequired) {
        String strategy = normalize(writeStrategy);
        if ("UPSERT".equals(strategy) || "REPLACE".equals(strategy) || "INSERT_IGNORE".equals(strategy)) {
            return "CONFLICT_KEY_IDEMPOTENCY_REQUIRED";
        }
        if ("OVERWRITE".equals(strategy)) {
            return "DESTRUCTIVE_REWRITE_REQUIRES_APPROVAL_BACKUP_AND_ROLLBACK_PLAN";
        }
        if (dedicatedRunnerRequired) {
            return "DEDICATED_RUNNER_BATCH_ID_AND_CHECKPOINT_IDEMPOTENCY_REQUIRED";
        }
        return "APPEND_RETRY_REQUIRES_BATCH_BOUNDARY_AND_OPERATOR_REVIEW";
    }

    static String conflictPolicy(String writeStrategy) {
        String strategy = normalize(writeStrategy);
        if ("UPSERT".equals(strategy)) {
            return "UPSERT_ON_DECLARED_PRIMARY_OR_UNIQUE_KEY";
        }
        if ("INSERT_IGNORE".equals(strategy)) {
            return "IGNORE_DUPLICATE_ON_DECLARED_CONFLICT_KEY";
        }
        if ("REPLACE".equals(strategy)) {
            return "REPLACE_ON_DECLARED_CONFLICT_KEY";
        }
        if ("OVERWRITE".equals(strategy)) {
            return "OVERWRITE_TARGET_RANGE_AFTER_APPROVAL";
        }
        return "NO_CONFLICT_POLICY_APPEND_ONLY";
    }

    static String channelKind(String shardKind) {
        if ("CUSTOM_SQL_RESULT_SET".equals(shardKind)) {
            return "CUSTOM_SQL_CHANNEL";
        }
        if (shardKind.contains("OBJECT_FAN_OUT")) {
            return "OBJECT_FAN_OUT_CHANNEL";
        }
        if ("SCHEMA_OR_DATABASE_DISCOVERY_FAN_OUT".equals(shardKind)) {
            return "DISCOVERY_FAN_OUT_CHANNEL";
        }
        if ("SCHEDULED_WINDOW".equals(shardKind)) {
            return "SCHEDULED_WINDOW_CHANNEL";
        }
        if ("CHECKPOINT_RANGE".equals(shardKind) || "RECOVERY_RANGE".equals(shardKind)) {
            return "CHECKPOINT_OR_RECOVERY_RANGE_CHANNEL";
        }
        if ("ARTIFACT_CHUNK".equals(shardKind)) {
            return "ARTIFACT_CHUNK_CHANNEL";
        }
        return "SINGLE_OBJECT_CHANNEL";
    }

    static String taskGroupKind(String shardKind) {
        if (shardKind.contains("OBJECT_FAN_OUT")) {
            return "OBJECT_FAN_OUT_GROUP";
        }
        if ("SCHEMA_OR_DATABASE_DISCOVERY_FAN_OUT".equals(shardKind)) {
            return "RUNTIME_DISCOVERY_GROUP";
        }
        if ("SCHEDULED_WINDOW".equals(shardKind)) {
            return "SCHEDULED_WINDOW_GROUP";
        }
        if ("CHECKPOINT_RANGE".equals(shardKind) || "RECOVERY_RANGE".equals(shardKind)) {
            return "CHECKPOINT_RANGE_GROUP";
        }
        return "SINGLE_OBJECT_GROUP";
    }

    static int estimatedTaskGroupCount(SyncOfflineRunnerShardPlan shardPlan, boolean minimalBridgeCompatible) {
        if (minimalBridgeCompatible) {
            return 1;
        }
        if (shardPlan.estimatedShardCount() < 0) {
            return -1;
        }
        return Math.max(1, shardPlan.estimatedShardCount());
    }

    static int estimatedChannelCount(SyncOfflineRunnerShardPlan shardPlan, boolean minimalBridgeCompatible) {
        if (minimalBridgeCompatible) {
            return 1;
        }
        if (shardPlan.estimatedShardCount() < 0 || shardPlan.partitionDeclared()) {
            return -1;
        }
        return Math.max(1, shardPlan.estimatedShardCount());
    }

    static int estimatedTaskCount(SyncOfflineRunnerShardPlan shardPlan) {
        if (shardPlan.estimatedShardCount() < 0) {
            return -1;
        }
        return Math.max(1, shardPlan.estimatedShardCount());
    }

    private static List<String> distinct(List<String> values) {
        return new ArrayList<>(new LinkedHashSet<>(values == null ? List.of() : values));
    }

    private static String normalize(String value) {
        return value != null && !value.isBlank() ? value.trim().toUpperCase(Locale.ROOT) : null;
    }
}
