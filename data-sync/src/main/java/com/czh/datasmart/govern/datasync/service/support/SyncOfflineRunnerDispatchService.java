/**
 * @Author : Cui
 * @Date: 2026/07/05 14:42
 * @Description DataSmart Govern Backend - SyncOfflineRunnerDispatchService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExecutionFailRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncWorkerExecutionPlanView;
import com.czh.datasmart.govern.datasync.entity.SyncExecution;
import com.czh.datasmart.govern.datasync.entity.SyncTask;
import com.czh.datasmart.govern.datasync.entity.SyncTemplate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * DataX-style 离线 Runner 调度门面。
 *
 * <p>上一阶段已经把离线作业计划升级为 {@link SyncOfflineRunnerJobContract}。本类是在执行链路中消费该合同的第一层：
 * worker loop 不再直接把所有已 claim 的任务交给最小 run-once，而是先通过本类判断“当前合同应该走哪里”。</p>
 *
 * <p>当前阶段的真实执行能力仍然收敛在最小 run-once：</p>
 * <p>1. 如果合同说明 {@code minimalBridgeEndToEndSupported=true}，本类把已准备好的 bridge plan 交给
 * {@link SyncBatchRunOnceDispatchService#dispatchPreparedRunOnce(SyncBatchRunnerBridgePlan, SyncExecution, SyncTask, SyncActorContext)}；</p>
 * <p>2. 如果合同需要审批、checkpoint handoff、对象 fan-out、自定义 SQL 托管、专用 Runner 或实时 CDC，本类不会调用真实读写，
 * 而是立即把 execution 低敏 fail，避免任务长期停留在 RUNNING；</p>
 * <p>3. 后续真正接入独立 DataX-style Runner 时，可以在本类中新增“专用 Runner adapter”，而不用改 worker loop、lease、
 * execution lifecycle 或 task-management receipt 语义。</p>
 *
 * <p>这就是“合同驱动执行循环”的意义：执行入口由合同状态决定，而不是由散落在各处的 if/else 猜测任务类型。</p>
 */
@Component
@RequiredArgsConstructor
public class SyncOfflineRunnerDispatchService {

    private static final String OFFLINE_RUNNER_CONTRACT_MISSING = "OFFLINE_RUNNER_CONTRACT_MISSING";
    private static final String OFFLINE_RUNNER_CONTRACT_BLOCKED = "OFFLINE_RUNNER_CONTRACT_BLOCKED";
    private static final String OFFLINE_RUNNER_APPROVAL_REQUIRED = "OFFLINE_RUNNER_APPROVAL_REQUIRED";
    private static final String OFFLINE_RUNNER_CHECKPOINT_HANDOFF_REQUIRED =
            "OFFLINE_RUNNER_CHECKPOINT_HANDOFF_REQUIRED";
    private static final String DEDICATED_OFFLINE_RUNNER_REQUIRED = "DEDICATED_OFFLINE_RUNNER_REQUIRED";
    private static final String OFFLINE_RUNNER_POLICY_NOT_READY = "OFFLINE_RUNNER_POLICY_NOT_READY";
    private static final String USE_REALTIME_CDC_PIPELINE = "USE_REALTIME_CDC_PIPELINE";

    private final SyncBatchRunnerBridgePlanSupport bridgePlanSupport;
    private final SyncBatchRunOnceDispatchService runOnceDispatchService;
    private final SyncExecutionLifecycleSupport lifecycleSupport;
    private final DataSyncTaskManagementReceiptPublisher receiptPublisher;

