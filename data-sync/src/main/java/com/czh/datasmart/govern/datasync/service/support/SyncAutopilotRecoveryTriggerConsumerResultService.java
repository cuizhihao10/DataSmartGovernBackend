/**
 * @Author : Cui
 * @Date: 2026/08/11 21:30
 * @Description DataSmart Govern Backend - SyncAutopilotRecoveryTriggerConsumerResultService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import com.czh.datasmart.govern.datasync.entity.SyncAutopilotRecoveryTriggerOutbox;
import com.czh.datasmart.govern.datasync.mapper.SyncAutopilotRecoveryTriggerOutboxMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/**
 * Persists durable, idempotent, low-sensitive results from the Autopilot Kafka trigger consumer.
 *
 * <p>Kafka delivery is at least once, so a consumer can complete its work and then repeat the HTTP callback after
 * a timeout. This service binds a result to the preexisting outbox row using {@code eventId + currentExecutionId},
 * computes a server-side SHA-256 digest, and uses one conditional update for the first write. A duplicate is
 * replayed only when its complete facts match that digest; a different fact set reusing the same event ID is
 * rejected rather than overwriting audit history.</p>
 *
 * <p>The stored payload is intentionally narrow: finite status, compact reason code, optional case ID, digest,
 * and first consumption time. The service never accepts or stores a Python response, model explanation, raw
 * exception, evidence document, SQL, credentials, or the Kafka event body.</p>
 */
@Service
@RequiredArgsConstructor
public class SyncAutopilotRecoveryTriggerConsumerResultService {

    private static final int MAX_EVENT_ID_LENGTH = 96;
    private static final String SHORT_ENUM_TEXT_PATTERN = "[A-Z][A-Z0-9_]{0,95}";

    private final SyncAutopilotRecoveryTriggerOutboxMapper outboxMapper;

    /**
     * Records one consumer outcome or replays the original outcome for an equivalent callback.
     *
     * <p>The method first loads the outbox by both its immutable event ID and original execution ID. This avoids
     * treating an arbitrary event ID as authority and ensures the result remains attached to the event actually
     * produced by data-sync. It then calculates the digest from normalized, low-sensitive facts; callers cannot
     * supply a digest or decide which existing row to replace.</p>
     *
     * <p>When the row has no result, a conditional SQL update writes all five result columns together. Concurrent
     * callbacks race on that update, then both reload the row. An identical stored digest becomes an idempotent
     * replay, while a differing digest, incomplete stored fact set, missing original outbox, or mismatched
     * execution fails closed with a stable business-state conflict. The transaction makes the accepted write and
     * returned durable view one coherent service operation.</p>
     *
     * @param eventId immutable trigger identity from the consumer event
     * @param command validated low-sensitive consumer facts
     * @return first stored or idempotently replayed low-sensitive result view
     * @throws PlatformBusinessException when identity, command, persistence facts, or idempotency checks fail
     */
    @Transactional
    public SyncAutopilotRecoveryTriggerConsumerResultView recordConsumerResult(
            String eventId,
            SyncAutopilotRecoveryTriggerConsumerResultCommand command) {
        requireEventId(eventId);
        requireCommand(command);

        SyncAutopilotRecoveryTriggerOutbox original = loadOriginalOutbox(
                eventId, command.currentExecutionId());
        String digest = resultDigest(eventId, command);
        if (!hasConsumerResult(original)) {
            outboxMapper.markConsumerResultIfAbsent(
                    eventId,
                    command.currentExecutionId(),
                    digest,
                    command.status().name(),
                    command.reasonCode(),
                    command.caseId(),
                    command.retrievalDecision(),
                    command.retrievalStrategy(),
                    command.retrievalEvidenceCount(),
                    command.retrievalEvidenceDigest());
        }

        // The reload resolves a concurrent conditional-update race and returns the durable timestamp.
        SyncAutopilotRecoveryTriggerOutbox persisted = loadOriginalOutbox(
                eventId, command.currentExecutionId());
        return replayOrRejectDifferentFacts(persisted, command, digest);
    }

