/**
 * @Author : Cui
 * @Date: 2026/07/05 15:58
 * @Description DataSmart Govern Backend - SyncDataXExecutionContractSupport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.datasync.controller.dto.SyncOfflineJobPlanResponse;
import com.czh.datasmart.govern.datasync.controller.dto.SyncWorkerExecutionPlanView;
import com.czh.datasmart.govern.datasync.entity.SyncTemplate;
import com.czh.datasmart.govern.datasync.support.SyncMode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * DataX-style 执行拓扑合同生成器。
 *
 * <p>本类专门负责把现有离线计划和 Runner 调度事实翻译成 Job/TaskGroup/Channel/Reader/Writer 拓扑。
 * 它不访问数据库、不调用 datasource-management、不解析凭据、不生成真实 DataX JSON，也不执行 SQL。
 * 这样做是为了保持“控制面建模”和“执行面搬运”解耦：data-sync 可以清楚描述要执行什么，真正的数据抽取和加载
 * 仍由 Java 执行面或未来专用 Runner 在受控边界内完成。</p>
 *
 * <p>为什么要单独拆出这个类，而不是继续往 {@link SyncOfflineRunnerContractSupport} 里加字段：</p>
 * <p>1. Runner 合同负责调度门禁，DataX 拓扑负责执行结构，二者关注点不同；</p>
 * <p>2. 后续接入真实 DataX、Flink batch 或 Spark batch 时，只需要替换拓扑到实际 job spec 的转换层；</p>
 * <p>3. 单独建模后，面试或学习时可以清楚解释 DataX 的 Job/TaskGroup/Channel/Reader/Writer 思想如何落在项目里。</p>
 */
final class SyncDataXExecutionContractSupport {

    static final String CONTRACT_VERSION = "datasmart.data-sync.datax-execution-contract.v1";
    static final String PAYLOAD_POLICY = "LOW_SENSITIVE_DATAX_EXECUTION_TOPOLOGY_NO_SQL_NO_CREDENTIALS_NO_ROW_DATA";
    private static final String READER_PAYLOAD_POLICY = "LOW_SENSITIVE_READER_CONTRACT_NO_SQL_NO_OBJECT_BODY";
    private static final String WRITER_PAYLOAD_POLICY = "LOW_SENSITIVE_WRITER_CONTRACT_NO_TARGET_OBJECT_BODY";
    private static final String CHANNEL_PAYLOAD_POLICY = "LOW_SENSITIVE_CHANNEL_CONTRACT_NO_ROW_PAYLOAD";
    private static final String TASK_GROUP_PAYLOAD_POLICY = "LOW_SENSITIVE_TASK_GROUP_CONTRACT_NO_TASK_LIST_BODY";
    private static final String SAFETY_PAYLOAD_POLICY = "LOW_SENSITIVE_RUNTIME_SAFETY_POLICY";

    /**
     * 从面向 UI/Agent 的离线作业计划生成 DataX-style 执行拓扑。
     *
     * <p>该方法用于模板规划阶段，此时还没有 task/execution，因此只能形成“将来应该如何执行”的低敏拓扑。
     * 例如 FULL 单对象会形成一个代表性 TaskGroup 和一个 Channel；OBJECT_LIST 或 SCHEMA_FULL 会说明需要 fan-out，
     * 但不会把对象映射清单展开到响应里。</p>
     */
    SyncDataXJobExecutionContract buildFromOfflinePlan(SyncOfflineJobPlanResponse plan,
                                                       SyncOfflineRunnerShardPlan shardPlan,
                                                       SyncOfflineRunnerExecutionReport reportContract) {
        if (plan == null) {
            return blockedTopology(List.of("OFFLINE_JOB_PLAN_MISSING"));
        }
        return buildContract(
                plan.sourceConnectorType(),
                plan.targetConnectorType(),
                plan.syncMode(),
                plan.transferChannel(),
                plan.referenceRuntime(),
                plan.syncScopeType(),
                plan.readerFamily(),
                plan.writerFamily(),
                plan.modeFamily(),
                null,
                plan.sqlStatementPolicy(),
                plan.filterDeclared(),
                plan.fieldMappingDeclared(),
                !plan.fieldMappingRunnableByMinimalBridge(),
                plan.executableByMinimalBridge(),
                plan.dedicatedOfflineRunnerRequired(),
                plan.approvalRequired(),
                plan.checkpointRequired(),
                plan.taskLevelScheduleRequired(),
                shardPlan,
                reportContract,
                plan.issueCodes()
        );
    }

