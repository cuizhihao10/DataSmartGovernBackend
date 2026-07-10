/**
 * @Author : Cui
 * @Date: 2026/07/06 21:49
 * @Description DataSmart Govern Backend - SyncObjectListFanOutDispatchService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExecutionCompleteRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExecutionFailRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExecutionPartialSuccessRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncWorkerExecutionPlanView;
import com.czh.datasmart.govern.datasync.entity.SyncExecution;
import com.czh.datasmart.govern.datasync.entity.SyncObjectExecution;
import com.czh.datasmart.govern.datasync.entity.SyncTask;
import com.czh.datasmart.govern.datasync.entity.SyncTemplate;
import com.czh.datasmart.govern.datasync.integration.datasource.runonce.DatasourceRunOnceResponse;
import com.czh.datasmart.govern.datasync.support.SyncObjectExecutionState;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * OBJECT_LIST 多对象同步 fan-out 调度器。
 *
 * <p>本组件负责把一个父级 OBJECT_LIST execution 拆成多个可恢复的对象级执行单元。它借鉴 DataX 的 Job/Task/Channel 思想，
 * 但不把当前项目强行改造成完整 DataX 引擎：</p>
 * <p>1. data-sync 仍然是控制面：解析对象映射、创建对象级执行账本、选择执行顺序、汇总父状态；</p>
 * <p>2. datasource-management 仍然是 Java 数据搬运执行面：每个对象最终仍调用已有 run-once Reader/Writer；</p>
 * <p>3. python-ai-runtime 仍然是 Agent 调度、规划和诊断桥梁，不承担核心数据搬运动作。</p>
 *
 * <p>和上一版“第一个对象失败就 fail 父 execution”的区别：</p>
 * <p>1. 每个对象都会写入 {@code data_sync_object_execution}；</p>
 * <p>2. 成功对象在同一个父 execution 恢复时会被跳过，不重复写入；</p>
 * <p>3. 可重试失败对象会在对象级 attemptCount 未耗尽时继续重试；</p>
 * <p>4. 某个对象最终失败后不会阻断后续对象，最后统一汇总为 SUCCEEDED、PARTIALLY_SUCCEEDED 或 FAILED。</p>
 *
 * <p>安全边界：</p>
 * <p>普通 dispatch result、receipt、日志和指标只暴露低敏 issueCode 与聚合计数，不暴露对象名、字段名、SQL、
 * where 条件、连接串、凭据或样本行。对象名只保存在 data-sync 内部执行账本，用于恢复和运维排障。</p>
 */
@Component
@RequiredArgsConstructor
public class SyncObjectListFanOutDispatchService {

    private static final Set<String> SUPPORTED_SYNC_MODES = Set.of("FULL", "ONE_TIME_MIGRATION", "SCHEDULED_BATCH");
    private static final String OBJECT_LIST = "OBJECT_LIST";
    private static final String SINGLE_OBJECT = "SINGLE_OBJECT";
    private static final int DEFAULT_OBJECT_MAX_ATTEMPT_COUNT = 3;
    private static final int MAX_OBJECT_MAX_ATTEMPT_COUNT = 10;

    private final SyncObjectMappingExecutionContractSupport objectMappingExecutionContractSupport;
    private final SyncObjectExecutionLifecycleSupport objectExecutionLifecycleSupport;
    private final SyncBatchRunnerBridgePlanSupport bridgePlanSupport;
    private final SyncBatchRunOnceDispatchService runOnceDispatchService;
    private final SyncExecutionLifecycleSupport lifecycleSupport;
    private final DataSyncTaskManagementReceiptPublisher receiptPublisher;
    private final SyncExecutionLogSupport executionLogSupport;
    private final ObjectMapper objectMapper;

    /**
     * 判断当前合同是否可以进入对象级 fan-out。
     *
     * <p>这里仍然保持保守范围：只承接 FULL/ONE_TIME_MIGRATION + OBJECT_LIST + 无 checkpoint 的离线迁移。
     * SCHEDULED_BATCH、INCREMENTAL、CDC、REPLAY、BACKFILL、CUSTOM_SQL、SCHEMA_FULL 和 DATABASE_FULL 仍应走
     * checkpoint handoff、专用 Runner 或受控 SQL 治理路径，避免把尚未准备好的复杂语义偷偷塞进最小 run-once。</p>
     */
    public boolean supports(SyncOfflineRunnerJobContract contract, SyncActorContext actorContext) {
        if (contract == null) {
            return false;
        }
        return contract.offlineChannel()
                && OBJECT_LIST.equals(normalize(contract.syncScopeType()))
                && SUPPORTED_SYNC_MODES.contains(normalize(contract.syncMode()))
                && !contract.checkpointRequired()
                && !firstText(contract.customSqlStatementPolicy(), "NOT_APPLICABLE").startsWith("CUSTOM_SQL")
                && !Boolean.TRUE.equals(actorContext == null ? null : actorContext.approvalRequired());
    }