    /**
     * Creates the canonical server-side digest for the only callback facts that are allowed to persist.
     *
     * <p>Each field includes its name and length so adjacent values cannot change the binding through delimiter
     * ambiguity. This is a package-visible helper solely to make the deterministic idempotency contract directly
     * testable; HTTP clients still cannot send a digest. The method is pure, has no database or logging side
     * effect, and never receives model prose because {@link #requireCommand} rejects it before this point.</p>
     *
     * @param eventId known durable trigger identity
     * @param command validated compact consumer facts
     * @return lowercase SHA-256 digest calculated by data-sync
     */
    String resultDigest(String eventId, SyncAutopilotRecoveryTriggerConsumerResultCommand command) {
        return SyncAutopilotDigestSupport.sha256(String.join("|",
                "schemaVersion=2",
                canonicalField("eventId", eventId),
                canonicalField("currentExecutionId", String.valueOf(command.currentExecutionId())),
                canonicalField("status", command.status().name()),
                canonicalField("reasonCode", command.reasonCode()),
                canonicalField("caseId", command.caseId() == null ? "<none>" : String.valueOf(command.caseId())),
                canonicalField("retrievalDecision", nullable(command.retrievalDecision())),
                canonicalField("retrievalStrategy", nullable(command.retrievalStrategy())),
                canonicalField("retrievalEvidenceCount", command.retrievalEvidenceCount() == null
                        ? "<none>" : String.valueOf(command.retrievalEvidenceCount())),
                canonicalField("retrievalEvidenceDigest", nullable(command.retrievalEvidenceDigest()))));
    }

    /**
     * Loads the original trigger only when both callback identity facts match the durable outbox.
     *
     * <p>Using the pair is important because a later recovery cycle can have a different current execution even
     * when the task lineage is related. A null result is deliberately reported as one generic business conflict:
     * the API does not reveal whether an event ID exists for some other execution or tenant.</p>
     *
     * @param eventId callback event identifier
     * @param currentExecutionId callback execution identifier
     * @return matching durable outbox row
     * @throws PlatformBusinessException when no original outbox matches both values
     */
    private SyncAutopilotRecoveryTriggerOutbox loadOriginalOutbox(
            String eventId,
            Long currentExecutionId) {
        SyncAutopilotRecoveryTriggerOutbox outbox =
                outboxMapper.selectByEventIdAndCurrentExecutionId(eventId, currentExecutionId);
        if (outbox == null) {
            throw conflict("Autopilot trigger consumer result does not match a durable outbox");
        }
        return outbox;
    }

    /**
     * Returns the original view for an exact digest match and rejects all other stored states.
     *
     * <p>This method is the fail-closed half of idempotency. A matching digest alone is not enough when a database
     * row is incomplete or corrupted, so every compact field and the consumed timestamp are checked as well.
     * Different status, reason, case, or execution facts cannot reuse an event ID to revise history. The method
     * performs no write; its only effect is to construct a restricted response from an already durable row.</p>
     *
     * @param outbox reloaded original row after the conditional update attempt
     * @param command callback facts that must equal the first accepted result
     * @param digest server-calculated digest for the callback facts
     * @return the first persisted low-sensitive result view
     * @throws PlatformBusinessException when no complete equal result is present
     */
    private SyncAutopilotRecoveryTriggerConsumerResultView replayOrRejectDifferentFacts(
            SyncAutopilotRecoveryTriggerOutbox outbox,
            SyncAutopilotRecoveryTriggerConsumerResultCommand command,
            String digest) {
        if (isSameConsumerResult(outbox, command, digest)) {
            return toView(outbox);
        }
        throw conflict("Autopilot trigger consumer result conflicts with the first accepted facts");
    }

