/**
 * @Author : Cui
 * @Date: 2026/08/11 18:50
 * @Description DataSmart Govern Backend - SyncAutopilotRecoveryTriggerPublisher.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.datasync.entity.SyncAutopilotRecoveryCase;
import com.czh.datasmart.govern.datasync.entity.SyncExecution;
import com.czh.datasmart.govern.datasync.entity.SyncExecutionPolicy;
import com.czh.datasmart.govern.datasync.entity.SyncTask;
import com.czh.datasmart.govern.datasync.entity.SyncTaskDefinition;
import com.czh.datasmart.govern.datasync.mapper.SyncAutopilotRecoveryCaseMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncExecutionPolicyMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncTaskDefinitionMapper;
import com.czh.datasmart.govern.datasync.support.SyncAutopilotRecoveryReceiptType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 把 data-sync 的 execution failed 事实转换成 durable Autopilot 恢复触发事件。
 *
 * <p>本类不调用模型、不选择修复动作，也不直接重跑任务。它只做三件事：</p>
 * <p>1. 从任务定义读取首次确认后持久化的授权快照，并验证租户/项目/有效期；</p>
 * <p>2. 计算错误指纹、轮次和重复次数，关闭上一轮已失败的恢复案例；</p>
 * <p>3. 把低敏触发合同交给 outbox，由 Kafka 异步唤醒 Agent Runtime。</p>
 *
 * <p>这种拆分保证“任务失败”不会因为 Python 或 Kafka 暂时不可用而丢失，同时也避免
 * data-sync 越权替模型决定检索策略或修复方案。</p>
 */
@Slf4j
@Component
public class SyncAutopilotRecoveryTriggerPublisher {

    private static final String SCHEMA_VERSION = "datasmart.autopilot.recovery-trigger.v1";
    private static final int MAX_ISSUE_CODES = 20;

    private final SyncTaskDefinitionMapper definitionMapper;
    private final SyncAutopilotRecoveryCaseMapper caseMapper;
    private final SyncAutopilotRecoveryCaseService caseService;
    private final SyncAutopilotRecoveryTriggerOutboxService outboxService;
    private final SyncExecutionPolicyMapper executionPolicyMapper;
    private final ObjectMapper objectMapper;
    private final SyncAutopilotRecoveryMetrics metrics;

    /**
     * Spring 生产构造器，把持久化依赖和低基数观测组件一次性注入。
     *
     * @param definitionMapper 读取任务定义及首次确认后的 Autopilot 授权快照
     * @param caseMapper 查找当前 execution 对应的 active recovery case
     * @param caseService 通过 receipt 和乐观锁推进 recovery case
     * @param outboxService 原子写入并尝试投递 Kafka trigger
     * @param executionPolicyMapper 在恢复成功后禁用临时任务级策略覆盖
     * @param objectMapper 解析并规范化持久授权快照
     * @param metrics 记录 trigger 与最终恢复结果，不承载业务 ID
     */
    @Autowired
    public SyncAutopilotRecoveryTriggerPublisher(
            SyncTaskDefinitionMapper definitionMapper,
            SyncAutopilotRecoveryCaseMapper caseMapper,
            SyncAutopilotRecoveryCaseService caseService,
            SyncAutopilotRecoveryTriggerOutboxService outboxService,
            SyncExecutionPolicyMapper executionPolicyMapper,
            ObjectMapper objectMapper,
            SyncAutopilotRecoveryMetrics metrics) {
        this.definitionMapper = definitionMapper;
        this.caseMapper = caseMapper;
        this.caseService = caseService;
        this.outboxService = outboxService;
        this.executionPolicyMapper = executionPolicyMapper;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
    }

    /**
     * 单元测试兼容构造器，使既有业务测试可以只关注持久化与策略，不必创建 MeterRegistry。
     *
     * <p>生产 Spring 容器始终选择上面的六参数构造器；这里的 {@code metrics=null} 只关闭测试指标，
     * 不改变 trigger、case、receipt 或 outbox 行为。</p>
     */
    SyncAutopilotRecoveryTriggerPublisher(
            SyncTaskDefinitionMapper definitionMapper,
            SyncAutopilotRecoveryCaseMapper caseMapper,
            SyncAutopilotRecoveryCaseService caseService,
            SyncAutopilotRecoveryTriggerOutboxService outboxService,
            ObjectMapper objectMapper) {
        this(definitionMapper, caseMapper, caseService, outboxService, null, objectMapper, null);
    }