    /**
     * 执行 OBJECT_LIST 中的每个对象映射，并根据对象级结果汇总父 execution。
     *
     * @param execution 当前已被 worker claim 的父 execution，通常处于 RUNNING。
     * @param task 当前同步任务。
     * @param template 原始多对象模板。
     * @param workerPlan 父级 worker 计划。
     * @param actorContext 当前服务账号或操作者上下文。
     * @param parentContract 原始离线 Runner 合同，用于 dispatch result 继续保留合同状态。
     * @return 低敏调度结果。
     */
    public SyncOfflineRunnerDispatchResult dispatchObjectList(SyncExecution execution,
                                                              SyncTask task,
                                                              SyncTemplate template,
                                                              SyncWorkerExecutionPlanView workerPlan,
                                                              SyncActorContext actorContext,
                                                              SyncOfflineRunnerJobContract parentContract) {
        SyncObjectMappingExecutionContract objectContract = objectMappingExecutionContractSupport.parse(template);
        if (!objectContract.executableBySerialFanOut()) {
            return failFanOut(task, execution, actorContext, parentContract,
                    false,
                    0L,
                    0L,
                    1L,
                    "OBJECT_LIST_MAPPING_CONTRACT_BLOCKED",
                    "OBJECT_LIST_MAPPING_CONTRACT_BLOCKED",
                    "OBJECT_LIST 多对象映射配置不可执行，本次执行未触发真实读写",
                    objectContract.issueCodes());
        }

        int maxAttemptCount = resolveObjectMaxAttemptCount(template);
        List<SyncObjectExecution> objectExecutions = objectExecutionLifecycleSupport.initializeObjectExecutions(
                task, execution, template, objectContract, maxAttemptCount);
        recordFanOutStarted(task, execution, actorContext, objectExecutions.size(), maxAttemptCount);
        Map<Integer, SyncObjectExecution> objectExecutionByOrdinal = objectExecutions.stream()
                .collect(Collectors.toMap(SyncObjectExecution::getObjectOrdinal, Function.identity(), (left, right) -> left));
        boolean remoteCalled = false;
        List<String> accumulatedIssues = new ArrayList<>();

        for (SyncObjectMappingExecutionItem item : objectContract.mappings()) {
            SyncObjectExecution objectExecution = objectExecutionByOrdinal.get(item.ordinal());
            if (objectExecution == null) {
                return failFanOut(task, execution, actorContext, parentContract,
                        remoteCalled,
                        0L,
                        0L,
                        1L,
                        "OBJECT_LIST_OBJECT_EXECUTION_MISSING",
                        "OBJECT_LIST_OBJECT_EXECUTION_MISSING",
                        "OBJECT_LIST 对象级执行账本缺失，控制面无法安全判断哪些对象已成功，已按 fail-closed 终止父 execution",
                        mergeIssueCodes(accumulatedIssues, item.warnings(), "OBJECT_LIST_OBJECT_EXECUTION_MISSING"));
            }
            ObjectDispatchOutcome objectOutcome = dispatchOneObjectWithRetry(
                    task, execution, template, workerPlan, actorContext, item, objectExecution);
            remoteCalled = remoteCalled || objectOutcome.remoteCalled();
            accumulatedIssues.addAll(objectOutcome.issueCodes());
        }

        SyncObjectExecutionSummary summary = objectExecutionLifecycleSupport.summarize(objectExecutions);
        List<String> summaryIssues = mergeIssueCodes(summary.issueCodes(), accumulatedIssues,
                "OBJECT_LIST_OBJECT_LEVEL_SUMMARY_READY");
        if (summary.allSucceeded()) {
            recordFanOutFinished(task, execution, actorContext, summary, "SUCCEEDED",
                    "多对象同步已全部完成",
                    "所有对象均已成功写入，父级执行记录即将进入成功状态。");
            completeFanOut(task, execution, actorContext, summary);
            return new SyncOfflineRunnerDispatchResult(
                    remoteCalled,
                    true,
                    false,
                    "OBJECT_LIST_OBJECT_LEVEL_FAN_OUT_COMPLETED",
                    execution.getId(),
                    "OBJECT_LIST_ALL_OBJECTS_COMPLETED",
                    parentContract == null ? "OBJECT_LIST_OBJECT_LEVEL_FAN_OUT" : parentContract.contractStatus(),
                    mergeIssueCodes(summaryIssues, objectContract.warnings(), "OBJECT_LIST_OBJECT_LEVEL_FAN_OUT_COMPLETED"),
                    SyncOfflineRunnerDispatchResult.PAYLOAD_POLICY
            );
        }
        if (summary.partiallySucceeded()) {
            recordFanOutFinished(task, execution, actorContext, summary, "PARTIALLY_SUCCEEDED",
                    "多对象同步已部分完成",
                    "部分对象同步失败，成功对象会保留结果，失败对象可在运行历史中选择性重试。");
            partialFanOut(task, execution, actorContext, summary);
            return new SyncOfflineRunnerDispatchResult(
                    remoteCalled,
                    false,
                    false,
                    "OBJECT_LIST_OBJECT_LEVEL_FAN_OUT_PARTIALLY_SUCCEEDED",
                    execution.getId(),
                    "OBJECT_LIST_PARTIALLY_SUCCEEDED_RETRY_FAILED_OBJECTS",
                    parentContract == null ? "OBJECT_LIST_OBJECT_LEVEL_FAN_OUT" : parentContract.contractStatus(),
                    mergeIssueCodes(summaryIssues, objectContract.warnings(), "OBJECT_LIST_PARTIALLY_SUCCEEDED"),
                    SyncOfflineRunnerDispatchResult.PAYLOAD_POLICY
            );
        }
        recordFanOutFinished(task, execution, actorContext, summary, "FAILED",
                "多对象同步未成功完成",
                "所有对象均未成功或关键账本异常，请查看对象级执行账本和错误样本后重试。");
        return failFanOut(task, execution, actorContext, parentContract,
                remoteCalled,
                summary.recordsRead(),
                summary.recordsWritten(),
                Math.max(1L, summary.failedRecordCount()),
                "OBJECT_LIST_OBJECT_LEVEL_FAN_OUT_FAILED",
                "OBJECT_LIST_ALL_OBJECTS_FAILED",
                "OBJECT_LIST 所有对象均未成功，父 execution 进入 FAILED；请根据对象级执行账本定位失败对象并修复配置或连接器环境",
                mergeIssueCodes(summaryIssues, objectContract.warnings(), "OBJECT_LIST_ALL_OBJECTS_FAILED"));
    }

