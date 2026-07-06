/**
 * @Author : Cui
 * @Date: 2026/06/29 13:03
 * @Description DataSmart Govern Backend - SyncBatchRunOnceDispatchService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import com.czh.datasmart.govern.datasync.config.DataSyncDatasourceRunOnceProperties;
import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExecutionCompleteRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExecutionFailRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncWorkerExecutionPlanView;
import com.czh.datasmart.govern.datasync.entity.SyncExecution;
import com.czh.datasmart.govern.datasync.entity.SyncTask;
import com.czh.datasmart.govern.datasync.entity.SyncTemplate;
import com.czh.datasmart.govern.datasync.integration.datasource.runonce.DatasourceRunOnceClient;
import com.czh.datasmart.govern.datasync.integration.datasource.runonce.DatasourceRunOnceRequest;
import com.czh.datasmart.govern.datasync.integration.datasource.runonce.DatasourceRunOnceResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * data-sync 到 datasource-management run-once 的执行派发服务。
 *
 * <p>本服务是当前 Java 数据同步闭环的关键收敛点：data-sync 仍然是 execution、checkpoint、lease、
 * complete/fail 的控制面所有者；datasource-management 只作为受控 connector runtime 执行“一批 read/write”。
 * 这样可以避免两个微服务同时修改各自的同步任务状态，形成不可审计的“双控制面”。</p>
 *
 * <p>当前最小闭环只放行 {@code FULL}/{@code ONE_TIME_MIGRATION} 且 checkpoint 类型为
 * {@code NONE_OR_FINAL_WATERMARK} 的场景。原因是增量同步需要安全交接 checkpoint 原始值，而上一阶段
 * datasource-management 出于低敏原则不会把 checkpoint 原始值放入响应；如果现在强行打开增量，就会出现
 * “远端知道下一水位，但 data-sync 无法安全持久化水位”的半闭环风险。</p>
 *
 * <p>fail-closed 原则：</p>
 * <p>1. bridgePlan 被阻断时，不调用远端，直接 fail execution；</p>
 * <p>2. run-once 配置关闭时，不让 execution 悬挂在 RUNNING，而是明确 fail；</p>
 * <p>3. 远端返回“还有后续批次”时，因为 data-sync 外层多批循环尚未实现，当前先 fail，而不是假装成功；</p>
 * <p>4. 所有 fail message 都只包含低敏原因码，不包含 SQL、字段值、连接信息、checkpoint 原始值或响应正文。</p>
 */
@Component
@RequiredArgsConstructor
public class SyncBatchRunOnceDispatchService {

    private static final String PLAN_VERSION = "datasmart.datasource.sync-batch-plan.v1";
    private static final String EXECUTION_BOUNDARY = "DATA_SYNC_TO_DATASOURCE_RUN_ONCE_NO_RAW_SQL_NO_CREDENTIALS";
    private static final String FULL_CHECKPOINT_TYPE = "NONE_OR_FINAL_WATERMARK";

    private final SyncBatchRunnerBridgePlanSupport bridgePlanSupport;
    private final DatasourceRunOnceClient datasourceRunOnceClient;
    private final DataSyncDatasourceRunOnceProperties properties;
    private final SyncExecutionLifecycleSupport lifecycleSupport;
    private final DataSyncTaskManagementReceiptPublisher receiptPublisher;

    /**
     * 生成 bridge plan、调用 datasource-management run-once，并把结果回写到 data-sync 生命周期。
     *
     * @param execution 当前已被 worker claim 的执行记录。正常情况下状态应为 RUNNING。
     * @param task execution 所属同步任务。
     * @param template 同步模板，提供对象定位、写入策略、字段映射等执行契约。
     * @param workerPlan claim 阶段返回给 worker 的低敏计划。
     * @param actorContext 当前服务调用上下文。若为空，本服务会用 execution/task 构造服务账号上下文兜底。
     * @return 低敏派发结果摘要。
     */
    public SyncBatchRunOnceDispatchResult dispatchRunOnce(SyncExecution execution,
                                                          SyncTask task,
                                                          SyncTemplate template,
                                                          SyncWorkerExecutionPlanView workerPlan,
                                                          SyncActorContext actorContext) {
        requireDispatchInputs(execution, task, template, workerPlan);
        SyncBatchRunnerBridgePlan bridgePlan = bridgePlanSupport.buildPlan(execution, task, template, workerPlan);
        return dispatchPreparedRunOnce(bridgePlan, execution, task, actorContext);
    }

