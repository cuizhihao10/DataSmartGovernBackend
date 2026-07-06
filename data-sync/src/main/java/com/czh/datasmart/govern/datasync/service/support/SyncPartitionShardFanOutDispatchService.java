/**
 * @Author : Cui
 * @Date: 2026/07/07 23:52
 * @Description DataSmart Govern Backend - SyncPartitionShardFanOutDispatchService.java
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
import com.czh.datasmart.govern.datasync.integration.datasource.partition.DatasourcePartitionRangeProbeClient;
import com.czh.datasmart.govern.datasync.integration.datasource.partition.DatasourcePartitionRangeProbeRequest;
import com.czh.datasmart.govern.datasync.integration.datasource.partition.DatasourcePartitionRangeProbeResponse;
import com.czh.datasmart.govern.datasync.integration.datasource.runonce.DatasourceRunOnceResponse;
import com.czh.datasmart.govern.datasync.support.SyncObjectExecutionState;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 单表大数据量分片 fan-out 调度器。
 *
 * <p>这个组件回答用户刚才追问的核心问题：当一个离线同步任务的数据量非常大时，平台不能只把整张表当成
 * 一个不可拆分的大黑盒执行；更符合 DataX 思路的做法，是把一个大 Job 拆成多个更小的可恢复工作单元。
 * 对当前项目而言，OBJECT_LIST 已经解决了“多张表分别执行、失败表可重试”的问题，本类进一步解决
 * “同一张大表按 partitionConfig 拆成多个 ID range 分片，每个分片独立执行、独立计数、独立失败和独立重试”的问题。</p>
 *
 * <p>当前闭环范围刻意收敛在 {@code FULL}/{@code ONE_TIME_MIGRATION + SINGLE_OBJECT + ID_RANGE/AUTO_SPLIT_PK}：</p>
 * <p>1. FULL/ONE_TIME_MIGRATION 不需要 checkpoint 原始水位交接，适合先做端到端闭环；</p>
 * <p>2. SINGLE_OBJECT 表示用户同步的是一张源表到一张目标表，分片只发生在这张表内部；</p>
 * <p>3. ID_RANGE 使用结构化过滤条件表达 {@code id >= ? AND id < ?} 这类边界，边界值只进入 internal run-once
 * 请求并通过 PreparedStatement 绑定，不写入公开日志、receipt 或指标；</p>
 * <p>4. AUTO_SPLIT_PK 会先调用 datasource-management 的只读 range-probe 探测 min/max，再转换成 ID_RANGE；
 * HASH_BUCKET、TIME_WINDOW、文件 chunk 暂不在本轮扩散，避免闭环阶段引入更多方言一致性语义。</p>
 *
 * <p>并行执行说明：</p>
 * <p>本类会按照 {@link SyncPartitionShardExecutionContract#maxParallelism()} 对分片做有界并发。也就是说，
 * 一个父 execution 内部可以同时派发多个 shard run-once 调用，但并发度被 partitionConfig 控制，避免对源库、目标库、
 * datasource-management 或当前 worker 造成不可控压力。每个分片仍然会写入 {@code data_sync_object_execution}，
 * 因此即使某个分片失败，其他分片也可以继续完成；父 execution 最终根据分片账本汇总为 SUCCEEDED、PARTIALLY_SUCCEEDED
 * 或 FAILED。</p>
 *
 * <p>失败恢复说明：</p>
 * <p>成功分片状态为 SUCCEEDED，后续重试时直接跳过；失败分片状态为 FAILED，可以通过已有对象级重试入口重置为
 * PENDING。虽然该入口历史名称是“对象级重试”，但底层账本现在通过 {@code workUnitType=PARTITION_SHARD}
 * 明确表达这是分片工作单元，因此同一套恢复机制可以同时服务多表对象和单表分片。</p>
 */
@Component
@RequiredArgsConstructor
public class SyncPartitionShardFanOutDispatchService {

    private static final Set<String> SUPPORTED_SYNC_MODES = Set.of("FULL", "ONE_TIME_MIGRATION");
    private static final String SINGLE_OBJECT = "SINGLE_OBJECT";
    private static final String PARTITION_SHARD_FAN_OUT = "PARTITION_SHARD_FAN_OUT";
    private static final int MAX_EFFECTIVE_PARALLELISM = 16;

