/**
 * @Author : Cui
 * @Date: 2026/08/14
 * @Description DataSmart Govern Backend - SyncAutopilotRecoveryRepairService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import com.czh.datasmart.govern.datasync.controller.dto.SyncObjectRetryRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncObjectRetryResult;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskRecoveryOperationRequest;
import com.czh.datasmart.govern.datasync.entity.SyncAutopilotRecoveryCase;
import com.czh.datasmart.govern.datasync.entity.SyncCallbackIdempotency;
import com.czh.datasmart.govern.datasync.entity.SyncCheckpoint;
import com.czh.datasmart.govern.datasync.entity.SyncExecution;
import com.czh.datasmart.govern.datasync.entity.SyncExecutionPolicy;
import com.czh.datasmart.govern.datasync.entity.SyncExecutionPolicySnapshot;
import com.czh.datasmart.govern.datasync.entity.SyncObjectExecution;
import com.czh.datasmart.govern.datasync.entity.SyncTask;
import com.czh.datasmart.govern.datasync.entity.SyncTaskDefinition;
import com.czh.datasmart.govern.datasync.mapper.SyncAutopilotRecoveryCaseMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncCheckpointMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncExecutionMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncExecutionPolicyMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncExecutionPolicySnapshotMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncObjectExecutionMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncTaskDefinitionMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncTaskMapper;
import com.czh.datasmart.govern.datasync.support.SyncAutopilotExecutionMode;
import com.czh.datasmart.govern.datasync.support.SyncAutopilotRecoveryAction;
import com.czh.datasmart.govern.datasync.support.SyncAutopilotRecoveryCaseState;
import com.czh.datasmart.govern.datasync.support.SyncAutopilotRiskLevel;
import com.czh.datasmart.govern.datasync.support.SyncAuditActionType;
import com.czh.datasmart.govern.datasync.support.SyncWriteStrategy;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * 在首次授权盒内执行固定、低风险且可审计的数据同步修复。
 *
 * <p>本服务不是通用配置修改器。每次调用都会重新读取 recovery case、任务、任务定义和失败 execution，
 * 再用持久 Autopilot 策略复算授权。动作参数采用逐动作白名单，并通过统一 SHA-256 指纹与原事件绑定。
 * 幂等记录、配置变更、恢复计划或失败分片重排队位于同一事务中，HTTP 响应丢失后可安全重放首次结果。</p>
 *
 * <p>以下情况会返回 {@code applied=false}：不存在成功策略快照、调参越界、没有 checkpoint、没有可安全
 * 重放的分片、元数据预检仍有问题、字段映射无法唯一修复等。它们是确定性业务结论。服务会在同一事务内
 * 把旧 case 推进到 ATTENTION_REQUIRED，并在授权预算尚未耗尽时把新问题码写入下一轮恢复 outbox；网络、
 * 数据库或损坏合同异常则继续抛出，交给 Kafka 有界重试。</p>
 */
@Service
@RequiredArgsConstructor
public class SyncAutopilotRecoveryRepairService {

    private static final String IDEMPOTENCY_ACTION = "AUTOPILOT_RECOVERY_REPAIR";
    private static final String RECEIPT_SUFFIX = ":repair-apply";
    static final String POLICY_CODE = "AUTOPILOT_RECOVERY_OVERRIDE";
    private static final Pattern SHA_256 = Pattern.compile("^[0-9a-f]{64}$");
    private static final Pattern SAFE_RECEIPT_ID = Pattern.compile("^[A-Za-z0-9._:-]{1,128}$");

    private final SyncAutopilotRecoveryCaseMapper caseMapper;
    private final SyncTaskMapper taskMapper;
    private final SyncTaskDefinitionMapper definitionMapper;
    private final SyncExecutionMapper executionMapper;
    private final SyncObjectExecutionMapper objectExecutionMapper;
    private final SyncCheckpointMapper checkpointMapper;
    private final SyncExecutionPolicyMapper policyMapper;
    private final SyncExecutionPolicySnapshotMapper snapshotMapper;
    private final SyncAutopilotRecoveryPolicyEvaluator policyEvaluator;
    private final SyncDataScopeSupport dataScopeSupport;
    private final SyncCallbackIdempotencySupport idempotencySupport;
    private final SyncObjectExecutionOperationSupport objectRetrySupport;
    private final SyncTaskRecoveryOperationSupport recoveryOperationSupport;
    private final SyncTaskDefinitionMetadataAwarePrecheckSupport metadataPrecheckSupport;
    private final SyncAuditSupport auditSupport;
    private final SyncAutopilotRecoveryTriggerPublisher triggerPublisher;
    private final ObjectMapper objectMapper;