    /**
     * 派发已经生成好的 bridge plan。
     *
     * <p>这个入口是为新的离线 Runner 调度门面准备的。上一阶段开始，bridge plan 已经携带
     * {@link SyncOfflineRunnerJobContract}，专用门面会先读取合同判断是否应该进入最小 run-once、等待专用 Runner、
     * 等待审批或按 checkpoint handoff 阻断。如果合同判断允许进入当前最小闭环，就通过本方法复用原有
     * datasource-management run-once 请求构造、远端调用、complete/fail 回写和 task-management receipt 发布逻辑。</p>
     *
     * <p>为什么不让门面直接调用 datasource-management：data-sync 的 run-once 调用里已经沉淀了低敏失败回写、
     * 远端响应处理、外层多批循环阻断、receipt 发布和 checkpoint 安全边界。如果绕开这里，会形成第二套执行闭环，
     * 后续排查和审计都会变复杂。</p>
     *
     * @param bridgePlan 已经由 data-sync 控制面生成的内部桥接计划。
     * @param execution 当前执行记录。
     * @param task 当前任务。
     * @param actorContext 当前操作者或服务账号上下文。
     * @return 低敏 run-once 派发结果。
     */
    public SyncBatchRunOnceDispatchResult dispatchPreparedRunOnce(SyncBatchRunnerBridgePlan bridgePlan,
                                                                  SyncExecution execution,
                                                                  SyncTask task,
                                                                  SyncActorContext actorContext) {
        SyncActorContext safeActorContext = ensureActorContext(execution, task, actorContext);
        SyncBatchRunOnceRemoteExecutionResult remoteResult =
                executePreparedRunOnceRemoteOnly(bridgePlan, execution, task, safeActorContext);
        return applyRemoteExecutionResult(task, execution, safeActorContext, remoteResult);
    }

    /**
     * 只执行 datasource-management run-once 远端批次循环，不直接回写 data-sync execution 终态。
     *
     * <p>这个入口是 OBJECT_LIST 串行 fan-out 的关键复用点。多对象同步需要把一个模板拆成多个单对象子计划，
     * 每个子计划都应复用同一套 datasource-management Java Reader/Writer 批处理逻辑；但每个子对象完成时不能立刻
     * complete 整个 execution，否则后续对象尚未处理，用户却会看到任务成功。</p>
     *
     * <p>因此本方法只返回低敏远端执行结果：</p>
     * <p>1. 本地配置关闭、bridge 阻断、checkpoint handoff 未实现时，返回 failed=true，但不写状态机；</p>
     * <p>2. 远端多批次循环成功结束时，返回 completed=true 和累计计数；</p>
     * <p>3. 远端失败、空响应、无前进进度或超过批次数上限时，返回 failed=true 和低敏错误码；</p>
     * <p>4. 调用方负责决定是立即 complete/fail，还是在更大的 fan-out 编排中累加结果后统一回写。</p>
     *
     * @param bridgePlan 已准备好的内部桥接计划。
     * @param execution 当前执行记录，仅用于生成请求、执行边界和低敏结果。
     * @param task 当前任务，用于兜底构造服务账号上下文。
     * @param actorContext 当前操作者或服务账号上下文。
     * @return 远端执行结果，不包含 SQL、连接串、行样本、字段值或 checkpoint 原始值。
     */
    public SyncBatchRunOnceRemoteExecutionResult executePreparedRunOnceRemoteOnly(
            SyncBatchRunnerBridgePlan bridgePlan,
            SyncExecution execution,
            SyncTask task,
            SyncActorContext actorContext) {
        return executePreparedRunOnceRemoteOnly(bridgePlan, execution, task, actorContext, null, List.of());
    }

    /**
     * 带分片上下文的 run-once 远程执行入口。
     *
     * <p>普通单对象同步不需要 shard；但单张大表被 partitionConfig 拆分后，每个分片都必须把自己的
     * shardOrPartition 和 range filter 传给 datasource-management。这里不重新实现一套 runner，而是在
     * 既有 run-once 请求上增加受控上下文：</p>
     * <p>1. shardOrPartition 只使用低敏编号，例如 id-range-0000；</p>
     * <p>2. additionalFilterConditions 是已经由 data-sync 校验过的结构化条件，最终仍由 JDBC 方言层二次校验；</p>
     * <p>3. 分片请求使用 child execution 视图中的 0 起始计数，避免不同分片之间的 offset/累计计数互相污染。</p>
     */
    public SyncBatchRunOnceRemoteExecutionResult executePreparedRunOnceRemoteOnly(
            SyncBatchRunnerBridgePlan bridgePlan,
            SyncExecution execution,
            SyncTask task,
            SyncActorContext actorContext,
            String shardOrPartition,
            List<SyncFilterExecutionCondition> additionalFilterConditions) {
        requirePreparedDispatchInputs(bridgePlan, execution, task);
        SyncActorContext safeActorContext = ensureActorContext(execution, task, actorContext);

        if (!properties.isEnabled()) {
            return failBeforeRemote(task, execution, safeActorContext,
                    "CONNECTOR_RUNTIME_RUN_ONCE_DISABLED",
                    "DATASOURCE_RUN_ONCE_DISABLED",
                    "datasource run-once 调用已被配置关闭，本次执行按 fail-closed 终止",
                    List.of("DATASOURCE_RUN_ONCE_DISABLED"));
        }
        if (!bridgePlan.isDispatchable()) {
            return failBeforeRemote(task, execution, safeActorContext,
                    "CONNECTOR_RUNTIME_BRIDGE_BLOCKED",
                    "BRIDGE_PLAN_BLOCKED",
                    "同步执行器桥接计划被阻断，本次执行未触发真实读写",
                    bridgePlan.getIssueCodes());
        }
        if (!FULL_CHECKPOINT_TYPE.equals(bridgePlan.getCheckpointType())) {
            return failBeforeRemote(task, execution, safeActorContext,
                    "CONNECTOR_RUNTIME_CHECKPOINT_HANDOFF_BLOCKED",
                    "CHECKPOINT_HANDOFF_NOT_IMPLEMENTED",
                    "当前同步模式需要 checkpoint 原始值安全交接，但该机制尚未实现，本次执行按 fail-closed 终止",
                    withIssue(bridgePlan.getIssueCodes(), "CHECKPOINT_HANDOFF_NOT_IMPLEMENTED"));
        }

        DatasourceRunOnceRequest request = buildRequest(bridgePlan, execution, safeActorContext,
                shardOrPartition, additionalFilterConditions);
        try {
            return dispatchDataXStyleBatchLoop(task, execution, safeActorContext, request);
        } catch (PlatformBusinessException exception) {
            return failAfterRemoteCallException(task, execution, safeActorContext, exception);
        }
    }