    private final SyncPartitionShardExecutionContractSupport partitionContractSupport;
    private final SyncObjectExecutionLifecycleSupport objectExecutionLifecycleSupport;
    private final SyncBatchRunnerBridgePlanSupport bridgePlanSupport;
    private final SyncBatchRunOnceDispatchService runOnceDispatchService;
    private final DatasourcePartitionRangeProbeClient rangeProbeClient;
    private final SyncExecutionLifecycleSupport lifecycleSupport;
    private final DataSyncTaskManagementReceiptPublisher receiptPublisher;

    /**
     * 判断当前父 execution 是否必须进入单表分片 fan-out。
     *
     * <p>注意这里的判断和 {@code executableByPartitionFanOut} 不完全等价：只要模板声明了 partitionConfig，
     * 且外层合同属于本类负责的 SINGLE_OBJECT/FULL 离线范围，就应该由本类接管。这样做是为了避免 partitionConfig
     * 写错时被普通 minimal run-once 忽略，导致用户以为已经分片，实际却全表单通道扫描。</p>
     *
     * @param contract 离线 Runner 合同，来自 bridge plan。
     * @param template 同步模板，用于判断是否声明 partitionConfig。
     * @param actorContext 当前操作者或服务账号上下文。
     * @return true 表示应该由分片 fan-out 调度器处理，false 表示交给后续普通 runner 策略判断。
     */
    public boolean supports(SyncOfflineRunnerJobContract contract,
                            SyncTemplate template,
                            SyncActorContext actorContext) {
        if (contract == null || template == null) {
            return false;
        }
        return contract.offlineChannel()
                && SINGLE_OBJECT.equals(normalize(contract.syncScopeType()))
                && SUPPORTED_SYNC_MODES.contains(normalize(contract.syncMode()))
                && hasText(template.getPartitionConfig())
                && !contract.checkpointRequired()
                && !Boolean.TRUE.equals(actorContext == null ? null : actorContext.approvalRequired());
    }

