/**
 * @Author : Cui
 * @Date: 2026/08/12
 * @Description DataSmart Govern Backend - SyncAutopilotRecoveryAutonomousQuarantineService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import com.czh.datasmart.govern.datasync.controller.dto.SyncDirtyRecordQuarantineRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncDirtyRecordQuarantineResult;
import com.czh.datasmart.govern.datasync.entity.SyncAutopilotRecoveryCase;
import com.czh.datasmart.govern.datasync.entity.SyncAutopilotRecoveryQuarantineReceipt;
import com.czh.datasmart.govern.datasync.entity.SyncTask;
import com.czh.datasmart.govern.datasync.entity.SyncTaskDefinition;
import com.czh.datasmart.govern.datasync.mapper.SyncAutopilotRecoveryCaseMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncAutopilotRecoveryQuarantineReceiptMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncTaskDefinitionMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncTaskMapper;
import com.czh.datasmart.govern.datasync.support.SyncAutopilotExecutionMode;
import com.czh.datasmart.govern.datasync.support.SyncAutopilotRecoveryAction;
import com.czh.datasmart.govern.datasync.support.SyncAutopilotRecoveryCaseState;
import com.czh.datasmart.govern.datasync.support.SyncAutopilotRiskLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Executes one low-risk quarantine inside an already authorized Autopilot recovery case.
 *
 * <p>This is a second policy boundary, not a generic internal proxy. It reloads the case and current task policy,
 * checks scope/version/cycle/deadline/action/risk/digests, recomputes the canonical action fingerprint, reserves a
 * durable receipt, and only then delegates to the shared quarantine implementation. A transaction makes receipt
 * reservation, error-sample state changes, audit, and receipt completion one atomic unit.</p>
 */
@Service
@RequiredArgsConstructor
public class SyncAutopilotRecoveryAutonomousQuarantineService {

    private static final String RECEIPT_SUFFIX = ":quarantine-apply";
    private static final Pattern SHA_256 = Pattern.compile("^[0-9a-f]{64}$");
    private static final Pattern SAFE_RECEIPT_ID = Pattern.compile("^[A-Za-z0-9._:-]{1,128}$");
    private static final int MAX_SAMPLE_COUNT = 500;

    private final SyncAutopilotRecoveryCaseMapper caseMapper;
    private final SyncTaskMapper taskMapper;
    private final SyncTaskDefinitionMapper definitionMapper;
    private final SyncAutopilotRecoveryQuarantineReceiptMapper receiptMapper;
    private final SyncDirtyRecordQuarantineSupport quarantineSupport;
    private final SyncAutopilotRecoveryPolicyEvaluator policyEvaluator;

    /**
     * Compatibility entry used by focused service tests; production HTTP calls use the dual-principal overload.
     */
    @Transactional
    public SyncAutopilotRecoveryQuarantineReceiptView apply(
            SyncAutopilotRecoveryQuarantineCommand command) {
        return apply(command, new SyncAutopilotRecoveryPrincipalContext(
                "0", "AGENT_AUTOPILOT", "agent-autopilot", "test-delegation", command.receiptId()));
    }