    /**
     * 将远端执行结果应用到 data-sync 生命周期。
     *
     * <p>单对象 run-once 路径会调用本方法，因此对外行为仍然和改造前一致：远端完成则 complete，远端失败则 fail。
     * 多对象 fan-out 路径不会调用本方法，而是聚合多个
     * {@link SyncBatchRunOnceRemoteExecutionResult} 后由 fan-out 服务统一回写终态。</p>
     */
    private SyncBatchRunOnceDispatchResult applyRemoteExecutionResult(SyncTask task,
                                                                      SyncExecution execution,
                                                                      SyncActorContext actorContext,
                                                                      SyncBatchRunOnceRemoteExecutionResult remoteResult) {
        if (remoteResult == null) {
            failExecution(task, execution, actorContext,
                    "CONNECTOR_RUNTIME_RUN_ONCE_FAILED",
                    "RUN_ONCE_REMOTE_RESULT_EMPTY",
                    "datasource-management run-once 未返回低敏执行结果，本次执行按 fail-closed 终止",
                    false);
            return result(false, false, true, "RUN_ONCE_REMOTE_RESULT_EMPTY",
                    execution.getId(), null, List.of("RUN_ONCE_REMOTE_RESULT_EMPTY"));
        }
        if (remoteResult.completed()) {
            completeExecution(task, execution, actorContext, responseFromRemoteResult(remoteResult));
            return result(remoteResult.remoteCalled(), true, false, remoteResult.dispatchStatus(),
                    execution.getId(), remoteResult.remoteRunStatus(), remoteResult.issueCodes());
        }
        if (remoteResult.failed()) {
            failExecution(task, execution, actorContext,
                    firstText(remoteResult.errorType(), "CONNECTOR_RUNTIME_RUN_ONCE_FAILED"),
                    firstText(remoteResult.errorCode(), "RUN_ONCE_REMOTE_FAILED"),
                    firstText(remoteResult.errorMessage(), "datasource-management run-once 执行失败，本次执行已按 fail-closed 终止"),
                    remoteResult.retryable());
            return result(remoteResult.remoteCalled(), false, true, remoteResult.dispatchStatus(),
                    execution.getId(), remoteResult.remoteRunStatus(), remoteResult.issueCodes());
        }
        return result(remoteResult.remoteCalled(), false, false, remoteResult.dispatchStatus(),
                execution.getId(), remoteResult.remoteRunStatus(), remoteResult.issueCodes());
    }