    /**
     * 执行单表 ID_RANGE 分片 fan-out，并把所有分片结果聚合回父 execution。
     *
     * @param execution 当前已被 worker claim 的父 execution。
     * @param task 当前同步任务。
     * @param template 当前 SINGLE_OBJECT 同步模板。
     * @param workerPlan worker claim 阶段产生的低敏计划。
     * @param actorContext 当前操作者或服务账号上下文。
     * @param parentContract 父级离线 Runner 合同。
     * @return 低敏调度结果；不包含表数据、SQL、连接串、分片边界值或样本行。
     */
    public SyncOfflineRunnerDispatchResult dispatchPartitionShards(SyncExecution execution,
                                                                   SyncTask task,
                                                                   SyncTemplate template,
                                                                   SyncWorkerExecutionPlanView workerPlan,
                                                                   SyncActorContext actorContext,
                                                                   SyncOfflineRunnerJobContract parentContract) {
        SyncPartitionShardExecutionContract partitionContract = resolvePartitionContract(task, execution, template,
                actorContext, parentContract);
        if (!partitionContract.executableByPartitionFanOut()) {
            return failFanOut(task, execution, actorContext, parentContract,
                    false,
                    0L,
                    0L,
                    1L,
                    "PARTITION_SHARD_CONTRACT_BLOCKED",
                    "PARTITION_SHARD_CONTRACT_BLOCKED",
                    "partitionConfig 分片合同不可执行，本次未触发真实读写；请检查分片策略、分片字段和 ranges 配置",
                    mergeIssueCodes(partitionContract.issueCodes(), partitionContract.warnings(),
                            "PARTITION_SHARD_CONTRACT_BLOCKED"));
        }

        List<SyncObjectExecution> shardExecutions =
                objectExecutionLifecycleSupport.initializePartitionShardExecutions(
                        task, execution, template, partitionContract);
        Map<Integer, SyncObjectExecution> shardExecutionByOrdinal = shardExecutions.stream()
                .collect(Collectors.toMap(SyncObjectExecution::getObjectOrdinal, Function.identity(), (left, right) -> left));
        List<ShardExecutionBundle> bundles = new ArrayList<>();
        for (SyncPartitionShardExecutionItem shard : partitionContract.shards()) {
            SyncObjectExecution shardExecution = shardExecutionByOrdinal.get(shard.ordinal());
            if (shardExecution == null) {
                return failFanOut(task, execution, actorContext, parentContract,
                        false,
                        0L,
                        0L,
                        1L,
                        "PARTITION_SHARD_EXECUTION_MISSING",
                        "PARTITION_SHARD_EXECUTION_MISSING",
                        "分片级执行账本缺失，控制面无法安全判断哪些分片已成功，已按 fail-closed 终止父 execution",
                        mergeIssueCodes(partitionContract.issueCodes(), shard.warnings(),
                                "PARTITION_SHARD_EXECUTION_MISSING"));
            }
            bundles.add(new ShardExecutionBundle(shard, shardExecution));
        }

        List<ShardDispatchOutcome> outcomes = dispatchShardTaskGroups(
                task, execution, template, workerPlan, actorContext, partitionContract, bundles);
        boolean remoteCalled = outcomes.stream().anyMatch(ShardDispatchOutcome::remoteCalled);
        List<String> accumulatedIssues = outcomes.stream()
                .flatMap(outcome -> outcome.issueCodes().stream())
                .toList();

        SyncObjectExecutionSummary summary = objectExecutionLifecycleSupport.summarize(shardExecutions);
        List<String> summaryIssues = mergeIssueCodes(summary.issueCodes(), accumulatedIssues,
                "PARTITION_SHARD_LEVEL_SUMMARY_READY");
        if (summary.allSucceeded()) {
            completeFanOut(task, execution, actorContext, summary);
            return new SyncOfflineRunnerDispatchResult(
                    remoteCalled,
                    true,
                    false,
                    "PARTITION_SHARD_FAN_OUT_COMPLETED",
                    execution.getId(),
                    "PARTITION_SHARD_ALL_SHARDS_COMPLETED",
                    parentContract == null ? PARTITION_SHARD_FAN_OUT : parentContract.contractStatus(),
                    mergeIssueCodes(summaryIssues, partitionContract.warnings(),
                            "PARTITION_SHARD_FAN_OUT_COMPLETED"),
                    SyncOfflineRunnerDispatchResult.PAYLOAD_POLICY
            );
        }
        if (summary.partiallySucceeded()) {
            partialFanOut(task, execution, actorContext, summary);
            return new SyncOfflineRunnerDispatchResult(
                    remoteCalled,
                    false,
                    false,
                    "PARTITION_SHARD_FAN_OUT_PARTIALLY_SUCCEEDED",
                    execution.getId(),
                    "PARTITION_SHARD_PARTIALLY_SUCCEEDED_RETRY_FAILED_SHARDS",
                    parentContract == null ? PARTITION_SHARD_FAN_OUT : parentContract.contractStatus(),
                    mergeIssueCodes(summaryIssues, partitionContract.warnings(),
                            "PARTITION_SHARD_PARTIALLY_SUCCEEDED"),
                    SyncOfflineRunnerDispatchResult.PAYLOAD_POLICY
            );
        }
        return failFanOut(task, execution, actorContext, parentContract,
                remoteCalled,
                summary.recordsRead(),
                summary.recordsWritten(),
                Math.max(1L, summary.failedRecordCount()),
                "PARTITION_SHARD_FAN_OUT_FAILED",
                "PARTITION_SHARD_ALL_SHARDS_FAILED",
                "所有分片均未成功，父 execution 进入 FAILED；请根据分片账本定位失败分片并修复配置或连接器环境",
                mergeIssueCodes(summaryIssues, partitionContract.warnings(),
                        "PARTITION_SHARD_ALL_SHARDS_FAILED"));
    }