    /**
     * 校验、执行或幂等重放一个受治理修复动作。
     *
     * @param command case、范围、动作、指纹和受限参数
     * @param principal 被代理用户与 Agent/委派双主体
     * @return 控制面修复回执；不代表 worker 已完成数据搬运
     */
    @Transactional
    public SyncAutopilotRecoveryRepairReceiptView apply(
            SyncAutopilotRecoveryRepairCommand command,
            SyncAutopilotRecoveryPrincipalContext principal,
            SyncActorContext actor) {
        Map<String, Object> parameters = requireCommand(command);
        requirePrincipal(principal);
        SyncAutopilotRecoveryCase recoveryCase = requireCase(command.caseId());
        requireCaseScope(recoveryCase, command);
        SyncTask task = requireTask(command);
        SyncTaskDefinition definition = requireDefinition(command, task);
        SyncExecution execution = requireExecution(command, task);
        requireExecutableCase(recoveryCase, command);
        requireCurrentAuthorization(recoveryCase, definition, command);
        requirePrincipalBinding(definition, principal);
        requireManageableTask(command, task, principal, actor);
        requireActionFingerprint(recoveryCase, command, parameters);

        String scopeKey = "case:" + command.caseId();
        String requestDigest = requestDigest(command, principal, parameters);
        if (idempotencySupport.isDuplicate(
                command.tenantId(), command.syncTaskId(), command.executionId(),
                IDEMPOTENCY_ACTION, scopeKey, command.receiptId(),
                principal.representedActorId(), requestDigest)) {
            return replay(command, scopeKey, requestDigest);
        }

        SyncAutopilotRecoveryRepairReceiptView result = switch (command.action()) {
            case ROLLBACK_EXECUTION_POLICY -> rollbackPolicy(
                    command, recoveryCase, task, execution, actor);
            case TUNE_EXECUTION_POLICY -> tunePolicy(
                    command, recoveryCase, task, execution, parameters, actor);
            case REFRESH_METADATA -> refreshMetadata(command, task, definition, execution, actor);
            case RESUME_FROM_CHECKPOINT -> resumeFromCheckpoint(command, task, execution, actor);
            case REPLAY_FAILED_SHARDS -> replayFailedShards(command, task, definition, execution, actor);
            case REPAIR_FIELD_MAPPING -> repairFieldMapping(command, task, definition, execution, actor);
            default -> throw badRequest("Autopilot repair action is not executable by this endpoint");
        };
        if (!result.applied()) {
            SyncAutopilotRecoveryRepairReplanResult replan = triggerPublisher.publishRepairNotApplied(
                    task, execution, recoveryCase, result.reasonCode(), result.issueCodes());
            result = withConvergedCase(result, replan);
        }
        saveAudit(command, principal, actor, result);
        idempotencySupport.markSucceeded(
                command.tenantId(), IDEMPOTENCY_ACTION, scopeKey, command.receiptId(), writeResult(result));
        return result;
    }

    /**
     * 把 Publisher 的持久重规划结果合并到对 Agent Runtime 返回的强类型回执。
     *
     * <p>只有 Publisher 已经用状态机回执收敛旧 case 后才调用本方法。因此即使没有剩余循环预算，
     * {@code caseState} 也会明确返回 {@code ATTENTION_REQUIRED}；只有真实写入下一轮 outbox 时才携带事件 ID。
     * 该方法是纯复制，不修改任务定义、execution 或 case。</p>
     */
    private SyncAutopilotRecoveryRepairReceiptView withConvergedCase(
            SyncAutopilotRecoveryRepairReceiptView result,
            SyncAutopilotRecoveryRepairReplanResult replan) {
        return new SyncAutopilotRecoveryRepairReceiptView(
                result.receiptId(), result.caseId(), result.syncTaskId(), result.sourceExecutionId(),
                result.executionId(), result.action(), result.applied(), result.affectedCount(),
                result.executionState(), result.taskState(), result.reasonCode(), result.issueCodes(),
                result.actionFingerprint(), "ATTENTION_REQUIRED", replan.queued(), replan.eventId(),
                replan.nextCycle());
    }

    /**
     * 为首次受治理修复写入低敏双主体审计；幂等重放在调用本方法前已经返回，因此不会重复记账。
     */
    private void saveAudit(
            SyncAutopilotRecoveryRepairCommand command,
            SyncAutopilotRecoveryPrincipalContext principal,
            SyncActorContext actor,
            SyncAutopilotRecoveryRepairReceiptView result) {
        String payload = String.join(";",
                "caseId=" + command.caseId(),
                "action=" + command.action().name(),
                "applied=" + result.applied(),
                "reasonCode=" + result.reasonCode(),
                "affectedCount=" + result.affectedCount(),
                "agentId=" + principal.agentId(),
                "delegationId=" + principal.delegationId(),
                "actionFingerprint=" + command.actionFingerprint());
        auditSupport.saveAudit(
                command.tenantId(), command.syncTaskId(), result.executionId(),
                SyncAuditActionType.AUTOPILOT_GOVERNED_REPAIR, actor, payload);
    }

    /** 回滚到最近一次成功 execution 的运行策略快照，并重排当前失败对象。 */
    private SyncAutopilotRecoveryRepairReceiptView rollbackPolicy(
            SyncAutopilotRecoveryRepairCommand command,
            SyncAutopilotRecoveryCase recoveryCase,
            SyncTask task,
            SyncExecution execution,
            SyncActorContext actor) {
        SyncExecution successful = executionMapper.selectOne(new LambdaQueryWrapper<SyncExecution>()
                .eq(SyncExecution::getSyncTaskId, task.getId())
                .eq(SyncExecution::getExecutionState, "SUCCEEDED")
                .lt(SyncExecution::getId, execution.getId())
                .orderByDesc(SyncExecution::getFinishedAt)
                .orderByDesc(SyncExecution::getId)
                .last("LIMIT 1"));
        SyncExecutionPolicySnapshot snapshot = successful == null ? null : snapshotFor(successful.getId(), task.getId());
        if (snapshot == null) {
            return notApplied(command, execution, "AUTOPILOT_LAST_SUCCESSFUL_POLICY_SNAPSHOT_MISSING");
        }
        upsertPolicyOverride(command, recoveryCase, task, snapshot, Map.of(), actor,
                "回滚到最近成功 execution 的运行策略快照");
        return requeueAllFailed(command, task, execution, actor, 1);
    }

