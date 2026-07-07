/**
 * @Author : Cui
 * @Date: 2026/07/05 14:26
 * @Description DataSmart Govern Backend - SyncOfflineRunnerContractPolicySupport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.datasync.support.SyncMode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * 离线 Runner 合同策略辅助类。
 *
 * <p>{@link SyncOfflineRunnerContractSupport} 负责从 template、task、execution、workerPlan 中收集低敏事实；
 * 本类负责把这些事实翻译成合同状态、分片策略、执行报告策略和推荐动作。拆分的原因不是追求机械的文件数量，
 * 而是避免“入口编排 + 所有策略规则 + 所有字符串常量”继续堆在一个 Support 中，后续接入真实 Runner 时难以维护。</p>
 *
 * <p>本类所有方法都保持 package-private static：它不持有状态、不访问数据库、不调用远端服务，也不解析敏感 JSON。
 * 这种纯函数式策略类更容易被单元测试覆盖，也方便后续替换为配置化策略或数据库驱动策略。</p>
 */
final class SyncOfflineRunnerContractPolicySupport {

    static final String SHARD_PLAN_VERSION = "datasmart.data-sync.offline-runner-shard-plan.v1";
    static final String SHARD_PAYLOAD_POLICY = "LOW_SENSITIVE_SHARD_SUMMARY_NO_OBJECT_LIST_NO_PARTITION_BODY";
    static final String REPORT_PAYLOAD_POLICY = "LOW_SENSITIVE_REPORT_CONTRACT_NO_ROWS_NO_CHECKPOINT_VALUE";

    private SyncOfflineRunnerContractPolicySupport() {
    }

    /**
     * 构建低敏分片计划。
     *
     * <p>该方法只输出分片类别、数量估计和 Runner 能力需求，不返回对象名、分区字段、范围条件或配置原文。
     * 真实分片展开应该在专用 Runner 的受控环境中完成，并通过低敏报告回写结果。</p>
     */
    static SyncOfflineRunnerShardPlan shardPlan(String syncMode,
                                                String syncScopeType,
                                                String shardStrategy,
                                                int selectedObjectCount,
                                                boolean objectMappingDeclared,
                                                boolean partitionDeclared,
                                                boolean checkpointRequired,
                                                boolean taskLevelScheduleRequired,
                                                boolean minimalEndToEndSupported) {
        String shardKind = shardKind(syncMode, syncScopeType, selectedObjectCount);
        int estimatedShardCount = estimatedShardCount(shardKind, selectedObjectCount);
        String confidence = estimatedShardCount < 0 ? "RUNTIME_DISCOVERY" : "EXACT_OR_CONFIG_DECLARED";
        String parallelismPolicy = minimalEndToEndSupported
                ? "SERIAL_OR_SMALL_BATCH_PARALLELISM_BY_MINIMAL_BRIDGE"
                : "DEDICATED_RUNNER_PARALLELISM_AND_QUOTA_POLICY_REQUIRED";
        return new SyncOfflineRunnerShardPlan(
                SHARD_PLAN_VERSION,
                shardKind,
                firstText(shardStrategy, "UNKNOWN_SHARD_STRATEGY"),
                estimatedShardCount,
                confidence,
                parallelismPolicy,
                checkpointRequired,
                taskLevelScheduleRequired,
                objectMappingDeclared,
                partitionDeclared,
                requiredRunnerCapabilities(shardKind, checkpointRequired, taskLevelScheduleRequired, partitionDeclared),
                SHARD_PAYLOAD_POLICY
        );
    }

    /**
     * 构建缺省阻断分片计划。
     *
     * <p>当模板或 workerPlan 缺失时，系统仍然返回一个结构化合同，而不是返回 null。
     * 这样运维台和测试可以稳定读取“不要派发”的原因。</p>
     */
    static SyncOfflineRunnerShardPlan emptyShardPlan() {
        return new SyncOfflineRunnerShardPlan(
                SHARD_PLAN_VERSION,
                "UNKNOWN",
                "UNKNOWN_SHARD_STRATEGY",
                0,
                "UNKNOWN",
                "DO_NOT_DISPATCH",
                false,
                false,
                false,
                false,
                List.of("CONTRACT_CONTEXT_REQUIRED"),
                SHARD_PAYLOAD_POLICY
        );
    }