    /**
     * Detects any partially or fully written consumer-result state before a callback attempts an overwrite.
     *
     * <p>The V22 database constraint normally makes result fields all-null or all-present. This defensive check
     * treats a partially populated row as existing data rather than filling its missing pieces from a callback.
     * That choice sacrifices availability for integrity, which is appropriate for a durable recovery audit
     * boundary and ensures unexpected database corruption never becomes an implicit client-controlled repair.</p>
     *
     * @param outbox durable trigger row to inspect
     * @return {@code true} when any result column is already populated
     */
    private boolean hasConsumerResult(SyncAutopilotRecoveryTriggerOutbox outbox) {
        return outbox.getConsumerResultDigest() != null
                || outbox.getConsumerResultStatus() != null
                || outbox.getConsumerResultReasonCode() != null
                || outbox.getConsumerResultCaseId() != null
                || outbox.getRetrievalDecision() != null
                || outbox.getRetrievalStrategy() != null
                || outbox.getRetrievalEvidenceCount() != null
                || outbox.getRetrievalEvidenceDigest() != null
                || outbox.getConsumedAt() != null;
    }

    /**
     * Compares every durable callback fact with a prospective callback after digest calculation.
     *
     * <p>The digest is the compact idempotency proof, while the field-by-field checks protect against an
     * incomplete/corrupt row and make the persisted representation self-consistent. This is a pure comparison:
     * it has no mapper call, no clock access, and does not mutate the outbox. A null case ID is a valid explicit
     * fact because a permanent trigger rejection can occur before data-sync creates a recovery case.</p>
     *
     * @param outbox persisted outbox with a possible prior consumer result
     * @param command incoming compact consumer facts
     * @param digest server-computed digest for the incoming facts
     * @return {@code true} only for a fully equal first result
     */
    private boolean isSameConsumerResult(
            SyncAutopilotRecoveryTriggerOutbox outbox,
            SyncAutopilotRecoveryTriggerConsumerResultCommand command,
            String digest) {
        return digest.equals(outbox.getConsumerResultDigest())
                && command.status().name().equals(outbox.getConsumerResultStatus())
                && command.reasonCode().equals(outbox.getConsumerResultReasonCode())
                && Objects.equals(command.caseId(), outbox.getConsumerResultCaseId())
                && Objects.equals(command.currentExecutionId(), outbox.getCurrentExecutionId())
                && Objects.equals(command.retrievalDecision(), outbox.getRetrievalDecision())
                && Objects.equals(command.retrievalStrategy(), outbox.getRetrievalStrategy())
                && Objects.equals(command.retrievalEvidenceCount(), outbox.getRetrievalEvidenceCount())
                && Objects.equals(command.retrievalEvidenceDigest(), outbox.getRetrievalEvidenceDigest())
                && outbox.getConsumedAt() != null;
    }

    /**
     * Projects a durable outbox row into the intentionally restricted callback response.
     *
     * <p>This conversion copies only identifiers, short codes, digest, and timing values. In particular it never
     * reads or returns {@code payloadJson}, authorization fields embedded in the original event, dispatch errors,
     * or any information that could expose the consumer's model reasoning. It is deterministic and has no side
     * effect, so a replay returns the same business facts as the first successful callback.</p>
     *
     * @param outbox fully populated durable result row
     * @return low-sensitive response view
     */
    private SyncAutopilotRecoveryTriggerConsumerResultView toView(
            SyncAutopilotRecoveryTriggerOutbox outbox) {
        return new SyncAutopilotRecoveryTriggerConsumerResultView(
                outbox.getEventId(),
                outbox.getCurrentExecutionId(),
                outbox.getConsumerResultStatus(),
                outbox.getConsumerResultReasonCode(),
                outbox.getConsumerResultCaseId(),
                outbox.getConsumerResultDigest(),
                outbox.getRetrievalDecision(),
                outbox.getRetrievalStrategy(),
                outbox.getRetrievalEvidenceCount(),
                outbox.getRetrievalEvidenceDigest(),
                outbox.getConsumedAt());
    }