    /** 应用受限调参：并发/批量只能下降，timeout 只能在两倍且 3600 秒内上升。 */
    private SyncAutopilotRecoveryRepairReceiptView tunePolicy(
            SyncAutopilotRecoveryRepairCommand command,
            SyncAutopilotRecoveryCase recoveryCase,
            SyncTask task,
            SyncExecution execution,
            Map<String, Object> parameters,
            SyncActorContext actor) {
        SyncExecutionPolicySnapshot current = snapshotFor(execution.getId(), task.getId());
        if (current == null) {
            return notApplied(command, execution, "AUTOPILOT_CURRENT_POLICY_SNAPSHOT_MISSING");
        }
        String issue = validateTuning(current, parameters);
        if (issue != null) {
            return notApplied(command, execution, issue);
        }
        upsertPolicyOverride(command, recoveryCase, task, current, parameters, actor,
                "Autopilot 依据失败证据执行有界运行策略调整");
        return requeueAllFailed(command, task, execution, actor, 1);
    }

    /** 强制刷新两端元数据；只有完整预检仍通过时才重新排队失败对象。 */
    private SyncAutopilotRecoveryRepairReceiptView refreshMetadata(
            SyncAutopilotRecoveryRepairCommand command,
            SyncTask task,
            SyncTaskDefinition definition,
            SyncExecution execution,
            SyncActorContext actor) {
        SyncTaskDefinitionMetadataAwarePrecheckSupport.MetadataAwarePrecheckResult precheck =
                metadataPrecheckSupport.evaluate(definition, actor, true);
        if (!precheck.issueCodes().isEmpty()) {
            return notApplied(command, execution, "AUTOPILOT_REFRESHED_METADATA_PRECHECK_FAILED",
                    precheck.issueCodes());
        }
        return requeueAllFailed(command, task, execution, actor, 1);
    }

    /** 从当前失败 execution 的最新持久 checkpoint 创建严格 replay execution。 */
    private SyncAutopilotRecoveryRepairReceiptView resumeFromCheckpoint(
            SyncAutopilotRecoveryRepairCommand command,
            SyncTask task,
            SyncExecution execution,
            SyncActorContext actor) {
        SyncCheckpoint checkpoint = checkpointMapper.selectOne(new LambdaQueryWrapper<SyncCheckpoint>()
                .eq(SyncCheckpoint::getSyncTaskId, task.getId())
                .eq(SyncCheckpoint::getExecutionId, execution.getId())
                .orderByDesc(SyncCheckpoint::getCheckpointTime)
                .orderByDesc(SyncCheckpoint::getId)
                .last("LIMIT 1"));
        if (checkpoint == null) {
            return notApplied(command, execution, "AUTOPILOT_PERSISTED_CHECKPOINT_MISSING");
        }
        SyncTaskRecoveryOperationRequest request = new SyncTaskRecoveryOperationRequest();
        request.setSourceExecutionId(execution.getId());
        request.setSourceCheckpointId(checkpoint.getId());
        request.setReason("AUTOPILOT_PREAUTHORIZED_CHECKPOINT_RESUME");
        recoveryOperationSupport.replayTask(task, request, actor);
        SyncTask refreshed = taskMapper.selectById(task.getId());
        Long newExecutionId = refreshed == null ? null : refreshed.getLastExecutionId();
        if (newExecutionId == null || Objects.equals(newExecutionId, execution.getId())) {
            throw conflict("Checkpoint resume did not create a new execution");
        }
        return applied(command, execution.getId(), newExecutionId, 1,
                "QUEUED", "QUEUED", "AUTOPILOT_CHECKPOINT_RESUME_QUEUED",
                List.of("CHECKPOINT_BOUND_REPLAY_CREATED"));
    }

    /** 只选择当前 execution 中 FAILED 的 PARTITION_SHARD，并复用对象级幂等重排队能力。 */
    private SyncAutopilotRecoveryRepairReceiptView replayFailedShards(
            SyncAutopilotRecoveryRepairCommand command,
            SyncTask task,
            SyncTaskDefinition definition,
            SyncExecution execution,
            SyncActorContext actor) {
        SyncWriteStrategy strategy = SyncWriteStrategy.fromValueForMode(
                definition.getWriteStrategy(), definition.getSyncMode());
        if (!strategy.mergeLike()) {
            return notApplied(command, execution, "AUTOPILOT_FAILED_SHARD_REPLAY_REQUIRES_IDEMPOTENT_WRITE");
        }
        List<Long> failedShardIds = safeObjects(objectExecutionMapper.selectByExecutionId(execution.getId())).stream()
                .filter(row -> "FAILED".equalsIgnoreCase(row.getObjectState()))
                .filter(row -> "PARTITION_SHARD".equalsIgnoreCase(row.getWorkUnitType()))
                .map(SyncObjectExecution::getId)
                .filter(Objects::nonNull)
                .sorted()
                .toList();
        if (failedShardIds.isEmpty()) {
            return notApplied(command, execution, "AUTOPILOT_FAILED_PARTITION_SHARDS_MISSING");
        }
        SyncObjectRetryRequest request = retryRequest(command, failedShardIds, 1,
                "AUTOPILOT_PREAUTHORIZED_FAILED_SHARD_REPLAY");
        SyncObjectRetryResult retry = objectRetrySupport.retryFailedObjects(task, execution, request, actor);
        return applied(command, execution.getId(), retry.executionId(), retry.retryObjectCount(),
                retry.executionState(), retry.taskState(), "AUTOPILOT_FAILED_SHARDS_REQUEUED",
                retry.issueCodes());
    }