    /**
     * 从 worker/bridge 执行事实生成 DataX-style 执行拓扑。
     *
     * <p>该方法用于真实执行阶段。此时 template、workerPlan、字段映射合同和范围合同都已经存在，因此可以更准确地判断：
     * 当前是最小单通道 run-once、需要专用 Runner、需要 checkpoint handoff，还是因为审批/范围/字段问题必须阻断。</p>
     */
    SyncDataXJobExecutionContract buildFromBridgeFacts(SyncTemplate template,
                                                       SyncWorkerExecutionPlanView workerPlan,
                                                       SyncFieldMappingExecutionContract fieldMappingContract,
                                                       SyncTemplateScopeContract scopeContract,
                                                       SyncOfflineRunnerShardPlan shardPlan,
                                                       SyncOfflineRunnerExecutionReport reportContract,
                                                       boolean minimalEndToEndSupported,
                                                       boolean dedicatedRunnerRequired,
                                                       boolean approvalRequired,
                                                       boolean checkpointRequired,
                                                       boolean taskLevelScheduleRequired,
                                                       String customSqlStatementPolicy,
                                                       List<String> issueCodes) {
        if (template == null) {
            return blockedTopology(withIssue(issueCodes, "TEMPLATE_CONTEXT_MISSING_FOR_DATAX_TOPOLOGY"));
        }
        String transferChannel = workerPlan == null ? null : workerPlan.transferChannel();
        String referenceRuntime = workerPlan == null ? null : workerPlan.referenceRuntime();
        String scopeType = scopeContract == null ? normalize(template.getSyncScopeType()) : scopeContract.scopeType();
        boolean fieldMappingDeclared = hasText(template.getFieldMappingConfig());
        boolean fieldRenameRequired = fieldMappingContract != null
                && fieldMappingContract.isRequiresFieldRenameTransform();
        return buildContract(
                normalize(template.getSourceConnectorType()),
                normalize(template.getTargetConnectorType()),
                normalize(template.getSyncMode()),
                transferChannel,
                referenceRuntime,
                scopeType,
                SyncOfflineJobPlanClassificationSupport.readerFamily(template.getSourceConnectorType()),
                SyncOfflineJobPlanClassificationSupport.writerFamily(template.getTargetConnectorType()),
                SyncOfflineJobPlanClassificationSupport.modeFamily(resolveMode(template.getSyncMode())),
                normalize(template.getWriteStrategy()),
                customSqlStatementPolicy,
                hasText(template.getFilterConfig()),
                fieldMappingDeclared,
                fieldRenameRequired,
                minimalEndToEndSupported,
                dedicatedRunnerRequired,
                approvalRequired,
                checkpointRequired,
                taskLevelScheduleRequired,
                shardPlan,
                reportContract,
                issueCodes
        );
    }