    /**
     * 在持久授权有效时，把 execution 失败事实转换为受治理、可持久恢复触发器。
     *
     * <p>task 与 execution 是 data-sync 权威实体；{@code errorCode} 和 {@code issueCodes} 只会清洗后生成
     * 稳定指纹，不传输原始错误正文。方法在独立事务内重新读取并白名单化授权快照，验证任务、定义和 execution
     * 的租户/项目范围，计算有界恢复轮次，必要时为上一活动 case 记录失败回执，最后通过持久 outbox 写入低敏事件。
     * 它不会调用模型、重试 worker 或直接发布任意消息。</p>
     *
     * <p>授权缺失、过期、损坏、未激活或越界时直接安全返回，不改变已持久化同步失败。派生事件 ID 使同一失败轮次
     * 在 outbox 边界幂等；上一轮恢复失败只能通过自身回执推进，循环耗尽、重复错误和 deadline 会在创建新事件前
     * 阻断。Agent Runtime 仍必须重新验证授权后才能提出修复。</p>
     *
     * @param task 拥有失败 execution 的持久任务
     * @param execution 属于该任务的持久失败 execution
     * @param errorCode 主要低敏失败码，任意正文会先被清洗
     * @param issueCodes 可选次级原因码，只用于有界指纹和诊断列表
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void publishFailed(SyncTask task,
                              SyncExecution execution,
                              String errorCode,
                              List<String> issueCodes) {
        if (!validScopeInputs(task, execution)) {
            recordTriggerRejected();
            return;
        }
        SyncTaskDefinition definition = definitionMapper.selectById(task.getId());
        if (definition == null || definition.getAutopilotPolicy() == null
                || definition.getAutopilotPolicy().isBlank()) {
            recordTriggerRejected();
            return;
        }

        ParsedAuthorization authorization;
        try {
            authorization = parseAuthorization(definition.getAutopilotPolicy());
        } catch (RuntimeException exception) {
            /*
             * 授权快照损坏属于配置事实不可信：安全做法是跳过自动恢复，同时不记录原始 JSON、
             * 异常正文或其他可能含敏感信息的字段。
             */
            log.warn("Autopilot recovery trigger was skipped, taskId={}, executionId={}, exceptionType={}",
                    task.getId(), execution.getId(), exception.getClass().getSimpleName());
            recordTriggerRejected();
            return;
        }
        if (!authorization.active() || !scopeMatches(task, execution, definition, authorization)) {
            recordTriggerRejected();
            return;
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (!isAfterInstant(authorization.expiresAt(), now)) {
            recordTriggerRejected();
            return;
        }

        String fingerprint = errorFingerprint(errorCode, issueCodes);
        SyncAutopilotRecoveryCase active = caseMapper.selectRecoveringByCurrentExecution(
                task.getTenantId(), task.getId(), execution.getId());
        Long rootExecutionId = active == null ? execution.getId() : active.getRootExecutionId();
        int cycle = active == null ? 1 : Math.max(1, active.getCycle()) + 1;
        int repeatedErrorCount = active == null ? 0
                : Objects.equals(active.getLastErrorFingerprint(), fingerprint)
                ? Math.max(0, active.getRepeatedErrorCount()) + 1
                : 0;
        String previousRepairFingerprint = active == null ? null : active.getRepairFingerprint();
        OffsetDateTime deadlineAt = active != null && active.getDeadlineAt() != null
                ? OffsetDateTime.of(active.getDeadlineAt(), ZoneOffset.UTC)
                : earliest(now.plusMinutes(authorization.maxTotalDurationMinutes()),
                authorization.expiresAt());

        if (active != null) {
            recordFailedRecoveryAttempt(active, task, execution, cycle, fingerprint, repeatedErrorCount);
        }
        if (cycle > authorization.maxRecoveryCycles()
                || repeatedErrorCount >= authorization.maxRepeatedErrorCount()
                || !isAfterInstant(deadlineAt, now)) {
            log.info("Autopilot recovery trigger stopped by bounded loop guard, taskId={}, executionId={}, cycle={}",
                    task.getId(), execution.getId(), cycle);
            recordTriggerRejected();
            return;
        }

        SyncAutopilotRecoveryTriggerEvent event = new SyncAutopilotRecoveryTriggerEvent(
                SCHEMA_VERSION,
                eventId(authorization, task.getId(), rootExecutionId, execution.getId(), cycle, fingerprint),
                authorization.rootSessionId(),
                authorization.rootRunId(),
                authorization.tenantId(),
                authorization.applicationId(),
                authorization.projectId(),
                authorization.userId(),
                authorization.actorId(),
                authorization.agentId(),
                authorization.delegationId(),
                task.getId(),
                rootExecutionId,
                execution.getId(),
                cycle,
                authorization.maxRecoveryCycles(),
                deadlineAt.toString(),
                fingerprint,
                repeatedErrorCount,
                previousRepairFingerprint,
                safeIssueCodes(errorCode, issueCodes),
                authorization.snapshot(),
                authorization.snapshotDigest(),
                now.toString()
        );
        outboxService.enqueueAndDispatch(event);
        if (metrics != null) {
            metrics.recordTriggerAccepted();
        }
    }

    /**
     * 将一次确定性的“受治理修复未应用”结论转换为下一轮 Recovery 触发事件。
     *
     * <p>这与普通 execution 再次失败不同：修复动作可能尚未创建新的 execution，例如元数据刷新发现了
     * 字段映射问题。若此处直接结束，模型就永远没有机会消费新发现的预检问题。本方法先使用
     * {@code RECOVERY_FAILED} 回执把旧 case 收敛到 {@code ATTENTION_REQUIRED}，再把修复原因、上一动作和
     * 问题码写入下一轮低敏事件。下一轮仍由 Python 模型自主选择其他受治理动作，data-sync 不替模型指定方案。</p>
     *
     * <p>调用方必须位于修复事务内。case 迁移、修复幂等回执和 outbox 因而原子提交；事务回滚时不会留下
     * 孤立事件。循环次数、重复次数、授权有效期或 deadline 任一耗尽时，只收敛旧 case，不再产生事件。
     * 重复调用会使用稳定的迁移回执和事件 ID，由 receipt/outbox 唯一约束安全重放。</p>
     *
     * @param task 当前失败 execution 所属的权威任务
     * @param execution 尚未恢复成功的权威 execution
     * @param activeCase 本轮已经自动批准、但修复前提不成立的 recovery case
     * @param reasonCode data-sync 产生的固定低敏修复结论码
     * @param issueCodes 修复动作新发现的固定低敏问题码
     * @return 是否写入下一轮事件以及下一轮身份
     */
    @Transactional
    public SyncAutopilotRecoveryRepairReplanResult publishRepairNotApplied(
            SyncTask task,
            SyncExecution execution,
            SyncAutopilotRecoveryCase activeCase,
            String reasonCode,
            List<String> issueCodes) {
        if (!validScopeInputs(task, execution) || activeCase == null
                || activeCase.getCaseId() == null || activeCase.getVersion() == null
                || !Objects.equals(activeCase.getTenantId(), task.getTenantId())
                || !Objects.equals(activeCase.getProjectId(), task.getProjectId())
                || !Objects.equals(activeCase.getSyncTaskId(), task.getId())
                || !Objects.equals(activeCase.getCurrentExecutionId(), execution.getId())
                || !"AUTO_APPROVED".equals(activeCase.getCaseState())) {
            throw new IllegalArgumentException("Autopilot repair replan scope is invalid");
        }

        SyncTaskDefinition definition = definitionMapper.selectById(task.getId());
        if (definition == null || definition.getAutopilotPolicy() == null
                || definition.getAutopilotPolicy().isBlank()) {
            throw new IllegalArgumentException("Autopilot repair replan authorization is missing");
        }
        ParsedAuthorization authorization = parseAuthorization(definition.getAutopilotPolicy());
        if (!authorization.active() || !scopeMatches(task, execution, definition, authorization)) {
            throw new IllegalArgumentException("Autopilot repair replan authorization scope is invalid");
        }

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        // recovery case 使用 PostgreSQL timestamp 保存截止时间，数据库只保留微秒，纳秒值可能向上舍入。
        // 因此即使 case 已有 deadline，也必须再次与原始授权 expiresAt 取较早瞬时：既不扩大授权，
        // 也避免 100 纳秒级的持久化精度差让下一轮被 Agent Runtime 误判为授权过期。
        OffsetDateTime persistedDeadline = activeCase.getDeadlineAt() == null
                ? now.plusMinutes(authorization.maxTotalDurationMinutes())
                : OffsetDateTime.of(activeCase.getDeadlineAt(), ZoneOffset.UTC);
        OffsetDateTime deadlineAt = earliest(persistedDeadline, authorization.expiresAt());
        int nextCycle = Math.max(1, activeCase.getCycle() == null ? 1 : activeCase.getCycle()) + 1;
        int repeatedErrorCount = Math.max(0,
                activeCase.getRepeatedErrorCount() == null ? 0 : activeCase.getRepeatedErrorCount()) + 1;

        List<String> enrichedIssues = new ArrayList<>();
        enrichedIssues.add("PREVIOUS_REPAIR_ACTION_" + safeCode(activeCase.getRecoveryAction()));
        if (issueCodes != null) {
            enrichedIssues.addAll(issueCodes);
        }
        List<String> safeIssues = safeIssueCodes(reasonCode, enrichedIssues);
        String repairFailureFingerprint = errorFingerprint(reasonCode, safeIssues);
        String transitionReceiptId = "autopilot-repair-not-applied:"
                + SyncAutopilotDigestSupport.sha256(String.join("|",
                String.valueOf(activeCase.getCaseId()), String.valueOf(activeCase.getVersion()),
                repairFailureFingerprint));
        caseService.recordTransition(new SyncAutopilotRecoveryTransitionCommand(
                activeCase.getCaseId(), activeCase.getVersion(), transitionReceiptId,
                SyncAutopilotRecoveryReceiptType.RECOVERY_FAILED, execution.getId(), nextCycle,
                repairFailureFingerprint, repeatedErrorCount, safeIssues.getFirst()));
        disableTemporaryPolicyOverride(task,
                "Autopilot 受治理修复未满足应用前提，临时覆盖已禁用，等待下一轮重新规划");

        int maximumCycles = Math.min(authorization.maxRecoveryCycles(),
                activeCase.getMaxCycles() == null ? authorization.maxRecoveryCycles() : activeCase.getMaxCycles());
        if (nextCycle > maximumCycles
                || repeatedErrorCount >= authorization.maxRepeatedErrorCount()
                || !isAfterInstant(authorization.expiresAt(), now)
                || !isAfterInstant(deadlineAt, now)) {
            recordTriggerRejected();
            return new SyncAutopilotRecoveryRepairReplanResult(false, null, nextCycle);
        }

        String eventId = eventId(authorization, task.getId(), activeCase.getRootExecutionId(),
                execution.getId(), nextCycle, repairFailureFingerprint);
        SyncAutopilotRecoveryTriggerEvent event = new SyncAutopilotRecoveryTriggerEvent(
                SCHEMA_VERSION, eventId, authorization.rootSessionId(), authorization.rootRunId(),
                authorization.tenantId(), authorization.applicationId(), authorization.projectId(),
                authorization.userId(), authorization.actorId(), authorization.agentId(),
                authorization.delegationId(), task.getId(), activeCase.getRootExecutionId(), execution.getId(),
                nextCycle, maximumCycles, deadlineAt.toString(), repairFailureFingerprint,
                repeatedErrorCount, activeCase.getRepairFingerprint(), safeIssues,
                authorization.snapshot(), authorization.snapshotDigest(), now.toString());
        outboxService.enqueueAndDispatch(event);
        if (metrics != null) {
            metrics.recordTriggerAccepted();
        }
        return new SyncAutopilotRecoveryRepairReplanResult(true, eventId, nextCycle);
    }

    /**
     * 当 Autopilot 重新入队的 execution 成功后，关闭对应的活动恢复 case。
     *
     * <p>方法接收权威任务和执行实体，首先证明二者处于同一范围；随后只查询该租户、任务和当前执行下
     * 状态为 {@code RECOVERY_STARTED} 的 case，并通过 case 服务记录 {@code RECOVERY_SUCCEEDED} 回执。
     * 独立事务可避免控制面写入失败回滚 worker 已成功提交的业务事务或 task-management 回执。</p>
     *
     * <p>固定回执 ID 同时包含 case 和 execution ID，重复成功回调会安全重放，不会重复递增乐观锁版本。
     * 找不到活动 case 时保持无操作；方法不会把任意 case 标记为已恢复，也不会绕过 case 服务的状态与范围校验。</p>
     *
     * @param task 持有已完成 execution 的持久任务
     * @param execution 可能完成活动恢复 case 的成功执行
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void publishSucceeded(SyncTask task, SyncExecution execution) {
        if (!validScopeInputs(task, execution)) {
            return;
        }
        SyncAutopilotRecoveryCase active = caseMapper.selectRecoveringByCurrentExecution(
                task.getTenantId(), task.getId(), execution.getId());
        if (active == null) {
            return;
        }
        caseService.recordTransition(new SyncAutopilotRecoveryTransitionCommand(
                active.getCaseId(),
                active.getVersion(),
                "autopilot-recovery-succeeded:" + active.getCaseId() + ":" + execution.getId(),
                SyncAutopilotRecoveryReceiptType.RECOVERY_SUCCEEDED,
                execution.getId(),
                active.getCycle(),
                active.getLastErrorFingerprint(),
                active.getRepeatedErrorCount(),
                null
        ));
        disableTemporaryPolicyOverride(task, "Autopilot 恢复成功，临时覆盖已自动禁用并保留审计");
        if (metrics != null) {
            metrics.recordRecoverySucceeded();
        }
    }

    /**
     * 在恢复 execution 成功后禁用 Autopilot 临时任务级策略覆盖。
     *
     * <p>ROLLBACK/TUNE 动作创建的覆盖只服务当前恢复循环。如果成功后继续启用，后续定时任务
     * 会永久继承夜间故障处置参数。这里采用软禁用保留完整审计，重复成功回调也只会得到同一结果。
     * 测试兼容构造器没有注入 mapper 时直接跳过，不改变既有 case 状态机测试。</p>
     */
    private void disableTemporaryPolicyOverride(SyncTask task, String reason) {
        if (executionPolicyMapper == null || task == null || task.getId() == null) {
            return;
        }
        SyncExecutionPolicy override = executionPolicyMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SyncExecutionPolicy>()
                        .eq(SyncExecutionPolicy::getTenantId, task.getTenantId())
                        .eq(SyncExecutionPolicy::getScopeType, "TASK")
                        .eq(SyncExecutionPolicy::getScopeKey, "TASK:" + task.getId())
                        .eq(SyncExecutionPolicy::getPolicyCode, SyncAutopilotRecoveryRepairService.POLICY_CODE)
                        .last("LIMIT 1"));
        if (override == null || !Boolean.TRUE.equals(override.getEnabled())) {
            return;
        }
        override.setEnabled(Boolean.FALSE);
        override.setDescription(reason);
        override.setUpdateTime(LocalDateTime.now(ZoneOffset.UTC));
        executionPolicyMapper.updateById(override);
    }

    /**
     * 记录上一轮已启动恢复尝试失败，并将 case 转入需要重新规划的关注状态。
     *
     * <p>活动 case 与失败 execution 共同确定稳定的 {@code RECOVERY_FAILED} 回执。case 服务使用乐观锁执行
     * 唯一合法的状态迁移，更新最新错误事实并持久化关注原因。本方法不直接执行 SQL 修复或 worker 重试，
     * 唯一副作用是外围独立事务内、由回执支撑的 case 状态迁移。</p>
     *
     * <p>回执 ID 由 case/execution 确定，重复失败回调可安全重放。旧 case 到达
     * {@code ATTENTION_REQUIRED} 后，模型后续提案必须使用不同修复指纹创建新 case，不能复用失败方案死循环。</p>
     *
     * @param active 与上一轮 Autopilot 尝试关联的活动恢复 case
     * @param execution 该尝试中失败的执行
     * @param cycle 需要保留到 case 的下一受限恢复轮次
     * @param errorFingerprint 失败事实的低敏安全指纹
     * @param repeatedErrorCount 等价错误的有界重复次数
     */
    private void recordFailedRecoveryAttempt(SyncAutopilotRecoveryCase active,
                                             SyncTask task,
                                             SyncExecution execution,
                                             int cycle,
                                             String errorFingerprint,
                                             int repeatedErrorCount) {
        caseService.recordTransition(new SyncAutopilotRecoveryTransitionCommand(
                active.getCaseId(),
                active.getVersion(),
                "autopilot-recovery-failed:" + active.getCaseId() + ":" + execution.getId(),
                SyncAutopilotRecoveryReceiptType.RECOVERY_FAILED,
                execution.getId(),
                cycle,
                errorFingerprint,
                repeatedErrorCount,
                "RECOVERY_FAILED_REPLANNING"
        ));
        /*
         * 临时调参/回滚只允许服务上一轮恢复。该 execution 已再次失败后必须立即软禁用覆盖，
         * 否则下一轮重新规划期间或后续定时运行仍可能继承已经证明无效的参数。新一轮若再次选择
         * ROLLBACK/TUNE，会在新的 case/execution/截止时间绑定下重新启用并写入完整审计。
         */
        disableTemporaryPolicyOverride(task,
                "Autopilot 恢复执行失败，临时覆盖已自动禁用，等待下一轮重新规划");
        if (metrics != null) {
            metrics.recordRecoveryFailed();
        }
    }

    /**
     * 记录一次未产生 trigger 的安全拒绝，并兼容不启用指标的隔离单元测试。
     *
     * <p>拒绝原因保留在代码分支、日志或持久业务状态中，不作为 Prometheus 标签，避免 errorCode、对象 ID
     * 或授权字段形成高基数序列。该方法没有业务副作用。</p>
     */
    private void recordTriggerRejected() {
        if (metrics != null) {
            metrics.recordTriggerRejected();
        }
    }

    /**
     * 将持久化授权快照解析为白名单化、可安全传输的结构。
     *
     * <p>只把明确 ID、有界预算、动作码、时间戳和生命周期元数据复制到 {@link LinkedHashMap}；密码、SQL、
     * URL、checkpoint、模型字段或提示词等未识别 JSON 字段绝不会进入结果。白名单 Map 保持顺序并序列化后
     * 绑定 SHA-256，使 Agent Runtime 无需接收原始策略正文也能发现传输合同发生变化。</p>
     *
     * <p>解析过程不访问数据库、Kafka 或 worker，对等价 JSON 具有确定性。格式错误时失败关闭，允许
     * {@link #publishFailed(SyncTask, SyncExecution, String, List)} 安全跳过自动化。返回值本身不是执行权限；
     * 写入 outbox 前，调用方仍需把其范围、激活状态和过期时间与本地持久事实逐项比较。</p>
     *
     * @param policyJson 持久化在任务定义中的授权 JSON
     * @return 已清洗授权事实及白名单快照的稳定摘要
     * @throws IllegalArgumentException 快照格式错误或缺少安全必填字段时抛出
     */
    @SuppressWarnings("unchecked")
    private ParsedAuthorization parseAuthorization(String policyJson) {
        try {
            JsonNode root = objectMapper.readTree(policyJson);
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException("Autopilot policy must be a JSON object");
            }
            String state = optionalText(root, "state", "ACTIVE").toUpperCase(Locale.ROOT);
            String policyId = requiredText(root, "policyId", "authorizationId");
            String policyVersion = optionalText(
                    root, "policyVersion", "datasmart.autopilot.authorization.v1");
            String rootSessionId = requiredText(root, "rootSessionId");
            String rootRunId = requiredText(root, "rootRunId");
            Long tenantId = requiredLong(root, "tenantId");
            Long applicationId = nullableLong(root, "applicationId");
            Long projectId = nullableLong(root, "projectId");
            String userId = requiredText(root, "userId");
            String actorId = requiredText(root, "actorId");
            String agentId = requiredText(root, "agentId");
            String delegationId = requiredText(root, "delegationId");
            int maxCycles = boundedInt(root, "maxRecoveryCycles", 5, 1, 10);
            int durationMinutes = boundedInt(root, "maxTotalDurationMinutes", 120, 5, 1440);
            int maxRepeatedErrors = boundedInt(root, "maxRepeatedErrorCount", 3, 1, 10);
            String maxRisk = optionalText(root, "maxAutomaticRiskLevel", "LOW")
                    .toUpperCase(Locale.ROOT);
            List<String> allowedActions = safeCodes(root.path("allowedRecoveryActions"));
            List<String> approvalActions = safeCodes(root.path("requireApprovalFor"));
            OffsetDateTime issuedAt = parseDateTime(optionalText(
                    root, "issuedAt", OffsetDateTime.now(ZoneOffset.UTC).toString()));
            OffsetDateTime expiresAt = parseDateTime(requiredText(root, "expiresAt"));
            String policyDigest = optionalText(root, "policyDigest", null);

            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("policyId", policyId);
            snapshot.put("policyVersion", policyVersion);
            snapshot.put("executionMode", "AUTOPILOT");
            snapshot.put("state", state);
            snapshot.put("rootSessionId", rootSessionId);
            snapshot.put("rootRunId", rootRunId);
            snapshot.put("tenantId", tenantId);
            snapshot.put("applicationId", applicationId);
            snapshot.put("projectId", projectId);
            snapshot.put("userId", userId);
            snapshot.put("actorId", actorId);
            snapshot.put("agentId", agentId);
            snapshot.put("delegationId", delegationId);
            snapshot.put("maxRecoveryCycles", maxCycles);
            snapshot.put("maxTotalDurationMinutes", durationMinutes);
            snapshot.put("maxRepeatedErrorCount", maxRepeatedErrors);
            snapshot.put("maxAutomaticRiskLevel", maxRisk);
            snapshot.put("allowedRecoveryActions", allowedActions);
            snapshot.put("requireApprovalFor", approvalActions);
            snapshot.put("issuedAt", issuedAt.toString());
            snapshot.put("expiresAt", expiresAt.toString());
            if (policyDigest != null) {
                snapshot.put("policyDigest", policyDigest);
            }
            String snapshotJson = objectMapper.writeValueAsString(snapshot);
            return new ParsedAuthorization(
                    "ACTIVE".equals(state),
                    rootSessionId,
                    rootRunId,
                    tenantId,
                    applicationId,
                    projectId,
                    userId,
                    actorId,
                    agentId,
                    delegationId,
                    maxCycles,
                    durationMinutes,
                    maxRepeatedErrors,
                    expiresAt,
                    snapshot,
                    "sha256:" + SyncAutopilotDigestSupport.sha256(snapshotJson)
            );
        } catch (Exception exception) {
            throw new IllegalArgumentException("Cannot parse persisted Autopilot authorization", exception);
        }
    }

    /**
     * 校验任务、任务定义、执行记录和已解析授权是否共享同一租户/项目边界。
     *
     * <p>该纯函数和幂等判断在任何恢复触发 outbox 写入前执行。范围不一致时不会尝试修补，也不会用缺失值
     * 兜底，而是让发布器失败关闭，防止某一范围的持久授权唤醒另一范围的恢复工作。</p>
     *
     * @param task 权威同步任务
     * @param execution 权威失败执行
     * @param definition 持有策略快照的权威任务定义
     * @param authorization 从快照解析出的已清洗授权事实
     * @return 仅所有持久范围值完全一致时返回 {@code true}
     */
    private boolean scopeMatches(SyncTask task,
                                 SyncExecution execution,
                                 SyncTaskDefinition definition,
                                 ParsedAuthorization authorization) {
        return Objects.equals(task.getTenantId(), authorization.tenantId())
                && Objects.equals(task.getProjectId(), authorization.projectId())
                && Objects.equals(execution.getTenantId(), authorization.tenantId())
                && Objects.equals(execution.getProjectId(), authorization.projectId())
                && Objects.equals(definition.getTenantId(), authorization.tenantId())
                && Objects.equals(definition.getProjectId(), authorization.projectId());
    }

    /**
     * 对传入发布器的实体执行早期结构化归属校验。
     *
     * <p>在读取策略或计算事件 ID 前，先证明 task/execution ID 存在、execution 属于该任务且租户/项目一致。
     * 该判断纯函数且幂等；返回 false 时静默安全退出，因为发布触发器不能为报告调用接线错误或意外跨租户
     * 而改变业务状态。</p>
     *
     * @param task 候选归属任务
     * @param execution 候选失败或完成 execution
     * @return 仅当任务、租户和项目完整一致时返回 {@code true}
     */
    private boolean validScopeInputs(SyncTask task, SyncExecution execution) {
        return task != null && task.getId() != null && task.getTenantId() != null
                && execution != null && execution.getId() != null
                && Objects.equals(task.getId(), execution.getSyncTaskId())
                && Objects.equals(task.getTenantId(), execution.getTenantId())
                && Objects.equals(task.getProjectId(), execution.getProjectId());
    }

    /**
     * 根据主错误码和可选问题码计算稳定、低敏的 SHA-256 指纹。
     *
     * <p>每个输入先经过 {@link #safeCode(String)} 规范化，次级问题码在摘要前排序，因此传输顺序和不安全
     * 标点不会改变等价故障的身份。该方法纯函数且幂等，不执行持久化或日志写入，也绝不摘要原始异常正文、
     * SQL、URL 或日志。指纹只支持循环门禁和 case 身份，不是可独立授权恢复的诊断载荷或证据。</p>
     *
     * @param errorCode 主错误码，缺失时规范为 {@code UNKNOWN}
     * @param issueCodes 可选次级问题码，空值按空列表处理
     * @return 规范安全码列表对应的小写 SHA-256 摘要
     */
    public static String errorFingerprint(String errorCode, List<String> issueCodes) {
        List<String> normalized = new ArrayList<>();
        normalized.add(safeCode(errorCode));
        if (issueCodes != null) {
            issueCodes.stream().map(SyncAutopilotRecoveryTriggerPublisher::safeCode)
                    .filter(value -> !value.isBlank())
                    .sorted()
                    .forEach(normalized::add);
        }
        return SyncAutopilotDigestSupport.sha256(String.join("|", normalized));
    }

    /**
     * 为一次已授权的失败恢复循环生成确定性的 outbox 事件身份。
     *
     * <p>事件 ID 同时绑定授权会话与运行链路、任务与执行链路、当前循环以及低敏错误指纹。该计算没有
     * 副作用且可重复：同一失败回调会得到相同事件 ID，并由数据库唯一键完成去重；循环、当前执行或错误
     * 指纹发生变化时会得到新事件 ID，但新事件仍必须通过最大循环次数、重复错误次数和截止时间门禁。</p>
     *
     * @param authorization 已清洗且仍然有效的首次授权事实
     * @param taskId 所属数据同步任务 ID
     * @param rootExecutionId 自治恢复链路中的首次执行 ID
     * @param currentExecutionId 本轮刚刚失败的执行 ID
     * @param cycle 受最大循环次数约束的恢复轮次
     * @param errorFingerprint 用于关联同类故障的低敏摘要
     * @return 带固定前缀、可用于 outbox 去重的确定性事件 ID
     */
    private String eventId(ParsedAuthorization authorization,
                           Long taskId,
                           Long rootExecutionId,
                           Long currentExecutionId,
                           int cycle,
                           String errorFingerprint) {
        return "autopilot-trigger:" + SyncAutopilotDigestSupport.sha256(String.join("|",
                authorization.rootSessionId(),
                authorization.rootRunId(),
                String.valueOf(taskId),
                String.valueOf(rootExecutionId),
                String.valueOf(currentExecutionId),
                String.valueOf(cycle),
                errorFingerprint));
    }

    /**
     * 构造写入触发合同的有界、已清洗问题码列表。
     *
     * <p>主问题码始终位于首位，次级值会规范化、去重并限制数量，避免错误报告无限放大 outbox 载荷。
     * 结果不可变且无副作用；相同输入顺序会得到确定结果，但这不是原始错误导出，任意正文会被安全枚举码替代。</p>
     *
     * @param errorCode 即使没有次级输入也必须保留的主问题码
     * @param issueCodes 可选次级问题码
     * @return 最多包含 {@code MAX_ISSUE_CODES} 项的不可变安全列表
     */
    private List<String> safeIssueCodes(String errorCode, List<String> issueCodes) {
        List<String> result = new ArrayList<>();
        result.add(safeCode(errorCode));
        if (issueCodes != null) {
            issueCodes.stream().map(SyncAutopilotRecoveryTriggerPublisher::safeCode)
                    .filter(value -> !result.contains(value))
                    .limit(MAX_ISSUE_CODES - 1L)
                    .forEach(result::add);
        }
        return List.copyOf(result);
    }

    /**
     * 将任意文本规范成有界的枚举式诊断码。
     *
     * <p>空输入转换为 {@code UNKNOWN}；其他文本统一大写，仅保留小范围 ASCII 白名单字符并截断。该纯函数
     * 有意丢失细节，对已规范值保持幂等；它是摘要、日志或问题码发送前的数据最小化边界，不负责保留详细错误，
     * 也不证明某个代码具有业务含义。</p>
     *
     * @param value 不可信诊断文本
     * @return 安全且有界的代码文本
     */
    private static String safeCode(String value) {
        if (value == null || value.isBlank()) {
            return "UNKNOWN";
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9_\\-:.]", "_");
        return normalized.substring(0, Math.min(96, normalized.length()));
    }

    /**
     * 把 JSON 动作数组读取成不可变的已清洗代码列表。
     *
     * <p>字段缺失或不是数组代表没有动作；数组中出现非文本元素时直接拒绝，不做强制转换。每个文本值都经过
     * {@link #safeCode(String)}，防止授权快照把任意原文带入 Kafka。方法纯函数且无副作用，不判断动作是否获准，
     * 权限裁决仍属于策略和 case 服务。</p>
     *
     * @param node 预期包含动作码数组的 JSON 节点
     * @return 不可变的已清洗动作列表；字段缺失或非数组时返回空列表
     * @throws IllegalArgumentException 数组包含非文本元素时抛出
     */
    private List<String> safeCodes(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (JsonNode item : node) {
            if (!item.isTextual()) {
                throw new IllegalArgumentException("Autopilot actions must be text codes");
            }
            result.add(safeCode(item.asText()));
        }
        return List.copyOf(result);
    }

    /**
     * 返回两个授权相关 deadline 中更早的一个。
     *
     * <p>发布器会组合单次恢复时长预算与授权过期时间，绝不能使用更晚值，否则自动化可能在授权失效后继续。
     * 该辅助方法纯函数、幂等且无状态副作用；结果写入触发器，后续组件据此停止超期循环，而不是重新推测 deadline。</p>
     *
     * @param first 第一个非空 deadline
     * @param second 第二个非空 deadline
     * @return 绝对时间更早的 deadline
     */
    private OffsetDateTime earliest(OffsetDateTime first, OffsetDateTime second) {
        return isAfterInstant(first, second)
                ? second.withOffsetSameInstant(ZoneOffset.UTC)
                : first.withOffsetSameInstant(ZoneOffset.UTC);
    }

    /**
     * 按绝对时间点比较两个带时区偏移的时间，而不是比较各自本地时钟字段。
     *
     * <p>授权快照可能按客户时区签发，而 data-sync 使用 UTC 持久化 deadline。直接比较本地日期时间会把不同
     * 偏移误当成同一墙上时钟，可能让已过期自动化继续运行。统一转换为 {@link java.time.Instant} 后，过期与
     * 有界循环校验不再受策略所带偏移影响。</p>
     *
     * @param candidate 需要判断是否晚于参照时间的候选时间
     * @param reference 当前或竞争参照时间
     * @return 仅候选时间在 UTC 时间线上严格更晚时返回 {@code true}
     */
    private boolean isAfterInstant(OffsetDateTime candidate, OffsetDateTime reference) {
        return candidate.toInstant().isAfter(reference.toInstant());
    }

    /**
     * 读取必填非空授权文本，并支持一个有文档记录的兼容别名。
     *
     * <p>解析器只接受 JSON 文本并在校验后去空白，不把数字、数组或对象强制转换为标识。方法纯函数且幂等。
     * policy/session ID 等必填标识缺失时失败关闭，避免损坏快照静默采用更宽默认范围或发出审计链路不明确的触发器。</p>
     *
     * @param root 已解析授权对象
     * @param field 首选 schema 字段名
     * @param alias 可选旧版 schema 字段名
     * @return 首选字段或别名中去空白后的文本
     * @throws IllegalArgumentException 两个来源均无非空文本时抛出
     */
    private String requiredText(JsonNode root, String field, String alias) {
        JsonNode value = root.get(field);
        if ((value == null || value.isNull()) && alias != null) {
            value = root.get(alias);
        }
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.asText().trim();
    }

    private String requiredText(JsonNode root, String field) {
        return requiredText(root, field, null);
    }

    /**
     * 读取可选文本；字段缺失或不可用时返回调用方指定的安全默认值。
     *
     * <p>只用于默认值已经属于固定授权合同的字段，例如激活状态或 schema 版本。方法纯函数且幂等，不修改
     * 解析树，也绝不伪造必填身份或范围值。安全边界标识缺失时应通过
     * {@link #requiredText(JsonNode, String)} 失败关闭，不能调用本方法兜底。</p>
     *
     * @param root 已解析授权对象
     * @param field 可选 schema 字段名
     * @param fallback 字段缺失、非文本或空白时使用的固定合同默认值
     * @return 去空白后的字段文本或 {@code fallback}
     */
    private String optionalText(JsonNode root, String field, String fallback) {
        JsonNode value = root.get(field);
        if (value == null || value.isNull() || !value.isTextual() || value.asText().isBlank()) {
            return fallback;
        }
        return value.asText().trim();
    }

    /**
     * 读取必填整数授权标识，不接受文本或小数 JSON 值。
     *
     * <p>方法纯函数、幂等且无副作用，并保留“字段缺失”和“字段存在但无效”的区别，防止策略把恢复工作
     * 误绑定到伪造租户。具体字段的正数和范围要求由调用方在结构校验后继续执行。</p>
     *
     * @param root 已解析授权对象
     * @param field 必填整数字段名
     * @return 解析后的 long 标识
     * @throws IllegalArgumentException 字段缺失或不是整数时抛出
     */
    private Long requiredLong(JsonNode root, String field) {
        Long value = nullableLong(root, field);
        if (value == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }

    /**
     * 读取可选整数授权字段，但不会把无效输入放宽为 {@code null}。
     *
     * <p>只有 JSON 字段缺失或明确为 null 才返回 {@code null}；字段存在但不是整数代表持久授权无效。该方法
     * 纯函数且幂等，使调用方在发布触发器前区分“明确可选的项目/应用字段”和“损坏的范围数据”。</p>
     *
     * @param root 已解析授权对象
     * @param field 可选整数字段名
     * @return 解析后的 long；仅字段缺失或为 null 时返回 {@code null}
     * @throws IllegalArgumentException 字段存在但无法表示为 long 时抛出
     */
    private Long nullableLong(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.canConvertToLong()) {
            throw new IllegalArgumentException(field + " must be an integer");
        }
        return value.longValue();
    }

    /**
     * 读取有界恢复数值限制，仅在字段缺失时应用安全默认值。
     *
     * <p>显式值必须是整数并落在调用方给定的闭区间内，避免损坏策略产生无限恢复轮次、持续时间或重复错误
     * 容忍度。该辅助方法纯函数且幂等，本身不计算重试，也不修改任何持久状态。</p>
     *
     * @param root 已解析授权对象
     * @param field 数值字段名
     * @param fallback 字段缺失时使用的固定安全默认值
     * @param min 可接受显式值下限
     * @param max 可接受显式值上限
     * @return 默认值或已验证整数
     * @throws IllegalArgumentException 字段存在但无效或越界时抛出
     */
    private int boundedInt(JsonNode root, String field, int fallback, int min, int max) {
        JsonNode value = root.get(field);
        if (value == null || value.isNull()) {
            return fallback;
        }
        if (!value.canConvertToInt()) {
            throw new IllegalArgumentException(field + " must be an integer");
        }
        int parsed = value.intValue();
        if (parsed < min || parsed > max) {
            throw new IllegalArgumentException(field + " is outside the supported range");
        }
        return parsed;
    }

    /**
     * 解析用于签发/过期比较的带时区偏移授权时间戳。
     *
     * <p>该方法不访问时钟、持久层或传输层，对同一 ISO-8601 文本保持幂等。解析错误会继续传播给授权解析器，
     * 使其跳过自动化，而不是把无法读取的过期时间当成永久有效。</p>
     *
     * @param value 包含时区偏移的必填 ISO-8601 时间文本
     * @return 保留原始偏移信息的解析时间
     * @throws RuntimeException 输入无法解析为带偏移日期时间时抛出
     */
    private OffsetDateTime parseDateTime(String value) {
        return OffsetDateTime.parse(value);
    }

    /** 解析后的白名单授权，不保留原始 JSON。 */
    private record ParsedAuthorization(
            boolean active,
            String rootSessionId,
            String rootRunId,
            Long tenantId,
            Long applicationId,
            Long projectId,
            String userId,
            String actorId,
            String agentId,
            String delegationId,
            int maxRecoveryCycles,
            int maxTotalDurationMinutes,
            int maxRepeatedErrorCount,
            OffsetDateTime expiresAt,
            Map<String, Object> snapshot,
            String snapshotDigest) {
    }
}