    /**
     * Validates and atomically applies an autonomous quarantine or replays its first completed receipt.
     *
     * @param command low-sensitive case, preview, digest, sample-ID, and idempotency facts
     * @param principal represented user plus Agent/delegation audit identities
     * @return durable receipt proving exactly how many rows changed state
     * @throws PlatformBusinessException when authorization, scope, preview, state, or replay facts differ
     */
    @Transactional
    public SyncAutopilotRecoveryQuarantineReceiptView apply(
            SyncAutopilotRecoveryQuarantineCommand command,
            SyncAutopilotRecoveryPrincipalContext principal) {
        List<Long> sampleIds = requireCommand(command);
        SyncAutopilotRecoveryCase recoveryCase = requireCase(command.caseId());
        requireCaseScope(recoveryCase, command);
        String digest = requestDigest(command);

        SyncAutopilotRecoveryQuarantineReceipt replay = receiptMapper.selectByReceiptId(command.receiptId());
        if (replay != null) {
            return replay(replay, digest, command, principal);
        }

        requireExecutableCase(recoveryCase, command);
        SyncTask task = requireCurrentTask(command);
        SyncTaskDefinition definition = requireCurrentPolicy(command, task);
        requireCurrentAuthorization(recoveryCase, definition, command);
        requireActionFingerprint(recoveryCase, command, sampleIds);

        SyncAutopilotRecoveryQuarantineReceipt receipt = processingReceipt(
                command, principal, digest, sampleIds.size());
        if (receiptMapper.insertIfAbsent(receipt) == 0) {
            SyncAutopilotRecoveryQuarantineReceipt concurrent =
                    receiptMapper.selectByReceiptId(command.receiptId());
            if (concurrent == null) {
                throw conflict("Autopilot quarantine receipt could not be reserved");
            }
            return replay(concurrent, digest, command, principal);
        }

        SyncDirtyRecordQuarantineRequest request = quarantineRequest(command, sampleIds);
        SyncDirtyRecordQuarantineResult applied = quarantineSupport.applyAutonomous(
                task, request, auditActor(command, principal));
        if (!"APPLIED".equals(applied.operationState())
                || applied.affectedCount() != sampleIds.size()
                || !Objects.equals(applied.confirmationDigest(), command.previewDigest())) {
            throw conflict("Autopilot quarantine did not produce the required applied result");
        }
        if (receiptMapper.completeReceipt(
                command.receiptId(), sampleIds.size(), applied.affectedCount(), applied.operationState()) != 1) {
            throw conflict("Autopilot quarantine receipt could not be completed");
        }
        return new SyncAutopilotRecoveryQuarantineReceiptView(
                receipt.getReceiptId(), receipt.getCaseId(), receipt.getSyncTaskId(),
                receipt.getExecutionId(), receipt.getSelectedCount(), applied.affectedCount(),
                applied.operationState(), "COMPLETED", receipt.getPreviewDigest(),
                receipt.getActionFingerprint());
    }

    /**
     * Computes the receipt binding from canonical low-sensitive request facts.
     *
     * <p>Sorting IDs makes semantically identical requests replay the same result; every governance digest and
     * scope value remains included so a receipt ID cannot be reused after any authorized fact changes.</p>
     */
    static String requestDigest(SyncAutopilotRecoveryQuarantineCommand command) {
        String ids = command.selectedSampleIds().stream().sorted().map(String::valueOf)
                .reduce((left, right) -> left + "," + right).orElse("");
        return SyncAutopilotDigestSupport.sha256(String.join("|",
                text(command.caseId()), text(command.expectedVersion()), text(command.tenantId()),
                text(command.projectId()), text(command.syncTaskId()), text(command.executionId()),
                text(command.cycle()), text(command.authorizationDigest()), text(command.policyDigest()),
                text(command.previewDigest()), ids, text(command.actionFingerprint()), text(command.receiptId())));
    }

    /** Performs cheap shape checks before reading or reserving persistence facts. */
    private List<Long> requireCommand(SyncAutopilotRecoveryQuarantineCommand command) {
        if (command == null || command.caseId() == null || command.caseId() <= 0
                || command.expectedVersion() == null || command.expectedVersion() < 0
                || command.tenantId() == null || command.tenantId() <= 0
                || command.syncTaskId() == null || command.syncTaskId() <= 0
                || command.executionId() == null || command.executionId() <= 0
                || command.cycle() == null || command.cycle() <= 0
                || !sha256(command.authorizationDigest()) || !sha256(command.policyDigest())
                || !sha256(command.previewDigest()) || !sha256(command.actionFingerprint())
                || command.receiptId() == null || !SAFE_RECEIPT_ID.matcher(command.receiptId()).matches()
                || !command.receiptId().endsWith(RECEIPT_SUFFIX)) {
            throw badRequest("Autopilot quarantine command is incomplete");
        }
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        for (Long id : command.selectedSampleIds()) {
            if (id == null || id <= 0 || !ids.add(id)) {
                throw badRequest("Autopilot quarantine sample IDs must be unique positive integers");
            }
        }
        if (ids.isEmpty() || ids.size() > MAX_SAMPLE_COUNT) {
            throw badRequest("Autopilot quarantine sample count is outside the allowed boundary");
        }
        return ids.stream().sorted().toList();
    }