    /**
     * 执行单个对象，并在对象级尝试次数未耗尽时重试。
     *
     * <p>这里是本次改造最接近 DataX Task/Channel 重试语义的地方：某个对象失败不会立即让父 execution 失败，而是先写入
     * 对象级失败尝试；如果错误被远端标记为 retryable 且 attemptCount 未达到上限，则继续重试当前对象。如果该对象最终失败，
     * fan-out 也会继续处理后续对象，最后再由汇总决定父状态。</p>
     *
     * <p>幂等提醒：自动重试最适合 UPSERT、INSERT_IGNORE、REPLACE 或具备 checkpoint/去重能力的写入策略。对 APPEND 场景，
     * 若远端失败发生在部分提交之后，重试可能造成重复写入；当前版本依赖远端 run-once 的 retryable 信号控制是否重试，
     * 后续生产级优化应继续补对象级 checkpoint、目标端去重键和失败批次确认能力。</p>
     */
    private ObjectDispatchOutcome dispatchOneObjectWithRetry(SyncTask task,
                                                             SyncExecution execution,
                                                             SyncTemplate template,
                                                             SyncWorkerExecutionPlanView workerPlan,
                                                             SyncActorContext actorContext,
                                                             SyncObjectMappingExecutionItem item,
                                                             SyncObjectExecution objectExecution) {
        if (SyncObjectExecutionState.SUCCEEDED.name().equals(objectExecution.getObjectState())) {
            return new ObjectDispatchOutcome(false,
                    mergeIssueCodes(item.warnings(), List.of(), "OBJECT_LIST_CHILD_ALREADY_SUCCEEDED_SKIPPED"));
        }
        boolean remoteCalled = false;
        List<String> issueCodes = new ArrayList<>(item.warnings());
        while (safeInt(objectExecution.getAttemptCount()) < effectiveMaxAttemptCount(objectExecution)) {
            objectExecutionLifecycleSupport.markObjectRunning(objectExecution);
            recordObjectDispatchEvent(task, execution, objectExecution, actorContext,
                    "INFO", "OBJECT_LIST_CHILD_STARTED", "STARTED",
                    "对象工作单元开始同步",
                    "系统已为当前对象创建单对象执行计划，并准备调用受控 run-once 执行器。", null);
            SyncTemplate childTemplate = singleObjectTemplate(template, item);
            SyncExecution childExecution = childExecutionView(execution);
            SyncWorkerExecutionPlanView childWorkerPlan = singleObjectWorkerPlan(workerPlan, item);
            SyncBatchRunnerBridgePlan childBridgePlan =
                    bridgePlanSupport.buildPlan(childExecution, task, childTemplate, childWorkerPlan);
            if (!childBridgePlan.isDispatchable()) {
                objectExecutionLifecycleSupport.markObjectFailed(objectExecution, null, false,
                        "OBJECT_LIST_CHILD_BRIDGE_PLAN_BLOCKED",
                        "OBJECT_LIST_CHILD_BRIDGE_PLAN_BLOCKED",
                        "OBJECT_LIST 子对象桥接计划被阻断，请修复对象级字段映射、过滤条件或写入策略");
                recordObjectDispatchEvent(task, execution, objectExecution, actorContext,
                        "WARN", "OBJECT_LIST_CHILD_BRIDGE_PLAN_BLOCKED", "BLOCKED",
                        "对象工作单元暂时不能执行",
                        "当前对象的字段映射、过滤条件、写入策略或连接器能力未通过执行计划检查，请返回对象/字段映射修复。", null);
                issueCodes = mergeIssueCodes(issueCodes, childBridgePlan.getIssueCodes(),
                        "OBJECT_LIST_CHILD_BRIDGE_PLAN_BLOCKED");
                break;
            }

            SyncBatchRunOnceRemoteExecutionResult remoteResult =
                    runOnceDispatchService.executePreparedRunOnceRemoteOnly(childBridgePlan, childExecution, task, actorContext);
            remoteCalled = remoteCalled || remoteResult != null && remoteResult.remoteCalled();
            if (remoteResult != null && remoteResult.completed() && !remoteResult.failed()) {
                objectExecutionLifecycleSupport.markObjectSucceeded(objectExecution, remoteResult);
                recordObjectDispatchEvent(task, execution, objectExecution, actorContext,
                        "INFO", "OBJECT_LIST_CHILD_COMPLETED", "SUCCEEDED",
                        "对象工作单元同步完成",
                        "当前对象已完成读取与写入，累计读写数量已刷新到对象账本。",
                        SyncExecutionLogSupport.ExecutionMetricSnapshot.fromObject(objectExecution));
                issueCodes = mergeIssueCodes(issueCodes, remoteResult.issueCodes(), "OBJECT_LIST_CHILD_COMPLETED");
                break;
            }
            boolean retrying = shouldRetryObject(remoteResult, objectExecution);
            objectExecutionLifecycleSupport.markObjectFailed(objectExecution, remoteResult, retrying,
                    "OBJECT_LIST_CHILD_RUN_ONCE_FAILED",
                    firstText(remoteResult == null ? null : remoteResult.errorCode(), "OBJECT_LIST_CHILD_RUN_ONCE_FAILED"),
                    "OBJECT_LIST 子对象 run-once 执行失败；已按对象级尝试次数判断是否继续重试");
            recordObjectDispatchEvent(task, execution, objectExecution, actorContext,
                    retrying ? "WARN" : "ERROR",
                    retrying ? "OBJECT_LIST_CHILD_RETRYING" : "OBJECT_LIST_CHILD_FAILED",
                    retrying ? "RETRYING" : "FAILED",
                    retrying ? "对象工作单元同步失败，系统将继续重试" : "对象工作单元同步失败",
                    retrying
                            ? "当前错误被判定为可重试，且对象级尝试次数尚未耗尽，系统会继续处理该对象。"
                            : "当前对象已经达到重试上限或错误不可重试，可在运行历史中查看详情后选择性重试。",
                    SyncExecutionLogSupport.ExecutionMetricSnapshot.fromObject(objectExecution));
            issueCodes = mergeIssueCodes(issueCodes,
                    remoteResult == null ? List.of() : remoteResult.issueCodes(),
                    retrying ? "OBJECT_LIST_CHILD_RETRYING" : "OBJECT_LIST_CHILD_FAILED_ATTEMPTS_EXHAUSTED");
            if (!retrying) {
                break;
            }
        }
        if (!SyncObjectExecutionState.SUCCEEDED.name().equals(objectExecution.getObjectState())
                && !SyncObjectExecutionState.FAILED.name().equals(objectExecution.getObjectState())) {
            objectExecutionLifecycleSupport.markObjectFailed(objectExecution, null, false,
                    "OBJECT_LIST_CHILD_ATTEMPT_POLICY_EXHAUSTED",
                    "OBJECT_LIST_CHILD_ATTEMPT_POLICY_EXHAUSTED",
                    "OBJECT_LIST 子对象尝试次数已耗尽或状态无法继续推进，已转为失败对象等待后续选择性重试");
            recordObjectDispatchEvent(task, execution, objectExecution, actorContext,
                    "ERROR", "OBJECT_LIST_CHILD_ATTEMPT_POLICY_EXHAUSTED", "FAILED",
                    "对象工作单元已耗尽尝试次数",
                    "系统无法继续自动推进该对象，请查看对象级错误原因并在修复后触发选择性重试。",
                    SyncExecutionLogSupport.ExecutionMetricSnapshot.fromObject(objectExecution));
            issueCodes = mergeIssueCodes(issueCodes, List.of(), "OBJECT_LIST_CHILD_ATTEMPT_POLICY_EXHAUSTED");
        }
        return new ObjectDispatchOutcome(remoteCalled, issueCodes);
    }