    /**
     * 解析分片合同，并在 AUTO_SPLIT_PK 场景下触发 datasource-management range-probe。
     *
     * <p>这个步骤对应 DataX 的 splitPk min/max 探测，但职责边界更清晰：data-sync 只负责控制面合同和账本，
     * datasource-management 负责真实源库连接和只读探测。探测到的 min/max 只用于生成 internal range filter，
     * 不进入普通日志、receipt 或指标。</p>
     */
    private SyncPartitionShardExecutionContract resolvePartitionContract(SyncTask task,
                                                                         SyncExecution execution,
                                                                         SyncTemplate template,
                                                                         SyncActorContext actorContext,
                                                                         SyncOfflineRunnerJobContract parentContract) {
        SyncPartitionShardExecutionContract parsed = partitionContractSupport.parse(template);
        if (!parsed.autoRangeProbeRequired()) {
            return parsed;
        }
        try {
            DatasourcePartitionRangeProbeRequest request = new DatasourcePartitionRangeProbeRequest();
            request.setDatasourceId(template.getSourceDatasourceId());
            request.setConnectorType(template.getSourceConnectorType());
            request.setObjectLocator(objectLocator(template.getSourceSchemaName(), template.getSourceObjectName()));
            request.setSplitPk(parsed.partitionField());
            DatasourcePartitionRangeProbeResponse response = rangeProbeClient.probeRange(request, actorContext);
            return partitionContractSupport.buildAutoRangeContract(parsed, response);
        } catch (RuntimeException exception) {
            return new SyncPartitionShardExecutionContract(
                    true,
                    true,
                    false,
                    "AUTO_SPLIT_PK",
                    parsed.partitionField(),
                    parsed.requestedShardCount(),
                    parsed.maxParallelism(),
                    parsed.taskGroupSize(),
                    parsed.maxAttemptCount(),
                    true,
                    parsed.maxDirtyRecordCount(),
                    parsed.maxDirtyRecordRatio(),
                    List.of(),
                    mergeIssueCodes(parsed.issueCodes(), List.of(), "PARTITION_AUTO_RANGE_PROBE_FAILED"),
                    mergeIssueCodes(parsed.warnings(), parentContract == null ? List.of() : parentContract.issueCodes(),
                            "PARTITION_AUTO_RANGE_PROBE_FAILED"),
                    SyncPartitionShardExecutionContract.PAYLOAD_POLICY
            );
        }
    }

    private String objectLocator(String schemaName, String objectName) {
        if (!hasText(schemaName)) {
            return objectName;
        }
        return schemaName + "." + objectName;
    }

    /**
     * 按 TaskGroup 分组，再在组内按 channel 执行有界并发。
     *
     * <p>DataX 中 TaskGroup 是分片任务的小调度容器，channel 是该容器里同时运行的 Reader/Writer 通道数量。
     * 本项目的第一阶段实现采用“TaskGroup 顺序推进、组内 channel 并发”的保守策略：这样可以先获得清晰账本和
     * 失败重试语义，避免一次性把所有分片同时压到源库/目标库。</p>
     */
    private List<ShardDispatchOutcome> dispatchShardTaskGroups(SyncTask task,
                                                               SyncExecution execution,
                                                               SyncTemplate template,
                                                               SyncWorkerExecutionPlanView workerPlan,
                                                               SyncActorContext actorContext,
                                                               SyncPartitionShardExecutionContract partitionContract,
                                                               List<ShardExecutionBundle> bundles) {
        List<ShardDispatchOutcome> outcomes = new ArrayList<>();
        int taskGroupSize = effectiveTaskGroupSize(partitionContract, bundles.size());
        for (int groupStart = 0; groupStart < bundles.size(); groupStart += taskGroupSize) {
            int groupEnd = Math.min(groupStart + taskGroupSize, bundles.size());
            outcomes.addAll(dispatchShardBatches(task, execution, template, workerPlan, actorContext,
                    partitionContract, bundles.subList(groupStart, groupEnd), groupStart / taskGroupSize));
        }
        return outcomes;
    }

    /**
     * 按 channel 对一个 TaskGroup 内的分片执行有界并发。
     */
    private List<ShardDispatchOutcome> dispatchShardBatches(SyncTask task,
                                                            SyncExecution execution,
                                                            SyncTemplate template,
                                                            SyncWorkerExecutionPlanView workerPlan,
                                                            SyncActorContext actorContext,
                                                            SyncPartitionShardExecutionContract partitionContract,
                                                            List<ShardExecutionBundle> bundles,
                                                            int taskGroupOrdinal) {
        int parallelism = effectiveParallelism(partitionContract, bundles.size());
        List<ShardDispatchOutcome> outcomes = new ArrayList<>();
        try (ExecutorService executorService = Executors.newFixedThreadPool(
                parallelism, Thread.ofVirtual().name("data-sync-partition-tg-" + taskGroupOrdinal + "-", 0).factory())) {
            for (int start = 0; start < bundles.size(); start += parallelism) {
                int end = Math.min(start + parallelism, bundles.size());
                List<Future<ShardDispatchOutcome>> futures = new ArrayList<>();
                for (ShardExecutionBundle bundle : bundles.subList(start, end)) {
                    futures.add(executorService.submit(() -> dispatchOneShardWithRetry(
                            task, execution, template, workerPlan, actorContext, bundle.shard(), bundle.row())));
                }
                for (Future<ShardDispatchOutcome> future : futures) {
                    outcomes.add(awaitShardOutcome(future));
                }
            }
        }
        return outcomes;
    }