    /**
     * 校验调度所需上下文。
     *
     * <p>这里选择抛出参数错误，而不是写 failExecution，是因为 execution/task/template/workerPlan 缺失通常代表调用方代码错误，
     * 还没有足够业务上下文安全地回写状态机。真实 worker 在调用本服务前应确保 claim 返回了完整对象。</p>
     */
    private void requireDispatchInputs(SyncExecution execution,
                                       SyncTask task,
                                       SyncTemplate template,
                                       SyncWorkerExecutionPlanView workerPlan) {
        if (execution == null || task == null || template == null || workerPlan == null) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR,
                    "run-once 派发缺少 execution/task/template/workerPlan 上下文，无法安全推进状态机");
        }
    }

    /**
     * 校验“已准备计划”派发所需的最小上下文。
     *
     * <p>和 {@link #requireDispatchInputs(SyncExecution, SyncTask, SyncTemplate, SyncWorkerExecutionPlanView)} 不同，
     * 该入口不再要求 template/workerPlan，因为上游离线 Runner 门面已经用它们生成了 bridge plan 和合同。
     * 这里仅确认真正回写状态机所需的 bridgePlan、execution、task 存在。</p>
     */
    private void requirePreparedDispatchInputs(SyncBatchRunnerBridgePlan bridgePlan,
                                               SyncExecution execution,
                                               SyncTask task) {
        if (bridgePlan == null || execution == null || task == null) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR,
                    "run-once 已准备计划派发缺少 bridgePlan/execution/task 上下文，无法安全推进状态机");
        }
    }

    /**
     * 构造服务账号上下文兜底。
     *
     * <p>内部派发可能来自后台 worker、定时器或测试，不一定经过 HTTP Controller。为了让后续审计和生命周期回写仍有
     * tenantId、actorRole、traceId，本方法会用 task/execution 中的事实补齐最小上下文。</p>
     */
    private SyncActorContext ensureActorContext(SyncExecution execution, SyncTask task, SyncActorContext actorContext) {
        Long tenantId = actorContext != null && actorContext.tenantId() != null
                ? actorContext.tenantId()
                : task.getTenantId();
        Long actorId = actorContext != null && actorContext.actorId() != null
                ? actorContext.actorId()
                : fallbackActorId(execution);
        String actorRole = actorContext != null && hasText(actorContext.actorRole())
                ? actorContext.actorRole()
                : properties.getActorRole();
        String traceId = actorContext != null && hasText(actorContext.traceId())
                ? actorContext.traceId()
                : "data-sync-run-once-dispatch";
        return new SyncActorContext(tenantId, actorId, actorRole, traceId,
                actorContext == null ? null : actorContext.dataScopeLevel(),
                actorContext == null ? null : actorContext.dataScopeExpression(),
                actorContext == null ? List.of() : actorContext.authorizedProjectIds(),
                actorContext == null ? false : actorContext.approvalRequired());
    }

    /**
     * 把内部 bridge plan 转换为 datasource-management run-once 请求。
     *
     * <p>该转换只发生在服务端内存中，不应被公开 API 或 runtime event 展示。请求中会包含对象定位和字段清单，
     * 这是 connector runtime 真正执行 read/write 必需的执行元数据，但它们仍然不是普通用户侧可见数据。</p>
     */
    private DatasourceRunOnceRequest buildRequest(SyncBatchRunnerBridgePlan bridgePlan,
                                                  SyncExecution execution,
                                                  SyncActorContext actorContext,
                                                  String shardOrPartition,
                                                  List<SyncFilterExecutionCondition> additionalFilterConditions) {
        DatasourceRunOnceRequest request = new DatasourceRunOnceRequest();
        request.setExecutionPlan(executionPlan(bridgePlan, execution, shardOrPartition, additionalFilterConditions));
        request.setSelectedColumns(bridgePlan.getFieldMappingContract().getSelectedColumns());
        request.setWriteColumns(bridgePlan.getFieldMappingContract().getWriteColumns());
        request.setPrimaryKeyColumns(bridgePlan.getFieldMappingContract().getPrimaryKeyColumns());
        request.setActorId(actorContext.actorId());
        request.setActorRole(actorContext.actorRole());
        request.setActorTenantId(actorContext.tenantId());
        request.setShardOrPartition(shardOrPartition);
        request.setCheckpointValue(null);
        request.setPreviousRecordsRead(zeroIfNull(execution.getRecordsRead()));
        request.setPreviousRecordsWritten(zeroIfNull(execution.getRecordsWritten()));
        request.setPreviousFailedRecordCount(zeroIfNull(execution.getFailedRecordCount()));
        return request;
    }

    private DatasourceRunOnceRequest.ExecutionPlan executionPlan(SyncBatchRunnerBridgePlan bridgePlan,
                                                                SyncExecution execution,
                                                                String shardOrPartition,
                                                                List<SyncFilterExecutionCondition> additionalFilterConditions) {
        DatasourceRunOnceRequest.ExecutionPlan plan = new DatasourceRunOnceRequest.ExecutionPlan();
        plan.setPlanVersion(PLAN_VERSION);
        plan.setExecutionBoundary(EXECUTION_BOUNDARY);
        plan.setTaskId(bridgePlan.getSyncTaskId());
        plan.setExecutionId(bridgePlan.getExecutionId());
        plan.setReadPlan(readPlan(bridgePlan, shardOrPartition, additionalFilterConditions));
        plan.setWritePlan(writePlan(bridgePlan));
        plan.setCheckpointPlan(checkpointPlan(bridgePlan, shardOrPartition));
        plan.setRuntimeControlPlan(runtimeControlPlan(bridgePlan, execution, shardOrPartition));
        plan.setWarnings(bridgePlan.getWarnings());
        plan.setGeneratedAt(LocalDateTime.now());
        return plan;
    }

    private DatasourceRunOnceRequest.ReadPlan readPlan(SyncBatchRunnerBridgePlan bridgePlan,
                                                       String shardOrPartition,
                                                       List<SyncFilterExecutionCondition> additionalFilterConditions) {
        DatasourceRunOnceRequest.ReadPlan readPlan = new DatasourceRunOnceRequest.ReadPlan();
        readPlan.setConnectorType(bridgePlan.getSourceConnectorType());
        readPlan.setDatasourceId(bridgePlan.getSourceDatasourceId());
        readPlan.setObjectLocator(bridgePlan.getSourceObjectLocator());
        readPlan.setReadStrategy(bridgePlan.getReadStrategy());
        readPlan.setSyncMode(bridgePlan.getSyncMode());
        readPlan.setIncrementalField(bridgePlan.getIncrementalField());
        readPlan.setFilterConditions(filterConditions(bridgePlan, additionalFilterConditions));
        readPlan.setPartitionConfigured(hasText(shardOrPartition)
                || additionalFilterConditions != null && !additionalFilterConditions.isEmpty());
        readPlan.setRecommendedFetchSize(properties.getDefaultFetchSize());
        readPlan.setRequiredWorkerCapabilities(readCapabilities(bridgePlan, shardOrPartition));
        return readPlan;
    }

    /**
     * 将 data-sync 内部过滤契约映射为 datasource-management internal 请求。
     *
     * <p>跨服务传递的是结构化条件而非 SQL 字符串；字段名、操作符和值绑定仍由 datasource-management 方言层兜底。</p>
     */
    private List<DatasourceRunOnceRequest.ReadFilterCondition> filterConditions(
            SyncBatchRunnerBridgePlan bridgePlan,
            List<SyncFilterExecutionCondition> additionalFilterConditions) {
        List<SyncFilterExecutionCondition> mergedConditions = new ArrayList<>(bridgePlan.getFilterConditions());
        if (additionalFilterConditions != null) {
            mergedConditions.addAll(additionalFilterConditions);
        }
        if (mergedConditions.isEmpty()) {
            return List.of();
        }
        return mergedConditions.stream()
                .map(condition -> {
                    DatasourceRunOnceRequest.ReadFilterCondition target =
                            new DatasourceRunOnceRequest.ReadFilterCondition();
                    target.setColumn(condition.getColumn());
                    target.setOperator(condition.getOperator());
                    target.setValue(condition.getValue());
                    target.setValueRequired(condition.isValueRequired());
                    return target;
                })
                .toList();
    }

    private DatasourceRunOnceRequest.WritePlan writePlan(SyncBatchRunnerBridgePlan bridgePlan) {
        DatasourceRunOnceRequest.WritePlan writePlan = new DatasourceRunOnceRequest.WritePlan();
        writePlan.setConnectorType(bridgePlan.getTargetConnectorType());
        writePlan.setDatasourceId(bridgePlan.getTargetDatasourceId());
        writePlan.setObjectLocator(bridgePlan.getTargetObjectLocator());
        writePlan.setWriteStrategy(bridgePlan.getWriteStrategy());
        writePlan.setConflictPolicy(conflictPolicy(bridgePlan.getWriteStrategy()));
        writePlan.setPrimaryKeyRequired(!bridgePlan.getFieldMappingContract().getPrimaryKeyColumns().isEmpty());
        writePlan.setPrimaryKeyField(firstOrNull(bridgePlan.getFieldMappingContract().getPrimaryKeyColumns()));
        writePlan.setRecommendedWriteBatchSize(properties.getDefaultWriteBatchSize());
        writePlan.setRecommendedCommitIntervalRecords(properties.getDefaultCommitIntervalRecords());
        writePlan.setRequiredWorkerCapabilities(writeCapabilities(bridgePlan));
        return writePlan;
    }

    private DatasourceRunOnceRequest.CheckpointPlan checkpointPlan(SyncBatchRunnerBridgePlan bridgePlan,
                                                                  String shardOrPartition) {
        DatasourceRunOnceRequest.CheckpointPlan checkpointPlan = new DatasourceRunOnceRequest.CheckpointPlan();
        checkpointPlan.setCheckpointType(bridgePlan.getCheckpointType());
        checkpointPlan.setInitialCheckpointPolicy("NO_CHECKPOINT_REQUIRED_FOR_FULL_RUN_ONCE");
        checkpointPlan.setResumeRequired(false);
        checkpointPlan.setShardAware(hasText(shardOrPartition));
        checkpointPlan.setPersistEveryRecords(properties.getDefaultFetchSize());
        checkpointPlan.setCheckpointValueVisibility("WORKER_INTERNAL_AND_SYNC_CHECKPOINT_TABLE_ONLY");
        return checkpointPlan;
    }

    private DatasourceRunOnceRequest.RuntimeControlPlan runtimeControlPlan(SyncBatchRunnerBridgePlan bridgePlan,
                                                                          SyncExecution execution,
                                                                          String shardOrPartition) {
        DatasourceRunOnceRequest.RuntimeControlPlan runtimeControlPlan = new DatasourceRunOnceRequest.RuntimeControlPlan();
        runtimeControlPlan.setExecutorId(execution.getExecutorId());
        runtimeControlPlan.setLeaseExpireAt(execution.getLeaseExpireTime());
        runtimeControlPlan.setHeartbeatRequired(true);
        runtimeControlPlan.setTimeoutSeconds(properties.getDefaultTimeoutSeconds());
        runtimeControlPlan.setMaxRetryCount(properties.getDefaultMaxRetryCount());
        String idempotencyScope = "task:" + bridgePlan.getSyncTaskId() + ":execution:" + bridgePlan.getExecutionId();
        if (hasText(shardOrPartition)) {
            idempotencyScope = idempotencyScope + ":shard:" + shardOrPartition;
        }
        runtimeControlPlan.setIdempotencyScope(idempotencyScope);
        runtimeControlPlan.setRequiredCallbacks(List.of("COMPLETE_OR_FAIL"));
        return runtimeControlPlan;
    }

    /**
     * 按 DataX-style 批次模型循环调用 Java Reader/Writer 执行面。
     *
     * <p>DataX 的核心不是让 Python 搬运数据，而是控制面拆 Job、Java Reader/Writer 执行数据抽取和写入。
     * 本项目当前也保持这个边界：data-sync 负责派发、累计计数和终态回写；datasource-management 执行每个受控批次。</p>
     */
    private SyncBatchRunOnceRemoteExecutionResult dispatchDataXStyleBatchLoop(SyncTask task,
                                                                              SyncExecution execution,
                                                                              SyncActorContext actorContext,
                                                                              DatasourceRunOnceRequest request) {
        int maxBatches = Math.max(1, properties.getMaxRunOnceBatches());
        DatasourceRunOnceResponse lastResponse = null;
        for (int batchIndex = 1; batchIndex <= maxBatches; batchIndex++) {
            DatasourceRunOnceResponse response = datasourceRunOnceClient.runOnce(request, actorContext);
            lastResponse = response;
            SyncBatchRunOnceRemoteExecutionResult terminal = handleRemoteResponse(task, execution, actorContext, response);
            if (terminal != null) {
                return terminal;
            }
            if (!hasForwardProgress(request, response)) {
                return failAfterRemoteResult(task, execution, actorContext,
                        "REMOTE_RUN_ONCE_NO_FORWARD_PROGRESS",
                        "datasource-management run-once 返回仍有后续批次，但累计计数未推进，已按 fail-closed 终止以避免重复读写",
                        response == null ? null : response.getRunStatus());
            }
            request.setPreviousRecordsRead(zeroIfNull(response.getTotalRecordsRead()));
            request.setPreviousRecordsWritten(zeroIfNull(response.getTotalRecordsWritten()));
            request.setPreviousFailedRecordCount(zeroIfNull(response.getTotalFailedRecordCount()));
        }
        return failAfterRemoteResult(task, execution, actorContext,
                "MAX_RUN_ONCE_BATCHES_EXCEEDED",
                "datasource-management run-once 连续批次数超过安全上限，已按 fail-closed 终止；请调大批大小或切换专用离线 Runner",
                lastResponse == null ? null : lastResponse.getRunStatus());
    }

    /**
     * 将远端 run-once 结果转换为 data-sync 生命周期动作；返回 null 表示应继续下一批。
     */
    private SyncBatchRunOnceRemoteExecutionResult handleRemoteResponse(SyncTask task,
                                                                       SyncExecution execution,
                                                                       SyncActorContext actorContext,
                                                                       DatasourceRunOnceResponse response) {
        if (response == null) {
            return failAfterRemoteResult(task, execution, actorContext,
                    "REMOTE_RUN_ONCE_EMPTY_RESPONSE",
                    "datasource-management run-once 返回空执行摘要，本次执行按 fail-closed 终止",
                    null);
        }
        if (Boolean.TRUE.equals(response.getFailed()) || Boolean.TRUE.equals(response.getFailCallbackRecommended())) {
            return failAfterRemoteResult(task, execution, actorContext,
                    firstText(response.getRunStatus(), "REMOTE_RUN_ONCE_FAILED"),
                    "datasource-management run-once 报告执行失败，本次执行已回写 fail",
                    response.getRunStatus());
        }
        if (Boolean.TRUE.equals(response.getCompleteCallbackRecommended())) {
            return remoteResult(true, true, false, "DISPATCHED_AND_COMPLETED",
                    execution.getId(), response.getRunStatus(),
                    zeroIfNull(response.getTotalRecordsRead()),
                    zeroIfNull(response.getTotalRecordsWritten()),
                    zeroIfNull(response.getTotalFailedRecordCount()),
                    null,
                    null,
                    null,
                    false,
                    List.of());
        }
        if (Boolean.TRUE.equals(response.getProgressCallbackRecommended()) || !Boolean.TRUE.equals(response.getEndOfSource())) {
            return null;
        }
        return failAfterRemoteResult(task, execution, actorContext,
                "RUN_ONCE_CALLBACK_DECISION_MISSING",
                "datasource-management run-once 未给出 complete/fail/progress 决策，本次执行按 fail-closed 终止",
                response.getRunStatus());
    }

    /**
     * 判断连续批次是否真的向前推进。
     *
     * <p>这里只依赖低敏计数，不依赖行样本、SQL、主键值或 checkpoint 原始值；
     * 若远端持续未结束但累计计数不变，必须终止以防重复写入。</p>
     */
    private boolean hasForwardProgress(DatasourceRunOnceRequest request, DatasourceRunOnceResponse response) {
        if (response == null) {
            return false;
        }
        return zeroIfNull(response.getTotalRecordsRead()) > zeroIfNull(request.getPreviousRecordsRead())
                || zeroIfNull(response.getTotalRecordsWritten()) > zeroIfNull(request.getPreviousRecordsWritten())
                || zeroIfNull(response.getTotalFailedRecordCount()) > zeroIfNull(request.getPreviousFailedRecordCount());
    }

    private SyncBatchRunOnceRemoteExecutionResult failBeforeRemote(SyncTask task,
                                                                   SyncExecution execution,
                                                                   SyncActorContext actorContext,
                                                                   String errorType,
                                                                   String errorCode,
                                                                   String errorMessage,
                                                                   List<String> issueCodes) {
        return remoteResult(false, false, true, "FAILED_BEFORE_REMOTE_CALL",
                execution.getId(), null, 0L, 0L, 0L, errorType, errorCode, errorMessage, false, issueCodes);
    }

    private SyncBatchRunOnceRemoteExecutionResult failAfterRemoteCallException(SyncTask task,
                                                                               SyncExecution execution,
                                                                               SyncActorContext actorContext,
                                                                               PlatformBusinessException exception) {
        return remoteResult(true, false, true, "DISPATCHED_AND_FAILED_BY_CLIENT_EXCEPTION",
                execution.getId(), null, 0L, 0L, 0L,
                "CONNECTOR_RUNTIME_RUN_ONCE_CALL_FAILED",
                "DATASOURCE_RUN_ONCE_UNAVAILABLE",
                "datasource-management run-once 调用不可用，本次执行已按 fail-closed 终止",
                false,
                List.of("DATASOURCE_RUN_ONCE_UNAVAILABLE"));
    }

    private SyncBatchRunOnceRemoteExecutionResult failAfterRemoteResult(SyncTask task,
                                                                        SyncExecution execution,
                                                                        SyncActorContext actorContext,
                                                                        String errorCode,
                                                                        String errorMessage,
                                                                        String remoteRunStatus) {
        return remoteResult(true, false, true, "DISPATCHED_AND_FAILED_BY_REMOTE_RESULT",
                execution.getId(), remoteRunStatus, 0L, 0L, 0L,
                "CONNECTOR_RUNTIME_RUN_ONCE_FAILED", errorCode, errorMessage, false, List.of(errorCode));
    }

    private void completeExecution(SyncTask task,
                                   SyncExecution execution,
                                   SyncActorContext actorContext,
                                   DatasourceRunOnceResponse response) {
        SyncExecutionCompleteRequest request = new SyncExecutionCompleteRequest();
        request.setExecutorId(execution.getExecutorId());
        request.setRecordsRead(zeroIfNull(response.getTotalRecordsRead()));
        request.setRecordsWritten(zeroIfNull(response.getTotalRecordsWritten()));
        request.setCheckpointRef(null);
        request.setIdempotencyKey("datasource-run-once-complete-" + execution.getId());
        lifecycleSupport.completeExecution(task, execution, request, actorContext);
        receiptPublisher.publishComplete(task, execution, actorContext, response);
    }

    private void failExecution(SyncTask task,
                               SyncExecution execution,
                               SyncActorContext actorContext,
                               String errorType,
                               String errorCode,
                               String errorMessage,
                               boolean retryable) {
        SyncExecutionFailRequest request = new SyncExecutionFailRequest();
        request.setExecutorId(execution.getExecutorId());
        request.setErrorType(errorType);
        request.setErrorCode(errorCode);
        request.setErrorMessage(errorMessage);
        request.setSourceRecordKey(null);
        request.setTargetRecordKey(null);
        request.setSamplePayload(null);
        request.setRetryable(retryable);
        request.setIdempotencyKey("datasource-run-once-fail-" + execution.getId() + "-" + errorCode);
        lifecycleSupport.failExecution(task, execution, request, actorContext);
        receiptPublisher.publishFailed(task, execution, actorContext, errorCode, List.of(errorCode));
    }

    private List<String> readCapabilities(SyncBatchRunnerBridgePlan bridgePlan, String shardOrPartition) {
        List<String> capabilities = new ArrayList<>();
        capabilities.add("JDBC_BATCH_READ");
        if (hasText(shardOrPartition)) {
            /*
             * 分片读并不是新的连接器类型，而是同一套 JDBC Reader 在 WHERE 条件上额外叠加受控 range。
             * 这里显式声明 PARTITION_AWARE_READ，便于 datasource-management、审计和后续真实专用 Runner
             * 区分“普通单表全量读”和“单表大数据量分片读”，同时不把真实边界值放入 capability。
             */
            capabilities.add("PARTITION_AWARE_READ");
        }
        if (!FULL_CHECKPOINT_TYPE.equals(bridgePlan.getCheckpointType())) {
            capabilities.add("CHECKPOINT_AWARE_READ");
        }
        return List.copyOf(capabilities);
    }

    private List<String> writeCapabilities(SyncBatchRunnerBridgePlan bridgePlan) {
        List<String> capabilities = new ArrayList<>();
        capabilities.add("JDBC_BATCH_WRITE");
        if ("UPSERT".equals(bridgePlan.getWriteStrategy())
                || "INSERT_IGNORE".equals(bridgePlan.getWriteStrategy())
                || "REPLACE".equals(bridgePlan.getWriteStrategy())) {
            capabilities.add("IDEMPOTENT_CONFLICT_WRITE");
        }
        return List.copyOf(capabilities);
    }

    private String conflictPolicy(String writeStrategy) {
        if ("UPSERT".equals(writeStrategy)) {
            return "UPDATE_ON_CONFLICT";
        }
        if ("INSERT_IGNORE".equals(writeStrategy)) {
            return "IGNORE_ON_CONFLICT";
        }
        if ("REPLACE".equals(writeStrategy)) {
            return "REPLACE_ON_CONFLICT";
        }
        return "NO_CONFLICT_HANDLING";
    }

    /**
     * 把远端低敏结果转换为 receipt 发布器可以复用的 datasource-management 响应镜像。
     *
     * <p>这里不会补任何 SQL、对象名、字段名或样本，只把计数和状态映射回原有 receipt 发布入口，
     * 避免为了 fan-out 再实现一套 task-management 回执协议。</p>
     */
    private DatasourceRunOnceResponse responseFromRemoteResult(SyncBatchRunOnceRemoteExecutionResult remoteResult) {
        DatasourceRunOnceResponse response = new DatasourceRunOnceResponse();
        response.setRunStatus(remoteResult.remoteRunStatus());
        response.setBatchRecordsRead(zeroIfNull(remoteResult.totalRecordsRead()));
        response.setBatchRecordsWritten(zeroIfNull(remoteResult.totalRecordsWritten()));
        response.setBatchFailedRecordCount(zeroIfNull(remoteResult.totalFailedRecordCount()));
        response.setTotalRecordsRead(zeroIfNull(remoteResult.totalRecordsRead()));
        response.setTotalRecordsWritten(zeroIfNull(remoteResult.totalRecordsWritten()));
        response.setTotalFailedRecordCount(zeroIfNull(remoteResult.totalFailedRecordCount()));
        response.setEndOfSource(Boolean.TRUE);
        response.setFailed(Boolean.FALSE);
        response.setCompleteCallbackRecommended(Boolean.TRUE);
        response.setFailCallbackRecommended(Boolean.FALSE);
        response.setProgressCallbackRecommended(Boolean.FALSE);
        response.setCheckpointCandidateProduced(Boolean.FALSE);
        response.setPayloadPolicy(SyncBatchRunOnceRemoteExecutionResult.PAYLOAD_POLICY);
        return response;
    }

    private SyncBatchRunOnceRemoteExecutionResult remoteResult(boolean remoteCalled,
                                                              boolean completed,
                                                              boolean failed,
                                                              String dispatchStatus,
                                                              Long executionId,
                                                              String remoteRunStatus,
                                                              Long totalRecordsRead,
                                                              Long totalRecordsWritten,
                                                              Long totalFailedRecordCount,
                                                              String errorType,
                                                              String errorCode,
                                                              String errorMessage,
                                                              boolean retryable,
                                                              List<String> issueCodes) {
        return new SyncBatchRunOnceRemoteExecutionResult(remoteCalled, completed, failed, dispatchStatus, executionId,
                remoteRunStatus, totalRecordsRead, totalRecordsWritten, totalFailedRecordCount, errorType, errorCode,
                errorMessage, retryable, issueCodes, SyncBatchRunOnceRemoteExecutionResult.PAYLOAD_POLICY);
    }

    private SyncBatchRunOnceDispatchResult result(boolean dispatched,
                                                 boolean completed,
                                                 boolean failed,
                                                 String dispatchStatus,
                                                 Long executionId,
                                                 String remoteRunStatus,
                                                 List<String> issueCodes) {
        return new SyncBatchRunOnceDispatchResult(dispatched, completed, failed, dispatchStatus, executionId,
                remoteRunStatus, List.copyOf(issueCodes), SyncBatchRunOnceDispatchResult.PAYLOAD_POLICY);
    }

    private List<String> withIssue(List<String> issueCodes, String issueCode) {
        List<String> merged = new ArrayList<>(issueCodes);
        merged.add(issueCode);
        return List.copyOf(merged);
    }

    private Long fallbackActorId(SyncExecution execution) {
        return execution.getTriggeredBy() == null ? 0L : execution.getTriggeredBy();
    }

    private Long zeroIfNull(Long value) {
        return value == null ? 0L : value;
    }

    private String firstOrNull(List<String> values) {
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    private String firstText(String value, String fallback) {
        return hasText(value) ? value : fallback;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
