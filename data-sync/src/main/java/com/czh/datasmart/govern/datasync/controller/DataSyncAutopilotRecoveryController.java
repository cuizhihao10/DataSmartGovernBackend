/**
 * @Author : Cui
 * @Date: 2026/08/11 19:35
 * @Description DataSmart Govern Backend - DataSyncAutopilotRecoveryController.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller;

import com.czh.datasmart.govern.common.api.PlatformApiResponse;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import com.czh.datasmart.govern.datasync.controller.dto.SyncAutopilotRecoveryDecisionRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncAutopilotRecoveryDeadLetterRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncAutopilotRecoveryQuarantineRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncAutopilotRecoveryTriggerConsumerResultRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncAutopilotRecoveryTransitionRequest;
import com.czh.datasmart.govern.datasync.service.support.SyncAutopilotRecoveryCaseService;
import com.czh.datasmart.govern.datasync.service.support.SyncAutopilotRecoveryCaseView;
import com.czh.datasmart.govern.datasync.service.support.SyncAutopilotRecoveryAutonomousQuarantineService;
import com.czh.datasmart.govern.datasync.service.support.SyncAutopilotRecoveryDecisionCommand;
import com.czh.datasmart.govern.datasync.service.support.SyncAutopilotRecoveryDeadLetterService;
import com.czh.datasmart.govern.datasync.service.support.SyncAutopilotRecoveryPrincipalContext;
import com.czh.datasmart.govern.datasync.service.support.SyncAutopilotRecoveryQuarantineCommand;
import com.czh.datasmart.govern.datasync.service.support.SyncAutopilotRecoveryQuarantineReceiptView;
import com.czh.datasmart.govern.datasync.service.support.SyncAutopilotRecoveryTransitionCommand;
import com.czh.datasmart.govern.datasync.service.support.SyncAutopilotRecoveryTriggerConsumerResultCommand;
import com.czh.datasmart.govern.datasync.service.support.SyncAutopilotRecoveryTriggerConsumerResultService;
import com.czh.datasmart.govern.datasync.service.support.SyncAutopilotRecoveryTriggerConsumerResultView;
import com.czh.datasmart.govern.datasync.support.SyncAutopilotRecoveryAction;
import com.czh.datasmart.govern.datasync.support.SyncAutopilotRecoveryConsumerResultStatus;
import com.czh.datasmart.govern.datasync.support.SyncAutopilotRecoveryReceiptType;
import com.czh.datasmart.govern.datasync.support.SyncAutopilotRiskLevel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.ZoneOffset;
import java.util.Locale;

/**
 * agent-runtime 与 data-sync 之间的 Autopilot 状态控制 API。
 *
 * <p>该控制器不暴露给浏览器。Gateway 会清理内部令牌 Header，部署环境还应使用内网路由、mTLS
 * 或服务网格 ACL。即使内部认证通过，服务层仍会重新加载任务、授权和 execution 归属，并通过
 * 乐观锁与幂等 receipt 防止越权和重复状态推进。</p>
 */
@RestController
@RequestMapping("/internal/data-sync/autopilot/recovery")
public class DataSyncAutopilotRecoveryController {

    private final SyncAutopilotRecoveryCaseService caseService;
    private final SyncAutopilotRecoveryTriggerConsumerResultService consumerResultService;
    private final SyncAutopilotRecoveryAutonomousQuarantineService autonomousQuarantineService;
    private final SyncAutopilotRecoveryDeadLetterService deadLetterService;

    /**
     * Secret injected by the deployment that proves the request crossed the trusted internal hop.
     *
     * <p>A blank value is an invalid deployment configuration, never a local-development
     * permission. The value remains in memory only and is never copied to a response, log,
     * database row, or audit payload.</p>
     */
    private final String internalServiceToken;

