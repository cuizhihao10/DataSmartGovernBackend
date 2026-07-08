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
import org.springframework.beans.factory.annotation.Autowired;
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
public class SyncOfflineRunnerDispatchService {

    private static final String OFFLINE_RUNNER_CONTRACT_MISSING = "OFFLINE_RUNNER_CONTRACT_MISSING";
    private static final String OFFLINE_RUNNER_CONTRACT_BLOCKED = "OFFLINE_RUNNER_CONTRACT_BLOCKED";
    private static final String OFFLINE_RUNNER_APPROVAL_REQUIRED = "OFFLINE_RUNNER_APPROVAL_REQUIRED";
    private static final String OFFLINE_RUNNER_CHECKPOINT_HANDOFF_REQUIRED =
            "OFFLINE_RUNNER_CHECKPOINT_HANDOFF_REQUIRED";
    private static final String DEDICATED_OFFLINE_RUNNER_REQUIRED = "DEDICATED_OFFLINE_RUNNER_REQUIRED";
    private static final String OFFLINE_RUNNER_ADAPTER_DISPATCH_FAILED = "OFFLINE_RUNNER_ADAPTER_DISPATCH_FAILED";
    private static final String OFFLINE_RUNNER_ADAPTER_RESULT_EMPTY = "OFFLINE_RUNNER_ADAPTER_RESULT_EMPTY";
    private static final String OFFLINE_RUNNER_POLICY_NOT_READY = "OFFLINE_RUNNER_POLICY_NOT_READY";
    private static final String USE_REALTIME_CDC_PIPELINE = "USE_REALTIME_CDC_PIPELINE";

    private final SyncBatchRunnerBridgePlanSupport bridgePlanSupport;
    private final SyncBatchRunOnceDispatchService runOnceDispatchService;
    private final SyncOfflineRunnerAdapterRegistry runnerAdapterRegistry;
    private final SyncObjectListFanOutDispatchService objectListFanOutDispatchService;
    private final SyncDiscoveredObjectFanOutDispatchService discoveredObjectFanOutDispatchService;
    private final SyncPartitionShardFanOutDispatchService partitionShardFanOutDispatchService;
    private final SyncExecutionLifecycleSupport lifecycleSupport;
    private final DataSyncTaskManagementReceiptPublisher receiptPublisher;
    private final SyncExecutionLogSupport executionLogSupport;

    /**
     * Spring 生产运行时使用的完整构造器。
     *
     * <p>这里显式使用 {@link Autowired}，而不是继续依赖 Lombok 自动生成构造器，是为了解决“生产构造器”和
     * “历史单元测试兼容构造器”同时存在时的 Spring 注入歧义。Spring 在发现多个构造器且没有明确注入点时，
     * 会尝试寻找无参构造器；本类全部依赖都是 final 字段，不应该也不能提供无参构造器，否则会把关键执行组件置空。
     * 因此生产构造器必须显式标注，告诉 Spring：运行时应注入包含 discoveredObject fan-out 的完整依赖集合。</p>
     *
     * <p>{@code discoveredObjectFanOutDispatchService} 用于 SCHEMA_FULL / DATABASE_FULL：
     * data-sync 不直接连接源库，而是通过 datasource-management 的元数据发现生成对象清单，再复用 OBJECT_LIST/fan-out
     * 执行链路。把它纳入生产构造器，可以保证真实容器启动时不会退化成“只支持单对象 run-once”。</p>
     */
    @Autowired
    public SyncOfflineRunnerDispatchService(SyncBatchRunnerBridgePlanSupport bridgePlanSupport,
                                            SyncBatchRunOnceDispatchService runOnceDispatchService,
                                            SyncOfflineRunnerAdapterRegistry runnerAdapterRegistry,
                                            SyncObjectListFanOutDispatchService objectListFanOutDispatchService,
                                            SyncDiscoveredObjectFanOutDispatchService discoveredObjectFanOutDispatchService,
                                            SyncPartitionShardFanOutDispatchService partitionShardFanOutDispatchService,
                                            SyncExecutionLifecycleSupport lifecycleSupport,
                                            DataSyncTaskManagementReceiptPublisher receiptPublisher,
                                            SyncExecutionLogSupport executionLogSupport) {
        this.bridgePlanSupport = bridgePlanSupport;
        this.runOnceDispatchService = runOnceDispatchService;
        this.runnerAdapterRegistry = runnerAdapterRegistry;
        this.objectListFanOutDispatchService = objectListFanOutDispatchService;
        this.discoveredObjectFanOutDispatchService = discoveredObjectFanOutDispatchService;
        this.partitionShardFanOutDispatchService = partitionShardFanOutDispatchService;
        this.lifecycleSupport = lifecycleSupport;
        this.receiptPublisher = receiptPublisher;
        this.executionLogSupport = executionLogSupport;
    }