    /**
     * 执行单个分片，并在分片级尝试次数未耗尽时做即时重试。
     *
     * <p>该方法和 OBJECT_LIST 的单对象执行逻辑类似，但额外把 shardOrPartition 与 range filter 传入 run-once。
     * 成功分片会写成 SUCCEEDED；失败分片不会阻断同一父 execution 下的其他分片，最终由父级汇总决定整体状态。</p>
     */
    private ShardDispatchOutcome dispatchOneShardWithRetry(SyncTask task,
                                                           SyncExecution execution,
                                                           SyncTemplate template,
                                                           SyncWorkerExecutionPlanView workerPlan,
                                                           SyncActorContext actorContext,
                                                           SyncPartitionShardExecutionItem shard,
                                                           SyncObjectExecution shardExecution) {
        if (SyncObjectExecutionState.SUCCEEDED.name().equals(shardExecution.getObjectState())) {
            return new ShardDispatchOutcome(false,
                    mergeIssueCodes(shard.warnings(), List.of(), "PARTITION_SHARD_ALREADY_SUCCEEDED_SKIPPED"));
        }
        boolean remoteCalled = false;
        List<String> issueCodes = new ArrayList<>(shard.warnings());
        while (safeInt(shardExecution.getAttemptCount()) < effectiveMaxAttemptCount(shardExecution)) {
            objectExecutionLifecycleSupport.markObjectRunning(shardExecution);
            SyncTemplate childTemplate = singleObjectTemplate(template);
            SyncExecution childExecution = childExecutionView(execution);
            SyncWorkerExecutionPlanView childWorkerPlan = singleObjectWorkerPlan(workerPlan, shard);
            SyncBatchRunnerBridgePlan childBridgePlan =
                    bridgePlanSupport.buildPlan(childExecution, task, childTemplate, childWorkerPlan);
            if (!childBridgePlan.isDispatchable()) {
                objectExecutionLifecycleSupport.markObjectFailed(shardExecution, null, false,
                        "PARTITION_SHARD_BRIDGE_PLAN_BLOCKED",
                        "PARTITION_SHARD_BRIDGE_PLAN_BLOCKED",
                        "分片子计划桥接被阻断，请修复字段映射、过滤条件、连接器兼容性或写入策略");
                issueCodes = mergeIssueCodes(issueCodes, childBridgePlan.getIssueCodes(),
                        "PARTITION_SHARD_BRIDGE_PLAN_BLOCKED");
                break;
            }

            try {
                SyncBatchRunOnceRemoteExecutionResult remoteResult =
                        runOnceDispatchService.executePreparedRunOnceRemoteOnly(
                                childBridgePlan,
                                childExecution,
                                task,
                                actorContext,
                                shard.shardOrPartition(),
                                shard.filterConditions());
                remoteCalled = remoteCalled || remoteResult != null && remoteResult.remoteCalled();
                if (remoteResult != null && remoteResult.completed() && !remoteResult.failed()) {
                    objectExecutionLifecycleSupport.markObjectSucceeded(shardExecution, remoteResult);
                    issueCodes = mergeIssueCodes(issueCodes, remoteResult.issueCodes(),
                            "PARTITION_SHARD_COMPLETED");
                    break;
                }
                boolean retrying = shouldRetryShard(remoteResult, shardExecution);
                objectExecutionLifecycleSupport.markObjectFailed(shardExecution, remoteResult, retrying,
                        "PARTITION_SHARD_RUN_ONCE_FAILED",
                        firstText(remoteResult == null ? null : remoteResult.errorCode(),
                                "PARTITION_SHARD_RUN_ONCE_FAILED"),
                        "分片 run-once 执行失败，已按分片级尝试次数判断是否继续重试");
                issueCodes = mergeIssueCodes(issueCodes,
                        remoteResult == null ? List.of() : remoteResult.issueCodes(),
                        retrying ? "PARTITION_SHARD_RETRYING" : "PARTITION_SHARD_FAILED_ATTEMPTS_EXHAUSTED");
                if (!retrying) {
                    break;
                }
            } catch (RuntimeException exception) {
                objectExecutionLifecycleSupport.markObjectFailed(shardExecution, null, false,
                        "PARTITION_SHARD_DISPATCH_EXCEPTION",
                        "PARTITION_SHARD_DISPATCH_EXCEPTION",
                        "分片派发发生受控异常，异常详情请查看服务端日志，公开结果仅保留低敏错误码");
                issueCodes = mergeIssueCodes(issueCodes, List.of(), "PARTITION_SHARD_DISPATCH_EXCEPTION");
                break;
            }
        }
        if (!SyncObjectExecutionState.SUCCEEDED.name().equals(shardExecution.getObjectState())
                && !SyncObjectExecutionState.FAILED.name().equals(shardExecution.getObjectState())) {
            objectExecutionLifecycleSupport.markObjectFailed(shardExecution, null, false,
                    "PARTITION_SHARD_ATTEMPT_POLICY_EXHAUSTED",
                    "PARTITION_SHARD_ATTEMPT_POLICY_EXHAUSTED",
                    "分片尝试次数已耗尽或状态无法继续推进，已转为失败分片等待后续选择性重试");
            issueCodes = mergeIssueCodes(issueCodes, List.of(), "PARTITION_SHARD_ATTEMPT_POLICY_EXHAUSTED");
        }
        return new ShardDispatchOutcome(remoteCalled, issueCodes);
    }