    /** Loads the durable case that owns the requested side effect. */
    private SyncAutopilotRecoveryCase requireCase(Long caseId) {
        SyncAutopilotRecoveryCase recoveryCase = caseMapper.selectByCaseId(caseId);
        if (recoveryCase == null) {
            throw new PlatformBusinessException(PlatformErrorCode.NOT_FOUND,
                    "Autopilot recovery case not found: " + caseId);
        }
        return recoveryCase;
    }

    /** Proves the caller has not mixed a valid case with another tenant, task, or execution. */
    private void requireCaseScope(SyncAutopilotRecoveryCase recoveryCase,
                                  SyncAutopilotRecoveryQuarantineCommand command) {
        if (!Objects.equals(recoveryCase.getTenantId(), command.tenantId())
                || !Objects.equals(recoveryCase.getProjectId(), command.projectId())
                || !Objects.equals(recoveryCase.getSyncTaskId(), command.syncTaskId())
                || !Objects.equals(recoveryCase.getCurrentExecutionId(), command.executionId())) {
            throw new PlatformBusinessException(PlatformErrorCode.FORBIDDEN,
                    "Autopilot quarantine is outside the authorized task and execution scope");
        }
    }

    /** Checks state, optimistic version, cycle, deadline, action, and risk immediately before execution. */
    private void requireExecutableCase(SyncAutopilotRecoveryCase recoveryCase,
                                       SyncAutopilotRecoveryQuarantineCommand command) {
        if (!SyncAutopilotRecoveryCaseState.AUTO_APPROVED.name().equals(recoveryCase.getCaseState())
                || !Objects.equals(recoveryCase.getVersion(), command.expectedVersion())
                || !Objects.equals(recoveryCase.getCycle(), command.cycle())
                || recoveryCase.getCycle() == null || recoveryCase.getMaxCycles() == null
                || recoveryCase.getCycle() > recoveryCase.getMaxCycles()
                || recoveryCase.getDeadlineAt() == null
                || !recoveryCase.getDeadlineAt().isAfter(LocalDateTime.now(ZoneOffset.UTC))
                || !SyncAutopilotRecoveryAction.APPLY_QUARANTINE.name()
                .equals(recoveryCase.getRecoveryAction())
                || !SyncAutopilotRiskLevel.LOW.name().equals(recoveryCase.getRiskLevel())) {
            throw conflict("Autopilot recovery case is not executable inside the authorization boundary");
        }
    }

    /**
     * Reloads the authoritative task row immediately before the quarantine side effect.
     *
     * <p>The recovery case and definition are control-plane facts, but neither replaces the task aggregate that owns
     * the execution and audit entry. Requiring the task's current tenant/project scope prevents a stale definition or
     * cross-boundary command from constructing a synthetic task view. The loaded entity is passed unchanged to the
     * shared quarantine implementation, so all existing task/execution/sample checks observe the persisted owner.</p>
     */
    private SyncTask requireCurrentTask(SyncAutopilotRecoveryQuarantineCommand command) {
        SyncTask task = taskMapper.selectById(command.syncTaskId());
        if (task == null || !Objects.equals(task.getTenantId(), command.tenantId())
                || !Objects.equals(task.getProjectId(), command.projectId())) {
            throw conflict("Autopilot quarantine task or scope is unavailable");
        }
        return task;
    }

    /** Reloads the task's current authorization snapshot and validates definition scope. */
    private SyncTaskDefinition requireCurrentPolicy(SyncAutopilotRecoveryQuarantineCommand command,
                                                    SyncTask task) {
        SyncTaskDefinition definition = definitionMapper.selectById(command.syncTaskId());
        if (definition == null || definition.getAutopilotPolicy() == null
                || definition.getAutopilotPolicy().isBlank()
                || !Objects.equals(definition.getTenantId(), command.tenantId())
                || !Objects.equals(definition.getProjectId(), command.projectId())
                || !Objects.equals(definition.getTenantId(), task.getTenantId())
                || !Objects.equals(definition.getProjectId(), task.getProjectId())) {
            throw conflict("Autopilot quarantine task policy or scope is unavailable");
        }
        return definition;
    }

