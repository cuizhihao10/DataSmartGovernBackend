/**
 * @Author : Cui
 * @Date: 2026/07/05 14:26
 * @Description DataSmart Govern Backend - SyncOfflineRunnerContractSupport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.datasync.controller.dto.SyncOfflineJobPlanResponse;
import com.czh.datasmart.govern.datasync.controller.dto.SyncWorkerExecutionPlanView;
import com.czh.datasmart.govern.datasync.entity.SyncExecution;
import com.czh.datasmart.govern.datasync.entity.SyncTask;
import com.czh.datasmart.govern.datasync.entity.SyncTemplate;
import com.czh.datasmart.govern.datasync.support.SyncMode;
import com.czh.datasmart.govern.datasync.support.SyncTransferChannel;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * 离线 Runner 合同生成器。
 *
 * <p>本组件是 data-sync 从“计划能解释”走向“执行器能接入”的中间层。它不访问数据库、不调用远端服务、不读取
 * SQL、对象映射或字段映射原文，只把已经存在于 offline-job-plan、worker plan、template、task、execution 中的低敏事实
 * 组装成统一合同。</p>
 *
 * <p>为什么不直接把这段逻辑塞进 {@link SyncOfflineJobPlanSupport} 或 {@link SyncBatchRunnerBridgePlanSupport}：</p>
 * <p>1. offline-job-plan 面向 UI/Agent/审批解释，职责是“告诉用户这份模板是什么”；</p>
 * <p>2. bridge plan 面向当前最小 run-once 派发，职责是“当前这个执行能不能交给 datasource-management 单批 runner”；</p>
 * <p>3. offline-runner-contract 面向未来专用 DataX-style Runner，职责是“执行器入口需要哪些低敏调度、分片和报告合同”。</p>
 *
 * <p>保持这三个层次独立，可以避免后续接入真正 DataX、Flink batch、Spark、Airbyte-like worker 或自研 Runner 时推翻
 * 现有控制面；只要新 Runner 能消费该合同，就可以逐步替换执行层。</p>
 */
@Component
public class SyncOfflineRunnerContractSupport {

    public static final String CONTRACT_VERSION = "datasmart.data-sync.offline-runner-contract.v1";
    public static final String PAYLOAD_POLICY = "LOW_SENSITIVE_OFFLINE_RUNNER_CONTRACT_NO_SQL_NO_CREDENTIALS_NO_MAPPING_BODY";

    /**
     * DataX-style 执行拓扑生成器。
     *
     * <p>该生成器没有外部依赖、没有状态，也不访问数据库或远端服务；它只把 Runner 合同中的低敏事实进一步翻译成
     * Job/TaskGroup/Channel/Reader/Writer 拓扑。这里保持为普通对象，可以兼容既有无参构造测试，并且避免为纯函数辅助类
     * 引入额外 Spring Bean 生命周期。</p>
     */
    private final SyncDataXExecutionContractSupport dataXExecutionContractSupport =
            new SyncDataXExecutionContractSupport();