    /** 应用元数据证明型字段映射修复；完整预检不通过时不持久化任何配置变化。 */
    private SyncAutopilotRecoveryRepairReceiptView repairFieldMapping(
            SyncAutopilotRecoveryRepairCommand command,
            SyncTask task,
            SyncTaskDefinition definition,
            SyncExecution execution,
            SyncActorContext actor) {
        SyncTaskDefinitionMetadataAwarePrecheckSupport.MetadataFieldMappingRepairResult repair =
                metadataPrecheckSupport.repairFieldMappings(definition, actor);
        if (repair.changedCount() <= 0 || !repair.issueCodes().isEmpty()) {
            return notApplied(command, execution, "AUTOPILOT_FIELD_MAPPING_REPAIR_NOT_DETERMINISTIC",
                    repair.issueCodes());
        }
        String previousConfig = definition.getFieldMappingConfig();
        definition.setFieldMappingConfig(repair.fieldMappingConfig());
        SyncTaskDefinitionMetadataAwarePrecheckSupport.MetadataAwarePrecheckResult precheck =
                metadataPrecheckSupport.evaluate(definition, actor, true);
        if (!precheck.issueCodes().isEmpty()) {
            definition.setFieldMappingConfig(previousConfig);
            return notApplied(command, execution, "AUTOPILOT_REPAIRED_FIELD_MAPPING_PRECHECK_FAILED",
                    precheck.issueCodes());
        }
        LocalDateTime updatedAt = LocalDateTime.now(ZoneOffset.UTC);
        /*
         * 实体属性名虽然是 id，但 data_sync_task_definition 与任务共用主键，真实列名是 task_id。
         * UpdateWrapper 的字符串条件不会读取实体上的 @TableId 自动换列名，因此这里必须写数据库列名；
         * 否则 mock mapper 的单元测试可能通过，而 PostgreSQL 会在自治恢复事务中拒绝不存在的 id 列。
         */
        UpdateWrapper<SyncTaskDefinition> update = new UpdateWrapper<SyncTaskDefinition>()
                .eq("task_id", definition.getId())
                .eq("tenant_id", definition.getTenantId())
                .eq("project_id", definition.getProjectId())
                .eq("autopilot_policy", definition.getAutopilotPolicy())
                .set("field_mapping_config", repair.fieldMappingConfig())
                .set("update_time", updatedAt);
        if (previousConfig == null) {
            update.isNull("field_mapping_config");
        } else {
            update.eq("field_mapping_config", previousConfig);
        }
        /*
         * 这里只更新字段映射和更新时间，并把“读取时的映射 + 读取时的授权 JSON”作为条件锁。
         * 若管理员在 Agent 预检期间撤销授权或人工修改映射，UPDATE 会返回 0，整个事务回滚且不会重排失败对象。
         * 不能使用 updateById(definition)：那会把几分钟前读取的整行实体写回，覆盖并发修改的授权、checkpoint 或其他配置。
         */
        if (definitionMapper.update(null, update) != 1) {
            throw conflict("Autopilot field mapping repair could not be persisted");
        }
        return requeueAllFailed(command, task, execution, actor, repair.changedCount());
    }

    /** 复用已存在的对象级失败重试，并把外层 repair receipt 作为内层幂等键。 */
    private SyncAutopilotRecoveryRepairReceiptView requeueAllFailed(
            SyncAutopilotRecoveryRepairCommand command,
            SyncTask task,
            SyncExecution execution,
            SyncActorContext actor,
            int repairAffectedCount) {
        SyncObjectRetryRequest request = retryRequest(command, null, 1,
                "AUTOPILOT_PREAUTHORIZED_GOVERNED_REPAIR_RETRY");
        SyncObjectRetryResult retry = objectRetrySupport.retryFailedObjects(task, execution, request, actor);
        return applied(command, execution.getId(), retry.executionId(),
                Math.max(repairAffectedCount, retry.retryObjectCount()), retry.executionState(), retry.taskState(),
                "AUTOPILOT_GOVERNED_REPAIR_APPLIED_AND_REQUEUED", retry.issueCodes());
    }

    /** 构造不包含模型文本的失败对象重试请求。 */
    private SyncObjectRetryRequest retryRequest(
            SyncAutopilotRecoveryRepairCommand command,
            List<Long> objectIds,
            int attemptBudget,
            String reason) {
        SyncObjectRetryRequest request = new SyncObjectRetryRequest();
        request.setIdempotencyKey(command.receiptId());
        request.setObjectExecutionIds(objectIds);
        request.setRetryAttemptBudget(attemptBudget);
        request.setResetAttemptCount(true);
        request.setReason(reason);
        return request;
    }