    /**
     * 构建执行报告合同。
     *
     * <p>这里强调 checkpoint 只能以引用或 digest 方式出现在报告中，不能把水位原始值、SQL、行数据或错误样本正文写到
     * 普通事件里。这样后续接入 Prometheus、Kafka 事件、审计表或 Agent 可观测节点时，默认就符合低敏原则。</p>
     */
    static SyncOfflineRunnerExecutionReport reportContract(boolean checkpointRequired) {
        return new SyncOfflineRunnerExecutionReport(
                "LOW_SENSITIVE_STRUCTURED_EXECUTION_REPORT_REQUIRED",
                "HEARTBEAT_AND_BATCH_PROGRESS_WITHOUT_ROW_PAYLOAD",
                checkpointRequired
                        ? "CHECKPOINT_REF_OR_DIGEST_ONLY_NO_RAW_VALUE"
                        : "FINAL_WATERMARK_OPTIONAL_NO_RAW_VALUE",
                "FAILED_SAMPLE_DIGEST_OR_REFERENCE_ONLY",
                "LOW_CARDINALITY_LABELS_TENANT_PROJECT_MODE_CONNECTOR_STATUS",
                true,
                true,
                true,
                true,
                List.of(
                        "executionId",
                        "runStatus",
                        "recordsRead",
                        "recordsWritten",
                        "failedRecordCount",
                        "checkpointRefOrDigest",
                        "retryable",
                        "operatorActionRequired"
                ),
                REPORT_PAYLOAD_POLICY
        );
    }

    /**
     * 计算 Runner 合同状态。
     *
     * <p>该状态是给执行调度看的，不是给普通用户看的自然语言。它刻意区分“bridge 预派发可行”和
     * “最小 bridge 端到端支持”：增量、恢复回放等场景可能通过部分预检查，但只要缺 checkpoint handoff，
     * 就不能宣称端到端完成。</p>
     */
    static String contractStatus(boolean offlineChannel,
                                 boolean planReady,
                                 boolean approvalRequired,
                                 boolean dedicatedRunnerRequired,
                                 boolean minimalBridgeDispatchable,
                                 boolean minimalEndToEndSupported,
                                 boolean checkpointRequired,
                                 List<String> issueCodes) {
        if (!offlineChannel) {
            return "NOT_ACCEPTED_USE_REALTIME_CDC_PIPELINE";
        }
        if (!planReady || hasHardBlockingIssue(issueCodes)) {
            return "BLOCKED_BEFORE_RUNNER_SELECTION";
        }
        if (approvalRequired) {
            return "WAITING_APPROVAL_BEFORE_RUNNER_DISPATCH";
        }
        if (minimalEndToEndSupported) {
            return "MINIMAL_BRIDGE_END_TO_END_SUPPORTED";
        }
        if (minimalBridgeDispatchable && checkpointRequired) {
            return "CHECKPOINT_HANDOFF_REQUIRED_BEFORE_MINIMAL_BRIDGE_COMPLETION";
        }
        if (dedicatedRunnerRequired) {
            return "DEDICATED_OFFLINE_RUNNER_REQUIRED";
        }
        return "PLAN_READY_WAITING_RUNNER_POLICY";
    }