    /**
     * 记录 OBJECT_LIST 父级 fan-out 开始事件。
     *
     * <p>这里记录的是“控制面已经把多表/多对象任务拆成可恢复工作单元”的事实。它不会改变执行状态，也不会泄露真实表名，
     * 只告诉前端和运维人员：本次执行一共准备处理多少个对象、对象级最多允许尝试几次。</p>
     */
    private void recordFanOutStarted(SyncTask task,
                                     SyncExecution execution,
                                     SyncActorContext actorContext,
                                     int objectCount,
                                     int maxAttemptCount) {
        executionLogSupport.recordExecutionEvent(task, execution, actorContext,
                "OBJECT_FAN_OUT",
                "INFO",
                "OBJECT_LIST_FAN_OUT_STARTED",
                "STARTED",
                "多对象同步计划已生成",
                "系统已生成 " + objectCount + " 个对象级工作单元；每个对象最多尝试 " + maxAttemptCount + " 次。",
                SyncExecutionLogSupport.ExecutionMetricSnapshot.of(0L, 0L, 0L,
                        0, 0, 0, BigDecimal.ZERO));
    }

    /**
     * 记录 OBJECT_LIST 父级 fan-out 汇总事件。
     *
     * <p>父级汇总日志用于解释最终状态为什么是成功、失败或部分成功。对象明细仍由 data_sync_object_execution 和对象级日志承载，
     * 父级日志只展示低敏聚合计数，方便用户在运行历史中快速判断“整体走到哪一步”。</p>
     */
    private void recordFanOutFinished(SyncTask task,
                                      SyncExecution execution,
                                      SyncActorContext actorContext,
                                      SyncObjectExecutionSummary summary,
                                      String status,
                                      String message,
                                      String detailPrefix) {
        int completedCount = summary.succeededCount() + summary.failedCount();
        int totalCount = Math.max(1, summary.totalCount());
        BigDecimal progress = BigDecimal.valueOf(completedCount)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(totalCount), 2, RoundingMode.HALF_UP);
        executionLogSupport.recordExecutionEvent(task, execution, actorContext,
                "OBJECT_FAN_OUT",
                "FAILED".equals(status) ? "ERROR" : "PARTIALLY_SUCCEEDED".equals(status) ? "WARN" : "INFO",
                "OBJECT_LIST_FAN_OUT_FINISHED",
                status,
                message,
                detailPrefix + " 对象总数=" + summary.totalCount()
                        + "，成功=" + summary.succeededCount()
                        + "，失败=" + summary.failedCount()
                        + "，累计读取=" + summary.recordsRead()
                        + "，累计写入=" + summary.recordsWritten()
                        + "，失败行=" + summary.failedRecordCount() + "。",
                SyncExecutionLogSupport.ExecutionMetricSnapshot.of(summary.recordsRead(), summary.recordsWritten(),
                        summary.failedRecordCount(), completedCount, summary.succeededCount(),
                        summary.failedCount(), progress));
    }

    /**
     * 记录对象级工作单元事件。
     *
     * <p>调用方传入的 message/detail 必须是面向用户的中文说明，不直接拼接表名、where、SQL 或分片真实范围。
     * 真正需要定位对象时，前端可以用 objectOrdinal/objectExecutionId 与对象级账本表关联，而不是依赖日志正文泄露业务对象名。</p>
     */
    private void recordObjectDispatchEvent(SyncTask task,
                                           SyncExecution execution,
                                           SyncObjectExecution objectExecution,
                                           SyncActorContext actorContext,
                                           String level,
                                           String eventType,
                                           String status,
                                           String message,
                                           String detailSummary,
                                           SyncExecutionLogSupport.ExecutionMetricSnapshot metrics) {
        executionLogSupport.recordObjectEvent(task, execution, objectExecution, actorContext,
                "OBJECT",
                level,
                eventType,
                status,
                message,
                detailSummary,
                metrics);
    }

    /**
     * 根据一个对象映射构造临时 SINGLE_OBJECT 模板。
     *
     * <p>该对象不入库，只用于生成子 bridge plan。这样可以最大化复用已有字段映射、过滤条件、写入策略、connector 能力和
     * checkpoint 类型校验逻辑，避免 fan-out 自己再实现一套“看起来像 bridge plan”的重复规则。</p>
     */
    private SyncTemplate singleObjectTemplate(SyncTemplate template, SyncObjectMappingExecutionItem item) {
        SyncTemplate child = new SyncTemplate();
        child.setId(template.getId());
        child.setTenantId(template.getTenantId());
        child.setProjectId(template.getProjectId());
        child.setWorkspaceId(template.getWorkspaceId());
        child.setName(template.getName());
        child.setDescription(template.getDescription());
        child.setSourceDatasourceId(template.getSourceDatasourceId());
        child.setTargetDatasourceId(template.getTargetDatasourceId());
        child.setSourceSchemaName(item.sourceSchemaName());
        child.setSourceObjectName(item.sourceObjectName());
        child.setTargetSchemaName(item.targetSchemaName());
        child.setTargetObjectName(item.targetObjectName());
        child.setSourceConnectorType(template.getSourceConnectorType());
        child.setTargetConnectorType(template.getTargetConnectorType());
        child.setSyncMode(template.getSyncMode());
        child.setSyncScopeType(SINGLE_OBJECT);
        child.setWriteStrategy(template.getWriteStrategy());
        child.setPrimaryKeyField(template.getPrimaryKeyField());
        child.setIncrementalField(template.getIncrementalField());
        child.setFieldMappingConfig(resolveSingleObjectFieldMappingConfig(template, item));
        /*
         * 多对象 fan-out 拆成单对象子任务后，不能只把 source/target 顶层字段写入 child template。
         * 对象级 whereCondition 是用户在“对象映射”列表里针对每张表单独配置的过滤条件；
         * 如果这里不继续携带 objectMappingConfig，子 bridge plan 只能看到顶层 filterConfig，
         * 最终会出现“父任务详情中每张表都有 where，但真实执行每张表都全量读取”的产品缺陷。
         *
         * 因此每个子模板都保留一个只含当前对象的 objectMappingConfig。它不入库，只在本次内存调度中流转，
         * 既能让 bridge plan 继续复用统一的对象定位/where 解析逻辑，也不会把对象名、where 原文写入日志。
         */
        child.setObjectMappingConfig(singleObjectMappingConfig(item));
        child.setFilterConfig(template.getFilterConfig());
        child.setPartitionConfig(null);
        child.setRetryPolicy(template.getRetryPolicy());
        child.setTimeoutPolicy(template.getTimeoutPolicy());
        child.setEnabled(template.getEnabled());
        child.setCreatedBy(template.getCreatedBy());
        child.setUpdatedBy(template.getUpdatedBy());
        child.setCreateTime(template.getCreateTime());
        child.setUpdateTime(template.getUpdateTime());
        return child;
    }

    /**
     * 为 fan-out 子任务提取当前对象自己的字段映射。
     *
     * <p>新版向导和 Agent 将多表字段映射保存为 {@code objectMappings[].mappings}。如果子任务继续携带
     * 整份多表配置，Reader/Writer 会把其他表的字段也合并进当前 SQL，并且无法稳定推导当前表的主键。
     * 本方法优先使用对象映射中的显式覆盖；没有覆盖时，按源/目标 schema 与表名从字段映射配置中定位当前对象。</p>
     */
    private String resolveSingleObjectFieldMappingConfig(SyncTemplate template,
                                                         SyncObjectMappingExecutionItem item) {
        if (hasText(item.fieldMappingConfigOverride())) {
            return item.fieldMappingConfigOverride();
        }
        if (!hasText(template.getFieldMappingConfig())) {
            return template.getFieldMappingConfig();
        }
        try {
            JsonNode root = objectMapper.readTree(template.getFieldMappingConfig());
            JsonNode objectMappings = root.path("objectMappings");
            if (!objectMappings.isArray()) {
                return template.getFieldMappingConfig();
            }
            for (JsonNode candidate : objectMappings) {
                if (sameObject(candidate, item)) {
                    return objectMapper.writeValueAsString(candidate);
                }
            }
            return template.getFieldMappingConfig();
        } catch (Exception ignored) {
            return template.getFieldMappingConfig();
        }
    }

    private boolean sameObject(JsonNode candidate, SyncObjectMappingExecutionItem item) {
        String sourceObject = jsonText(candidate, "sourceObjectName", "sourceObject", "sourceTableName", "sourceTable");
        String targetObject = jsonText(candidate, "targetObjectName", "targetObject", "targetTableName", "targetTable");
        String sourceSchema = jsonText(candidate, "sourceSchemaName", "sourceSchema");
        String targetSchema = jsonText(candidate, "targetSchemaName", "targetSchema");
        return equalsIgnoreCase(sourceObject, item.sourceObjectName())
                && equalsIgnoreCase(targetObject, item.targetObjectName())
                && compatibleOptionalScope(sourceSchema, item.sourceSchemaName())
                && compatibleOptionalScope(targetSchema, item.targetSchemaName());
    }

    private String jsonText(JsonNode node, String... fieldNames) {
        for (String fieldName : fieldNames) {
            JsonNode value = node.get(fieldName);
            if (value != null && !value.isNull() && hasText(value.asText())) {
                return value.asText().trim();
            }
        }
        return null;
    }

    private boolean compatibleOptionalScope(String configured, String resolved) {
        return !hasText(configured) || !hasText(resolved) || equalsIgnoreCase(configured, resolved);
    }

    private boolean equalsIgnoreCase(String left, String right) {
        return hasText(left) && hasText(right) && left.trim().equalsIgnoreCase(right.trim());
    }

    /**
     * 生成一次 worker 认领代次内稳定、跨重试代次变化的幂等键片段。
     *
     * <p>对象级选择性重试会复用父 executionId。如果终态键只包含 executionId，第二轮失败会被误判为
     * 第一轮失败回调的重复请求，父 execution 因而残留在 RUNNING。leaseExpireTime 每次认领都会刷新，
     * 正好可以区分重试代次，同时保证同一租约内的 HTTP/outbox 重复回调仍然幂等。</p>
     */
    private String executionAttemptKey(SyncExecution execution) {
        String leaseGeneration = execution.getLeaseExpireTime() == null
                ? "no-lease"
                : Long.toString(execution.getLeaseExpireTime()
                        .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli());
        return execution.getId() + "-" + leaseGeneration;
    }

    private String singleObjectMappingConfig(SyncObjectMappingExecutionItem item) {
        try {
            Map<String, Object> mapping = new java.util.LinkedHashMap<>();
            mapping.put("sourceSchemaName", item.sourceSchemaName());
            mapping.put("sourceObjectName", item.sourceObjectName());
            mapping.put("targetSchemaName", item.targetSchemaName());
            mapping.put("targetObjectName", item.targetObjectName());
            if (hasText(item.whereCondition())) {
                mapping.put("whereCondition", item.whereCondition());
            }
            Map<String, Object> root = new java.util.LinkedHashMap<>();
            root.put("mappings", List.of(mapping));
            root.put("generatedBy", "OBJECT_LIST_FAN_OUT_CHILD_TEMPLATE");
            root.put("payloadPolicy", "INTERNAL_SINGLE_OBJECT_MAPPING_NO_ROWS_NO_CREDENTIALS");
            return objectMapper.writeValueAsString(root);
        } catch (Exception exception) {
            throw new IllegalStateException("OBJECT_LIST 子对象映射配置序列化失败，已按 fail-closed 终止当前对象调度", exception);
        }
    }

    /**
     * 构造子对象执行视图。
     *
     * <p>每个子对象都从 0 计数开始交给 datasource-management。不能把父 execution 已读记录数作为子对象 offset，
     * 否则第二张表会错误跳过第一张表的行数。对象级 checkpoint 后续应进入 data_sync_object_execution 或专用 checkpoint 表。</p>
     */
    private SyncExecution childExecutionView(SyncExecution execution) {
        SyncExecution child = new SyncExecution();
        child.setId(execution.getId());
        child.setTenantId(execution.getTenantId());
        child.setProjectId(execution.getProjectId());
        child.setWorkspaceId(execution.getWorkspaceId());
        child.setSyncTaskId(execution.getSyncTaskId());
        child.setExecutionNo(execution.getExecutionNo());
        child.setExecutionState(execution.getExecutionState());
        child.setTriggerType(execution.getTriggerType());
        child.setExecutorId(execution.getExecutorId());
        child.setLeaseExpireTime(execution.getLeaseExpireTime());
        child.setRecordsRead(0L);
        child.setRecordsWritten(0L);
        child.setFailedRecordCount(0L);
        child.setTriggeredBy(execution.getTriggeredBy());
        return child;
    }

    /**
     * 构造子对象 worker plan。
     *
     * <p>父级 OBJECT_LIST workerPlan 可能带有 SCOPE_NOT_EXECUTABLE_BY_MINIMAL_RUN_ONCE_BRIDGE 阻断码。
     * fan-out 已经把多对象拆成单对象，因此子计划必须移除该父级阻断码，否则每个子对象都会被再次误判为不可执行。</p>
     */
    private SyncWorkerExecutionPlanView singleObjectWorkerPlan(SyncWorkerExecutionPlanView workerPlan,
                                                               SyncObjectMappingExecutionItem item) {
        return new SyncWorkerExecutionPlanView(
                true,
                "READY_TO_RUN",
                workerPlan.tenantId(),
                workerPlan.projectId(),
                workerPlan.workspaceId(),
                workerPlan.syncTaskId(),
                workerPlan.executionId(),
                workerPlan.executionNo(),
                workerPlan.executionState(),
                workerPlan.triggerType(),
                workerPlan.executorId(),
                workerPlan.leaseExpireTime(),
                workerPlan.templateId(),
                workerPlan.sourceDatasourceId(),
                workerPlan.targetDatasourceId(),
                workerPlan.sourceConnectorType(),
                workerPlan.targetConnectorType(),
                workerPlan.syncMode(),
                workerPlan.transferChannel(),
                workerPlan.referenceRuntime(),
                SINGLE_OBJECT,
                true,
                false,
                false,
                1,
                false,
                true,
                true,
                true,
                workerPlan.writeStrategy(),
                workerPlan.writeStrategyRequiresConflictKey(),
                workerPlan.primaryKeyDeclared(),
                workerPlan.incrementalFieldDeclared(),
                workerPlan.connectorCompatibilitySupported(),
                workerPlan.consistencyGoal(),
                false,
                workerPlan.retryPattern(),
                true,
                false,
                false,
                workerPlan.filterDeclared(),
                false,
                workerPlan.retryPolicyDeclared(),
                workerPlan.timeoutPolicyDeclared(),
                sanitizedChildIssueCodes(workerPlan.issueCodes()),
                List.of("OBJECT_LIST_CHILD_PLAN_DISPATCH_TO_RUN_ONCE", "CALL_PARENT_FAN_OUT_COMPLETE_AFTER_ALL_OBJECTS"),
                workerPlan.performanceNotes(),
                mergeIssueCodes(workerPlan.safetyNotes(), item.warnings(), "OBJECT_LIST_CHILD_SCOPE_CREATED_BY_FAN_OUT"),
                workerPlan.payloadPolicy()
        );
    }

    private List<String> sanitizedChildIssueCodes(List<String> issueCodes) {
        if (issueCodes == null || issueCodes.isEmpty()) {
            return List.of();
        }
        return issueCodes.stream()
                .filter(issueCode -> !"SCOPE_NOT_EXECUTABLE_BY_MINIMAL_RUN_ONCE_BRIDGE".equals(issueCode))
                .filter(issueCode -> !"OBJECT_MAPPING_CONFIG_REQUIRED".equals(issueCode))
                .filter(issueCode -> !"OBJECT_MAPPING_EMPTY".equals(issueCode))
                .filter(issueCode -> !"OBJECT_MAPPING_TOO_LARGE".equals(issueCode))
                .filter(issueCode -> !"OBJECT_MAPPING_IDENTIFIER_UNSAFE".equals(issueCode))
                .toList();
    }

    private void completeFanOut(SyncTask task,
                                SyncExecution execution,
                                SyncActorContext actorContext,
                                SyncObjectExecutionSummary summary) {
        SyncExecutionCompleteRequest request = new SyncExecutionCompleteRequest();
        request.setExecutorId(execution.getExecutorId());
        request.setRecordsRead(summary.recordsRead());
        request.setRecordsWritten(summary.recordsWritten());
        request.setCheckpointRef(null);
        request.setIdempotencyKey("object-list-fan-out-complete-" + executionAttemptKey(execution));
        lifecycleSupport.completeExecution(task, execution, request, actorContext);
        receiptPublisher.publishComplete(task, execution, actorContext,
                aggregateResponse(summary, "OBJECT_LIST_ALL_OBJECTS_COMPLETED", true, false));
    }

    private void partialFanOut(SyncTask task,
                               SyncExecution execution,
                               SyncActorContext actorContext,
                               SyncObjectExecutionSummary summary) {
        SyncExecutionPartialSuccessRequest request = new SyncExecutionPartialSuccessRequest();
        request.setExecutorId(execution.getExecutorId());
        request.setRecordsRead(summary.recordsRead());
        request.setRecordsWritten(summary.recordsWritten());
        request.setFailedRecordCount(summary.failedRecordCount());
        request.setErrorSummary("OBJECT_LIST partially succeeded, succeededObjects=" + summary.succeededCount()
                + ", failedObjects=" + summary.failedCount());
        request.setIdempotencyKey("object-list-fan-out-partial-" + executionAttemptKey(execution));
        lifecycleSupport.partiallySucceedExecution(task, execution, request, actorContext);
        receiptPublisher.publishPartiallySucceeded(task, execution, actorContext,
                aggregateResponse(summary, "OBJECT_LIST_PARTIALLY_SUCCEEDED", false, false),
                mergeIssueCodes(summary.issueCodes(), List.of(), "OBJECT_LIST_PARTIALLY_SUCCEEDED"));
    }

    private SyncOfflineRunnerDispatchResult failFanOut(SyncTask task,
                                                       SyncExecution execution,
                                                       SyncActorContext actorContext,
                                                       SyncOfflineRunnerJobContract parentContract,
                                                       boolean remoteCalled,
                                                       long recordsRead,
                                                       long recordsWritten,
                                                       long failedRecordCount,
                                                       String dispatchStatus,
                                                       String errorCode,
                                                       String errorMessage,
                                                       List<String> issueCodes) {
        SyncExecutionFailRequest request = new SyncExecutionFailRequest();
        request.setExecutorId(execution.getExecutorId());
        request.setErrorType("OBJECT_LIST_OBJECT_LEVEL_FAN_OUT_FAILED");
        request.setErrorCode(errorCode);
        request.setErrorMessage(errorMessage);
        request.setSourceRecordKey(null);
        request.setTargetRecordKey(null);
        request.setSamplePayload(null);
        request.setRecordsRead(recordsRead);
        request.setRecordsWritten(recordsWritten);
        request.setFailedRecordCount(Math.max(1L, failedRecordCount));
        request.setRetryable(false);
        request.setIdempotencyKey("object-list-fan-out-fail-" + executionAttemptKey(execution) + "-" + errorCode);
        lifecycleSupport.failExecution(task, execution, request, actorContext);
        receiptPublisher.publishFailed(task, execution, actorContext, errorCode, issueCodes);
        return new SyncOfflineRunnerDispatchResult(
                remoteCalled,
                false,
                true,
                dispatchStatus,
                execution.getId(),
                null,
                parentContract == null ? "OBJECT_LIST_OBJECT_LEVEL_FAN_OUT" : parentContract.contractStatus(),
                mergeIssueCodes(issueCodes, parentContract == null ? List.of() : parentContract.issueCodes(), errorCode),
                SyncOfflineRunnerDispatchResult.PAYLOAD_POLICY
        );
    }

    private DatasourceRunOnceResponse aggregateResponse(SyncObjectExecutionSummary summary,
                                                        String runStatus,
                                                        boolean endOfSource,
                                                        boolean failed) {
        DatasourceRunOnceResponse response = new DatasourceRunOnceResponse();
        response.setRunStatus(runStatus);
        response.setBatchRecordsRead(summary.recordsRead());
        response.setBatchRecordsWritten(summary.recordsWritten());
        response.setBatchFailedRecordCount(summary.failedRecordCount());
        response.setTotalRecordsRead(summary.recordsRead());
        response.setTotalRecordsWritten(summary.recordsWritten());
        response.setTotalFailedRecordCount(summary.failedRecordCount());
        response.setEndOfSource(endOfSource);
        response.setFailed(failed);
        response.setCompleteCallbackRecommended(!failed && endOfSource);
        response.setFailCallbackRecommended(failed);
        response.setProgressCallbackRecommended(false);
        response.setCheckpointCandidateProduced(false);
        response.setPayloadPolicy("LOW_SENSITIVE_OBJECT_LIST_FAN_OUT_RESULT_NO_OBJECT_NAMES_NO_ROWS_NO_SQL");
        return response;
    }

    /**
     * 从模板 retryPolicy 中解析对象级最大尝试次数。
     *
     * <p>支持两类常见写法：</p>
     * <p>1. maxObjectAttempts/objectMaxAttempts/maxAttempts：表示总尝试次数；</p>
     * <p>2. maxObjectRetries/objectMaxRetries/maxRetryAttempts/maxRetryCount：表示失败后的重试次数，总尝试次数会自动 +1。</p>
     *
     * <p>解析失败时使用默认值 3。这里不把 retryPolicy 原文写入日志或事件，避免配置中误放敏感信息时泄露。</p>
     */
    private int resolveObjectMaxAttemptCount(SyncTemplate template) {
        if (template == null || !hasText(template.getRetryPolicy())) {
            return DEFAULT_OBJECT_MAX_ATTEMPT_COUNT;
        }
        try {
            JsonNode root = objectMapper.readTree(template.getRetryPolicy());
            Integer directAttempts = firstPositive(root,
                    "maxObjectAttempts", "objectMaxAttempts", "maxAttempts");
            if (directAttempts != null) {
                return clamp(directAttempts, 1, MAX_OBJECT_MAX_ATTEMPT_COUNT);
            }
            Integer retryCount = firstPositive(root,
                    "maxObjectRetries", "objectMaxRetries", "maxRetryAttempts", "maxRetryCount");
            if (retryCount != null) {
                return clamp(retryCount + 1, 1, MAX_OBJECT_MAX_ATTEMPT_COUNT);
            }
        } catch (Exception ignored) {
            return DEFAULT_OBJECT_MAX_ATTEMPT_COUNT;
        }
        return DEFAULT_OBJECT_MAX_ATTEMPT_COUNT;
    }

    private Integer firstPositive(JsonNode root, String... fieldNames) {
        if (root == null || fieldNames == null) {
            return null;
        }
        for (String fieldName : fieldNames) {
            JsonNode value = root.get(fieldName);
            if (value != null && value.canConvertToInt() && value.asInt() > 0) {
                return value.asInt();
            }
        }
        return null;
    }

    private boolean shouldRetryObject(SyncBatchRunOnceRemoteExecutionResult remoteResult,
                                      SyncObjectExecution objectExecution) {
        return remoteResult != null
                && remoteResult.retryable()
                && safeInt(objectExecution.getAttemptCount()) < effectiveMaxAttemptCount(objectExecution);
    }

    private int effectiveMaxAttemptCount(SyncObjectExecution objectExecution) {
        return clamp(safeInt(objectExecution.getMaxAttemptCount()), 1, MAX_OBJECT_MAX_ATTEMPT_COUNT);
    }

    private int clamp(int value, int minValue, int maxValue) {
        return Math.max(minValue, Math.min(value, maxValue));
    }

    private int safeInt(Integer value) {
        return value == null ? 0 : value;
    }

    private List<String> mergeIssueCodes(List<String> first, List<String> second, String issueCode) {
        List<String> values = new ArrayList<>();
        values.addAll(first == null ? List.of() : first);
        values.addAll(second == null ? List.of() : second);
        if (hasText(issueCode)) {
            values.add(issueCode);
        }
        return new ArrayList<>(new LinkedHashSet<>(values));
    }

    private String firstText(String value, String fallback) {
        return hasText(value) ? value.trim() : fallback;
    }

    private String normalize(String value) {
        return hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : null;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private record ObjectDispatchOutcome(boolean remoteCalled, List<String> issueCodes) {

        private ObjectDispatchOutcome {
            issueCodes = issueCodes == null ? List.of() : List.copyOf(issueCodes);
        }
    }
}
