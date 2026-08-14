/**
 * @Author : Cui
 * @Date: 2026/08/11 20:45
 * @Description DataSmart Govern Backend - AgentAutopilotRecoveryExecutionService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.autopilot;

import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 对 Python 恢复候选执行 Java 与 data-sync 双重策略，并启动受支持的低风险动作。
 *
 * <p>当前完整自动动作包括 ``RETRY_EXECUTION``，以及绑定真实 Java preview receipt 的
 * ``APPLY_QUARANTINE``。后者必须先由 data-sync 幂等完成隔离应用，再复用同一失败对象选择性重试入口；
 * 其它动作即使模型提出，也只能持久化为 WAITING_APPROVAL、REJECTED 或 ATTENTION_REQUIRED。本服务
 * 不会通过通用 Tool auto-execution 降低既有高风险目录的审批等级。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentAutopilotRecoveryExecutionService {

    /**
     * 一次初始调用加一次同幂等键重放，处理最常见的短暂网络故障和“提交成功但响应丢失”。
     */
    private static final int RETRY_DISPATCH_MAX_ATTEMPTS = 2;

    private final AgentAutopilotRecoveryEvidenceVerifier evidenceVerifier;
    private final AgentAutopilotRecoveryQuarantinePreviewVerifier quarantinePreviewVerifier;
    private final AgentAutopilotRecoveryRepairVerifier repairVerifier;
    private final AgentAutopilotRecoveryPolicyEvaluator policyEvaluator;
    private final AgentAutopilotRecoveryDataSyncClient dataSyncClient;
    private final AgentAutopilotRecoveryPythonClient pythonClient;
    private final AgentAutopilotRecoveryMetrics metrics;

    /**
     * 评估恢复候选，并且只在 Java 与 data-sync 两道策略都自动批准时执行隔离（如需要）并重新排队失败对象。
     *
     * <p>输入是已验证的触发器和 Python 低敏规划响应；输出为供 Kafka 日志和指标使用的低敏执行结果。
     * 当响应不是 {@code CANDIDATE_READY} 时不会请求证据或下游服务。对可执行候选，本方法依次复算证据、
     * 检查重复修复指纹、验证可选 quarantine preview、运行 Java 策略并落 data-sync 决策。对于
     * {@code APPLY_QUARANTINE}，服务先要求 data-sync 返回 durable applied receipt；随后写入或复用
     * {@code RECOVERY_STARTED}，最后才调用失败对象重试。这个顺序使每个副作用都有可审计状态与幂等回执。</p>
     *
     * <p>权限不能由模型决定：可信触发器绑定持久授权，Java 策略检查范围、风险、循环、时限和幂等指纹，
     * data-sync 再次给出权威状态。下游调用使用稳定 event receipt；本服务不保存本地去重缓存，重复 Kafka
     * 投递的最终幂等性由 data-sync case、乐观锁和失败对象重试规则承担。证据不充分、策略拒绝、风险过高
     * 或重复且未改变的修复策略都会返回非执行状态，绝不降级为通用工具自动执行。</p>
     *
     * <p>证据校验、决策落案、状态迁移和 retry 调度都是需要可靠完成的下游边界。小范围本地重放耗尽后，
     * 这些异常会继续向 Kafka 监听器传播。特别是已经写入 {@code RECOVERY_STARTED} 后的调度失败，应由
     * 后续幂等投递沿用现有 started case 重放，而不能被转换成已确认的 {@code ATTENTION_REQUIRED} 结果。</p>
     *
     * @param trigger 已完成身份、会话、运行、授权和时限验证的恢复触发器
     * @param response Python 返回的候选或阻断响应
     * @return 当前恢复步骤的低敏状态、原因码、case 标识和 execution 标识
     *
     * <p>证据验证器抛出的 {@link PlatformBusinessException} 属于独立终态分支：对当前规划响应而言，范围、摘要、
     * 来源或新鲜度事实都是确定的，因此本方法将其转换为低敏 {@code REJECTED} 结果。Kafka 消费者必须先持久回调
     * 该结果再确认记录；其他运行期失败继续作为技术异常抛出，交给 Kafka 有界重试。</p>
     */
    public AgentAutopilotRecoveryExecutionResult execute(
            AgentAutopilotVerifiedRecoveryTrigger trigger,
            AgentAutopilotRecoveryPlanResponse response) {
        if (!"CANDIDATE_READY".equals(code(response.status()))) {
            // Python 已完成一次范围受限、低敏感的规划。即使建议的动作不可执行，也要将其明确的
            // SEARCH/SKIP 检索结果保留在持久审计中。
            // 但该响应仍不是执行授权：此分支不会校验候选、创建恢复 case、修改数据或调度重试。
            return result(trigger, response, response.status(), response.reasonCode(), null);
        }
        boolean evidenceVerified;
        try {
            evidenceVerified = evidenceVerifier.verify(trigger, response);
            metrics.recordEvidenceAccepted();
        } catch (PlatformBusinessException exception) {
            metrics.recordEvidenceRejected();
            return rejectedEvidenceResult(trigger, exception);
        } catch (RuntimeException exception) {
            metrics.recordEvidenceRejected();
            throw exception;
        }
        String repeatedStrategyIssue = repeatedStrategyIssue(trigger, response);
        if (repeatedStrategyIssue != null) {
            return result(trigger, response, "ATTENTION_REQUIRED", repeatedStrategyIssue, null);
        }

        String action = code(response.action());
        Map<String, Object> verifiedRepairParameters = Map.of();
        if (repairVerifier.supports(action)) {
            try {
                verifiedRepairParameters = repairVerifier.verify(trigger, response);
            } catch (PlatformBusinessException exception) {
                return result(trigger, response, "ATTENTION_REQUIRED",
                        safeRepairReason(exception), null);
            }
        }

        if ("RETRY_EXECUTION".equals(action)
                && !AgentAutopilotRecoveryFactsVerifier.eligibleForAutomaticRetry(
                response.autopilotRecoveryFacts())) {
            return result(trigger, response, "ATTENTION_REQUIRED",
                    "RECOVERY_AUTOMATIC_RETRY_FACTS_REQUIRED", null);
        }

        AgentAutopilotRecoveryQuarantinePreview quarantinePreview = null;
        if ("APPLY_QUARANTINE".equals(code(response.action()))) {
            try {
                quarantinePreview = quarantinePreviewVerifier.verify(trigger, response);
            } catch (PlatformBusinessException exception) {
                return result(trigger, response, "ATTENTION_REQUIRED",
                        safeQuarantineReason(exception), null);
            }
        }

        AgentAutopilotRecoveryCandidate candidate = new AgentAutopilotRecoveryCandidate(
                trigger.event().tenantId(),
                trigger.event().applicationId(),
                trigger.event().projectId(),
                trigger.event().userId(),
                trigger.event().agentId(),
                trigger.event().delegationId(),
                response.action(),
                response.riskLevel(),
                response.idempotent(),
                response.repairFingerprint(),
                response.errorFingerprint(),
                response.autopilotRecoveryFacts());
        AgentAutopilotRecoveryLoopState loopState = new AgentAutopilotRecoveryLoopState(
                trigger.event().cycle() - 1,
                trigger.recoveryStartedAt(),
                trigger.event().errorFingerprint(),
                trigger.event().repeatedErrorCount(),
                evidenceVerified,
                true,
                response.confidence());
        AgentAutopilotRecoveryDecision javaDecision = policyEvaluator.evaluate(
                trigger.authorization(), candidate, loopState);

        AgentAutopilotRecoveryCaseView recoveryCase = dataSyncClient.recordDecision(
                trigger, response, evidenceVerified);
        if (javaDecision.decision() != AgentAutopilotRecoveryDecisionType.AUTO_APPROVED) {
            return stopExecutableCase(
                    trigger, response, recoveryCase, javaDecision.reasonCode(), "java-policy-denied");
        }
        if ("RECOVERED".equals(code(recoveryCase.state()))) {
            return result(trigger, response, "RECOVERED",
                    "AUTOPILOT_RECOVERY_ALREADY_SUCCEEDED", recoveryCase.caseId());
        }
        if (!"AUTO_APPROVED".equals(code(recoveryCase.state()))
                && !"RECOVERY_STARTED".equals(code(recoveryCase.state()))) {
            return result(trigger, response, recoveryCase.state(),
                    "DATA_SYNC_AUTOPILOT_POLICY_NOT_AUTO_APPROVED", recoveryCase.caseId());
        }
        if (!"RETRY_EXECUTION".equals(action) && !"APPLY_QUARANTINE".equals(action)
                && !repairVerifier.supports(action)) {
            return stopExecutableCase(trigger, response, recoveryCase,
                    "AUTOPILOT_ACTION_EXECUTOR_NOT_IMPLEMENTED", "executor-missing");
        }

        if ("APPLY_QUARANTINE".equals(action)
                && !"RECOVERY_STARTED".equals(code(recoveryCase.state()))) {
            AgentAutopilotRecoveryQuarantineApplyReceipt applyReceipt =
                    dataSyncClient.applyAutonomousQuarantine(
                            trigger, recoveryCase, response, quarantinePreview);
            if (!applyReceipt.isDurablyApplied()) {
                return stopExecutableCase(trigger, response, recoveryCase,
                        "AUTOPILOT_QUARANTINE_APPLY_NOT_COMPLETED", "quarantine-not-applied");
            }
        }

        if (repairVerifier.supports(action)) {
            return executeGovernedRepair(
                    trigger, response, recoveryCase, action, verifiedRepairParameters);
        }

        AgentAutopilotRecoveryCaseView started = "RECOVERY_STARTED".equals(code(recoveryCase.state()))
                ? recoveryCase
                : dataSyncClient.recordTransition(
                        trigger,
                        recoveryCase,
                        "RECOVERY_STARTED",
                        "started",
                        null);
        AgentAutopilotRecoveryRetryReceipt retryReceipt = dispatchRetryWithBoundedReplay(trigger);
        /*
         * HTTP 重试成功只证明 data-sync 已把控制面工作重新入队。提交 Kafka offset 前，还必须基于真实回执
         * 执行两个只读 Specialist，并要求 agent-runtime 持久接收它们的事实。Python、Specialist、事实接收器
         * 或 checkpoint 任一失败都会原样向上抛出；后续 Kafka 重投复用同一 data-sync 回执和稳定 turn ID。
         */
        pythonClient.verifyPostRecoveryAction(trigger, started, action, retryReceipt);
        return new AgentAutopilotRecoveryExecutionResult(
                trigger.event().eventId(),
                "RECOVERY_STARTED",
                "AUTOPILOT_FAILED_OBJECTS_REQUEUED",
                started.caseId(),
                retryReceipt.executionId(),
                retrievalDecision(response),
                retrievalStrategy(response),
                retrievalEvidenceCount(response),
                retrievalEvidenceDigest(response));
    }

    /**
     * 执行配置、元数据、checkpoint、分片或字段映射修复，并完成恢复后双 Specialist 验证。
     *
     * <p>新修复动作本身已经在 data-sync 内完成必要的配置变更和重新排队，因此这里不会再调用通用
     * {@code retryFailedObjects}。首次投递先取得强类型修复回执，再用回执中的 execution 推进 case；若
     * checkpoint 恢复创建了新 execution，后续 PRECHECK_AGENT 与 MONITOR_AGENT 会验证该新标识。</p>
     *
     * <p>Kafka 重投看到 {@code RECOVERY_STARTED} 时不会重复执行修复，而是从 case 的权威 execution
     * 继续幂等后置验证。data-sync 返回 {@code applied=false} 表示缺少可证明的低风险修复前提；若回执
     * 同时证明旧 case 已收敛且下一轮 outbox 已持久化，本方法只确认本轮关注结果，让下一轮模型携带新证据
     * 重新决策。达到循环或时间上限时没有下一轮事件，case 保持人工关注。</p>
     */
    private AgentAutopilotRecoveryExecutionResult executeGovernedRepair(
            AgentAutopilotVerifiedRecoveryTrigger trigger,
            AgentAutopilotRecoveryPlanResponse response,
            AgentAutopilotRecoveryCaseView recoveryCase,
            String action,
            Map<String, Object> verifiedParameters) {
        AgentAutopilotRecoveryCaseView started = recoveryCase;
        AgentAutopilotRecoveryRetryReceipt postRecoveryReceipt;
        String reasonCode = "AUTOPILOT_GOVERNED_REPAIR_ALREADY_STARTED";
        if ("RECOVERY_STARTED".equals(code(recoveryCase.state()))) {
            postRecoveryReceipt = new AgentAutopilotRecoveryRetryReceipt(
                    trigger.event().syncTaskId(), recoveryCase.currentExecutionId(),
                    1, "QUEUED", "RETRYING");
        } else {
            AgentAutopilotRecoveryRepairReceipt repairReceipt = dataSyncClient.applyGovernedRepair(
                    trigger, recoveryCase, response, verifiedParameters);
            if (!repairReceipt.isDurablyApplied()) {
                if (repairReceipt.isDurablyConvergedNotApplied(trigger.event())) {
                    return result(trigger, response, "ATTENTION_REQUIRED",
                            repairReceipt.reasonCode(), repairReceipt.caseId());
                }
                return stopExecutableCase(trigger, response, recoveryCase,
                        repairReceipt.reasonCode(), "repair-not-applied");
            }
            postRecoveryReceipt = new AgentAutopilotRecoveryRetryReceipt(
                    repairReceipt.syncTaskId(), repairReceipt.executionId(), repairReceipt.affectedCount(),
                    repairReceipt.executionState(), repairReceipt.taskState());
            if (!postRecoveryReceipt.matchesRequeuedScope(
                    trigger.event(), repairReceipt.executionId())) {
                throw new IllegalStateException("AUTOPILOT_REPAIR_POST_RECOVERY_RECEIPT_INVALID");
            }
            started = dataSyncClient.recordTransition(
                    trigger, recoveryCase, "RECOVERY_STARTED", "started", null,
                    repairReceipt.executionId());
            reasonCode = repairReceipt.reasonCode();
        }
        pythonClient.verifyPostRecoveryAction(trigger, started, action, postRecoveryReceipt);
        return new AgentAutopilotRecoveryExecutionResult(
                trigger.event().eventId(), "RECOVERY_STARTED", reasonCode,
                started.caseId(), postRecoveryReceipt.executionId(),
                retrievalDecision(response), retrievalStrategy(response),
                retrievalEvidenceCount(response), retrievalEvidenceDigest(response));
    }

    /**
     * 把已经创建但无法安全执行的 case 持久推进到人工关注，避免永久停留在 AUTO_APPROVED。
     */
    private AgentAutopilotRecoveryExecutionResult stopExecutableCase(
            AgentAutopilotVerifiedRecoveryTrigger trigger,
            AgentAutopilotRecoveryPlanResponse response,
            AgentAutopilotRecoveryCaseView recoveryCase,
            String reasonCode,
            String receiptSuffix) {
        String state = code(recoveryCase == null ? null : recoveryCase.state());
        AgentAutopilotRecoveryCaseView converged = recoveryCase;
        if ("AUTO_APPROVED".equals(state) || "MANUALLY_APPROVED".equals(state)
                || "RECOVERY_STARTED".equals(state)) {
            Long executionId = recoveryCase.currentExecutionId() == null
                    ? trigger.event().currentExecutionId() : recoveryCase.currentExecutionId();
            converged = dataSyncClient.recordTransition(
                    trigger, recoveryCase, "RECOVERY_FAILED", receiptSuffix, reasonCode, executionId);
        }
        return result(trigger, response,
                converged == null ? "ATTENTION_REQUIRED" : converged.state(),
                reasonCode, converged == null ? null : converged.caseId());
    }

    /** 将修复参数验证失败转换为固定低敏原因码，不复制模型内容或参数值。 */
    private String safeRepairReason(PlatformBusinessException exception) {
        String reasonCode = exception == null ? null : exception.getMessage();
        if (reasonCode == null || !reasonCode.matches("AUTOPILOT_[A-Z0-9_]{1,96}")) {
            return "AUTOPILOT_REPAIR_PARAMETERS_INVALID";
        }
        return reasonCode;
    }

    /**
     * 把确定性的隔离预览拒绝转换为稳定低敏原因码。
     *
     * <p>预览验证器使用固定 {@code AUTOPILOT_*} 原因码表达范围、计数、摘要、选择器和指纹失败。
     * 回执据此解释无人值守执行停止原因，无需复制模型载荷或行标识；未知消息统一替换为通用码。
     * 本方法不持久化、不发起 HTTP 请求，也不做授权决策。</p>
     *
     * @param exception 确定性的预览校验失败
     * @return 可写入持久触发结果回调的安全原因码
     */
    private String safeQuarantineReason(PlatformBusinessException exception) {
        String reasonCode = exception == null ? null : exception.getMessage();
        if (reasonCode == null || !reasonCode.matches("AUTOPILOT_[A-Z0-9_]{1,96}")) {
            return "AUTOPILOT_QUARANTINE_PREVIEW_REJECTED";
        }
        return reasonCode;
    }

    /**
     * 把确定性的证据策略拒绝转换为 Kafka 消费者可持久确认的终态结果。
     *
     * <p>输入只接受本地证据验证器抛出的 {@link PlatformBusinessException}，通常对应范围、摘要、来源或
     * 新鲜度固定原因码。写入回调前仍会清洗文本，避免异常消息暴露证据载荷、远端详情或堆栈。本方法不写
     * data-sync case，也不触发重试；调用方把结果交给消费者，由消费者对每次接受的 Kafka 投递执行一次持久回调。</p>
     *
     * <p>底层证据事实已绑定在不可变规划响应中，同一消息重放会得到同一拒绝。I/O、数据库、超时和运行时
     * 失败不会在此转换，必须继续进入 Kafka 有界重试。</p>
     *
     * @param trigger 已验证恢复触发器，用于保留稳定事件和 execution 标识
     * @param exception 当前候选的永久证据验证拒绝
     * @return 不创建 recovery case、带固定安全原因码的低敏 {@code REJECTED} 结果
     */
    private AgentAutopilotRecoveryExecutionResult rejectedEvidenceResult(
            AgentAutopilotVerifiedRecoveryTrigger trigger,
            PlatformBusinessException exception) {
        String reasonCode = exception.getMessage();
        if (reasonCode == null || !reasonCode.matches("AUTOPILOT_[A-Z0-9_]{1,96}")) {
            reasonCode = "AUTOPILOT_RECOVERY_EVIDENCE_REJECTED";
        }
        return result(trigger, null, "REJECTED", reasonCode, null);
    }

    /**
     * 独立验证重复错误是否真的改变了诊断证据和修复策略。
     *
     * <p>Python 会返回 {@code strategyChanged} 作为解释性事实，但 Java 不能把模型自报的布尔值当作执行许可。
     * 所有重复错误都必须先改变 {@code repairFingerprint}；在此基础上再按可信证据来源分成两条路径：</p>
     * <p>1. 普通执行错误没有新的服务端诊断事实，必须由模型选择 {@code SEARCH}，并携带已经由
     * {@link AgentAutopilotRecoveryEvidenceVerifier} 复算过来源、范围、时间和摘要的 retrieval audit；</p>
     * <p>2. 上一受治理动作已经由 data-sync 尝试但未应用时，下一轮事件会携带
     * {@code PREVIOUS_REPAIR_ACTION_*} 和至少一条新发现的固定问题码。这些值来自服务端白名单与持久回执，
     * 本身就是一次结构化证据扩展，模型可自主选择 {@code SKIP}，但不得再次选择同一动作。</p>
     *
     * <p>第二条路径不是让模型绕过 RAG 或策略控制：它只避免在本地结构化证据已经足够时机械地强制检索。
     * 后续参数验证、Java 策略、data-sync 权威策略、幂等回执和循环上限仍会完整执行。缺少动作标记、缺少新增
     * 问题码或重复同一动作时，本方法返回稳定低敏原因码并停止本轮。</p>
     *
     * @param trigger 包含服务端持久化重复次数、上一轮指纹和问题码的可信触发器
     * @param response 已通过诊断证据完整性校验的 Python 候选
     * @return {@code null} 表示可继续策略评估；否则返回必须停在 ATTENTION_REQUIRED 的原因码
     */
    private String repeatedStrategyIssue(AgentAutopilotVerifiedRecoveryTrigger trigger,
                                         AgentAutopilotRecoveryPlanResponse response) {
        AgentAutopilotRecoveryTriggerEvent event = trigger.event();
        if (event.repeatedErrorCount() <= 0) {
            return null;
        }
        String previousFingerprint = code(event.previousRepairFingerprint());
        String currentFingerprint = code(response.repairFingerprint());
        if (previousFingerprint.isBlank()) {
            return "RECOVERY_REPEATED_ERROR_PREVIOUS_REPAIR_FINGERPRINT_MISSING";
        }
        if (previousFingerprint.equals(currentFingerprint)) {
            return "RECOVERY_REPEATED_REPAIR_STRATEGY_UNCHANGED";
        }

        String previousNotAppliedAction = previousNotAppliedAction(event);
        if (!previousNotAppliedAction.isBlank()) {
            if (previousNotAppliedAction.equals(code(response.action()))) {
                return "RECOVERY_REPEATED_REPAIR_ACTION_UNCHANGED";
            }
            if (!hasStructuredRepairEvidenceExpansion(event)) {
                return "RECOVERY_REPEATED_REPAIR_EVIDENCE_INCOMPLETE";
            }
            return null;
        }

        if (!"SEARCH".equals(code(response.retrievalDecision()))
                || response.retrievalAudit() == null
                || response.retrievalAudit().isEmpty()) {
            return "RECOVERY_REPEATED_ERROR_RETRIEVAL_NOT_EXPANDED";
        }
        return null;
    }

    /**
     * 从可信触发事件中提取上一轮未应用的受治理动作。
     *
     * <p>该标记只能由 data-sync 在提交“修复未应用”事务时生成，格式为
     * {@code PREVIOUS_REPAIR_ACTION_<ACTION>}。方法先复用统一编码规则再提取动作；不存在、空动作或出现多个
     * 标记时返回空字符串，使调用方回到更严格的普通重复错误路径。这样既不会信任模型自由文本，也不会因
     * 畸形事件放宽检索要求。</p>
     *
     * @param event 已完成摘要、身份、范围和授权校验的 data-sync 触发事件
     * @return 唯一上一动作编码；无法唯一证明时返回空字符串
     */
    private String previousNotAppliedAction(AgentAutopilotRecoveryTriggerEvent event) {
        String prefix = "PREVIOUS_REPAIR_ACTION_";
        String matched = "";
        for (String issueCode : event.issueCodes()) {
            String normalized = code(issueCode);
            if (!normalized.startsWith(prefix) || normalized.length() <= prefix.length()) {
                continue;
            }
            if (!matched.isBlank()) {
                return "";
            }
            matched = normalized.substring(prefix.length());
        }
        return matched;
    }

    /**
     * 检查“修复未应用”事件是否真的携带了上一动作之外的新结构化诊断。
     *
     * <p>data-sync 约定列表首项为本轮主原因，随后写入上一动作标记和修复执行阶段新发现的问题码。因此这里
     * 至少要求三项，并且在首项之后找到一条非动作标记的问题码。检查只处理有界枚举值，不读取日志正文；
     * 单独一个主原因加动作标记不足以证明诊断有新增信息，仍会停止 Loop。</p>
     *
     * @param event 已验证的恢复触发事件
     * @return 存在可供模型改变动作的新增结构化问题码时返回 {@code true}
     */
    private boolean hasStructuredRepairEvidenceExpansion(AgentAutopilotRecoveryTriggerEvent event) {
        if (event.issueCodes().size() < 3) {
            return false;
        }
        for (int index = 1; index < event.issueCodes().size(); index++) {
            String normalized = code(event.issueCodes().get(index));
            if (!normalized.isBlank() && !normalized.startsWith("PREVIOUS_REPAIR_ACTION_")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 使用同一个 data-sync 幂等键执行一次初始调用和至多一次重放。
     *
     * <p>每次调用都携带 recovery {@code eventId}。若第一次请求已经在 data-sync 提交、只是响应在网络中
     * 丢失，第二次会由 data-sync 回放首次成功结果；若第一次根本未到达服务端，第二次可以完成正常提交。
     * 两次都失败时会把最后一次技术异常交给 Kafka 的有限重试和 DLT。这里不做无限循环、不改变修复
     * cycle，也不吞掉最后一次异常。</p>
     *
     * @param trigger 已验证且包含稳定 eventId 的恢复触发器
     * @return data-sync 首次成功或幂等回放的强类型重排队回执
     * @throws RuntimeException 两次调用都失败时抛出最后一次异常
     */
    private AgentAutopilotRecoveryRetryReceipt dispatchRetryWithBoundedReplay(
            AgentAutopilotVerifiedRecoveryTrigger trigger) {
        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= RETRY_DISPATCH_MAX_ATTEMPTS; attempt++) {
            try {
                return dataSyncClient.retryFailedObjects(trigger);
            } catch (RuntimeException exception) {
                lastFailure = exception;
                log.warn("Autopilot retry dispatch attempt failed, eventId={}, attempt={}, exceptionType={}",
                        trigger.event().eventId(), attempt, exception.getClass().getSimpleName());
            }
        }
        throw lastFailure == null
                ? new IllegalStateException("Autopilot retry dispatch failed without an exception")
                : lastFailure;
    }

    /**
     * 按统一格式构造一个不包含证据正文或下游异常消息的恢复结果。
     *
     * <p>输入来自可信触发器和当前分支的状态/原因码，输出总是沿用该事件的 {@code eventId} 与当前
     * execution 标识。该纯函数不写 case、不调用 retry，也不授予权限；它只保证所有早退和失败分支拥有
     * 可审计且可比较的低敏结果。相同输入产生相同输出，因而不会引入新的幂等副作用。</p>
     *
     * @param trigger 已验证的恢复触发器
     * @param status 当前步骤的状态文本，可为空
     * @param reasonCode 稳定原因码，可为空时使用默认原因码
     * @param caseId 已创建 case 的标识；尚未创建时可为空
     * @return 规范化后的低敏执行结果
     */
    private AgentAutopilotRecoveryExecutionResult result(
            AgentAutopilotVerifiedRecoveryTrigger trigger,
            AgentAutopilotRecoveryPlanResponse response,
            String status,
            String reasonCode,
            Long caseId) {
        return new AgentAutopilotRecoveryExecutionResult(
                trigger.event().eventId(),
                code(status),
                reasonCode == null ? "AUTOPILOT_RECOVERY_RESULT_UNSPECIFIED" : reasonCode,
                caseId,
                trigger.event().currentExecutionId(),
                retrievalDecision(response),
                retrievalStrategy(response),
                retrievalEvidenceCount(response),
                retrievalEvidenceDigest(response));
    }

    /**
     * 返回唯一允许跨越 Java/data-sync 回调边界的模型检索决策。
     *
     * <p>规划响应在 Java 验证完成前仍是不可信传输数据。这里规范化可保证回调构造确定性，data-sync
     * 仍会执行自己的严格白名单校验。响应缺失表示规划未完成，因此不能声称存在任何检索决策。</p>
     */
    private String retrievalDecision(AgentAutopilotRecoveryPlanResponse response) {
        return response == null ? null : code(response.retrievalDecision());
    }

    /** 只投影规划器的有界策略码，不转发查询、引用或模型自由文本。 */
    private String retrievalStrategy(AgentAutopilotRecoveryPlanResponse response) {
        return response == null ? null : code(response.retrievalStrategy());
    }

    /**
     * 仅在模型选择 SEARCH 时返回有依据的 RAG 证据数量。
     *
     * <p>SKIP 固定返回零；SEARCH 从已经 Java 验证的检索审计中读取数量。格式错误的值转换为零，
     * 后续由 data-sync 拒绝，不能静默接受。</p>
     */
    private Integer retrievalEvidenceCount(AgentAutopilotRecoveryPlanResponse response) {
        if (response == null) {
            return null;
        }
        if (!"SEARCH".equals(retrievalDecision(response))) {
            return 0;
        }
        return intValue(response.retrievalAudit().get("evidenceCount"), 0);
    }

    /** SEARCH 返回已验证的 RAG 证据标识摘要，SKIP 不伪造摘要。 */
    private String retrievalEvidenceDigest(AgentAutopilotRecoveryPlanResponse response) {
        if (response == null || !"SEARCH".equals(retrievalDecision(response))) {
            return null;
        }
        Object value = response.retrievalAudit().get("evidenceDigest");
        return value == null ? null : String.valueOf(value).trim().toLowerCase();
    }

    /** 安全转换低敏数字审计值，不把解析异常正文复制到日志或回调。 */
    private Integer intValue(Object value, int fallback) {
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (RuntimeException exception) {
            return fallback;
        }
    }

    /**
     * 规范化状态和动作编码，避免大小写或连字符改变策略分支。
     *
     * <p>输入为空时返回空字符串，其他输入会去除首尾空白、转大写并把连字符替换为下划线。该纯函数不校验
     * 授权、证据或业务范围，却保证来自不同服务的枚举文本能稳定比较；它不产生副作用或幂等状态。</p>
     *
     * @param value 待比较的状态或动作文本，可为空
     * @return 用于内部比较的规范化编码，或空字符串
     */
    private String code(String value) {
        return value == null ? "" : value.trim().toUpperCase().replace('-', '_');
    }
}