    /**
     * Re-evaluates the persisted policy and requires exact agreement with both case and caller digests.
     */
    private void requireCurrentAuthorization(SyncAutopilotRecoveryCase recoveryCase,
                                             SyncTaskDefinition definition,
                                             SyncAutopilotRecoveryQuarantineCommand command) {
        if (recoveryCase.getRepeatedErrorCount() == null
                || recoveryCase.getLastErrorFingerprint() == null) {
            throw conflict("Autopilot recovery case is missing error-boundary facts");
        }
        SyncAutopilotRecoveryPolicyDecision decision = policyEvaluator.evaluate(
                definition.getAutopilotPolicy(),
                new SyncAutopilotRecoveryEvaluationRequest(
                        SyncAutopilotExecutionMode.AUTOPILOT,
                        command.tenantId(), command.projectId(), command.syncTaskId(), command.cycle(),
                        recoveryCase.getDeadlineAt(), recoveryCase.getLastErrorFingerprint(),
                        recoveryCase.getRepeatedErrorCount(), SyncAutopilotRecoveryAction.APPLY_QUARANTINE,
                        SyncAutopilotRiskLevel.LOW, command.actionFingerprint(), command.receiptId(),
                        100, true, LocalDateTime.now(ZoneOffset.UTC)));
        if (decision.state() != SyncAutopilotRecoveryCaseState.AUTO_APPROVED
                || !Objects.equals(decision.authorizationDigest(), recoveryCase.getAuthorizationDigest())
                || !Objects.equals(decision.policyDigest(), recoveryCase.getPolicyDigest())
                || !Objects.equals(command.authorizationDigest(), recoveryCase.getAuthorizationDigest())
                || !Objects.equals(command.policyDigest(), recoveryCase.getPolicyDigest())) {
            throw conflict("Autopilot quarantine authorization or policy digest has changed");
        }
    }

    /** Independently reconstructs the Python/Agent Runtime canonical apply fingerprint. */
    private void requireActionFingerprint(SyncAutopilotRecoveryCase recoveryCase,
                                          SyncAutopilotRecoveryQuarantineCommand command,
                                          List<Long> sampleIds) {
        String eventId = command.receiptId().substring(
                0, command.receiptId().length() - RECEIPT_SUFFIX.length());
        String ids = sampleIds.stream().map(String::valueOf)
                .reduce((left, right) -> left + "," + right).orElse("");
        String expected = SyncAutopilotDigestSupport.sha256(String.join("|",
                eventId, recoveryCase.getLastErrorFingerprint(), text(command.executionId()),
                SyncAutopilotRecoveryAction.APPLY_QUARANTINE.name(), command.previewDigest(), ids));
        if (!Objects.equals(expected, command.actionFingerprint())
                || !Objects.equals(expected, recoveryCase.getRepairFingerprint())) {
            throw conflict("Autopilot quarantine action fingerprint does not match preview facts");
        }
    }

    /** Creates the exact sample-ID request consumed by the shared quarantine implementation. */
    private SyncDirtyRecordQuarantineRequest quarantineRequest(
            SyncAutopilotRecoveryQuarantineCommand command, List<Long> sampleIds) {
        SyncDirtyRecordQuarantineRequest request = new SyncDirtyRecordQuarantineRequest();
        request.setExecutionId(command.executionId());
        request.setErrorSampleIds(sampleIds);
        request.setQuarantineAllRetryableInExecution(false);
        request.setReason(SyncDirtyRecordQuarantineSupport.AUTOPILOT_QUARANTINE_REASON);
        request.setConfirmationDigest(command.previewDigest());
        request.setConfirmed(false);
        return request;
    }

    /** Converts the represented actor to the existing audit contract without inventing another identity. */
    private SyncActorContext auditActor(SyncAutopilotRecoveryQuarantineCommand command,
                                        SyncAutopilotRecoveryPrincipalContext principal) {
        Long actorId = null;
        try {
            actorId = Long.valueOf(principal.representedActorId());
        } catch (RuntimeException ignored) {
            // The durable quarantine receipt still retains a non-numeric platform actor identifier.
        }
        return new SyncActorContext(command.tenantId(), actorId,
                principal.actorRole(), principal.traceId());
    }