    /**
     * 按离线 Runner 合同调度一次已 claim 的 execution。
     *
     * @param execution 当前已被 worker claim 的执行记录，通常处于 RUNNING。
     * @param task execution 所属任务。
     * @param template 任务关联模板。
     * @param workerPlan claim 阶段返回的低敏 worker 计划。
     * @param actorContext 当前操作者或服务账号上下文。
     * @return 低敏调度结果。
     */
    public SyncOfflineRunnerDispatchResult dispatchOffline(SyncExecution execution,
                                                           SyncTask task,
                                                           SyncTemplate template,
                                                           SyncWorkerExecutionPlanView workerPlan,
                                                           SyncActorContext actorContext) {
        requireDispatchInputs(execution, task, template, workerPlan);
        SyncActorContext safeActorContext = ensureActorContext(execution, task, actorContext);
        SyncBatchRunnerBridgePlan bridgePlan = bridgePlanSupport.buildPlan(execution, task, template, workerPlan);
        SyncOfflineRunnerJobContract contract = bridgePlan.getOfflineRunnerContract();

        if (contract == null) {
            return failBeforeDelegate(task, execution, safeActorContext,
                    "FAILED_BEFORE_RUNNER_SELECTION",
                    "OFFLINE_RUNNER_CONTRACT_MISSING",
                    OFFLINE_RUNNER_CONTRACT_MISSING,
                    "离线 Runner 合同缺失，本次执行未触发真实读写",
                    bridgePlan.getIssueCodes(),
                    null);
        }
        if (!contract.offlineChannel()) {
            return failBeforeDelegate(task, execution, safeActorContext,
                    "FAILED_BEFORE_RUNNER_SELECTION",
                    contract.contractStatus(),
                    USE_REALTIME_CDC_PIPELINE,
                    "当前任务属于实时 CDC 通道，应进入 Debezium/Kafka Connect pipeline，不能由离线 Runner 执行",
                    mergeIssues(bridgePlan, contract, USE_REALTIME_CDC_PIPELINE),
                    contract);
        }
        if (contract.approvalRequired()) {
            return failBeforeDelegate(task, execution, safeActorContext,
                    "WAITING_APPROVAL_BEFORE_RUNNER_DISPATCH",
                    contract.contractStatus(),
                    OFFLINE_RUNNER_APPROVAL_REQUIRED,
                    "离线 Runner 合同要求审批或人工确认，本次执行按 fail-closed 终止",
                    mergeIssues(bridgePlan, contract, OFFLINE_RUNNER_APPROVAL_REQUIRED),
                    contract);
        }
        if (!bridgePlan.isDispatchable() || !contract.planReady()) {
            return failBeforeDelegate(task, execution, safeActorContext,
                    "FAILED_BEFORE_RUNNER_SELECTION",
                    contract.contractStatus(),
                    OFFLINE_RUNNER_CONTRACT_BLOCKED,
                    "离线 Runner 合同或 bridge plan 被阻断，本次执行未触发真实读写",
                    mergeIssues(bridgePlan, contract, OFFLINE_RUNNER_CONTRACT_BLOCKED),
                    contract);
        }
        if (!contract.minimalBridgeEndToEndSupported()) {
            return failUnsupportedContractBeforeDelegate(task, execution, safeActorContext, bridgePlan, contract);
        }

        SyncBatchRunOnceDispatchResult runOnceResult =
                runOnceDispatchService.dispatchPreparedRunOnce(bridgePlan, execution, task, safeActorContext);
        return fromRunOnceResult(runOnceResult, contract);
    }