    /**
     * 从面向 UI/Agent 的离线作业计划生成 Runner 合同。
     *
     * <p>该方法适合“模板阶段”使用：此时还没有具体 task/execution，所以合同中的 syncTaskId、executionId 可以为空。
     * 这样前端或 Agent 在创建任务前就能知道后续需要专用 Runner、审批、调度配置或 checkpoint handoff。</p>
     *
     * @param plan 已由 {@link SyncOfflineJobPlanSupport} 生成的低敏离线作业计划。
     * @return Runner 作业合同。
     */
    public SyncOfflineRunnerJobContract buildFromOfflinePlan(SyncOfflineJobPlanResponse plan) {
        if (plan == null) {
            return blockedContract(null, null, null, null,
                    List.of("OFFLINE_JOB_PLAN_MISSING"), List.of("DO_NOT_DISPATCH_RUNNER_WITHOUT_PLAN"));
        }
        boolean minimalEndToEndSupported = plan.executableByMinimalBridge()
                && SyncOfflineRunnerContractPolicySupport.minimalBridgeMode(plan.syncMode())
                && !plan.checkpointRequired();
        SyncOfflineRunnerShardPlan shardPlan = SyncOfflineRunnerContractPolicySupport.shardPlan(
                plan.syncMode(),
                plan.syncScopeType(),
                plan.shardStrategy(),
                plan.selectedObjectCount(),
                plan.objectMappingDeclared(),
                plan.partitionDeclared(),
                plan.checkpointRequired(),
                plan.taskLevelScheduleRequired(),
                minimalEndToEndSupported
        );
        SyncOfflineRunnerExecutionReport reportContract =
                SyncOfflineRunnerContractPolicySupport.reportContract(plan.checkpointRequired());
        SyncDataXJobExecutionContract dataXJobExecutionContract =
                dataXExecutionContractSupport.buildFromOfflinePlan(plan, shardPlan, reportContract);
        String contractStatus = SyncOfflineRunnerContractPolicySupport.contractStatus(plan.offlineChannel(),
                plan.planReady(), plan.approvalRequired(),
                plan.dedicatedOfflineRunnerRequired(), plan.executableByMinimalBridge(), minimalEndToEndSupported,
                plan.checkpointRequired(), plan.issueCodes());

        return new SyncOfflineRunnerJobContract(
                CONTRACT_VERSION,
                contractStatus,
                plan.templateId(),
                plan.tenantId(),
                plan.projectId(),
                plan.workspaceId(),
                null,
                null,
                plan.sourceDatasourceId(),
                plan.targetDatasourceId(),
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
                plan.runnerBoundary(),
                plan.offlineChannel(),
                plan.planReady(),
                plan.approvalRequired(),
                plan.checkpointRequired(),
                plan.taskLevelScheduleRequired(),
                plan.dedicatedOfflineRunnerRequired(),
                plan.executableByMinimalBridge(),
                minimalEndToEndSupported,
                plan.sqlStatementPolicy(),
                plan.fieldMappingDeclared(),
                plan.fieldMappingRunnableByMinimalBridge(),
                shardPlan,
                reportContract,
                dataXJobExecutionContract,
                copy(plan.issueCodes()),
                copy(plan.failClosedReasons()),
                copy(plan.recommendedActions()),
                copy(plan.safetyNotes()),
                PAYLOAD_POLICY
        );
    }