    /**
     * 计算 Runner 边界说明。
     *
     * <p>边界说明用于诊断和后续 runbook：它告诉我们应该走最小 bridge、专用离线 Runner，还是完全切到实时 CDC pipeline。</p>
     */
    static String runnerBoundary(boolean offlineChannel,
                                 boolean minimalEndToEndSupported,
                                 boolean dedicatedRunnerRequired,
                                 boolean checkpointRequired) {
        if (!offlineChannel) {
            return "NOT_OFFLINE_USE_REALTIME_CDC_PIPELINE";
        }
        if (minimalEndToEndSupported) {
            return "MINIMAL_RUN_ONCE_BRIDGE_CAN_COMPLETE_END_TO_END";
        }
        if (checkpointRequired) {
            return "CHECKPOINT_HANDOFF_REQUIRED_BEFORE_DATA_SYNC_CAN_COMPLETE";
        }
        if (dedicatedRunnerRequired) {
            return "DEDICATED_DATAX_STYLE_OFFLINE_RUNNER_REQUIRED";
        }
        return "OFFLINE_RUNNER_POLICY_NOT_SELECTED";
    }

    static List<String> failClosedReasons(boolean offlineChannel,
                                          boolean dedicatedRunnerRequired,
                                          boolean minimalEndToEndSupported,
                                          boolean checkpointRequired,
                                          List<String> issueCodes) {
        List<String> reasons = new ArrayList<>();
        if (!offlineChannel) {
            reasons.add("NOT_OFFLINE_CHANNEL");
        }
        if (dedicatedRunnerRequired && !minimalEndToEndSupported) {
            reasons.add("DEDICATED_OFFLINE_RUNNER_REQUIRED");
        }
        if (checkpointRequired && !minimalEndToEndSupported) {
            reasons.add("CHECKPOINT_HANDOFF_REQUIRED");
        }
        reasons.addAll(issueCodes == null ? List.of() : issueCodes);
        return distinct(reasons);
    }

    static List<String> recommendedActions(SyncMode syncMode,
                                           boolean dedicatedRunnerRequired,
                                           boolean checkpointRequired,
                                           boolean taskLevelScheduleRequired) {
        List<String> actions = new ArrayList<>();
        if (dedicatedRunnerRequired) {
            actions.add("接入专用 DataX-style 离线 Runner 后再放行该合同，当前最小 run-once bridge 不应猜测执行");
        }
        if (checkpointRequired) {
            actions.add("补齐 checkpointRef/checkpointDigest 安全交接、恢复单元和幂等写入后再执行需要水位的离线作业");
        }
        if (taskLevelScheduleRequired) {
            actions.add("在任务层声明 scheduleConfig、超时、重试和维护窗口；定期批量还必须声明批处理窗口，避免 Runner 自行推断调度语义");
        }
        if (syncMode == SyncMode.CUSTOM_SQL_QUERY) {
            actions.add("自定义 SQL 应优先使用 statementRef 托管，只读校验和审批通过后再进入 Runner");
        }
        if (actions.isEmpty()) {
            actions.add("当前合同可交由最小 run-once bridge 做端到端闭环，后续仍应补充完整运行报告和容量治理");
        }
        return List.copyOf(actions);
    }

    static boolean minimalBridgeMode(String syncMode) {
        String mode = normalize(syncMode);
        return "FULL".equals(mode)
                || "ONE_TIME_MIGRATION".equals(mode)
                || "SCHEDULED_FULL".equals(mode)
                || "SCHEDULED_BATCH".equals(mode)
                || "CUSTOM_SQL_QUERY".equals(mode);
    }

    static boolean hasHardBlockingIssue(List<String> issueCodes) {
        if (issueCodes == null) {
            return false;
        }
        return issueCodes.stream().anyMatch(issueCode ->
                "SYNC_MODE_MISSING".equals(issueCode)
                        || "SYNC_MODE_UNSUPPORTED".equals(issueCode)
                        || "CONNECTOR_FACTS_INCOMPLETE".equals(issueCode)
                        || "CONNECTOR_COMPATIBILITY_UNSUPPORTED".equals(issueCode)
                        || "CONNECTOR_FACTS_MISSING".equals(issueCode)
                        || "TEMPLATE_NOT_FOUND".equals(issueCode)
                        || "TEMPLATE_DISABLED".equals(issueCode)
                        || "WORKER_PLAN_BLOCKED".equals(issueCode)
                        || "BRIDGE_INPUT_CONTEXT_MISSING".equals(issueCode));
    }

