/**
 * @Author : Cui
 * @Date: 2026/08/11 02:10
 * @Description DataSmart Govern Backend - SyncAutopilotRecoveryCaseService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import com.czh.datasmart.govern.datasync.entity.SyncAutopilotRecoveryCase;
import com.czh.datasmart.govern.datasync.entity.SyncAutopilotRecoveryReceipt;
import com.czh.datasmart.govern.datasync.entity.SyncExecution;
import com.czh.datasmart.govern.datasync.entity.SyncErrorSample;
import com.czh.datasmart.govern.datasync.entity.SyncObjectExecution;
import com.czh.datasmart.govern.datasync.entity.SyncTask;
import com.czh.datasmart.govern.datasync.entity.SyncTaskDefinition;
import com.czh.datasmart.govern.datasync.mapper.SyncAutopilotRecoveryCaseMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncAutopilotRecoveryReceiptMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncExecutionMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncErrorSampleMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncObjectExecutionMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncTaskDefinitionMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncTaskMapper;
import com.czh.datasmart.govern.datasync.support.SyncAutopilotExecutionMode;
import com.czh.datasmart.govern.datasync.support.SyncAutopilotRecoveryAction;
import com.czh.datasmart.govern.datasync.support.SyncAutopilotRecoveryCaseState;
import com.czh.datasmart.govern.datasync.support.SyncAutopilotRecoveryReceiptType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.List;
import java.util.Locale;

/**
 * Persists deterministic Autopilot decisions and optimistic lifecycle receipts.
 *
 * <p>This service is the durable governance boundary between Agent Runtime candidates and a sync task's local
 * authorization. It reloads the task, definition, and executions from data-sync before it evaluates policy;
 * callers cannot create a case merely by naming another tenant or execution. Decisions and later lifecycle
 * callbacks are recorded through globally unique receipts plus conditional updates, making at-least-once
 * delivery replay-safe without allowing receipt reuse to change facts.</p>
 *
 * <p>The service stores control-plane state only. It neither starts workers nor invokes a model, Kafka, or an
 * external recovery endpoint. A successful transaction creates/updates a recovery case and receipt; conflicts
 * deliberately abort the transaction so callers can observe stale versions or unsafe idempotency reuse.</p>
 */
@Service
@RequiredArgsConstructor
public class SyncAutopilotRecoveryCaseService {

    private final SyncTaskMapper taskMapper;
    private final SyncTaskDefinitionMapper definitionMapper;
    private final SyncExecutionMapper executionMapper;
    private final SyncObjectExecutionMapper objectExecutionMapper;
    private final SyncErrorSampleMapper errorSampleMapper;
    private final SyncAutopilotRecoveryCaseMapper caseMapper;
    private final SyncAutopilotRecoveryReceiptMapper receiptMapper;
    private final SyncAutopilotRecoveryPolicyEvaluator policyEvaluator;
    private final SyncAutopilotRecoveryCaseStateMachine stateMachine;