    /**
     * 将成功快照或当前快照保存为有边界的最高优先级任务覆盖。
     *
     * <p>执行策略表目前只有 TASK 作用域，没有独立的 RECOVERY_CASE 作用域，因此不能只靠 scopeKey 防止临时参数影响
     * 后续定时运行。本方法把 case、execution、授权摘要和 UTC 截止时间写入结构化 JSON 描述；策略解析器只有在任务
     * {@code lastExecutionId}、活跃状态和截止时间仍匹配时才合并该覆盖。恢复成功或失败后，触发发布器还会软禁用该记录，
     * 从“运行时匹配”和“生命周期清理”两层阻止一次授权变成永久任务配置。</p>
     */
    private void upsertPolicyOverride(
            SyncAutopilotRecoveryRepairCommand command,
            SyncAutopilotRecoveryCase recoveryCase,
            SyncTask task,
            SyncExecutionPolicySnapshot baseline,
            Map<String, Object> parameters,
            SyncActorContext actor,
            String description) {
        SyncExecutionPolicy policy = policyMapper.selectOne(new LambdaQueryWrapper<SyncExecutionPolicy>()
                .eq(SyncExecutionPolicy::getTenantId, task.getTenantId())
                .eq(SyncExecutionPolicy::getScopeType, "TASK")
                .eq(SyncExecutionPolicy::getScopeKey, "TASK:" + task.getId())
                .eq(SyncExecutionPolicy::getPolicyCode, POLICY_CODE)
                .last("LIMIT 1"));
        boolean insert = policy == null;
        if (insert) {
            policy = new SyncExecutionPolicy();
            policy.setTenantId(task.getTenantId());
            policy.setProjectId(task.getProjectId());
            policy.setScopeType("TASK");
            policy.setScopeKey("TASK:" + task.getId());
            policy.setScopeName("任务 " + task.getId() + " Autopilot 恢复覆盖");
            policy.setPolicyCode(POLICY_CODE);
            policy.setPolicyName("Autopilot 受治理恢复覆盖");
            policy.setSyncTaskId(task.getId());
            policy.setCreatedBy(actor == null ? null : actor.actorId());
            policy.setCreateTime(LocalDateTime.now(ZoneOffset.UTC));
        }
        policy.setEnabled(true);
        policy.setTargetRowsPerShard(baseline.getTargetRowsPerShard());
        policy.setMaxChannel(integerParameter(parameters, "maxChannel", baseline.getResolvedChannel()));
        policy.setTaskGroupSize(baseline.getTaskGroupSize());
        policy.setReadBatchSize(integerParameter(parameters, "readBatchSize", baseline.getReadBatchSize()));
        policy.setWriteBatchSize(integerParameter(parameters, "writeBatchSize", baseline.getWriteBatchSize()));
        policy.setCommitIntervalRecords(baseline.getCommitIntervalRecords());
        policy.setTimeoutSeconds(integerParameter(parameters, "timeoutSeconds", baseline.getTimeoutSeconds()));
        policy.setMaxRetryCount(baseline.getMaxRetryCount());
        policy.setMaxDirtyRecordCount(baseline.getMaxDirtyRecordCount());
        policy.setMaxDirtyRecordRatio(baseline.getMaxDirtyRecordRatio());
        policy.setPriority(10_000);
        policy.setDescription(writePolicyBinding(command, recoveryCase, task, description));
        policy.setUpdatedBy(actor == null ? null : actor.actorId());
        policy.setUpdateTime(LocalDateTime.now(ZoneOffset.UTC));
        if (insert) {
            policyMapper.insert(policy);
        } else {
            policyMapper.updateById(policy);
        }
    }

    /**
     * 生成临时策略覆盖的结构化绑定信息，不包含错误正文、字段值或模型输出。
     *
     * <p>描述字段在这里不是展示用自由文本，而是执行策略解析器的 fail-closed 输入。序列化失败意味着系统无法证明
     * 覆盖只属于本次恢复，因此直接抛错并回滚事务，不能退化为无边界的普通 TASK 策略。</p>
     */
    private String writePolicyBinding(
            SyncAutopilotRecoveryRepairCommand command,
            SyncAutopilotRecoveryCase recoveryCase,
            SyncTask task,
            String businessPurpose) {
        Map<String, Object> binding = new LinkedHashMap<>();
        binding.put("bindingType", POLICY_CODE);
        binding.put("caseId", command.caseId());
        binding.put("tenantId", command.tenantId());
        binding.put("projectId", command.projectId());
        binding.put("taskId", task.getId());
        binding.put("executionId", command.executionId());
        binding.put("authorizationDigest", command.authorizationDigest());
        binding.put("policyDigest", command.policyDigest());
        binding.put("deadlineAt", recoveryCase.getDeadlineAt().toString());
        binding.put("action", command.action().name());
        binding.put("businessPurpose", businessPurpose);
        try {
            return objectMapper.writeValueAsString(binding);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Autopilot temporary policy binding cannot be encoded", exception);
        }
    }

    /** 比较建议参数与本次失败 execution 的真实快照。 */
    private String validateTuning(SyncExecutionPolicySnapshot baseline, Map<String, Object> parameters) {
        if (parameters.isEmpty()) {
            return "AUTOPILOT_TUNING_PARAMETERS_MISSING";
        }
        if (!decreasedOrEqual(parameters, "maxChannel", baseline.getResolvedChannel())
                || !decreasedOrEqual(parameters, "readBatchSize", baseline.getReadBatchSize())
                || !decreasedOrEqual(parameters, "writeBatchSize", baseline.getWriteBatchSize())) {
            return "AUTOPILOT_TUNING_MAY_NOT_INCREASE_LOAD";
        }
        Integer timeout = integerParameter(parameters, "timeoutSeconds", baseline.getTimeoutSeconds());
        if (parameters.containsKey("timeoutSeconds")) {
            int current = positive(baseline.getTimeoutSeconds(), 600);
            if (timeout == null || timeout < current || timeout > Math.min(3_600, current * 2)) {
                return "AUTOPILOT_TUNING_TIMEOUT_OUT_OF_BOUNDS";
            }
        }
        return null;
    }

    private boolean decreasedOrEqual(Map<String, Object> parameters, String name, Integer baseline) {
        if (!parameters.containsKey(name)) {
            return true;
        }
        Integer value = integerParameter(parameters, name, null);
        return value != null && value > 0 && value <= positive(baseline, Integer.MAX_VALUE);
    }

    private int positive(Integer value, int fallback) {
        return value == null || value <= 0 ? fallback : value;
    }