    private ShardDispatchOutcome awaitShardOutcome(Future<ShardDispatchOutcome> future) {
        try {
            return future.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new ShardDispatchOutcome(false, List.of("PARTITION_SHARD_FUTURE_INTERRUPTED"));
        } catch (ExecutionException exception) {
            return new ShardDispatchOutcome(false, List.of("PARTITION_SHARD_FUTURE_FAILED"));
        }
    }

    private SyncTemplate singleObjectTemplate(SyncTemplate template) {
        SyncTemplate child = new SyncTemplate();
        child.setId(template.getId());
        child.setTenantId(template.getTenantId());
        child.setProjectId(template.getProjectId());
        child.setWorkspaceId(template.getWorkspaceId());
        child.setName(template.getName());
        child.setDescription(template.getDescription());
        child.setSourceDatasourceId(template.getSourceDatasourceId());
        child.setTargetDatasourceId(template.getTargetDatasourceId());
        child.setSourceSchemaName(template.getSourceSchemaName());
        child.setSourceObjectName(template.getSourceObjectName());
        child.setTargetSchemaName(template.getTargetSchemaName());
        child.setTargetObjectName(template.getTargetObjectName());
        child.setSourceConnectorType(template.getSourceConnectorType());
        child.setTargetConnectorType(template.getTargetConnectorType());
        child.setSyncMode(template.getSyncMode());
        child.setSyncScopeType(SINGLE_OBJECT);
        child.setWriteStrategy(template.getWriteStrategy());
        child.setPrimaryKeyField(template.getPrimaryKeyField());
        child.setIncrementalField(template.getIncrementalField());
        child.setFieldMappingConfig(template.getFieldMappingConfig());
        child.setFilterConfig(template.getFilterConfig());
        /*
         * 子计划必须清空 partitionConfig：分片已经由本类解析成结构化 additionalFilterConditions，
         * 如果继续把原始 partitionConfig 传入 child bridge，后续策略可能误以为还需要再次拆分，形成递归 fan-out。
         */
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

    private SyncWorkerExecutionPlanView singleObjectWorkerPlan(SyncWorkerExecutionPlanView workerPlan,
                                                               SyncPartitionShardExecutionItem shard) {
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
                List.of("PARTITION_SHARD_CHILD_PLAN_DISPATCH_TO_RUN_ONCE",
                        "CALL_PARENT_FAN_OUT_COMPLETE_AFTER_ALL_SHARDS"),
                workerPlan.performanceNotes(),
                mergeIssueCodes(workerPlan.safetyNotes(), shard.warnings(),
                        "PARTITION_SHARD_CHILD_SCOPE_CREATED_BY_FAN_OUT"),
                workerPlan.payloadPolicy()
        );
    }