    /**
     * 从 worker/bridge 阶段的事实生成 Runner 合同。
     *
     * <p>该方法适合“执行阶段”使用：此时 task 和 execution 已存在，合同可以携带 syncTaskId、executionId、触发方式、
     * worker plan 的低敏问题码以及 bridge 的阻断原因。它仍然不会携带 objectLocator、字段列表、SQL 或任何连接凭据。</p>
     *
     * @param execution 当前执行记录。
     * @param task 当前任务。
     * @param template 当前模板。
     * @param workerPlan claim 后生成的低敏 worker 计划。
     * @param fieldMappingContract 字段映射执行合同；这里只读取“是否可被最小 bridge 直接执行”。
     * @param scopeContract 同步范围合同；这里只读取范围类型、对象数量和审批需求。
     * @param issueCodes bridge 阶段汇总的问题码。
     * @param warnings bridge 阶段汇总的低敏提示。
     * @param bridgeDispatchable 当前 bridge 预派发是否放行。
     * @return Runner 作业合同。
     */
    public SyncOfflineRunnerJobContract buildFromBridgeFacts(SyncExecution execution,
                                                             SyncTask task,
                                                             SyncTemplate template,
                                                             SyncWorkerExecutionPlanView workerPlan,
                                                             SyncFieldMappingExecutionContract fieldMappingContract,
                                                             SyncTemplateScopeContract scopeContract,
                                                             List<String> issueCodes,
                                                             List<String> warnings,
                                                             boolean bridgeDispatchable) {
        if (template == null) {
            return blockedContract(execution, task, workerPlan, null,
                    withIssue(issueCodes, "TEMPLATE_CONTEXT_MISSING_FOR_RUNNER_CONTRACT"),
                    warnings);
        }

        SyncMode syncMode = resolveMode(template.getSyncMode());
        SyncTransferChannel transferChannel = SyncTransferChannelSupport.resolve(syncMode);
        boolean offlineChannel = transferChannel == SyncTransferChannel.OFFLINE;
        boolean checkpointRequired = workerPlan != null && workerPlan.checkpointRequired();
        boolean taskLevelScheduleRequired = syncMode == SyncMode.SCHEDULED_BATCH
                || task != null && hasText(task.getScheduleConfig());
        boolean fieldMappingRunnable = fieldMappingContract != null
                && fieldMappingContract.directlyRunnableByMinimalBridge();
        boolean minimalEndToEndSupported = bridgeDispatchable
                && SyncOfflineRunnerContractPolicySupport.minimalBridgeMode(template.getSyncMode())
                && !checkpointRequired
                && fieldMappingRunnable;
        boolean dedicatedRunnerRequired = offlineChannel && !minimalEndToEndSupported;
        boolean planReady = offlineChannel && !SyncOfflineRunnerContractPolicySupport.hasHardBlockingIssue(issueCodes);
        boolean approvalRequired = scopeContract != null && scopeContract.requiresApproval();
        String scopeType = scopeContract == null ? normalize(template.getSyncScopeType()) : scopeContract.scopeType();
        int selectedObjectCount = scopeContract == null ? 0 : scopeContract.selectedObjectCount();
        boolean objectMappingDeclared = scopeContract != null && scopeContract.objectMappingDeclared();

        String shardStrategy = SyncOfflineJobPlanClassificationSupport.shardStrategy(syncMode,
                scopeContract == null ? fallbackSingleObjectScopeContract(scopeType) : scopeContract,
                template);
        SyncOfflineRunnerShardPlan shardPlan = SyncOfflineRunnerContractPolicySupport.shardPlan(
                template.getSyncMode(),
                scopeType,
                shardStrategy,
                selectedObjectCount,
                objectMappingDeclared,
                hasText(template.getPartitionConfig()),
                checkpointRequired,
                taskLevelScheduleRequired,
                minimalEndToEndSupported
        );
        SyncOfflineRunnerExecutionReport reportContract =
                SyncOfflineRunnerContractPolicySupport.reportContract(checkpointRequired);
        String customSqlStatementPolicy = customSqlPolicy(syncMode, hasText(template.getCustomSqlConfig()));
        SyncDataXJobExecutionContract dataXJobExecutionContract =
                dataXExecutionContractSupport.buildFromBridgeFacts(
                        template,
                        workerPlan,
                        fieldMappingContract,
                        scopeContract,
                        shardPlan,
                        reportContract,
                        minimalEndToEndSupported,
                        dedicatedRunnerRequired,
                        approvalRequired,
                        checkpointRequired,
                        taskLevelScheduleRequired,
                        customSqlStatementPolicy,
                        distinct(issueCodes));
        String contractStatus = SyncOfflineRunnerContractPolicySupport.contractStatus(offlineChannel, planReady,
                approvalRequired,
                dedicatedRunnerRequired, bridgeDispatchable, minimalEndToEndSupported, checkpointRequired, issueCodes);

        return new SyncOfflineRunnerJobContract(
                CONTRACT_VERSION,
                contractStatus,
                template.getId(),
                firstNonNull(template.getTenantId(), execution == null ? null : execution.getTenantId()),
                firstNonNull(template.getProjectId(), execution == null ? null : execution.getProjectId()),
                firstNonNull(template.getWorkspaceId(), execution == null ? null : execution.getWorkspaceId()),
                task == null ? null : task.getId(),
                execution == null ? null : execution.getId(),
                template.getSourceDatasourceId(),
                template.getTargetDatasourceId(),
                normalize(template.getSourceConnectorType()),
                normalize(template.getTargetConnectorType()),
                normalize(template.getSyncMode()),
                transferChannel == null ? null : transferChannel.name(),
                SyncTransferChannelSupport.referenceRuntime(transferChannel),
                scopeType,
                SyncOfflineJobPlanClassificationSupport.readerFamily(template.getSourceConnectorType()),
                SyncOfflineJobPlanClassificationSupport.writerFamily(template.getTargetConnectorType()),
                SyncOfflineJobPlanClassificationSupport.modeFamily(syncMode),
                normalize(template.getWriteStrategy()),
                SyncOfflineRunnerContractPolicySupport.runnerBoundary(offlineChannel, minimalEndToEndSupported,
                        dedicatedRunnerRequired, checkpointRequired),
                offlineChannel,
                planReady,
                approvalRequired,
                checkpointRequired,
                taskLevelScheduleRequired,
                dedicatedRunnerRequired,
                bridgeDispatchable,
                minimalEndToEndSupported,
                customSqlStatementPolicy,
                hasText(template.getFieldMappingConfig()),
                fieldMappingRunnable,
                shardPlan,
                reportContract,
                dataXJobExecutionContract,
                distinct(issueCodes),
                SyncOfflineRunnerContractPolicySupport.failClosedReasons(offlineChannel, dedicatedRunnerRequired,
                        minimalEndToEndSupported, checkpointRequired, issueCodes),
                SyncOfflineRunnerContractPolicySupport.recommendedActions(syncMode, dedicatedRunnerRequired,
                        checkpointRequired, taskLevelScheduleRequired),
                distinct(warnings),
                PAYLOAD_POLICY
        );
    }