    /**
     * Validates the path event ID before it becomes a database lookup value or digest component.
     *
     * <p>The outbox migration gives this identifier a 96-character bound. The service intentionally does not
     * invent a looser fallback ID for blank input, because an unbound callback must never create or update durable
     * recovery data. The method is pure and provides a stable BAD_REQUEST before mapper interaction.</p>
     *
     * @param eventId trigger identifier supplied by the internal route
     * @throws PlatformBusinessException when the identifier is blank or exceeds the outbox schema bound
     */
    private void requireEventId(String eventId) {
        if (eventId == null || eventId.isBlank() || eventId.length() > MAX_EVENT_ID_LENGTH) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "Autopilot trigger event ID is invalid");
        }
    }

    /**
     * Validates the compact consumer facts even when the service is called without the HTTP controller.
     *
     * <p>Status must come from the server-owned enum, execution and optional case IDs must be positive, and the
     * reason code must already be the uppercase short-token form. Revalidating here protects future internal
     * integrations and prevents a direct Java caller from persisting free-form model text despite the controller's
     * transport validation. This method is pure and performs no partial write on invalid input.</p>
     *
     * @param command candidate durable callback facts
     * @throws PlatformBusinessException when required facts are absent or unsafe for the result schema
     */
    private void requireCommand(SyncAutopilotRecoveryTriggerConsumerResultCommand command) {
        if (command == null || command.status() == null || command.reasonCode() == null
                || !command.reasonCode().matches(SHORT_ENUM_TEXT_PATTERN)
                || command.currentExecutionId() == null || command.currentExecutionId() <= 0
                || (command.caseId() != null && command.caseId() <= 0)
                || !validRetrievalProjection(command)) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "Autopilot trigger consumer result facts are invalid");
        }
    }

    /**
     * Validates the compact SEARCH/SKIP proof without accepting RAG text or arbitrary model metadata.
     *
     * <p>All-null is allowed only for outcomes created before planning, such as malformed JSON or authorization
     * rejection. SEARCH requires a positive bounded count plus a SHA-256 evidence-ID digest. SKIP requires an
     * explicit zero and no digest, proving the model made a choice without fabricating grounded citations.</p>
     */
    private boolean validRetrievalProjection(SyncAutopilotRecoveryTriggerConsumerResultCommand command) {
        boolean allAbsent = command.retrievalDecision() == null
                && command.retrievalStrategy() == null
                && command.retrievalEvidenceCount() == null
                && command.retrievalEvidenceDigest() == null;
        if (allAbsent) {
            return true;
        }
        if (command.retrievalDecision() == null
                || command.retrievalStrategy() == null
                || !command.retrievalStrategy().matches(SHORT_ENUM_TEXT_PATTERN)
                || command.retrievalEvidenceCount() == null
                || command.retrievalEvidenceCount() < 0
                || command.retrievalEvidenceCount() > 1000) {
            return false;
        }
        if ("SEARCH".equals(command.retrievalDecision())) {
            return command.retrievalEvidenceCount() > 0
                    && command.retrievalEvidenceDigest() != null
                    && command.retrievalEvidenceDigest().matches("sha256:[0-9a-f]{64}");
        }
        return "SKIP".equals(command.retrievalDecision())
                && command.retrievalEvidenceCount() == 0
                && command.retrievalEvidenceDigest() == null;
    }

    /** Converts an optional compact digest field into unambiguous canonical digest material. */
    private String nullable(String value) {
        return value == null ? "<none>" : value;
    }

    /**
     * Encodes one digest field with its name and length to preserve an unambiguous canonical representation.
     *
     * <p>The helper is intentionally local to digest construction. All inputs at this point are bounded,
     * low-sensitive identifiers or enum-like codes, and the length prefix makes delimiter characters in an event
     * ID unable to make two different field sequences hash as the same text. It has no I/O or persistence side
     * effect and does not sanitize arbitrary caller prose.</p>
     *
     * @param name fixed server-controlled field name
     * @param value non-null canonical field value
     * @return deterministic name/length/value encoding
     */
    private String canonicalField(String name, String value) {
        return name + "=" + value.length() + ":" + value;
    }

    /**
     * Creates a low-sensitive state-conflict exception without echoing callback values or outbox contents.
     *
     * <p>The same generic conflict type covers unknown event/execution pairs, malformed persisted results, and
     * changed facts for an existing event. This avoids revealing whether another tenant or execution owns a
     * matching event while still telling the authenticated caller that retrying with different facts is unsafe.</p>
     *
     * @param message stable non-sensitive explanation for the platform error envelope
     * @return a new business-state conflict exception
     */
    private PlatformBusinessException conflict(String message) {
        return new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT, message);
    }
}