    /**
     * 兼容旧单元测试的构造器。
     *
     * <p>新增 discoveredObject fan-out 后，Spring 运行时会使用 Lombok 生成的完整构造器注入真实 Bean；
     * 但部分历史测试直接 new 本类且不关注 SCHEMA_FULL/DATABASE_FULL。这里允许传入 null discovered 服务，
     * 调度时会跳过该路径，避免为了新能力大面积改动无关测试。</p>
     */
    public SyncOfflineRunnerDispatchService(SyncBatchRunnerBridgePlanSupport bridgePlanSupport,
                                            SyncBatchRunOnceDispatchService runOnceDispatchService,
                                            SyncOfflineRunnerAdapterRegistry runnerAdapterRegistry,
                                            SyncObjectListFanOutDispatchService objectListFanOutDispatchService,
                                            SyncPartitionShardFanOutDispatchService partitionShardFanOutDispatchService,
                                            SyncExecutionLifecycleSupport lifecycleSupport,
                                            DataSyncTaskManagementReceiptPublisher receiptPublisher) {
        this(bridgePlanSupport,
                runOnceDispatchService,
                runnerAdapterRegistry,
                objectListFanOutDispatchService,
                null,
                partitionShardFanOutDispatchService,
                lifecycleSupport,
                receiptPublisher,
                null);
    }