    /**
     * Records or replays one policy-governed recovery decision for a sync execution lineage.
     *
     * <p>The command supplies low-sensitive IDs, bounded facts, fingerprints, a proposed action/risk, and a
     * caller-generated receipt ID. The method reloads the owning task, task definition, root/current executions,
     * and tenant/project scope; it then evaluates the persisted Autopilot policy rather than trusting the
     * caller's conclusion. The output is a safe case view containing the persisted decision state and version.</p>
     *
     * <p>Within one transaction this may insert a recovery case identified by scope, authorization digest, and
     * repair fingerprint, and always reserves/completes a {@code DECISION_RECORDED} receipt. Repeating exactly
     * the same receipt facts returns the original case view without another mutation. Reusing the receipt ID for
     * different facts, missing authorization, cross-scope IDs, or a completion race becomes a conflict. This
     * method records a decision only; it does not launch a recovery execution.</p>
     *
     * @param command low-sensitive candidate decision facts from the internal controller
     * @return persisted or replayed case summary with its current lifecycle state and optimistic version
     * @throws PlatformBusinessException when validation, authorization, scope, idempotency, or persistence
     *                                   consistency checks fail
     */
    @Transactional
    public SyncAutopilotRecoveryCaseView recordDecision(SyncAutopilotRecoveryDecisionCommand command) {
        requireDecision(command);
        SyncTask task = requireTask(command.syncTaskId());
        SyncTaskDefinition definition = requireDefinition(command.syncTaskId());
        SyncExecution rootExecution = requireExecution(command.rootExecutionId(), command.syncTaskId());
        SyncExecution currentExecution = requireExecution(command.currentExecutionId(), command.syncTaskId());
        requireScope(task, definition, rootExecution, command);

        boolean automaticRetryFactsVerified = verifyAutomaticRetryFacts(command, currentExecution);

        SyncAutopilotRecoveryPolicyDecision decision = policyEvaluator.evaluate(
                definition.getAutopilotPolicy(),
                new SyncAutopilotRecoveryEvaluationRequest(
                        SyncAutopilotExecutionMode.AUTOPILOT,
                        command.tenantId(),
                        command.projectId(),
                        command.syncTaskId(),
                        command.cycle(),
                        command.deadlineAt(),
                        command.errorFingerprint(),
                        command.repeatedErrorCount(),
                        command.action(),
                        command.riskLevel(),
                        command.repairFingerprint(),
                        command.receiptId(),
                        command.confidenceScore(),
                        command.evidenceAvailable(),
                        automaticRetryFactsVerified,
                        // Decision commands and persisted LocalDateTime deadlines use UTC by contract.
                        LocalDateTime.now(ZoneOffset.UTC)
                )
        );
        SyncAutopilotRecoveryCase recoveryCase = ensureCase(command, decision);
        validateExistingCase(recoveryCase, command, decision);

        String receiptDigest = decisionReceiptDigest(command);
        SyncAutopilotRecoveryReceipt replay = receiptMapper.selectByReceiptId(command.receiptId());
        if (replay != null) {
            return replayDecision(replay, receiptDigest, recoveryCase);
        }
        SyncAutopilotRecoveryReceipt receipt = processingReceipt(
                command.receiptId(), recoveryCase.getCaseId(), receiptDigest,
                SyncAutopilotRecoveryReceiptType.DECISION_RECORDED);
        if (receiptMapper.insertIfAbsent(receipt) == 0) {
            return replayDecision(requireReceipt(command.receiptId()), receiptDigest, recoveryCase);
        }
        if (receiptMapper.completeReceipt(command.receiptId(), recoveryCase.getCaseState(), recoveryCase.getVersion()) != 1) {
            throw conflict("Recovery decision receipt could not be completed");
        }
        return view(recoveryCase);
    }