    /**
     * Constructs the internal recovery controller with its shared service credential.
     *
     * <p>All routes use the same credential check. Keeping it as an explicit constructor
     * dependency makes the security boundary visible and lets unit tests verify the missing
     * configuration path without mutating process-wide environment variables.</p>
     */
    public DataSyncAutopilotRecoveryController(
            SyncAutopilotRecoveryCaseService caseService,
            SyncAutopilotRecoveryTriggerConsumerResultService consumerResultService,
            SyncAutopilotRecoveryAutonomousQuarantineService autonomousQuarantineService,
            SyncAutopilotRecoveryDeadLetterService deadLetterService,
            @Value("${DATASMART_AGENT_RUNTIME_INTERNAL_SERVICE_TOKEN:}") String internalServiceToken) {
        this.caseService = caseService;
        this.consumerResultService = consumerResultService;
        this.autonomousQuarantineService = autonomousQuarantineService;
        this.deadLetterService = deadLetterService;
        this.internalServiceToken = internalServiceToken;
    }

    /**
     * Applies a preview-bound quarantine inside the task's initial Autopilot authorization box.
     *
     * <p>The route is fixed and service-internal: callers cannot supply another URL, tool name, reason, selector,
     * or source-record value. Transport authentication is followed by service-layer policy, scope, digest,
     * state, deadline, idempotency, and selector revalidation. A successful response is a durable completion
     * receipt; it does not by itself claim the later failed-object retry has succeeded.</p>
     *
     * @param caseId recovery case that already holds an AUTO_APPROVED APPLY_QUARANTINE decision
     * @param request low-sensitive preview and scope bindings
     * @param internalToken Agent Runtime service credential
     * @param representedActorId user whose initial authorization remains in force
     * @param actorRole represented user's role retained for the existing audit format
     * @param agentId autonomous Agent identity that selected the action
     * @param delegationId initial user-to-Agent authorization identifier
     * @param traceId cross-service trace identifier
     * @return durable idempotent quarantine receipt
     */
    @PostMapping("/cases/{caseId}/quarantine/apply")
    public PlatformApiResponse<SyncAutopilotRecoveryQuarantineReceiptView> applyAutonomousQuarantine(
            @PathVariable Long caseId,
            @RequestBody SyncAutopilotRecoveryQuarantineRequest request,
            @RequestHeader(value = PlatformContextHeaders.INTERNAL_SERVICE_TOKEN, required = false)
            String internalToken,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false)
            String representedActorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false)
            String actorRole,
            @RequestHeader(value = PlatformContextHeaders.AGENT_ID, required = false)
            String agentId,
            @RequestHeader(value = PlatformContextHeaders.AGENT_DELEGATION_ID, required = false)
            String delegationId,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        verifyInternalServiceToken(internalToken);
        if (caseId == null || caseId <= 0 || request == null) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "Autopilot quarantine request is incomplete");
        }
        SyncAutopilotRecoveryQuarantineReceiptView view = autonomousQuarantineService.apply(
                new SyncAutopilotRecoveryQuarantineCommand(
                        caseId, request.expectedVersion(), request.tenantId(), request.projectId(),
                        request.syncTaskId(), request.executionId(), request.cycle(),
                        request.authorizationDigest(), request.policyDigest(), request.previewDigest(),
                        request.selectedSampleIds(), request.actionFingerprint(), request.receiptId()),
                new SyncAutopilotRecoveryPrincipalContext(
                        representedActorId, actorRole, agentId, delegationId, traceId));
        return PlatformApiResponse.success(view, traceId);
    }

    /**
     * Records an Agent Runtime recovery candidate after data-sync re-evaluates the task-local policy.
     *
     * <p>The request supplies only low-sensitive scope facts, fingerprints, bounded counters, and a proposed
     * action. It is not treated as authority to execute: the service reloads the task, its persisted authorization
     * snapshot, and the referenced executions before it creates or reuses a recovery case. The only durable side
     * effect is a case plus its {@code DECISION_RECORDED} receipt; no worker, Kafka message, or repair action is
     * launched here. The response exposes state/version but not policy text, evidence, credentials, or model output.</p>
     *
     * <p>Calling the endpoint repeatedly with the same {@code receiptId} and identical decision facts is
     * idempotent because the completed receipt is replayed. Reusing that ID with changed facts is rejected. The
     * internal token protects this transport boundary, and the service rechecks tenant/project/execution scope
     * and policy authority so an internal caller cannot create a cross-tenant control path.</p>
     *
     * @param request low-sensitive decision facts, including deadline, fingerprints, action, risk, and receipt ID
     * @param internalToken Agent Runtime service token; configured deployments require an exact match
     * @param traceId cross-service correlation ID copied into the platform response envelope
     * @return persisted or replayed recovery case state and optimistic version
     * @throws PlatformBusinessException when authentication, shape, enum, scope, policy, or receipt checks fail
     */
    @PostMapping("/decisions")
    public PlatformApiResponse<SyncAutopilotRecoveryCaseView> recordDecision(
            @RequestBody SyncAutopilotRecoveryDecisionRequest request,
            @RequestHeader(value = PlatformContextHeaders.INTERNAL_SERVICE_TOKEN, required = false)
            String internalToken,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        verifyInternalServiceToken(internalToken);
        requireDecisionRequest(request);
        SyncAutopilotRecoveryCaseView view = caseService.recordDecision(
                new SyncAutopilotRecoveryDecisionCommand(
                        request.tenantId(),
                        request.projectId(),
                        request.syncTaskId(),
                        request.rootExecutionId(),
                        request.currentExecutionId(),
                        request.cycle(),
                        request.deadlineAt().withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime(),
                        request.errorFingerprint(),
                        request.repeatedErrorCount(),
                        enumValue(SyncAutopilotRecoveryAction.class, request.action(), "action"),
                        enumValue(SyncAutopilotRiskLevel.class, request.riskLevel(), "riskLevel"),
                        request.repairFingerprint(),
                        request.receiptId(),
                        request.confidenceScore(),
                        request.evidenceAvailable()
                ));
        return PlatformApiResponse.success(view, traceId);
    }

    /**
     * Records one legal lifecycle receipt for an existing recovery case.
     *
     * <p>{@code caseId} identifies the durable record, {@code expectedVersion} proves which version the caller
     * observed, and {@code receiptId} identifies an at-least-once callback. Optional execution, cycle, error,
     * and attention fields represent newly observed facts; null preserves the persisted fact. The service, not
     * the HTTP client, obtains the target state from its state machine and uses conditional SQL to arbitrate
     * concurrent writers.</p>
     *
     * <p>An identical completed receipt is idempotently replayed. A stale version, in-progress receipt, or
     * receipt reused for changed facts produces a conflict rather than a second state advance. Token validation
     * occurs here; the service then enforces durable scope and transition legality, so a caller cannot choose an
     * arbitrary state or cross a tenant/project boundary.</p>
     *
     * @param caseId positive recovery-case identifier from the control-plane URL
     * @param request optimistic-lock, receipt, and optional newly observed lifecycle facts
     * @param internalToken Agent Runtime service token required by a configured deployment
     * @param traceId cross-service correlation ID copied into the response envelope
     * @return persisted or replayed case state and optimistic version
     * @throws PlatformBusinessException when the request, token, receipt, state, or optimistic version is invalid
     */
    @PostMapping("/cases/{caseId}/transitions")
    public PlatformApiResponse<SyncAutopilotRecoveryCaseView> recordTransition(
            @PathVariable Long caseId,
            @RequestBody SyncAutopilotRecoveryTransitionRequest request,
            @RequestHeader(value = PlatformContextHeaders.INTERNAL_SERVICE_TOKEN, required = false)
            String internalToken,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        verifyInternalServiceToken(internalToken);
        if (caseId == null || caseId <= 0 || request == null) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "Autopilot recovery transition request is incomplete");
        }
        SyncAutopilotRecoveryCaseView view = caseService.recordTransition(
                new SyncAutopilotRecoveryTransitionCommand(
                        caseId,
                        request.expectedVersion(),
                        request.receiptId(),
                        enumValue(SyncAutopilotRecoveryReceiptType.class,
                                request.receiptType(), "receiptType"),
                        request.currentExecutionId(),
                        request.cycle(),
                        request.errorFingerprint(),
                        request.repeatedErrorCount(),
                        request.attentionReason()
                ));
        return PlatformApiResponse.success(view, traceId);
    }

    /**
     * Persists the final low-sensitive result returned by the durable Autopilot trigger consumer.
     *
     * <p>The path event ID and request execution ID are both required because the service must locate the
     * original outbox row before accepting a callback. The caller cannot provide a digest, payload, model
     * response, topic, or new event data: data-sync validates the finite status enum, normalizes a short reason
     * code, computes the digest itself, and persists only those small facts. This preserves a useful audit trail
     * without turning the outbox into storage for model text or raw errors.</p>
     *
     * <p>A repeated callback with the same facts returns the first stored low-sensitive view and keeps its
     * original consumed timestamp. Reusing the event ID for a different status, reason, case, or execution fails
     * closed in the service. The internal token authenticates the service boundary; the event/execution lookup
     * then proves that the callback belongs to a trigger actually produced by this data-sync instance.</p>
     *
     * @param eventId immutable trigger identifier from the Kafka event
     * @param request consumer outcome consisting only of status, reason code, optional case, and execution ID
     * @param internalToken Agent Runtime service token required by configured deployments
     * @param traceId cross-service correlation ID copied into the standard platform envelope
     * @return durable low-sensitive consumer-result view, never the raw outbox payload or model response
     * @throws PlatformBusinessException when token, request, status, reason format, or outbox facts are invalid
     */
    @PostMapping("/triggers/{eventId}/results")
    public PlatformApiResponse<SyncAutopilotRecoveryTriggerConsumerResultView> recordTriggerConsumerResult(
            @PathVariable String eventId,
            @RequestBody SyncAutopilotRecoveryTriggerConsumerResultRequest request,
            @RequestHeader(value = PlatformContextHeaders.INTERNAL_SERVICE_TOKEN, required = false)
            String internalToken,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        verifyInternalServiceToken(internalToken);
        requireConsumerResultRequest(eventId, request);
        SyncAutopilotRecoveryTriggerConsumerResultView view = consumerResultService.recordConsumerResult(
                eventId.trim(),
                new SyncAutopilotRecoveryTriggerConsumerResultCommand(
                        enumValue(SyncAutopilotRecoveryConsumerResultStatus.class, request.status(), "status"),
                        shortEnumText(request.reasonCode(), "reasonCode"),
                        request.caseId(),
                        request.currentExecutionId(),
                        optionalShortEnumText(request.retrievalDecision(), "retrievalDecision"),
                        optionalShortEnumText(request.retrievalStrategy(), "retrievalStrategy"),
                        request.retrievalEvidenceCount(),
                        normalizedEvidenceDigest(request.retrievalEvidenceDigest())
                ));
        return PlatformApiResponse.success(view, traceId);
    }

    /**
     * Converges one exhausted Agent Runtime Kafka delivery using only data-sync-owned persistence facts.
     *
     * <p>The caller supplies the event ID and its original execution ID, but cannot choose a case, target state,
     * receipt, reason, or error description. After service-token authentication, the dead-letter service resolves
     * the original outbox and exact decision receipt, advances an executable case through the normal state machine,
     * and records or replays the low-sensitive trigger result. Returning successfully tells the DLT handler that
     * the record is now represented by durable control-plane state and may be committed.</p>
     *
     * @param eventId immutable identifier copied from the original Kafka trigger
     * @param request body containing only the original current execution ID
     * @param internalToken configured Agent Runtime service credential
     * @param traceId optional cross-service trace identifier
     * @return durable trigger-result view after DLT convergence
     */
    @PostMapping("/triggers/{eventId}/dead-letter")
    public PlatformApiResponse<SyncAutopilotRecoveryTriggerConsumerResultView> recordTriggerDeadLetter(
            @PathVariable String eventId,
            @RequestBody SyncAutopilotRecoveryDeadLetterRequest request,
            @RequestHeader(value = PlatformContextHeaders.INTERNAL_SERVICE_TOKEN, required = false)
            String internalToken,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        verifyInternalServiceToken(internalToken);
        if (eventId == null || eventId.isBlank() || eventId.trim().length() > 96
                || request == null || request.currentExecutionId() == null
                || request.currentExecutionId() <= 0) {
            throw new PlatformBusinessException(
                    PlatformErrorCode.BAD_REQUEST,
                    "Autopilot dead-letter trigger request is incomplete");
        }
        SyncAutopilotRecoveryTriggerConsumerResultView view = deadLetterService.recordDeadLettered(
                eventId.trim(), request.currentExecutionId());
        return PlatformApiResponse.success(view, traceId);
    }

    /**
     * Verifies the credential on the internal service-to-service boundary without exposing credential data.
     *
     * <p>The supplied header is compared with {@code DATASMART_AGENT_RUNTIME_INTERNAL_SERVICE_TOKEN} using
     * {@link MessageDigest#isEqual(byte[], byte[])}, avoiding an early-exit comparison that leaks timing detail.
     * A missing or blank configured token is itself an authentication failure. This prevents a
     * partially configured deployment from silently converting an internal write API into an
     * unauthenticated endpoint. The check reads configuration only, writes no state, is idempotent
     * for unchanged input, and never logs or returns either token.</p>
     *
     * @param suppliedToken token from the internal request header; null is treated as empty when configured
     * @throws PlatformBusinessException with {@code FORBIDDEN} when configured authentication fails
     */
    private void verifyInternalServiceToken(String suppliedToken) {
        String expectedToken = internalServiceToken == null ? "" : internalServiceToken.trim();
        if (expectedToken.isEmpty()) {
            throw new PlatformBusinessException(PlatformErrorCode.FORBIDDEN,
                    "Autopilot recovery internal service authentication is not configured");
        }
        byte[] expected = expectedToken.getBytes(StandardCharsets.UTF_8);
        byte[] actual = suppliedToken == null
                ? new byte[0]
                : suppliedToken.trim().getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, actual)) {
            throw new PlatformBusinessException(PlatformErrorCode.FORBIDDEN,
                    "Autopilot recovery internal service authentication failed");
        }
    }

    /**
     * Rejects a decision DTO that lacks facts needed to construct a governed persistence command.
     *
     * <p>This shallow boundary check confirms identity, deadline, and idempotency receipt presence only. It does
     * not grant authorization or duplicate enum/policy/scope validation owned by later layers. The method is
     * pure and repeatable, so malformed input produces {@code BAD_REQUEST} before any persistence starts rather
     * than an ambiguous null-pointer failure.</p>
     *
     * @param request deserialized internal decision DTO to inspect
     * @throws PlatformBusinessException when required identity, deadline, or receipt fields are absent
     */
    private void requireDecisionRequest(SyncAutopilotRecoveryDecisionRequest request) {
        if (request == null || request.deadlineAt() == null || request.tenantId() == null
                || request.syncTaskId() == null || request.rootExecutionId() == null
                || request.currentExecutionId() == null || request.receiptId() == null
                || request.receiptId().isBlank()) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "Autopilot recovery decision request is incomplete");
        }
    }

    /**
     * Rejects an incomplete consumer-result callback before it can reach the durable outbox service.
     *
     * <p>This method checks only transport shape and numeric identities. It does not decide whether an event
     * exists, whether a duplicate is equal, or whether a result is permitted to replace another result; those
     * checks require the database row and remain in the service. Keeping this validation at the HTTP boundary
     * gives callers a stable BAD_REQUEST for malformed JSON rather than a later mapper error.</p>
     *
     * @param eventId path value expected to identify one bounded outbox row
     * @param request deserialized low-sensitive callback body
     * @throws PlatformBusinessException when required fields are blank, absent, oversized, or nonpositive
     */
    private void requireConsumerResultRequest(
            String eventId,
            SyncAutopilotRecoveryTriggerConsumerResultRequest request) {
        if (eventId == null || eventId.isBlank() || eventId.trim().length() > 96
                || request == null || request.status() == null || request.status().isBlank()
                || request.reasonCode() == null || request.reasonCode().isBlank()
                || request.currentExecutionId() == null || request.currentExecutionId() <= 0
                || (request.caseId() != null && request.caseId() <= 0)
                || (request.retrievalEvidenceCount() != null && request.retrievalEvidenceCount() < 0)) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "Autopilot trigger consumer result request is incomplete");
        }
    }

    /**
     * Converts a protocol enum string into a known server-side enum without accepting arbitrary values.
     *
     * <p>Hyphens and case differences are normalized only to make documented names convenient for the internal
     * caller; the final {@link Enum#valueOf(Class, String)} lookup remains a strict whitelist. The helper is
     * pure and idempotent, has no lifecycle effect, and maps malformed text to {@code BAD_REQUEST} rather than
     * exposing a Java exception or accepting an untyped state/action value.</p>
     *
     * @param enumType trusted enum class selected by this controller
     * @param value wire-format enum value supplied by the internal caller
     * @param fieldName field name included in the safe validation error
     * @param <T> concrete server-controlled enum type
     * @return validated enum constant
     * @throws PlatformBusinessException when the value is missing or unsupported
     */
    private <T extends Enum<T>> T enumValue(Class<T> enumType, String value, String fieldName) {
        try {
            return Enum.valueOf(enumType, value == null
                    ? ""
                    : value.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (RuntimeException exception) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "Unsupported Autopilot recovery " + fieldName);
        }
    }

    /**
     * Normalizes and validates a compact reason-code token without allowing arbitrary prose.
     *
     * <p>Consumer integrations may add new stable reason codes without requiring data-sync to persist a model
     * explanation. The accepted grammar is deliberately narrower than free text: uppercase letters, digits, and
     * underscores only, at most 96 characters. Hyphen/case normalization mirrors status handling while still
     * rejecting whitespace, JSON fragments, SQL, prompts, and exception bodies.</p>
     *
     * @param value wire-format reason code supplied by the internal consumer
     * @param fieldName safe field label included in a validation error
     * @return normalized short enum-like code suitable for the database constraint and digest
     * @throws PlatformBusinessException when the value is absent or not a compact enum-like token
     */
    private String shortEnumText(String value, String fieldName) {
        String normalized = value == null
                ? ""
                : value.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        if (!normalized.matches("[A-Z][A-Z0-9_]{0,95}")) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "Unsupported Autopilot recovery " + fieldName);
        }
        return normalized;
    }

    /**
     * Normalizes an optional compact retrieval code without inventing a decision before planning completed.
     *
     * <p>Null is meaningful for malformed JSON or authorization rejection, which can happen before a model turn.
     * A non-null value uses the same bounded grammar as other callback codes. The service later enforces the
     * SEARCH/SKIP relationship with evidence count and digest.</p>
     */
    private String optionalShortEnumText(String value, String fieldName) {
        return value == null ? null : shortEnumText(value, fieldName);
    }

    /**
     * Accepts only the public {@code sha256:} evidence-digest form and normalizes hexadecimal case.
     *
     * <p>The digest is an integrity pointer, not evidence text. Null is retained for SKIP or pre-planning
     * rejection; any non-null malformed value fails at the HTTP boundary before persistence.</p>
     */
    private String normalizedEvidenceDigest(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("sha256:[0-9a-f]{64}")) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "Unsupported Autopilot recovery retrievalEvidenceDigest");
        }
        return normalized;
    }
}