    private SyncDataXJobExecutionContract buildContract(String sourceConnectorType,
                                                       String targetConnectorType,
                                                       String syncMode,
                                                       String transferChannel,
                                                       String referenceRuntime,
                                                       String scopeType,
                                                       String readerFamily,
                                                       String writerFamily,
                                                       String modeFamily,
                                                       String writeStrategy,
                                                       String customSqlStatementPolicy,
                                                       boolean filterDeclared,
                                                       boolean fieldMappingDeclared,
                                                       boolean fieldRenameRequired,
                                                       boolean minimalBridgeCompatible,
                                                       boolean dedicatedRunnerRequired,
                                                       boolean approvalRequired,
                                                       boolean checkpointRequired,
                                                       boolean taskLevelScheduleRequired,
                                                       SyncOfflineRunnerShardPlan shardPlan,
                                                       SyncOfflineRunnerExecutionReport reportContract,
                                                       List<String> issueCodes) {
        SyncOfflineRunnerShardPlan safeShardPlan = shardPlan == null
                ? SyncOfflineRunnerContractPolicySupport.emptyShardPlan()
                : shardPlan;
        String shardKind = safeShardPlan.shardKind();
        int estimatedTaskGroupCount = SyncDataXExecutionContractPolicySupport
                .estimatedTaskGroupCount(safeShardPlan, minimalBridgeCompatible);
        int estimatedChannelCount = SyncDataXExecutionContractPolicySupport
                .estimatedChannelCount(safeShardPlan, minimalBridgeCompatible);
        SyncDataXRuntimeSafetyPolicy safetyPolicy = safetyPolicy(customSqlStatementPolicy, filterDeclared,
                checkpointRequired, minimalBridgeCompatible);
        SyncDataXReaderContract readerContract = readerContract(readerFamily, sourceConnectorType, shardKind,
                safeShardPlan, customSqlStatementPolicy, filterDeclared, checkpointRequired, minimalBridgeCompatible);
        SyncDataXWriterContract writerContract = writerContract(writerFamily, targetConnectorType, scopeType,
                writeStrategy, minimalBridgeCompatible, dedicatedRunnerRequired);
        SyncDataXChannelContract channelContract = channelContract(shardKind, readerContract, writerContract,
                fieldMappingDeclared, fieldRenameRequired, minimalBridgeCompatible, dedicatedRunnerRequired,
                reportContract);
        SyncDataXTaskGroupContract taskGroupContract = taskGroupContract(scopeType, safeShardPlan,
                estimatedChannelCount, taskLevelScheduleRequired, checkpointRequired, minimalBridgeCompatible,
                List.of(channelContract));

        return new SyncDataXJobExecutionContract(
                CONTRACT_VERSION,
                SyncDataXExecutionContractPolicySupport.topologyStatus("OFFLINE".equals(normalize(transferChannel)),
                        approvalRequired,
                        minimalBridgeCompatible, dedicatedRunnerRequired, checkpointRequired),
                SyncDataXExecutionContractPolicySupport.jobKind(shardKind),
                SyncDataXExecutionContractPolicySupport.jobExecutionMode(
                        "OFFLINE".equals(normalize(transferChannel)), minimalBridgeCompatible,
                        dedicatedRunnerRequired),
                firstText(referenceRuntime, "DATAX_STYLE_OFFLINE_READER_WRITER_RUNNER"),
                sourceConnectorType,
                targetConnectorType,
                firstText(readerFamily, "UNKNOWN_READER"),
                firstText(writerFamily, "UNKNOWN_WRITER"),
                firstText(modeFamily, "UNKNOWN_MODE"),
                shardKind,
                estimatedTaskGroupCount,
                estimatedChannelCount,
                minimalBridgeCompatible,
                dedicatedRunnerRequired,
                safetyPolicy,
                List.of(taskGroupContract),
                SyncDataXExecutionContractPolicySupport.requiredCapabilities(safeShardPlan, reportContract,
                        dedicatedRunnerRequired, checkpointRequired),
                distinct(issueCodes),
                PAYLOAD_POLICY
        );
    }

    private SyncDataXJobExecutionContract blockedTopology(List<String> issueCodes) {
        SyncDataXRuntimeSafetyPolicy safetyPolicy = safetyPolicy("UNKNOWN", false, false, false);
        return new SyncDataXJobExecutionContract(
                CONTRACT_VERSION,
                "BLOCKED_BEFORE_DATAX_TOPOLOGY",
                "UNKNOWN_JOB",
                "DO_NOT_DISPATCH",
                "UNKNOWN_RUNTIME",
                null,
                null,
                "UNKNOWN_READER",
                "UNKNOWN_WRITER",
                "UNKNOWN_MODE",
                "UNKNOWN",
                0,
                0,
                false,
                false,
                safetyPolicy,
                List.of(),
                List.of("CONTRACT_CONTEXT_REQUIRED"),
                distinct(issueCodes),
                PAYLOAD_POLICY
        );
    }