    /**
     * 根据 data-sync 持久账本重新计算重试资格，而不是直接信任 Python 的事实投影。
     *
     * <p>面向模型的动作和传输事实可用于说明原因，却不能单独作为依据。这里执行第二次事实校验：
     * 权威的失败对象数量和可重试性以 data-sync 持久账本为准。仅当当前 execution 至少有一个失败对象，
     * 且每条已观察到的错误都明确属于瞬态的连接器/worker 工作时，重试才具备资格。约束、模式、
     * 权限、凭据、范围和数据契约故障会明确返回 {@code false}。</p>
     *
     * @param command 包含受限 Python 事实的候选决策
     * @param rootExecution 必须检查其失败账本的 execution
     * @return 任务本地账本是否独立证明该候选项为瞬态重试
     */
    private boolean verifyAutomaticRetryFacts(SyncAutopilotRecoveryDecisionCommand command,
                                              SyncExecution currentExecution) {
        if (command.action() != SyncAutopilotRecoveryAction.RETRY_EXECUTION
                || !SyncAutopilotRecoveryFactsVerifier.eligibleForAutomaticRetry(command.autopilotRecoveryFacts())) {
            return false;
        }
        List<SyncObjectExecution> failedObjects = objectExecutionMapper.selectByExecutionId(
                currentExecution.getId());
        List<SyncObjectExecution> failed = failedObjects == null ? List.of() : failedObjects.stream()
                .filter(item -> "FAILED".equalsIgnoreCase(item.getObjectState()))
                .toList();
        int declaredCount = positiveInt(command.autopilotRecoveryFacts().get("failedObjectCount"));
        if (failed.isEmpty() || declaredCount != failed.size()
                || failed.stream().anyMatch(item -> !isTransientConnectorOrWorkerFailure(item))) {
            return false;
        }
        List<SyncErrorSample> samples = errorSampleMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SyncErrorSample>()
                        .eq(SyncErrorSample::getSyncTaskId, command.syncTaskId())
                        .eq(SyncErrorSample::getExecutionId, command.currentExecutionId())
                        .eq(SyncErrorSample::getRetryable, true));
        if (samples != null && samples.stream().anyMatch(sample ->
                !isTransientCode(sample.getErrorType()) && !isTransientCode(sample.getErrorCode()))) {
            return false;
        }
        return true;
    }

    /** 解析正的事实计数器；不接受布尔值或自由格式的数字。 */
    private int positiveInt(Object value) {
        if (value instanceof Boolean || value == null) {
            return 0;
        }
        try {
            int result = Integer.parseInt(String.valueOf(value));
            return result > 0 ? result : 0;
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    /** 仅依据受限的类型/代码字段检查一个失败对象，绝不读取错误原文。 */
    private boolean isTransientConnectorOrWorkerFailure(SyncObjectExecution object) {
        return isTransientCode(object.getLastErrorType()) || isTransientCode(object.getLastErrorCode());
    }

    /** 判断代码是否属于诊断契约规定的固定连接器/worker 瞬态词汇。 */
    private boolean isTransientCode(String value) {
        String code = value == null ? "" : value.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        return code.contains("CONNECTOR") || code.contains("NETWORK") || code.contains("WORKER")
                || code.contains("TIMEOUT") || code.contains("UNAVAILABLE")
                || code.contains("COMMUNICATION") || code.contains("CONNECTION");
    }

    /**
     * Applies or replays one receipt-backed lifecycle transition for an existing recovery case.
     *
     * <p>The command identifies the case, the version observed by the caller, a receipt type, and optional new
     * execution facts. The method reads the current durable case, asks the state machine for the only legal
     * target, then executes a conditional update matching both source state and expected version. The returned
     * view reports the resulting state/version, not an instruction to perform additional work.</p>
     *
     * <p>A matching completed receipt is replayed safely and does not increment the version again. For a new
     * receipt, the INSERT reservation and case transition happen in the same transaction; races, stale versions,
     * illegal state edges, or mismatched receipt facts fail closed. The case ID is the durable security scope,
     * and no request can supply an arbitrary target state or bypass the state machine.</p>
     *
     * @param command optimistic-lock and receipt facts for one documented transition edge
     * @return persisted or replayed case summary after the transition
     * @throws PlatformBusinessException when the case is absent, the receipt is unsafe, or concurrency/state
     *                                   requirements are not met
     */
    @Transactional
    public SyncAutopilotRecoveryCaseView recordTransition(SyncAutopilotRecoveryTransitionCommand command) {
        requireTransition(command);
        SyncAutopilotRecoveryCase recoveryCase = caseMapper.selectByCaseId(command.caseId());
        if (recoveryCase == null) {
            throw new PlatformBusinessException(PlatformErrorCode.NOT_FOUND,
                    "Autopilot recovery case not found: " + command.caseId());
        }
        String receiptDigest = transitionReceiptDigest(command);
        SyncAutopilotRecoveryReceipt replay = receiptMapper.selectByReceiptId(command.receiptId());
        if (replay != null) {
            return replayTransition(replay, receiptDigest, recoveryCase);
        }
        if (!Objects.equals(recoveryCase.getVersion(), command.expectedVersion())) {
            throw conflict("Autopilot recovery case version is stale");
        }
        SyncAutopilotRecoveryCaseState current = state(recoveryCase.getCaseState());
        SyncAutopilotRecoveryCaseState target = stateMachine.targetState(current, command.receiptType());
        SyncAutopilotRecoveryReceipt receipt = processingReceipt(
                command.receiptId(), command.caseId(), receiptDigest, command.receiptType());
        if (receiptMapper.insertIfAbsent(receipt) == 0) {
            return replayTransition(requireReceipt(command.receiptId()), receiptDigest, recoveryCase);
        }
        int updated = caseMapper.transition(
                command.caseId(),
                current.name(),
                command.expectedVersion(),
                target.name(),
                command.currentExecutionId() == null
                        ? recoveryCase.getCurrentExecutionId() : command.currentExecutionId(),
                command.cycle() == null ? recoveryCase.getCycle() : command.cycle(),
                command.errorFingerprint() == null
                        ? recoveryCase.getLastErrorFingerprint() : command.errorFingerprint(),
                command.repeatedErrorCount() == null
                        ? recoveryCase.getRepeatedErrorCount() : command.repeatedErrorCount(),
                command.attentionReason()
        );
        if (updated != 1) {
            throw conflict("Autopilot recovery case transition lost an optimistic update race");
        }
        long resultingVersion = command.expectedVersion() + 1L;
        if (receiptMapper.completeReceipt(command.receiptId(), target.name(), resultingVersion) != 1) {
            throw conflict("Recovery transition receipt could not be completed");
        }
        recoveryCase.setCaseState(target.name());
        recoveryCase.setVersion(resultingVersion);
        recoveryCase.setCurrentExecutionId(command.currentExecutionId() == null
                ? recoveryCase.getCurrentExecutionId() : command.currentExecutionId());
        recoveryCase.setCycle(command.cycle() == null ? recoveryCase.getCycle() : command.cycle());
        recoveryCase.setAttentionReason(command.attentionReason());
        return view(recoveryCase);
    }

    /**
     * Produces the stable digest that binds a decision receipt ID to its complete decision facts.
     *
     * <p>Only low-sensitive scalar values and enum names are joined before hashing; null values normalize to an
     * empty component so the representation is deterministic. This helper is pure and idempotent, writes no
     * receipt itself, and does not expose raw authorization/policy text. The resulting digest lets replay logic
     * distinguish a legitimate transport retry from reuse of one receipt ID for a different decision.</p>
     *
     * @param command decision facts to bind; {@code null} maps to the empty-input digest for defensive callers
     * @return lowercase SHA-256 digest of the canonical decision receipt facts
     */
    static String decisionReceiptDigest(SyncAutopilotRecoveryDecisionCommand command) {
        if (command == null) {
            return SyncAutopilotDigestSupport.sha256("");
        }
        return SyncAutopilotDigestSupport.sha256(String.join("|",
                text(command.receiptId()), text(command.tenantId()), text(command.projectId()),
                text(command.syncTaskId()), text(command.rootExecutionId()), text(command.currentExecutionId()),
                text(command.cycle()), text(command.deadlineAt()), text(command.errorFingerprint()),
                text(command.repeatedErrorCount()),
                text(command.action()), text(command.riskLevel()), text(command.repairFingerprint()),
                text(command.confidenceScore()), text(command.evidenceAvailable())));
    }

    /**
     * Produces the stable digest that binds a transition receipt ID to its requested lifecycle facts.
     *
     * <p>The input includes case identity, expected version, receipt type, and every optional fact that could
     * otherwise change a persisted case. It is pure, idempotent, and side-effect free. Persisted receipt replay
     * compares this value before returning a prior transition, preventing a duplicate callback from changing a
     * version, execution ID, error fingerprint, or attention reason under the same receipt ID.</p>
     *
     * @param command transition facts to bind; {@code null} maps to the empty-input digest defensively
     * @return lowercase SHA-256 digest of the canonical transition receipt facts
     */
    static String transitionReceiptDigest(SyncAutopilotRecoveryTransitionCommand command) {
        if (command == null) {
            return SyncAutopilotDigestSupport.sha256("");
        }
        return SyncAutopilotDigestSupport.sha256(String.join("|",
                text(command.receiptId()), text(command.caseId()), text(command.expectedVersion()),
                text(command.receiptType()), text(command.currentExecutionId()), text(command.cycle()),
                text(command.errorFingerprint()), text(command.repeatedErrorCount()), text(command.attentionReason())));
    }

    /**
     * Finds the case identified by governed recovery facts or inserts and reloads it atomically enough for races.
     *
     * <p>The identity combines tenant/task/root execution, authorization digest, and repair fingerprint so a
     * different authorization or repair intent cannot overwrite an existing case. If no row exists, the method
     * builds a version-zero case from the deterministic policy decision and uses the mapper's unique insert. It
     * then reloads the row because another instance may have won the same identity race.</p>
     *
     * <p>This method may insert a case within the caller's transaction but never starts recovery work. Repeating
     * the same identity returns the same durable row; a visibility failure after insert is treated as a conflict
     * rather than fabricating a response. All values originate from locally revalidated command/policy facts.</p>
     *
     * @param command validated candidate facts used to populate a newly created case
     * @param decision deterministic locally evaluated policy result
     * @return existing or newly persisted case for the governed identity
     * @throws PlatformBusinessException when the inserted case cannot be reloaded consistently
     */
    private SyncAutopilotRecoveryCase ensureCase(SyncAutopilotRecoveryDecisionCommand command,
                                                  SyncAutopilotRecoveryPolicyDecision decision) {
        SyncAutopilotRecoveryCase existing = caseMapper.selectByIdentity(
                command.tenantId(), command.syncTaskId(), command.rootExecutionId(),
                decision.authorizationDigest(), command.repairFingerprint());
        if (existing != null) {
            return existing;
        }
        SyncAutopilotRecoveryCase recoveryCase = new SyncAutopilotRecoveryCase();
        recoveryCase.setTenantId(command.tenantId());
        recoveryCase.setProjectId(command.projectId());
        recoveryCase.setSyncTaskId(command.syncTaskId());
        recoveryCase.setRootExecutionId(command.rootExecutionId());
        recoveryCase.setCurrentExecutionId(command.currentExecutionId());
        recoveryCase.setExecutionMode(SyncAutopilotExecutionMode.AUTOPILOT.name());
        recoveryCase.setAuthorizationDigest(decision.authorizationDigest());
        recoveryCase.setPolicyDigest(decision.policyDigest());
        recoveryCase.setCaseState(decision.state().name());
        recoveryCase.setCycle(command.cycle());
        recoveryCase.setMaxCycles(decision.maxCycles());
        recoveryCase.setDeadlineAt(decision.deadlineAt());
        recoveryCase.setLastErrorFingerprint(command.errorFingerprint());
        recoveryCase.setRepeatedErrorCount(command.repeatedErrorCount());
        recoveryCase.setRecoveryAction(command.action().name());
        recoveryCase.setRiskLevel(command.riskLevel().name());
        recoveryCase.setRepairFingerprint(command.repairFingerprint());
        recoveryCase.setAttentionReason(decision.attentionReason());
        recoveryCase.setVersion(0L);
        caseMapper.insertIfAbsent(recoveryCase);
        SyncAutopilotRecoveryCase inserted = caseMapper.selectByIdentity(
                command.tenantId(), command.syncTaskId(), command.rootExecutionId(),
                decision.authorizationDigest(), command.repairFingerprint());
        if (inserted == null) {
            throw conflict("Autopilot recovery case was not visible after insert");
        }
        return inserted;
    }

    /**
     * Confirms that an existing identity row still represents exactly the facts of this decision attempt.
     *
     * <p>The unique case lookup deliberately does not make all non-key facts mutable. This validation compares
     * tenant/project/task lineage, authorization/policy digests, action, risk, and repair fingerprint before
     * a decision receipt can be completed. It is read-only and idempotent; a mismatch fails closed instead of
     * letting a retried event attach to a case created under different governance conditions.</p>
     *
     * @param recoveryCase persisted case selected by governed identity
     * @param command current low-sensitive decision candidate
     * @param decision current locally re-evaluated policy result
     * @throws PlatformBusinessException when any immutable governance fact differs
     */
    private void validateExistingCase(SyncAutopilotRecoveryCase recoveryCase,
                                      SyncAutopilotRecoveryDecisionCommand command,
                                      SyncAutopilotRecoveryPolicyDecision decision) {
        if (!Objects.equals(recoveryCase.getTenantId(), command.tenantId())
                || !Objects.equals(recoveryCase.getProjectId(), command.projectId())
                || !Objects.equals(recoveryCase.getSyncTaskId(), command.syncTaskId())
                || !Objects.equals(recoveryCase.getRootExecutionId(), command.rootExecutionId())
                || !Objects.equals(recoveryCase.getAuthorizationDigest(), decision.authorizationDigest())
                || !Objects.equals(recoveryCase.getPolicyDigest(), decision.policyDigest())
                || !Objects.equals(recoveryCase.getRecoveryAction(), command.action().name())
                || !Objects.equals(recoveryCase.getRiskLevel(), command.riskLevel().name())
                || !Objects.equals(recoveryCase.getRepairFingerprint(), command.repairFingerprint())) {
            throw conflict("Autopilot recovery case identity conflicts with persisted facts");
        }
    }

    /**
     * Returns the original decision result for an at-least-once callback that owns a completed receipt.
     *
     * <p>The method first proves that the receipt digest and case ID match the current request. A receipt still
     * marked {@code PROCESSING} is not replayed because its transaction has not established a durable outcome.
     * This path has no write side effect and is idempotent: every valid repeat receives the same current case
     * view, while an unsafe receipt reuse becomes a conflict.</p>
     *
     * @param receipt previously stored receipt found by the caller-supplied ID
     * @param expectedDigest digest of the newly received decision facts
     * @param recoveryCase durable case selected for the current governed identity
     * @return safe view of the existing case
     * @throws PlatformBusinessException when receipt ownership/digest/state cannot prove a safe replay
     */
    private SyncAutopilotRecoveryCaseView replayDecision(SyncAutopilotRecoveryReceipt receipt,
                                                          String expectedDigest,
                                                          SyncAutopilotRecoveryCase recoveryCase) {
        requireMatchingReceipt(receipt, expectedDigest, recoveryCase.getCaseId());
        if (!"COMPLETED".equals(receipt.getReceiptState())) {
            throw conflict("Autopilot recovery receipt is still processing");
        }
        return view(recoveryCase);
    }

    /**
     * Reconstructs a completed transition result without issuing another conditional case update.
     *
     * <p>After verifying digest and case identity, the receipt's resulting state/version are copied into the
     * in-memory case view. That is intentional: a repeated callback must observe the original successful edge
     * even though the transition is not applied a second time. The method is read-only with respect to the
     * database, idempotent for matching input, and rejects processing or mismatched receipts.</p>
     *
     * @param receipt completed transition receipt found by receipt ID
     * @param expectedDigest digest of the callback currently being processed
     * @param recoveryCase durable case selected by the command's case ID
     * @return view reflecting the receipt's resulting lifecycle state and version
     * @throws PlatformBusinessException when the receipt cannot safely prove a replay
     */
    private SyncAutopilotRecoveryCaseView replayTransition(SyncAutopilotRecoveryReceipt receipt,
                                                            String expectedDigest,
                                                            SyncAutopilotRecoveryCase recoveryCase) {
        requireMatchingReceipt(receipt, expectedDigest, recoveryCase.getCaseId());
        if (!"COMPLETED".equals(receipt.getReceiptState())) {
            throw conflict("Autopilot recovery receipt is still processing");
        }
        recoveryCase.setCaseState(receipt.getResultingCaseState());
        recoveryCase.setVersion(receipt.getResultingVersion());
        return view(recoveryCase);
    }

    /**
     * Checks that a stored receipt belongs to the same case and exactly the same low-sensitive request facts.
     *
     * <p>A globally unique receipt ID is safe only when it is bound to a digest and case ID. This helper is pure,
     * idempotent, and has no state transition; it enforces the anti-confusion boundary that prevents one retry
     * from being mistaken for another tenant/task decision or another lifecycle callback.</p>
     *
     * @param receipt persisted receipt to verify
     * @param expectedDigest digest recomputed from the current request
     * @param expectedCaseId case selected for the current request
     * @throws PlatformBusinessException when the receipt ID was reused for different recovery facts
     */
    private void requireMatchingReceipt(SyncAutopilotRecoveryReceipt receipt,
                                        String expectedDigest,
                                        Long expectedCaseId) {
        if (!Objects.equals(receipt.getReceiptDigest(), expectedDigest)
                || !Objects.equals(receipt.getCaseId(), expectedCaseId)) {
            throw conflict("receiptId was reused with different recovery facts");
        }
    }

    /**
     * Creates the initial in-memory representation of a receipt reservation.
     *
     * <p>The result starts in {@code PROCESSING}; its mapper insert is performed by the caller and its completion
     * is written only after the case decision/transition succeeds. Construction has no database side effect and
     * is deterministic for equivalent inputs. The digest and type bind the later replay boundary without storing
     * raw request payloads or authorization text.</p>
     *
     * @param receiptId caller-generated global idempotency key
     * @param caseId durable case being protected
     * @param digest canonical request-fact digest
     * @param type decision or lifecycle fact represented by the receipt
     * @return new receipt object ready for an insert-if-absent operation
     */
    private SyncAutopilotRecoveryReceipt processingReceipt(String receiptId,
                                                            Long caseId,
                                                            String digest,
                                                            SyncAutopilotRecoveryReceiptType type) {
        SyncAutopilotRecoveryReceipt receipt = new SyncAutopilotRecoveryReceipt();
        receipt.setReceiptId(receiptId);
        receipt.setCaseId(caseId);
        receipt.setReceiptDigest(digest);
        receipt.setReceiptType(type.name());
        receipt.setReceiptState("PROCESSING");
        return receipt;
    }

    /**
     * Loads the owning sync task or stops the recovery flow before policy evaluation.
     *
     * <p>The database read provides the authoritative tenant/project scope later compared with the command.
     * This helper neither writes state nor widens a missing task into a default scope; repeating the lookup is
     * safe and deterministic for unchanged persistence. Returning {@code NOT_FOUND} avoids creating an orphaned
     * Autopilot case for a task that no longer exists.</p>
     *
     * @param taskId requested task identifier
     * @return authoritative persisted sync task
     * @throws PlatformBusinessException when no task exists for the identifier
     */
    private SyncTask requireTask(Long taskId) {
        SyncTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new PlatformBusinessException(PlatformErrorCode.NOT_FOUND,
                    "Sync task not found: " + taskId);
        }
        return task;
    }

    /**
     * Loads the task definition and requires its persisted Autopilot authorization snapshot.
     *
     * <p>The definition is the local source of policy authority, not the Agent Runtime request. A missing or
     * blank snapshot is a business-state conflict because automation must fail closed when authorization cannot
     * be proven. The method reads only, is idempotent for unchanged data, and does not parse/return raw policy
     * outside the policy evaluator.</p>
     *
     * @param taskId owning task identifier used to select its definition
     * @return persisted task definition with nonblank Autopilot policy text
     * @throws PlatformBusinessException when the definition or its authorization snapshot is unavailable
     */
    private SyncTaskDefinition requireDefinition(Long taskId) {
        SyncTaskDefinition definition = definitionMapper.selectById(taskId);
        if (definition == null || definition.getAutopilotPolicy() == null
                || definition.getAutopilotPolicy().isBlank()) {
            throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                    "Sync task has no persisted Autopilot authorization");
        }
        return definition;
    }

    /**
     * Loads an execution and proves that it belongs to the recovery command's task.
     *
     * <p>Both root and current execution IDs pass through this check before a case is created. The read has no
     * side effect and is safe to repeat, but it closes a key security boundary: a valid execution identifier
     * from another task cannot be used to create a cross-task recovery lineage or influence local policy.</p>
     *
     * @param executionId execution identifier to load
     * @param taskId task that must own the execution
     * @return authoritative execution belonging to {@code taskId}
     * @throws PlatformBusinessException when the execution is absent or belongs to another task
     */
    private SyncExecution requireExecution(Long executionId, Long taskId) {
        SyncExecution execution = executionMapper.selectById(executionId);
        if (execution == null || !Objects.equals(execution.getSyncTaskId(), taskId)) {
            throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                    "Sync execution is outside the recovery task scope");
        }
        return execution;
    }

    /**
     * Verifies tenant/project agreement across the task, definition, root execution, and decision command.
     *
     * <p>This is the final local scope check before policy evaluation/persistence. It is pure and idempotent;
     * no record is changed when the facts disagree. A mismatch becomes {@code FORBIDDEN}, rather than a relaxed
     * validation error, because accepting it would allow an internal caller to bind another tenant or project
     * to a recovery case through otherwise valid identifiers.</p>
     *
     * @param task authoritative task scope
     * @param definition authoritative definition scope
     * @param rootExecution authoritative execution scope for the lineage root
     * @param command candidate scope supplied by the internal caller
     * @throws PlatformBusinessException when any tenant/project component differs
     */
    private void requireScope(SyncTask task,
                              SyncTaskDefinition definition,
                              SyncExecution rootExecution,
                              SyncAutopilotRecoveryDecisionCommand command) {
        if (!Objects.equals(task.getTenantId(), command.tenantId())
                || !Objects.equals(task.getProjectId(), command.projectId())
                || !Objects.equals(definition.getTenantId(), command.tenantId())
                || !Objects.equals(definition.getProjectId(), command.projectId())
                || !Objects.equals(rootExecution.getTenantId(), command.tenantId())
                || !Objects.equals(rootExecution.getProjectId(), command.projectId())) {
            throw new PlatformBusinessException(PlatformErrorCode.FORBIDDEN,
                    "Autopilot recovery scope does not match the sync task");
        }
    }

    /**
     * Performs the minimum command-shape validation before persistence-backed governance checks.
     *
     * <p>The check rejects absent identity, proposed action/risk, and receipt facts without making any database
     * change. It intentionally does not duplicate full policy, fingerprint, confidence, or ownership validation:
     * those later checks have the authoritative definition and execution records. This pure, idempotent guard
     * turns malformed internal calls into a stable {@code BAD_REQUEST} rather than an incidental null failure.</p>
     *
     * @param command candidate decision command
     * @throws PlatformBusinessException when required command facts are missing
     */
    private void requireDecision(SyncAutopilotRecoveryDecisionCommand command) {
        if (command == null || command.tenantId() == null || command.syncTaskId() == null
                || command.rootExecutionId() == null || command.currentExecutionId() == null
                || command.action() == null || command.riskLevel() == null
                || command.receiptId() == null || command.receiptId().isBlank()) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "Autopilot recovery decision is incomplete");
        }
    }

    /**
     * Performs minimum transition-command validation before the case and receipt are read.
     *
     * <p>A transition must identify its case/version, have a nonblank idempotency receipt, and name a lifecycle
     * receipt type other than {@code DECISION_RECORDED}, which only creates a case. The guard is pure and
     * idempotent, writes no state, and makes an incomplete internal callback fail at the API boundary rather
     * than accidentally entering the optimistic transition path.</p>
     *
     * @param command requested transition facts
     * @throws PlatformBusinessException when required facts are absent or the receipt type is not a transition
     */
    private void requireTransition(SyncAutopilotRecoveryTransitionCommand command) {
        if (command == null || command.caseId() == null || command.expectedVersion() == null
                || command.receiptId() == null || command.receiptId().isBlank()
                || command.receiptType() == null
                || command.receiptType() == SyncAutopilotRecoveryReceiptType.DECISION_RECORDED) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "Autopilot recovery transition is incomplete");
        }
    }

    /**
     * Reloads the receipt after a concurrent insert-if-absent reported that this caller did not reserve it.
     *
     * <p>The caller uses this read to decide whether the winner created an identical replayable receipt or an
     * unsafe conflicting one. It has no write side effect and is safe to repeat. Missing visibility is treated
     * as a concurrency conflict rather than as permission to insert again, preserving the receipt's global
     * idempotency boundary.</p>
     *
     * @param receiptId global idempotency key that was expected to be visible
     * @return persisted receipt selected by the key
     * @throws PlatformBusinessException when the receipt cannot be observed after reservation contention
     */
    private SyncAutopilotRecoveryReceipt requireReceipt(String receiptId) {
        SyncAutopilotRecoveryReceipt receipt = receiptMapper.selectByReceiptId(receiptId);
        if (receipt == null) {
            throw conflict("Autopilot recovery receipt could not be reserved");
        }
        return receipt;
    }

    /**
     * Converts a persisted state code back into the closed lifecycle enum used by the state machine.
     *
     * <p>The database stores enum names as strings, so this pure helper makes corruption or an unsupported
     * migration value fail closed as a business conflict. It performs no update and is idempotent for valid
     * values; callers cannot use it to introduce an arbitrary state because {@link Enum#valueOf(Class, String)}
     * accepts only declared enum constants.</p>
     *
     * @param value persisted case-state code
     * @return matching lifecycle enum constant
     * @throws PlatformBusinessException when the persisted state is invalid
     */
    private SyncAutopilotRecoveryCaseState state(String value) {
        try {
            return SyncAutopilotRecoveryCaseState.valueOf(value);
        } catch (RuntimeException exception) {
            throw conflict("Autopilot recovery case has an invalid state");
        }
    }

    /**
     * Maps one persisted case to the restricted view returned to internal callers.
     *
     * <p>The mapping exposes only operational identifiers, state/version, bounded cycle data, action, attention
     * code, and policy digest. It omits raw policy JSON, authorization text, error payloads, credentials, and
     * any evidence. It is a side-effect-free, idempotent projection; the returned view documents state but does
     * not authorize another transition without the matching optimistic version and receipt.</p>
     *
     * @param recoveryCase authoritative case loaded or updated in the current transaction
     * @return browser-safe/internal-safe recovery case summary
     */
    private SyncAutopilotRecoveryCaseView view(SyncAutopilotRecoveryCase recoveryCase) {
        return new SyncAutopilotRecoveryCaseView(
                recoveryCase.getCaseId(),
                recoveryCase.getSyncTaskId(),
                recoveryCase.getRootExecutionId(),
                recoveryCase.getCurrentExecutionId(),
                state(recoveryCase.getCaseState()),
                recoveryCase.getVersion(),
                recoveryCase.getCycle(),
                recoveryCase.getMaxCycles(),
                recoveryCase.getRecoveryAction(),
                recoveryCase.getAttentionReason(),
                recoveryCase.getAuthorizationDigest(),
                recoveryCase.getPolicyDigest()
        );
    }

    private PlatformBusinessException conflict(String message) {
        return new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT, message);
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