    private Integer integerParameter(Map<String, Object> parameters, String name, Integer fallback) {
        Object value = parameters.get(name);
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        return fallback;
    }

    private SyncExecutionPolicySnapshot snapshotFor(Long executionId, Long taskId) {
        return snapshotMapper.selectOne(new LambdaQueryWrapper<SyncExecutionPolicySnapshot>()
                .eq(SyncExecutionPolicySnapshot::getSyncTaskId, taskId)
                .eq(SyncExecutionPolicySnapshot::getExecutionId, executionId)
                .last("LIMIT 1"));
    }

    /** 对命令形状和逐动作参数白名单做第一层 fail-closed 校验。 */
    private Map<String, Object> requireCommand(SyncAutopilotRecoveryRepairCommand command) {
        if (command == null || command.caseId() == null || command.caseId() <= 0
                || command.expectedVersion() == null || command.expectedVersion() < 0
                || command.tenantId() == null || command.tenantId() <= 0
                || command.projectId() == null || command.projectId() <= 0
                || command.syncTaskId() == null || command.syncTaskId() <= 0
                || command.executionId() == null || command.executionId() <= 0
                || command.cycle() == null || command.cycle() <= 0
                || command.action() == null || !command.action().isAutomaticLowRiskWhitelisted()
                || !sha256(command.authorizationDigest()) || !sha256(command.policyDigest())
                || !sha256(command.actionFingerprint())
                || command.receiptId() == null || !SAFE_RECEIPT_ID.matcher(command.receiptId()).matches()
                || !command.receiptId().endsWith(RECEIPT_SUFFIX)) {
            throw badRequest("Autopilot repair command is incomplete");
        }
        Map<String, Object> parameters = command.repairParameters() == null
                ? Map.of() : new LinkedHashMap<>(command.repairParameters());
        Map<String, Object> expected = switch (command.action()) {
            case ROLLBACK_EXECUTION_POLICY -> Map.of("rollbackTarget", "LAST_SUCCESSFUL_EXECUTION");
            case REFRESH_METADATA -> Map.of("forceRefresh", true);
            case RESUME_FROM_CHECKPOINT -> Map.of("checkpointSelector", "LATEST_PERSISTED");
            case REPLAY_FAILED_SHARDS -> Map.of(
                    "objectState", "FAILED", "workUnitType", "PARTITION_SHARD");
            case REPAIR_FIELD_MAPPING -> Map.of("repairMode", "METADATA_PROVEN_SAFE");
            case TUNE_EXECUTION_POLICY -> validateTuningShape(parameters);
            default -> throw badRequest("Autopilot repair action is outside the governed catalog");
        };
        if (!Objects.equals(expected, parameters)) {
            throw badRequest("Autopilot repair parameters do not match the action contract");
        }
        return Map.copyOf(parameters);
    }

    private Map<String, Object> validateTuningShape(Map<String, Object> parameters) {
        List<String> allowed = List.of("maxChannel", "readBatchSize", "writeBatchSize", "timeoutSeconds");
        if (parameters.isEmpty() || parameters.keySet().stream().anyMatch(key -> !allowed.contains(key))) {
            throw badRequest("Autopilot tuning parameters are outside the whitelist");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : allowed) {
            if (!parameters.containsKey(key)) {
                continue;
            }
            Object value = parameters.get(key);
            if (!(value instanceof Number number)) {
                throw badRequest("Autopilot tuning parameter must be an integer");
            }
            int normalized = number.intValue();
            int maximum = "maxChannel".equals(key) ? 64
                    : "timeoutSeconds".equals(key) ? 3_600 : 100_000;
            if (normalized <= 0 || normalized > maximum || number.doubleValue() != normalized) {
                throw badRequest("Autopilot tuning parameter is outside the hard boundary");
            }
            result.put(key, normalized);
        }
        return result;
    }

    private SyncAutopilotRecoveryCase requireCase(Long caseId) {
        SyncAutopilotRecoveryCase recoveryCase = caseMapper.selectByCaseId(caseId);
        if (recoveryCase == null) {
            throw new PlatformBusinessException(PlatformErrorCode.NOT_FOUND,
                    "Autopilot recovery case not found: " + caseId);
        }
        return recoveryCase;
    }

    private void requireCaseScope(SyncAutopilotRecoveryCase recoveryCase,
                                  SyncAutopilotRecoveryRepairCommand command) {
        if (!Objects.equals(recoveryCase.getTenantId(), command.tenantId())
                || !Objects.equals(recoveryCase.getProjectId(), command.projectId())
                || !Objects.equals(recoveryCase.getSyncTaskId(), command.syncTaskId())
                || !Objects.equals(recoveryCase.getCurrentExecutionId(), command.executionId())) {
            throw new PlatformBusinessException(PlatformErrorCode.FORBIDDEN,
                    "Autopilot repair is outside the authorized scope");
        }
    }

    private void requireExecutableCase(SyncAutopilotRecoveryCase recoveryCase,
                                       SyncAutopilotRecoveryRepairCommand command) {
        if (!SyncAutopilotRecoveryCaseState.AUTO_APPROVED.name().equals(recoveryCase.getCaseState())
                || !Objects.equals(recoveryCase.getVersion(), command.expectedVersion())
                || !Objects.equals(recoveryCase.getCycle(), command.cycle())
                || recoveryCase.getCycle() == null || recoveryCase.getMaxCycles() == null
                || recoveryCase.getCycle() > recoveryCase.getMaxCycles()
                || recoveryCase.getDeadlineAt() == null
                || !recoveryCase.getDeadlineAt().isAfter(LocalDateTime.now(ZoneOffset.UTC))
                || !command.action().name().equals(recoveryCase.getRecoveryAction())
                || !SyncAutopilotRiskLevel.LOW.name().equals(recoveryCase.getRiskLevel())) {
            throw conflict("Autopilot recovery case is not executable inside the authorization boundary");
        }
    }