    private static String shardKind(String syncMode, String syncScopeType, int selectedObjectCount) {
        String mode = normalize(syncMode);
        String scope = normalize(syncScopeType);
        if ("CDC_STREAMING".equals(mode)) {
            return "REALTIME_STREAM_PARTITION";
        }
        if ("CUSTOM_SQL_QUERY".equals(mode) || "CUSTOM_SQL_QUERY".equals(scope)) {
            return "CUSTOM_SQL_RESULT_SET";
        }
        if ("OBJECT_LIST".equals(scope)) {
            return selectedObjectCount > 0 ? "OBJECT_FAN_OUT_EXPLICIT" : "OBJECT_FAN_OUT_DISCOVERY";
        }
        if ("SCHEMA_FULL".equals(scope) || "DATABASE_FULL".equals(scope)) {
            return "SCHEMA_OR_DATABASE_DISCOVERY_FAN_OUT";
        }
        if ("SCHEDULED_FULL".equals(mode)) {
            return "SCHEDULED_FULL_SCAN";
        }
        if ("SCHEDULED_BATCH".equals(mode)) {
            return "SCHEDULED_WINDOW";
        }
        if ("INCREMENTAL_TIME".equals(mode) || "INCREMENTAL_ID".equals(mode)) {
            return "CHECKPOINT_RANGE";
        }
        if ("REPLAY".equals(mode) || "BACKFILL".equals(mode)) {
            return "RECOVERY_RANGE";
        }
        if ("OFFLINE_IMPORT".equals(mode) || "OFFLINE_EXPORT".equals(mode)) {
            return "ARTIFACT_CHUNK";
        }
        return "SINGLE_OBJECT";
    }

    private static int estimatedShardCount(String shardKind, int selectedObjectCount) {
        if ("OBJECT_FAN_OUT_EXPLICIT".equals(shardKind)) {
            return selectedObjectCount;
        }
        if ("SCHEMA_OR_DATABASE_DISCOVERY_FAN_OUT".equals(shardKind)
                || "OBJECT_FAN_OUT_DISCOVERY".equals(shardKind)) {
            return -1;
        }
        return 1;
    }

    private static List<String> requiredRunnerCapabilities(String shardKind,
                                                           boolean checkpointRequired,
                                                           boolean taskLevelScheduleRequired,
                                                           boolean partitionDeclared) {
        List<String> capabilities = new ArrayList<>();
        capabilities.add("LOW_SENSITIVE_JOB_CONTRACT");
        capabilities.add("READER_WRITER_PLUGIN_SELECTION");
        capabilities.add("STRUCTURED_EXECUTION_REPORT");
        if (shardKind.contains("OBJECT_FAN_OUT") || shardKind.contains("DISCOVERY")) {
            capabilities.add("OBJECT_FAN_OUT_COORDINATION");
        }
        if ("CUSTOM_SQL_RESULT_SET".equals(shardKind)) {
            capabilities.add("MANAGED_STATEMENT_REF_OR_READ_ONLY_SQL_EXECUTION");
        }
        if (checkpointRequired) {
            capabilities.add("CHECKPOINT_HANDOFF");
        }
        if (taskLevelScheduleRequired) {
            capabilities.add("TASK_LEVEL_SCHEDULE_WINDOW");
        }
        if (partitionDeclared) {
            capabilities.add("PARTITION_PARALLELISM");
        }
        return List.copyOf(capabilities);
    }

    private static List<String> distinct(List<String> values) {
        return new ArrayList<>(new LinkedHashSet<>(values == null ? List.of() : values));
    }

    private static String firstText(String value, String fallback) {
        return value != null && !value.isBlank() ? value : fallback;
    }

    private static String normalize(String value) {
        return value != null && !value.isBlank() ? value.trim().toUpperCase(Locale.ROOT) : null;
    }
}