    private SyncOfflineRunnerJobContract blockedContract(SyncExecution execution,
                                                         SyncTask task,
                                                         SyncWorkerExecutionPlanView workerPlan,
                                                         SyncOfflineRunnerShardPlan shardPlan,
                                                         List<String> issueCodes,
                                                         List<String> warnings) {
        SyncOfflineRunnerExecutionReport reportContract = SyncOfflineRunnerContractPolicySupport.reportContract(false);
        SyncDataXJobExecutionContract dataXJobExecutionContract =
                dataXExecutionContractSupport.buildFromBridgeFacts(
                        null,
                        workerPlan,
                        null,
                        null,
                        shardPlan,
                        reportContract,
                        false,
                        false,
                        false,
                        false,
                        false,
                        "UNKNOWN",
                        issueCodes);
        return new SyncOfflineRunnerJobContract(
                CONTRACT_VERSION,
                "BLOCKED_BEFORE_RUNNER_CONTRACT",
                workerPlan == null ? null : workerPlan.templateId(),
                firstNonNull(workerPlan == null ? null : workerPlan.tenantId(), execution == null ? null : execution.getTenantId()),
                firstNonNull(workerPlan == null ? null : workerPlan.projectId(), execution == null ? null : execution.getProjectId()),
                firstNonNull(workerPlan == null ? null : workerPlan.workspaceId(), execution == null ? null : execution.getWorkspaceId()),
                task == null ? null : task.getId(),
                execution == null ? null : execution.getId(),
                workerPlan == null ? null : workerPlan.sourceDatasourceId(),
                workerPlan == null ? null : workerPlan.targetDatasourceId(),
                workerPlan == null ? null : workerPlan.sourceConnectorType(),
                workerPlan == null ? null : workerPlan.targetConnectorType(),
                workerPlan == null ? null : workerPlan.syncMode(),
                workerPlan == null ? null : workerPlan.transferChannel(),
                workerPlan == null ? null : workerPlan.referenceRuntime(),
                workerPlan == null ? null : workerPlan.syncScopeType(),
                "UNKNOWN_READER",
                "UNKNOWN_WRITER",
                "UNKNOWN_MODE",
                null,
                "OFFLINE_RUNNER_CONTRACT_BLOCKED",
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                "UNKNOWN",
                false,
                false,
                shardPlan == null ? SyncOfflineRunnerContractPolicySupport.emptyShardPlan() : shardPlan,
                reportContract,
                dataXJobExecutionContract,
                distinct(issueCodes),
                distinct(issueCodes),
                List.of("补齐模板、任务、执行记录和 worker plan 上下文后再生成离线 Runner 合同"),
                distinct(warnings),
                PAYLOAD_POLICY
        );
    }

    private SyncTemplateScopeContract fallbackSingleObjectScopeContract(String scopeType) {
        boolean multi = "OBJECT_LIST".equals(scopeType)
                || "SCHEMA_FULL".equals(scopeType)
                || "DATABASE_FULL".equals(scopeType);
        boolean customSql = "CUSTOM_SQL_QUERY".equals(scopeType);
        return new SyncTemplateScopeContract(
                firstText(scopeType, "SINGLE_OBJECT"),
                !multi && !customSql,
                multi,
                customSql,
                0,
                false,
                false,
                multi || customSql,
                !multi && !customSql,
                List.of(),
                List.of(),
                List.of()
        );
    }

    private String customSqlPolicy(SyncMode syncMode, boolean customSqlDeclared) {
        if (syncMode != SyncMode.CUSTOM_SQL_QUERY) {
            return "NOT_APPLICABLE";
        }
        return customSqlDeclared
                ? "CUSTOM_SQL_DECLARED_LOW_SENSITIVE_STATEMENT_POLICY_REQUIRED"
                : "CUSTOM_SQL_CONFIG_REQUIRED";
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

    private List<String> copy(List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private List<String> distinct(List<String> values) {
        return new ArrayList<>(new LinkedHashSet<>(values == null ? List.of() : values));
    }

    private Long firstNonNull(Long first, Long second) {
        return first == null ? second : first;
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