    /**
     * 兼容已经传入 discoveredObject fan-out、但尚未注入运行日志组件的测试构造器。
     *
     * <p>真实应用启动不会走这个构造器，因为完整生产构造器已经标注 {@link Autowired}。
     * 这里的存在只是为了降低本次“运行日志能力”对历史单元测试的侵入性。</p>
     */
    public SyncOfflineRunnerDispatchService(SyncBatchRunnerBridgePlanSupport bridgePlanSupport,
                                            SyncBatchRunOnceDispatchService runOnceDispatchService,
                                            SyncOfflineRunnerAdapterRegistry runnerAdapterRegistry,
                                            SyncObjectListFanOutDispatchService objectListFanOutDispatchService,
                                            SyncDiscoveredObjectFanOutDispatchService discoveredObjectFanOutDispatchService,
                                            SyncPartitionShardFanOutDispatchService partitionShardFanOutDispatchService,
                                            SyncExecutionLifecycleSupport lifecycleSupport,
                                            DataSyncTaskManagementReceiptPublisher receiptPublisher) {
        this(bridgePlanSupport,
                runOnceDispatchService,
                runnerAdapterRegistry,
                objectListFanOutDispatchService,
                discoveredObjectFanOutDispatchService,
                partitionShardFanOutDispatchService,
                lifecycleSupport,
                receiptPublisher,
                null);
    }

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
        recordRunnerEvent(task, execution, safeActorContext,
                "PLAN",
                bridgePlan.isDispatchable() ? "INFO" : "WARN",
                "OFFLINE_RUNNER_CONTRACT_EVALUATED",
                bridgePlan.isDispatchable() ? "SUCCEEDED" : "BLOCKED",
                "离线 Runner 合同已完成评估",
                "dispatchStatus=" + bridgePlan.getDispatchStatus()
                        + ", contractStatus=" + (contract == null ? "MISSING" : contract.contractStatus())
                        + ", syncMode=" + bridgePlan.getSyncMode()
                        + ", syncScopeType=" + (contract == null ? "-" : contract.syncScopeType())
                        + ", minimalBridgeSupported=" + (contract != null && contract.minimalBridgeEndToEndSupported())
                        + ", dedicatedRunnerRequired=" + (contract != null && contract.dedicatedOfflineRunnerRequired()));

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
        if (discoveredObjectFanOutDispatchService != null
                && discoveredObjectFanOutDispatchService.supports(contract, safeActorContext)) {
            recordRunnerEvent(task, execution, safeActorContext,
                    "PLAN",
                    "INFO",
                    "OFFLINE_RUNNER_ROUTE_DISCOVERED_OBJECT_FAN_OUT",
                    "STARTED",
                    "全 schema/全库任务将先发现对象清单，再进入对象级 fan-out 执行",
                    "contractStatus=" + contract.contractStatus());
            return discoveredObjectFanOutDispatchService.dispatchDiscoveredObjects(execution, task, template, workerPlan,
                    safeActorContext, contract);
        }
        if (objectListFanOutDispatchService.supports(contract, safeActorContext)) {
            recordRunnerEvent(task, execution, safeActorContext,
                    "PLAN",
                    "INFO",
                    "OFFLINE_RUNNER_ROUTE_OBJECT_LIST_FAN_OUT",
                    "STARTED",
                    "多对象同步将按对象账本逐个执行，失败对象可选择性重试",
                    "contractStatus=" + contract.contractStatus());
            return objectListFanOutDispatchService.dispatchObjectList(execution, task, template, workerPlan,
                    safeActorContext, contract);
        }
        if (partitionShardFanOutDispatchService.supports(contract, template, safeActorContext)) {
            /*
             * partitionConfig 一旦声明，就不能继续落到普通 minimal run-once。
             * 否则用户配置了“大表分片”，系统却仍按单通道全表扫描执行，会造成性能误判和恢复语义缺失。
             * 分片合同即使解析失败，也由分片 fan-out 服务负责 fail-closed，避免静默忽略配置错误。
             */
            recordRunnerEvent(task, execution, safeActorContext,
                    "PLAN",
                    "INFO",
                    "OFFLINE_RUNNER_ROUTE_PARTITION_SHARD_FAN_OUT",
                    "STARTED",
                    "单表大数据量同步将进入 splitPk 分片 fan-out 执行",
                    "contractStatus=" + contract.contractStatus());
            return partitionShardFanOutDispatchService.dispatchPartitionShards(execution, task, template, workerPlan,
                    safeActorContext, contract);
        }
        if (Boolean.TRUE.equals(safeActorContext.approvalRequired())) {
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
            SyncOfflineRunnerAdapter dedicatedRunnerAdapter = runnerAdapterRegistry.select(contract).orElse(null);
            if (dedicatedRunnerAdapter != null) {
                return dispatchDedicatedRunner(dedicatedRunnerAdapter, task, execution, template, workerPlan,
                        safeActorContext, bridgePlan, contract);
            }
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
     * 将合同派发给专用离线 Runner adapter。
     *
     * <p>这是 data-sync 从“只能识别需要专用 Runner”走向“可以真正接入专用 Runner”的最小执行循环。
     * 当前仓库还没有注册真实 DataX adapter 时，本方法不会被调用；一旦后续新增 adapter Bean，
     * 调度门面就会把 bridge plan、低敏 Runner 合同、execution、task、template 和 actor 上下文统一交给 adapter。</p>
     *
     * <p>这里不直接做 DataX job 拼装，原因有两个：</p>
     * <p>1. 拼装 reader/writer、并发、限流、checkpoint 和回调协议属于具体 runner 实现，不能写死在调度门面；</p>
     * <p>2. 调度门面应该保持稳定，只负责选择执行路径、处理 adapter 异常、转换低敏结果和 fail-closed。</p>
     */
    private SyncOfflineRunnerDispatchResult dispatchDedicatedRunner(SyncOfflineRunnerAdapter adapter,
                                                                    SyncTask task,
                                                                    SyncExecution execution,
                                                                    SyncTemplate template,
                                                                    SyncWorkerExecutionPlanView workerPlan,
                                                                    SyncActorContext actorContext,
                                                                    SyncBatchRunnerBridgePlan bridgePlan,
                                                                    SyncOfflineRunnerJobContract contract) {
        SyncOfflineRunnerExecutionRequest request = new SyncOfflineRunnerExecutionRequest(
                bridgePlan, contract, execution, task, template, workerPlan, actorContext);
        try {
            SyncOfflineRunnerAdapterResult adapterResult = adapter.dispatch(request);
            if (adapterResult == null) {
                return failBeforeDelegate(task, execution, actorContext,
                        "DEDICATED_RUNNER_RESULT_EMPTY",
                        contract.contractStatus(),
                        OFFLINE_RUNNER_ADAPTER_RESULT_EMPTY,
                        "专用离线 Runner adapter 未返回低敏派发结果，本次执行按 fail-closed 终止",
                        mergeIssues(bridgePlan, contract, OFFLINE_RUNNER_ADAPTER_RESULT_EMPTY),
                        contract);
            }
            return fromDedicatedRunnerResult(adapterResult, contract);
        } catch (RuntimeException exception) {
            return failBeforeDelegate(task, execution, actorContext,
                    "DEDICATED_RUNNER_DISPATCH_FAILED",
                    contract.contractStatus(),
                    OFFLINE_RUNNER_ADAPTER_DISPATCH_FAILED,
                    "专用离线 Runner adapter 派发失败，本次执行按低敏 fail-closed 终止，异常详情请查看受控服务端日志",
                    mergeIssues(bridgePlan, contract, OFFLINE_RUNNER_ADAPTER_DISPATCH_FAILED),
                    contract);
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
        recordRunnerEvent(task, execution, actorContext,
                "PLAN",
                "ERROR",
                "OFFLINE_RUNNER_FAIL_CLOSED",
                "FAILED",
                "离线 Runner 合同无法安全执行，系统已阻断真实读写",
                "dispatchStatus=" + dispatchStatus
                        + ", contractStatus=" + contractStatus
                        + ", errorCode=" + errorCode
                        + ", issueCodes=" + issueCodes);
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
     * 将专用 Runner adapter 结果转换为 worker loop 能理解的低敏调度结果。
     *
     * <p>注意：adapter 返回 {@code dispatched=true} 只表示专用 Runner 已经接收或排队任务，
     * 不等同于数据同步已完成。对于异步 DataX-style Runner，后续应该通过 callback、Kafka 事件或执行报告继续推进
     * complete/fail/checkpoint，而不是在 worker loop 本次调用里假装同步完成。</p>
     */
    private SyncOfflineRunnerDispatchResult fromDedicatedRunnerResult(SyncOfflineRunnerAdapterResult adapterResult,
                                                                      SyncOfflineRunnerJobContract contract) {
        return new SyncOfflineRunnerDispatchResult(
                adapterResult.dispatched(),
                adapterResult.completed(),
                adapterResult.failed(),
                adapterResult.dispatchStatus(),
                adapterResult.executionId() == null ? contract.executionId() : adapterResult.executionId(),
                adapterResult.runnerStatus(),
                contract.contractStatus(),
                mergeIssueCodes(adapterResult.issueCodes(), contract.issueCodes(), adapterResult.adapterCode()),
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
        if (hasText(issueCode)) {
            values.add(issueCode);
        }
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

    /**
     * 记录离线 Runner 门面的路径选择日志。
     *
     * <p>该方法允许 executionLogSupport 为空，是为了兼容历史单元测试中直接调用旧构造器 new 本类的场景。
     * Spring 生产运行时会注入真实日志组件，因此不会丢失运行日志。</p>
     */
    private void recordRunnerEvent(SyncTask task,
                                   SyncExecution execution,
                                   SyncActorContext actorContext,
                                   String stage,
                                   String level,
                                   String eventType,
                                   String status,
                                   String message,
                                   String detailSummary) {
        if (executionLogSupport == null) {
            return;
        }
        executionLogSupport.recordExecutionEvent(task, execution, actorContext,
                stage, level, eventType, status, message, detailSummary);
    }
}