    private SyncDataXRuntimeSafetyPolicy safetyPolicy(String customSqlStatementPolicy,
                                                      boolean filterDeclared,
                                                      boolean checkpointRequired,
                                                      boolean minimalBridgeCompatible) {
        return new SyncDataXRuntimeSafetyPolicy(
                "DATASOURCE_ID_REFERENCE_ONLY_CREDENTIALS_RESOLVED_INSIDE_EXECUTION_PLANE",
                firstText(customSqlStatementPolicy, "NOT_APPLICABLE"),
                "OBJECT_MAPPING_BODY_STAYS_IN_CONTROLLED_TEMPLATE_STORE_OR_RUNNER_DISCOVERY",
                "FIELD_MAPPING_BODY_STAYS_INTERNAL_ONLY_PUBLIC_CONTRACT_EXPOSES_COUNT_AND_POLICY",
                filterDeclared
                        ? "STRUCTURED_FILTER_ONLY_PREPARED_STATEMENT_BINDING_REQUIRED"
                        : "NO_FILTER_DECLARED",
                checkpointRequired
                        ? "CHECKPOINT_REF_OR_DIGEST_ONLY_RAW_WATERMARK_STAYS_IN_CONTROLLED_STORE"
                        : "FINAL_WATERMARK_OPTIONAL_NO_RAW_VALUE",
                "DIRTY_RECORD_DIGEST_OR_REFERENCE_ONLY_NO_ROW_PAYLOAD",
                minimalBridgeCompatible
                        ? "MINIMAL_BRIDGE_BATCH_SIZE_AND_MAX_BATCH_COUNT_GUARD"
                        : "DEDICATED_RUNNER_RESOURCE_GROUP_SPEED_LIMIT_REQUIRED",
                "RETRY_REQUIRES_IDEMPOTENCY_KEY_BATCH_BOUNDARY_AND_WRITE_STRATEGY_AWARENESS",
                "DISPATCH_CALLBACK_FAILURE_AND_OPERATOR_ACTION_MUST_WRITE_LOW_SENSITIVE_AUDIT",
                List.of(
                        "connectionUrl",
                        "username",
                        "password",
                        "rawSql",
                        "statementRefValue",
                        "objectMappingBody",
                        "fieldMappingBody",
                        "filterBody",
                        "partitionBody",
                        "rowPayload",
                        "rawCheckpointValue"
                ),
                SAFETY_PAYLOAD_POLICY
        );
    }

    private SyncDataXReaderContract readerContract(String readerFamily,
                                                   String sourceConnectorType,
                                                   String shardKind,
                                                   SyncOfflineRunnerShardPlan shardPlan,
                                                   String customSqlStatementPolicy,
                                                   boolean filterDeclared,
                                                   boolean checkpointRequired,
                                                   boolean minimalBridgeCompatible) {
        return new SyncDataXReaderContract(
                firstText(readerFamily, "UNKNOWN_READER"),
                sourceConnectorType,
                "SOURCE_DATASOURCE_ID_REFERENCE_ONLY",
                SyncDataXExecutionContractPolicySupport.objectReadPolicy(shardKind),
                SyncDataXExecutionContractPolicySupport.splitPolicy(shardKind, shardPlan.partitionDeclared(),
                        minimalBridgeCompatible),
                minimalBridgeCompatible
                        ? "LIMIT_OFFSET_WITH_STABLE_ORDER_AND_MAX_BATCH_GUARD"
                        : "RUNNER_FETCH_SIZE_CHANNEL_AND_BACKPRESSURE_POLICY_REQUIRED",
                filterDeclared
                        ? "STRUCTURED_FILTER_CONDITIONS_PARAMETER_BOUND"
                        : "NO_FILTER_DECLARED",
                firstText(customSqlStatementPolicy, "NOT_APPLICABLE"),
                checkpointRequired
                        ? "READ_BOUNDARY_FROM_CHECKPOINT_REF_OR_DIGEST"
                        : "BOUNDED_READ_WITH_OPTIONAL_FINAL_WATERMARK",
                READER_PAYLOAD_POLICY
        );
    }

    private SyncDataXWriterContract writerContract(String writerFamily,
                                                   String targetConnectorType,
                                                   String scopeType,
                                                   String writeStrategy,
                                                   boolean minimalBridgeCompatible,
                                                   boolean dedicatedRunnerRequired) {
        String normalizedWriteStrategy = firstText(writeStrategy, "APPEND");
        return new SyncDataXWriterContract(
                firstText(writerFamily, "UNKNOWN_WRITER"),
                targetConnectorType,
                "TARGET_DATASOURCE_ID_REFERENCE_ONLY",
                SyncDataXExecutionContractPolicySupport.objectWritePolicy(scopeType),
                normalizedWriteStrategy,
                minimalBridgeCompatible
                        ? "JDBC_BATCH_WRITE_WITH_TARGET_COLUMNS_AND_SAFE_BINDING"
                        : "RUNNER_BATCH_SIZE_COMMIT_AND_RETRY_POLICY_REQUIRED",
                SyncDataXExecutionContractPolicySupport.idempotencyPolicy(normalizedWriteStrategy,
                        dedicatedRunnerRequired),
                SyncDataXExecutionContractPolicySupport.conflictPolicy(normalizedWriteStrategy),
                minimalBridgeCompatible
                        ? "COMMIT_AFTER_EACH_RUN_ONCE_BATCH_AND_FINAL_CALLBACK"
                        : "RUNNER_CONTROLLED_COMMIT_WITH_STRUCTURED_FINAL_REPORT",
                WRITER_PAYLOAD_POLICY
        );
    }