    private SyncTask requireTask(SyncAutopilotRecoveryRepairCommand command) {
        SyncTask task = taskMapper.selectById(command.syncTaskId());
        if (task == null || !Objects.equals(task.getTenantId(), command.tenantId())
                || !Objects.equals(task.getProjectId(), command.projectId())) {
            throw conflict("Autopilot repair task or scope is unavailable");
        }
        return task;
    }

    private SyncTaskDefinition requireDefinition(SyncAutopilotRecoveryRepairCommand command, SyncTask task) {
        SyncTaskDefinition definition = definitionMapper.selectById(command.syncTaskId());
        if (definition == null || definition.getAutopilotPolicy() == null
                || definition.getAutopilotPolicy().isBlank()
                || !Objects.equals(definition.getTenantId(), task.getTenantId())
                || !Objects.equals(definition.getProjectId(), task.getProjectId())) {
            throw conflict("Autopilot repair task definition or policy is unavailable");
        }
        return definition;
    }

    private SyncExecution requireExecution(SyncAutopilotRecoveryRepairCommand command, SyncTask task) {
        SyncExecution execution = executionMapper.selectById(command.executionId());
        if (execution == null || !Objects.equals(execution.getSyncTaskId(), task.getId())
                || !Objects.equals(execution.getTenantId(), task.getTenantId())
                || !Objects.equals(execution.getProjectId(), task.getProjectId())) {
            throw conflict("Autopilot repair execution is outside the task scope");
        }
        return execution;
    }

    /** 使用持久策略重新计算自动批准，防止决策后策略或授权发生变化。 */
    private void requireCurrentAuthorization(
            SyncAutopilotRecoveryCase recoveryCase,
            SyncTaskDefinition definition,
            SyncAutopilotRecoveryRepairCommand command) {
        SyncAutopilotRecoveryPolicyDecision decision = policyEvaluator.evaluate(
                definition.getAutopilotPolicy(),
                new SyncAutopilotRecoveryEvaluationRequest(
                        SyncAutopilotExecutionMode.AUTOPILOT,
                        command.tenantId(), command.projectId(), command.syncTaskId(), command.cycle(),
                        recoveryCase.getDeadlineAt(), recoveryCase.getLastErrorFingerprint(),
                        recoveryCase.getRepeatedErrorCount(), command.action(), SyncAutopilotRiskLevel.LOW,
                        command.actionFingerprint(), command.receiptId(), 100, true,
                        false, LocalDateTime.now(ZoneOffset.UTC)));
        if (decision.state() != SyncAutopilotRecoveryCaseState.AUTO_APPROVED
                || !Objects.equals(decision.authorizationDigest(), recoveryCase.getAuthorizationDigest())
                || !Objects.equals(decision.policyDigest(), recoveryCase.getPolicyDigest())
                || !Objects.equals(command.authorizationDigest(), recoveryCase.getAuthorizationDigest())
                || !Objects.equals(command.policyDigest(), recoveryCase.getPolicyDigest())) {
            throw conflict("Autopilot repair authorization or policy digest has changed");
        }
    }

    /**
     * 将当前内部请求双主体重新绑定到首次授权快照，防止“令牌可信但 Header 主体被拼错”的越权路径。
     */
    private void requirePrincipalBinding(
            SyncTaskDefinition definition,
            SyncAutopilotRecoveryPrincipalContext principal) {
        if (!policyEvaluator.matchesPrincipalBinding(
                definition.getAutopilotPolicy(), principal.representedActorId(),
                principal.agentId(), principal.delegationId())) {
            throw new PlatformBusinessException(PlatformErrorCode.FORBIDDEN,
                    "Autopilot repair principal does not match the persisted authorization");
        }
    }

    /**
     * 复用普通任务管理动作的项目权限边界，并证明领域上下文来自同一个被代理用户。
     *
     * <p>checkpoint replay 调用的是底层恢复计划组件，该组件假设上层已经完成权限校验；所以 repair 入口必须在进入
     * switch 前统一执行本方法，不能只在某一个动作里补检查。actor 中的 PROJECT 范围和项目角色来自 Agent Runtime
     * 从持久 session 恢复后透传的 Header，不能由 repair body 自行声明。</p>
     */
    private void requireManageableTask(
            SyncAutopilotRecoveryRepairCommand command,
            SyncTask task,
            SyncAutopilotRecoveryPrincipalContext principal,
            SyncActorContext actor) {
        if (actor == null || actor.actorId() == null
                || !Objects.equals(actor.tenantId(), command.tenantId())
                || !Objects.equals(actor.projectId(), command.projectId())
                || !Objects.equals(String.valueOf(actor.actorId()), principal.representedActorId().trim())) {
            throw new PlatformBusinessException(PlatformErrorCode.FORBIDDEN,
                    "Autopilot repair actor context is outside the persisted authorization scope");
        }
        dataScopeSupport.validateProjectManageable(
                task.getTenantId(), task.getProjectId(), task.getWorkspaceId(), actor,
                "Autopilot 受治理恢复动作");
    }