    /** Creates a PROCESSING receipt before the guarded sample updates run. */
    private SyncAutopilotRecoveryQuarantineReceipt processingReceipt(
            SyncAutopilotRecoveryQuarantineCommand command,
            SyncAutopilotRecoveryPrincipalContext principal,
            String digest,
            int selectedCount) {
        requirePrincipal(principal);
        SyncAutopilotRecoveryQuarantineReceipt receipt =
                new SyncAutopilotRecoveryQuarantineReceipt();
        receipt.setReceiptId(command.receiptId());
        receipt.setCaseId(command.caseId());
        receipt.setRequestDigest(digest);
        receipt.setPreviewDigest(command.previewDigest());
        receipt.setActionFingerprint(command.actionFingerprint());
        receipt.setSyncTaskId(command.syncTaskId());
        receipt.setExecutionId(command.executionId());
        receipt.setRepresentedActorId(principal.representedActorId());
        receipt.setAgentId(principal.agentId());
        receipt.setDelegationId(principal.delegationId());
        receipt.setSelectedCount(selectedCount);
        receipt.setAffectedCount(0);
        receipt.setOperationState("PROCESSING");
        receipt.setReceiptState("PROCESSING");
        return receipt;
    }

    /** Returns a completed matching receipt without reapplying sample updates. */
    private SyncAutopilotRecoveryQuarantineReceiptView replay(
            SyncAutopilotRecoveryQuarantineReceipt receipt,
            String digest,
            SyncAutopilotRecoveryQuarantineCommand command,
            SyncAutopilotRecoveryPrincipalContext principal) {
        if (!Objects.equals(receipt.getRequestDigest(), digest)
                || !Objects.equals(receipt.getCaseId(), command.caseId())
                || !Objects.equals(receipt.getPreviewDigest(), command.previewDigest())
                || !Objects.equals(receipt.getActionFingerprint(), command.actionFingerprint())
                || (receipt.getRepresentedActorId() != null
                && !Objects.equals(receipt.getRepresentedActorId(), principal.representedActorId()))
                || (receipt.getAgentId() != null && !Objects.equals(receipt.getAgentId(), principal.agentId()))
                || (receipt.getDelegationId() != null
                && !Objects.equals(receipt.getDelegationId(), principal.delegationId()))) {
            throw conflict("receiptId was reused with different Autopilot quarantine facts");
        }
        if (!"COMPLETED".equals(receipt.getReceiptState())
                || !"APPLIED".equals(receipt.getOperationState())) {
            throw conflict("Autopilot quarantine receipt is still processing");
        }
        return view(receipt);
    }

    /** Requires both represented-user and Agent/delegation identities for durable audit. */
    private void requirePrincipal(SyncAutopilotRecoveryPrincipalContext principal) {
        if (principal == null || blank(principal.representedActorId()) || blank(principal.actorRole())
                || blank(principal.agentId()) || blank(principal.delegationId())) {
            throw new PlatformBusinessException(PlatformErrorCode.FORBIDDEN,
                    "Autopilot quarantine requires dual-principal delegation facts");
        }
    }

    /** Projects only low-sensitive completion facts to the internal caller. */
    private SyncAutopilotRecoveryQuarantineReceiptView view(
            SyncAutopilotRecoveryQuarantineReceipt receipt) {
        return new SyncAutopilotRecoveryQuarantineReceiptView(
                receipt.getReceiptId(), receipt.getCaseId(), receipt.getSyncTaskId(),
                receipt.getExecutionId(), receipt.getSelectedCount(), receipt.getAffectedCount(),
                receipt.getOperationState(), receipt.getReceiptState(), receipt.getPreviewDigest(),
                receipt.getActionFingerprint());
    }

    private boolean sha256(String value) {
        return value != null && SHA_256.matcher(value).matches();
    }

    private boolean blank(String value) {
        return value == null || value.isBlank() || value.trim().length() > 128;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private PlatformBusinessException badRequest(String message) {
        return new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST, message);
    }

    private PlatformBusinessException conflict(String message) {
        return new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT, message);
    }
}