    private SyncDataXChannelContract channelContract(String shardKind,
                                                     SyncDataXReaderContract readerContract,
                                                     SyncDataXWriterContract writerContract,
                                                     boolean fieldMappingDeclared,
                                                     boolean fieldRenameRequired,
                                                     boolean minimalBridgeCompatible,
                                                     boolean dedicatedRunnerRequired,
                                                     SyncOfflineRunnerExecutionReport reportContract) {
        return new SyncDataXChannelContract(
                "CHANNEL-0",
                SyncDataXExecutionContractPolicySupport.channelKind(shardKind),
                minimalBridgeCompatible
                        ? "MINIMAL_JAVA_RUN_ONCE_BRIDGE_SINGLE_CHANNEL"
                        : "DEDICATED_RUNNER_CHANNEL_POLICY_REQUIRED",
                readerContract,
                writerContract,
                SyncDataXExecutionContractPolicySupport.transformerPolicy(fieldMappingDeclared, fieldRenameRequired,
                        dedicatedRunnerRequired),
                minimalBridgeCompatible
                        ? "BATCH_SIZE_AND_MAX_BATCH_COUNT_ONLY"
                        : "RESOURCE_GROUP_RECORDS_PER_SECOND_BYTES_PER_SECOND_LIMIT_REQUIRED",
                "DIRTY_RECORD_DIGEST_OR_REFERENCE_ONLY_WITH_THRESHOLD_POLICY",
                minimalBridgeCompatible
                        ? "RUN_ONCE_BATCH_RESULT_DRIVES_COMPLETE_OR_CONTINUE"
                        : "RUNNER_REPORT_CALLBACK_DRIVES_PROGRESS_CHECKPOINT_AND_FINAL_STATE",
                reportContract == null
                        ? "LOW_CARDINALITY_METRICS_AND_STRUCTURED_REPORT_REQUIRED"
                        : reportContract.metricsPolicy(),
                CHANNEL_PAYLOAD_POLICY
        );
    }

    private SyncDataXTaskGroupContract taskGroupContract(String scopeType,
                                                         SyncOfflineRunnerShardPlan shardPlan,
                                                         int estimatedChannelCount,
                                                         boolean taskLevelScheduleRequired,
                                                         boolean checkpointRequired,
                                                         boolean minimalBridgeCompatible,
                                                         List<SyncDataXChannelContract> channels) {
        return new SyncDataXTaskGroupContract(
                "TASK_GROUP-0",
                SyncDataXExecutionContractPolicySupport.taskGroupKind(shardPlan.shardKind()),
                firstText(scopeType, "UNKNOWN_SCOPE"),
                SyncDataXExecutionContractPolicySupport.estimatedTaskCount(shardPlan),
                taskLevelScheduleRequired
                        ? "TASK_LEVEL_SCHEDULE_WINDOW_REQUIRED"
                        : minimalBridgeCompatible
                        ? "IMMEDIATE_SINGLE_GROUP_DISPATCH"
                        : "DEDICATED_RUNNER_CAPACITY_AND_RESOURCE_GROUP_SCHEDULING",
                checkpointRequired
                        ? "RETRY_FROM_CHECKPOINT_REF_OR_DIGEST_PER_SHARD"
                        : "RETRY_BY_BATCH_ID_AND_IDEMPOTENCY_KEY",
                checkpointRequired
                        ? "TASK_GROUP_CHECKPOINT_HANDOFF_REQUIRED"
                        : "FINAL_WATERMARK_OPTIONAL",
                estimatedChannelCount < 0
                        ? "RUNNER_DECIDES_CHANNELS_BY_QUOTA_AND_DISCOVERY"
                        : "ESTIMATED_CHANNEL_COUNT_" + estimatedChannelCount,
                channels,
                TASK_GROUP_PAYLOAD_POLICY
        );
    }

    private SyncMode resolveMode(String syncMode) {
        String normalized = normalize(syncMode);
        if (normalized == null) {
            return null;
        }
        try {
            return SyncMode.valueOf(normalized);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private List<String> withIssue(List<String> issueCodes, String issueCode) {
        List<String> values = new ArrayList<>(issueCodes == null ? List.of() : issueCodes);
        values.add(issueCode);
        return values;
    }

    private List<String> distinct(List<String> values) {
        return new ArrayList<>(new LinkedHashSet<>(values == null ? List.of() : values));
    }

    private String firstText(String value, String fallback) {
        return hasText(value) ? value : fallback;
    }

    private String normalize(String value) {
        return hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : null;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
