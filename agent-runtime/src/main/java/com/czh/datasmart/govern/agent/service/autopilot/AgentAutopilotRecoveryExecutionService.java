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
     * <p>Evidence verifier {@link PlatformBusinessException}s are a separate terminal branch: their scope, digest,
     * source, or freshness facts are deterministic for this planner response, so this method converts them to a
     * low-sensitive {@code REJECTED} result. The Kafka consumer durably callbacks that result before acknowledging the
     * record. Other runtime failures remain technical and are rethrown for bounded Kafka retry.</p>
     */
    public AgentAutopilotRecoveryExecutionResult execute(
            AgentAutopilotVerifiedRecoveryTrigger trigger,
            AgentAutopilotRecoveryPlanResponse response) {
        if (!"CANDIDATE_READY".equals(code(response.status()))) {
            // An attention/failed planner response has not passed Java evidence verification. Persist its
            // terminal code, but do not publish unverified retrieval metadata as grounded public evidence.
            return result(trigger, null, response.status(), response.reasonCode(), null);
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
                response.errorFingerprint());
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
            return result(trigger, response, recoveryCase.state(), javaDecision.reasonCode(), recoveryCase.caseId());
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
        String action = code(response.action());
        if (!"RETRY_EXECUTION".equals(action) && !"APPLY_QUARANTINE".equals(action)) {
            return result(trigger, response, "ATTENTION_REQUIRED",
                    "AUTOPILOT_ACTION_EXECUTOR_NOT_IMPLEMENTED", recoveryCase.caseId());
        }

        if ("APPLY_QUARANTINE".equals(action)
                && !"RECOVERY_STARTED".equals(code(recoveryCase.state()))) {
            AgentAutopilotRecoveryQuarantineApplyReceipt applyReceipt =
                    dataSyncClient.applyAutonomousQuarantine(
                            trigger, recoveryCase, response, quarantinePreview);
            if (!applyReceipt.isDurablyApplied()) {
                return result(trigger, response, "ATTENTION_REQUIRED",
                        "AUTOPILOT_QUARANTINE_APPLY_NOT_COMPLETED", recoveryCase.caseId());
            }
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
         * A successful HTTP retry response proves only that data-sync requeued the control-plane work.  Before the
         * Kafka record can be acknowledged, run the two read-only specialists against that real receipt and require
         * their facts to be durably accepted by agent-runtime.  Any Python, Specialist, fact-sink or checkpoint
         * failure propagates unchanged; a later Kafka delivery reuses the same data-sync receipt and stable turn IDs.
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
     * Converts a deterministic quarantine-preview denial into a stable low-sensitive result code.
     *
     * <p>The preview verifier uses fixed {@code AUTOPILOT_*} codes for scope, count, digest, selector, and
     * fingerprint failures. Returning those codes lets the trigger-result receipt explain why unattended execution
     * stopped without copying model payloads or selected row identifiers. An unexpected message is replaced with a
     * generic code. This helper performs no persistence, HTTP request, or authorization decision.</p>
     *
     * @param exception deterministic preview validation failure
     * @return safe reason code suitable for the durable trigger-result callback
     */
    private String safeQuarantineReason(PlatformBusinessException exception) {
        String reasonCode = exception == null ? null : exception.getMessage();
        if (reasonCode == null || !reasonCode.matches("AUTOPILOT_[A-Z0-9_]{1,96}")) {
            return "AUTOPILOT_QUARANTINE_PREVIEW_REJECTED";
        }
        return reasonCode;
    }

    /**
     * Converts a deterministic evidence-policy denial into the terminal result that the Kafka consumer can durably
     * acknowledge.
     *
     * <p>The input is limited to {@link PlatformBusinessException} values thrown by the local evidence verifier. Its
     * reason normally is one of the verifier's fixed scope, digest, source, or freshness codes. The helper still
     * sanitizes the text before placing it in the callback result, so an unexpected exception message cannot expose
     * evidence payloads, remote details, or stack-trace text. It does not write a data-sync case or invoke retry; the
     * caller returns this result to the consumer, which performs the durable trigger-result callback exactly once per
     * accepted Kafka delivery.</p>
     *
     * <p>Because the underlying evidence facts are already present in the immutable planner response, replaying the
     * same message would produce the same denial. This is deliberately different from I/O, database, timeout, and
     * runtime failures, which are not converted here and must continue to Kafka retry.</p>
     *
     * @param trigger verified recovery trigger used to retain the stable event and execution identifiers
     * @param exception permanent evidence verifier rejection for the current candidate
     * @return low-sensitive {@code REJECTED} result with a fixed safe reason code and no recovery case
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
     * 独立验证重复错误是否真的改变了诊断/检索与修复策略。
     *
     * <p>Python 会返回 {@code strategyChanged} 作为解释性事实，但 Java 不能把模型或 Python 自报的布尔值
     * 当作执行许可。重复错误要继续无人值守恢复，必须同时满足两个可独立检查的条件：</p>
     * <p>1. 新 {@code repairFingerprint} 与持久化触发事件中的上一轮指纹不同，禁止把同一修复原样重跑；</p>
     * <p>2. 当前候选声明 {@code retrievalDecision=SEARCH} 且携带非空 retrieval audit。调用本方法之前，
     * {@link AgentAutopilotRecoveryEvidenceVerifier} 已经复算该审计的 evidence ID digest、范围和时间，因此这里
     * 可以把它视为“本轮确实扩大过受控证据来源”，而不是只相信一个字符串。</p>
     *
     * <p>该方法只返回稳定低敏原因码，不写数据库、不调用下游，也不改变循环次数。首次错误不需要强制搜索，
     * 因而返回 {@code null}；这保留了“已知错误可直接使用结构化日志/API 处理”的模型自主决策目标。</p>
     *
     * @param trigger 包含服务端持久化重复次数和上一轮修复指纹的可信触发器
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
        if (!"SEARCH".equals(code(response.retrievalDecision()))
                || response.retrievalAudit() == null
                || response.retrievalAudit().isEmpty()) {
            return "RECOVERY_REPEATED_ERROR_RETRIEVAL_NOT_EXPANDED";
        }
        return null;
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
     * Returns the only model retrieval decisions allowed to cross the Java/data-sync callback boundary.
     *
     * <p>The planner response is untrusted transport data until Java validation finishes. Normalizing here keeps
     * callback construction deterministic; the data-sync service still performs its own strict whitelist check.
     * A missing response means planning never completed and therefore yields no claimed decision.</p>
     */
    private String retrievalDecision(AgentAutopilotRecoveryPlanResponse response) {
        return response == null ? null : code(response.retrievalDecision());
    }

    /** Projects the planner's bounded strategy code without forwarding queries, citations, or model prose. */
    private String retrievalStrategy(AgentAutopilotRecoveryPlanResponse response) {
        return response == null ? null : code(response.retrievalStrategy());
    }

    /**
     * Reports grounded RAG evidence count only when the model selected SEARCH.
     *
     * <p>SKIP intentionally returns zero. SEARCH reads the count from the already Java-verified retrieval audit;
     * malformed values become zero and will be rejected by data-sync rather than being silently accepted.</p>
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

    /** Returns the verified RAG evidence-ID digest for SEARCH and no digest for SKIP. */
    private String retrievalEvidenceDigest(AgentAutopilotRecoveryPlanResponse response) {
        if (response == null || !"SEARCH".equals(retrievalDecision(response))) {
            return null;
        }
        Object value = response.retrievalAudit().get("evidenceDigest");
        return value == null ? null : String.valueOf(value).trim().toLowerCase();
    }

    /** Converts a small untrusted numeric audit value without copying parser errors into logs or callbacks. */
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