    private List<String> sanitizedChildIssueCodes(List<String> issueCodes) {
        if (issueCodes == null || issueCodes.isEmpty()) {
            return List.of();
        }
        return issueCodes.stream()
                .filter(issueCode -> !"PARTITION_PLAN_NOT_DECLARED".equals(issueCode))
                .filter(issueCode -> !"SCOPE_NOT_EXECUTABLE_BY_MINIMAL_RUN_ONCE_BRIDGE".equals(issueCode))
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
        request.setIdempotencyKey("partition-shard-fan-out-complete-" + execution.getId());
        lifecycleSupport.completeExecution(task, execution, request, actorContext);
        receiptPublisher.publishComplete(task, execution, actorContext,
                aggregateResponse(summary, "PARTITION_SHARD_ALL_SHARDS_COMPLETED", true, false));
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
        request.setErrorSummary("PARTITION_SHARD partially succeeded, succeededShards=" + summary.succeededCount()
                + ", failedShards=" + summary.failedCount());
        request.setIdempotencyKey("partition-shard-fan-out-partial-" + execution.getId());
        lifecycleSupport.partiallySucceedExecution(task, execution, request, actorContext);
        receiptPublisher.publishPartiallySucceeded(task, execution, actorContext,
                aggregateResponse(summary, "PARTITION_SHARD_PARTIALLY_SUCCEEDED", false, false),
                mergeIssueCodes(summary.issueCodes(), List.of(), "PARTITION_SHARD_PARTIALLY_SUCCEEDED"));
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
        request.setErrorType("PARTITION_SHARD_FAN_OUT_FAILED");
        request.setErrorCode(errorCode);
        request.setErrorMessage(errorMessage);
        request.setSourceRecordKey(null);
        request.setTargetRecordKey(null);
        request.setSamplePayload(null);
        request.setRecordsRead(recordsRead);
        request.setRecordsWritten(recordsWritten);
        request.setFailedRecordCount(Math.max(1L, failedRecordCount));
        request.setRetryable(false);
        request.setIdempotencyKey("partition-shard-fan-out-fail-" + execution.getId() + "-" + errorCode);
        lifecycleSupport.failExecution(task, execution, request, actorContext);
        receiptPublisher.publishFailed(task, execution, actorContext, errorCode, issueCodes);
        return new SyncOfflineRunnerDispatchResult(
                remoteCalled,
                false,
                true,
                dispatchStatus,
                execution.getId(),
                null,
                parentContract == null ? PARTITION_SHARD_FAN_OUT : parentContract.contractStatus(),
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
        response.setPayloadPolicy("LOW_SENSITIVE_PARTITION_SHARD_FAN_OUT_RESULT_NO_RANGE_VALUES_NO_ROWS_NO_SQL");
        return response;
    }

    private boolean shouldRetryShard(SyncBatchRunOnceRemoteExecutionResult remoteResult,
                                     SyncObjectExecution shardExecution) {
        return remoteResult != null
                && remoteResult.retryable()
                && safeInt(shardExecution.getAttemptCount()) < effectiveMaxAttemptCount(shardExecution);
    }

    private int effectiveMaxAttemptCount(SyncObjectExecution shardExecution) {
        return Math.max(1, Math.min(safeInt(shardExecution.getMaxAttemptCount()), 10));
    }

    private int effectiveParallelism(SyncPartitionShardExecutionContract partitionContract, int shardCount) {
        int configured = partitionContract == null ? 1 : partitionContract.maxParallelism();
        return Math.max(1, Math.min(Math.min(configured, MAX_EFFECTIVE_PARALLELISM), Math.max(1, shardCount)));
    }

    private int effectiveTaskGroupSize(SyncPartitionShardExecutionContract partitionContract, int shardCount) {
        int configured = partitionContract == null ? shardCount : partitionContract.taskGroupSize();
        return Math.max(1, Math.min(configured, Math.max(1, shardCount)));
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

    private record ShardExecutionBundle(SyncPartitionShardExecutionItem shard, SyncObjectExecution row) {
    }

    private record ShardDispatchOutcome(boolean remoteCalled, List<String> issueCodes) {

        private ShardDispatchOutcome {
            issueCodes = issueCodes == null ? List.of() : List.copyOf(issueCodes);
        }
    }
}