    /** 复算 Python/Java/data-sync 三方共享的动作指纹。 */
    private void requireActionFingerprint(
            SyncAutopilotRecoveryCase recoveryCase,
            SyncAutopilotRecoveryRepairCommand command,
            Map<String, Object> parameters) {
        String eventId = command.receiptId().substring(
                0, command.receiptId().length() - RECEIPT_SUFFIX.length());
        String canonicalParameters = new TreeMap<>(parameters).entrySet().stream()
                .map(entry -> entry.getKey() + "=" + canonicalValue(entry.getValue()))
                .reduce((left, right) -> left + "," + right).orElse("");
        String expected = SyncAutopilotDigestSupport.sha256(String.join("|",
                eventId, recoveryCase.getLastErrorFingerprint(), String.valueOf(command.executionId()),
                command.action().name(), canonicalParameters));
        if (!Objects.equals(expected, command.actionFingerprint())
                || !Objects.equals(expected, recoveryCase.getRepairFingerprint())) {
            throw conflict("Autopilot repair fingerprint does not match the governed parameters");
        }
    }

    private String canonicalValue(Object value) {
        return value instanceof Boolean bool ? String.valueOf(bool).toLowerCase() : String.valueOf(value);
    }

    private String requestDigest(
            SyncAutopilotRecoveryRepairCommand command,
            SyncAutopilotRecoveryPrincipalContext principal,
            Map<String, Object> parameters) {
        String canonicalParameters = new TreeMap<>(parameters).entrySet().stream()
                .map(entry -> entry.getKey() + "=" + canonicalValue(entry.getValue()))
                .reduce((left, right) -> left + "," + right).orElse("");
        return SyncAutopilotDigestSupport.sha256(String.join("|",
                String.valueOf(command.caseId()), String.valueOf(command.expectedVersion()),
                String.valueOf(command.tenantId()), String.valueOf(command.projectId()),
                String.valueOf(command.syncTaskId()), String.valueOf(command.executionId()),
                String.valueOf(command.cycle()), command.authorizationDigest(), command.policyDigest(),
                command.action().name(), command.actionFingerprint(), command.receiptId(),
                canonicalParameters, principal.representedActorId(), principal.agentId(),
                principal.delegationId()));
    }

    private SyncAutopilotRecoveryRepairReceiptView replay(
            SyncAutopilotRecoveryRepairCommand command,
            String scopeKey,
            String requestDigest) {
        SyncCallbackIdempotency record = idempotencySupport.findRecord(
                command.tenantId(), IDEMPOTENCY_ACTION, scopeKey, command.receiptId());
        if (record == null || !Objects.equals(record.getRequestDigest(), requestDigest)) {
            throw conflict("Autopilot repair receipt was reused with different facts");
        }
        if (record.getResponseSummary() == null || record.getResponseSummary().isBlank()) {
            throw conflict("Autopilot repair is still processing");
        }
        try {
            return objectMapper.readValue(record.getResponseSummary(),
                    SyncAutopilotRecoveryRepairReceiptView.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Autopilot repair receipt cannot be decoded", exception);
        }
    }

    private String writeResult(SyncAutopilotRecoveryRepairReceiptView result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Autopilot repair receipt cannot be encoded", exception);
        }
    }

    private SyncAutopilotRecoveryRepairReceiptView applied(
            SyncAutopilotRecoveryRepairCommand command,
            Long sourceExecutionId,
            Long executionId,
            int affectedCount,
            String executionState,
            String taskState,
            String reasonCode,
            List<String> issueCodes) {
        return new SyncAutopilotRecoveryRepairReceiptView(
                command.receiptId(), command.caseId(), command.syncTaskId(), sourceExecutionId,
                executionId, command.action().name(), true, affectedCount, executionState, taskState,
                reasonCode, issueCodes == null ? List.of() : List.copyOf(issueCodes),
                command.actionFingerprint(), "AUTO_APPROVED", false, null, null);
    }

    private SyncAutopilotRecoveryRepairReceiptView notApplied(
            SyncAutopilotRecoveryRepairCommand command,
            SyncExecution execution,
            String reasonCode) {
        return notApplied(command, execution, reasonCode, List.of(reasonCode));
    }

    private SyncAutopilotRecoveryRepairReceiptView notApplied(
            SyncAutopilotRecoveryRepairCommand command,
            SyncExecution execution,
            String reasonCode,
            List<String> issueCodes) {
        return new SyncAutopilotRecoveryRepairReceiptView(
                command.receiptId(), command.caseId(), command.syncTaskId(), execution.getId(),
                execution.getId(), command.action().name(), false, 0,
                execution.getExecutionState(), null, reasonCode,
                issueCodes == null ? List.of() : List.copyOf(issueCodes), command.actionFingerprint(),
                "AUTO_APPROVED", false, null, null);
    }

    private void requirePrincipal(SyncAutopilotRecoveryPrincipalContext principal) {
        if (principal == null || blank(principal.representedActorId()) || blank(principal.actorRole())
                || blank(principal.agentId()) || blank(principal.delegationId())) {
            throw new PlatformBusinessException(PlatformErrorCode.FORBIDDEN,
                    "Autopilot repair requires dual-principal delegation facts");
        }
    }

    private List<SyncObjectExecution> safeObjects(List<SyncObjectExecution> value) {
        return value == null ? List.of() : value;
    }

    private boolean sha256(String value) {
        return value != null && SHA_256.matcher(value).matches();
    }

    private boolean blank(String value) {
        return value == null || value.isBlank() || value.trim().length() > 128;
    }

    private PlatformBusinessException badRequest(String message) {
        return new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST, message);
    }

    private PlatformBusinessException conflict(String message) {
        return new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT, message);
    }
}