    /**
     * 校验调度所需上下文。
     *
     * <p>这里抛出参数错误而不是写 fail，是因为缺 execution/task/template/workerPlan 通常表示调用方编排错误，
     * 此时不一定有足够安全的业务上下文可以回写状态机。</p>
     */
    private void requireDispatchInputs(SyncExecution execution,
                                       SyncTask task,
                                       SyncTemplate template,
                                       SyncWorkerExecutionPlanView workerPlan) {
        if (execution == null || task == null || template == null || workerPlan == null) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR,
                    "离线 Runner 调度缺少 execution/task/template/workerPlan 上下文，无法安全推进状态机");
        }
    }

    /**
     * 对暂不支持的合同做低敏 fail。
     *
     * <p>这里把 checkpoint handoff、专用 Runner 和普通策略未就绪拆成不同错误码，是为了后续运营台能快速统计：
     * 到底是“需要补水位交接”、还是“需要真正专用 Runner”、还是“合同策略没有覆盖”。这比统一返回 BRIDGE_BLOCKED
     * 更适合商业化产品的故障分类和路线规划。</p>
     */
    private SyncOfflineRunnerDispatchResult failUnsupportedContractBeforeDelegate(SyncTask task,
                                                                                  SyncExecution execution,
                                                                                  SyncActorContext actorContext,
                                                                                  SyncBatchRunnerBridgePlan bridgePlan,
                                                                                  SyncOfflineRunnerJobContract contract) {
        if (contract.checkpointRequired()) {
            return failBeforeDelegate(task, execution, actorContext,
                    "CHECKPOINT_HANDOFF_REQUIRED_BEFORE_RUN_ONCE",
                    contract.contractStatus(),
                    OFFLINE_RUNNER_CHECKPOINT_HANDOFF_REQUIRED,
                    "离线 Runner 合同需要 checkpointRef/checkpointDigest 安全交接，当前最小 run-once 不能端到端完成",
                    mergeIssues(bridgePlan, contract, OFFLINE_RUNNER_CHECKPOINT_HANDOFF_REQUIRED),
                    contract);
        }
        if (contract.dedicatedOfflineRunnerRequired()) {
            return failBeforeDelegate(task, execution, actorContext,
                    "DEDICATED_RUNNER_REQUIRED_BEFORE_DISPATCH",
                    contract.contractStatus(),
                    DEDICATED_OFFLINE_RUNNER_REQUIRED,
                    "当前离线作业需要专用 DataX-style Runner，本次执行未交给最小 run-once",
                    mergeIssues(bridgePlan, contract, DEDICATED_OFFLINE_RUNNER_REQUIRED),
                    contract);
        }
        return failBeforeDelegate(task, execution, actorContext,
                "RUNNER_POLICY_NOT_READY",
                contract.contractStatus(),
                OFFLINE_RUNNER_POLICY_NOT_READY,
                "离线 Runner 合同尚未匹配到可执行策略，本次执行未触发真实读写",
                mergeIssues(bridgePlan, contract, OFFLINE_RUNNER_POLICY_NOT_READY),
                contract);
    }

    /**
     * 在不调用真实读写的情况下把 execution 置为失败。
     *
     * <p>错误请求不写 sourceRecordKey、targetRecordKey、samplePayload，也不写 SQL、对象名、字段列表或远端响应。
     * 这样既能让状态机闭环，又不会把敏感配置散落到审计、日志或任务回执里。</p>
     */
    private SyncOfflineRunnerDispatchResult failBeforeDelegate(SyncTask task,
                                                               SyncExecution execution,
                                                               SyncActorContext actorContext,
                                                               String dispatchStatus,
                                                               String contractStatus,
                                                               String errorCode,
                                                               String errorMessage,
                                                               List<String> issueCodes,
                                                               SyncOfflineRunnerJobContract contract) {
        SyncExecutionFailRequest request = new SyncExecutionFailRequest();
        request.setExecutorId(execution.getExecutorId());
        request.setErrorType("OFFLINE_RUNNER_CONTRACT_FAIL_CLOSED");
        request.setErrorCode(errorCode);
        request.setErrorMessage(errorMessage);
        request.setSourceRecordKey(null);
        request.setTargetRecordKey(null);
        request.setSamplePayload(null);
        request.setRetryable(false);
        request.setIdempotencyKey("offline-runner-fail-" + execution.getId() + "-" + errorCode);
        lifecycleSupport.failExecution(task, execution, request, actorContext);
        receiptPublisher.publishFailed(task, execution, actorContext, errorCode, List.of(errorCode));
        return new SyncOfflineRunnerDispatchResult(
                false,
                false,
                true,
                dispatchStatus,
                execution.getId(),
                null,
                contractStatus,
                mergeIssueCodes(issueCodes, contract == null ? List.of() : contract.issueCodes(), errorCode),
                SyncOfflineRunnerDispatchResult.PAYLOAD_POLICY
        );
    }

    /**
     * 将最小 run-once 结果包一层 Runner 合同状态。
     */
    private SyncOfflineRunnerDispatchResult fromRunOnceResult(SyncBatchRunOnceDispatchResult runOnceResult,
                                                             SyncOfflineRunnerJobContract contract) {
        if (runOnceResult == null) {
            return new SyncOfflineRunnerDispatchResult(
                    false,
                    false,
                    false,
                    "RUN_ONCE_RESULT_EMPTY",
                    contract.executionId(),
                    null,
                    contract.contractStatus(),
                    List.of("RUN_ONCE_DISPATCH_RESULT_EMPTY"),
                    SyncOfflineRunnerDispatchResult.PAYLOAD_POLICY
            );
        }
        return new SyncOfflineRunnerDispatchResult(
                runOnceResult.dispatched(),
                runOnceResult.completed(),
                runOnceResult.failed(),
                runOnceResult.dispatchStatus(),
                runOnceResult.executionId(),
                runOnceResult.remoteRunStatus(),
                contract.contractStatus(),
                runOnceResult.issueCodes(),
                SyncOfflineRunnerDispatchResult.PAYLOAD_POLICY
        );
    }

    /**
     * 补齐内部服务账号上下文。
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
                : "SERVICE_ACCOUNT";
        String traceId = actorContext != null && hasText(actorContext.traceId())
                ? actorContext.traceId()
                : "data-sync-offline-runner-dispatch";
        return new SyncActorContext(tenantId, actorId, actorRole, traceId,
                actorContext == null ? null : actorContext.dataScopeLevel(),
                actorContext == null ? null : actorContext.dataScopeExpression(),
                actorContext == null ? List.of() : actorContext.authorizedProjectIds(),
                actorContext != null && Boolean.TRUE.equals(actorContext.approvalRequired()));
    }

    private List<String> mergeIssues(SyncBatchRunnerBridgePlan bridgePlan,
                                     SyncOfflineRunnerJobContract contract,
                                     String issueCode) {
        List<String> values = new ArrayList<>();
        values.addAll(bridgePlan == null ? List.of() : bridgePlan.getIssueCodes());
        values.addAll(contract == null ? List.of() : contract.failClosedReasons());
        values.add(issueCode);
        return distinct(values);
    }

    private List<String> mergeIssueCodes(List<String> first, List<String> second, String issueCode) {
        List<String> values = new ArrayList<>();
        values.addAll(first == null ? List.of() : first);
        values.addAll(second == null ? List.of() : second);
        values.add(issueCode);
        return distinct(values);
    }

    private List<String> distinct(List<String> values) {
        return new ArrayList<>(new LinkedHashSet<>(values));
    }

    private Long fallbackActorId(SyncExecution execution) {
        return execution.getTriggeredBy() == null ? 0L : execution.getTriggeredBy();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
