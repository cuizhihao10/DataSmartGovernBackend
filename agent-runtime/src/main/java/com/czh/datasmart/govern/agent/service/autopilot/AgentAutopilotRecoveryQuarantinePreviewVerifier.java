/**
 * @Author : Cui
 * @Date: 2026/08/12
 * @Description DataSmart Govern Backend - AgentAutopilotRecoveryQuarantinePreviewVerifier.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.autopilot;

import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Independently validates a model-provided low-risk quarantine preview before it reaches data-sync.
 *
 * <p>Python may propose the action, but this service binds every usable preview fact to the already verified Kafka
 * trigger and recomputes the action fingerprint from trusted identifiers plus the exact selected IDs. It never calls
 * data-sync, reads a database, or grants authorization. A validation failure is deterministic for this immutable
 * planner response, so callers can record a governed rejection instead of treating malformed model output as an
 * execution success.</p>
 */
@Service
public class AgentAutopilotRecoveryQuarantinePreviewVerifier {

    private static final String APPLY_QUARANTINE = "APPLY_QUARANTINE";
    private static final String PREVIEWED = "PREVIEWED";
    private static final String OUTPUT_REF_PREFIX = "agent-runtime://";
    private static final int MAX_SELECTED_SAMPLE_IDS = 500;

    /**
     * Validates and freezes the exact preview needed for one autonomous quarantine apply request.
     *
     * <p>The method requires task and execution scope to match the trusted trigger, a completed preview state, an
     * empty issue list, matched selected/eligible counts in the bounded range, unique positive sample IDs, a
     * lowercase SHA-256 confirmation digest, and an Agent Runtime output reference. It then sorts IDs numerically
     * only for fingerprint material and compares the resulting SHA-256 digest with the model's repair fingerprint.
     * The original selected-ID order is preserved in the returned value so data-sync can revalidate the preview it
     * actually issued.</p>
     *
     * <p>No authorization decision is made here. This is a pure Java validation boundary that turns any malformed
     * or cross-scope preview into a stable low-sensitive business conflict before an HTTP side effect can begin.</p>
     *
     * @param trigger Java-verified recovery trigger that supplies the authoritative task, execution, and event facts
     * @param response planner candidate expected to propose {@code APPLY_QUARANTINE}
     * @return immutable preview containing only the validated digest, selected IDs, and output reference
     * @throws PlatformBusinessException when any preview field or the action fingerprint is invalid
     */
    public AgentAutopilotRecoveryQuarantinePreview verify(
            AgentAutopilotVerifiedRecoveryTrigger trigger,
            AgentAutopilotRecoveryPlanResponse response) {
        if (trigger == null || response == null || !APPLY_QUARANTINE.equals(code(response.action()))) {
            throw conflict("AUTOPILOT_QUARANTINE_CANDIDATE_INVALID");
        }
        Map<String, Object> preview = response.quarantinePreview();
        if (!sameLong(preview.get("taskId"), trigger.event().syncTaskId())
                || !sameLong(preview.get("executionId"), trigger.event().currentExecutionId())) {
            throw new PlatformBusinessException(PlatformErrorCode.FORBIDDEN,
                    "AUTOPILOT_QUARANTINE_PREVIEW_SCOPE_MISMATCH");
        }
        int selectedCount = boundedCount(preview.get("selectedCount"));
        int eligibleCount = boundedCount(preview.get("eligibleCount"));
        if (selectedCount != eligibleCount) {
            throw conflict("AUTOPILOT_QUARANTINE_PREVIEW_COUNT_MISMATCH");
        }
        if (!(preview.get("issueCodes") instanceof Collection<?> issueCodes) || !issueCodes.isEmpty()) {
            throw conflict("AUTOPILOT_QUARANTINE_PREVIEW_ISSUES_PRESENT");
        }
        String confirmationDigest = text(preview.get("confirmationDigest"));
        if (!confirmationDigest.matches("[0-9a-f]{64}")) {
            throw conflict("AUTOPILOT_QUARANTINE_PREVIEW_DIGEST_INVALID");
        }
        List<Long> selectedSampleIds = selectedSampleIds(preview.get("selectedSampleIds"), selectedCount);
        String outputRef = text(preview.get("outputRef"));
        if (!outputRef.startsWith(OUTPUT_REF_PREFIX)) {
            throw conflict("AUTOPILOT_QUARANTINE_PREVIEW_OUTPUT_REF_INVALID");
        }
        String expectedFingerprint = actionFingerprint(trigger, confirmationDigest, selectedSampleIds);
        if (!constantEquals(expectedFingerprint, response.repairFingerprint())) {
            throw conflict("AUTOPILOT_QUARANTINE_ACTION_FINGERPRINT_MISMATCH");
        }
        return new AgentAutopilotRecoveryQuarantinePreview(confirmationDigest, selectedSampleIds, outputRef);
    }

    /**
     * Converts one preview count into a bounded Java integer.
     *
     * <p>The preview protocol accepts only decimal integer text or JSON integer values. Fractions, signs, overflow,
     * zero, and values above the action ceiling fail closed instead of being rounded or truncated. This helper is
     * pure and does not mutate the model map.</p>
     *
     * @param value untrusted selected or eligible count
     * @return count within one through 500 inclusive
     * @throws PlatformBusinessException when the count is missing, nonintegral, or outside the allowed range
     */
    private int boundedCount(Object value) {
        try {
            long count = Long.parseLong(text(value));
            if (count < 1 || count > MAX_SELECTED_SAMPLE_IDS) {
                throw new NumberFormatException("count outside quarantine preview bounds");
            }
            return Math.toIntExact(count);
        } catch (RuntimeException exception) {
            throw conflict("AUTOPILOT_QUARANTINE_PREVIEW_COUNT_INVALID");
        }
    }

    /**
     * Parses exactly the declared number of unique positive sample identifiers.
     *
     * <p>The list must be a JSON collection with no null, fractional, duplicate, zero, or negative values. The
     * returned order remains the preview's order, while the fingerprint method independently creates an ascending
     * numeric view. Keeping those two concerns separate prevents a local sort from accidentally changing the preview
     * that data-sync is expected to revalidate.</p>
     *
     * @param value untrusted selected-sample collection from the planner preview
     * @param expectedCount selected and eligible count already validated as equal
     * @return immutable list of original-order positive IDs
     * @throws PlatformBusinessException when the collection shape, size, or IDs are invalid
     */
    private List<Long> selectedSampleIds(Object value, int expectedCount) {
        if (!(value instanceof Collection<?> rawIds) || rawIds.size() != expectedCount) {
            throw conflict("AUTOPILOT_QUARANTINE_PREVIEW_SELECTED_IDS_INVALID");
        }
        List<Long> ids = new ArrayList<>(rawIds.size());
        Set<Long> uniqueIds = new HashSet<>();
        for (Object rawId : rawIds) {
            try {
                long id = Long.parseLong(text(rawId));
                if (id <= 0L || !uniqueIds.add(id)) {
                    throw new NumberFormatException("invalid selected sample ID");
                }
                ids.add(id);
            } catch (RuntimeException exception) {
                throw conflict("AUTOPILOT_QUARANTINE_PREVIEW_SELECTED_IDS_INVALID");
            }
        }
        return List.copyOf(ids);
    }

    /**
     * Recomputes the canonical action fingerprint without trusting planner ordering.
     *
     * <p>The material is exactly {@code eventId|errorFingerprint|currentExecutionId|APPLY_QUARANTINE|
     * confirmationDigest|ascending-comma-separated-ids}. The event facts come from the verified trigger, and the IDs
     * are numerically sorted only for the digest. SHA-256 is deterministic and does not encrypt or authorize data.
     * A missing JDK algorithm is a technical runtime failure, not a business approval.</p>
     *
     * @param trigger trusted recovery trigger
     * @param confirmationDigest validated lowercase preview digest
     * @param selectedSampleIds validated original-order selected IDs
     * @return lowercase hexadecimal SHA-256 action fingerprint
     */
    private String actionFingerprint(
            AgentAutopilotVerifiedRecoveryTrigger trigger,
            String confirmationDigest,
            List<Long> selectedSampleIds) {
        String sortedIds = selectedSampleIds.stream()
                .sorted()
                .map(String::valueOf)
                .reduce((left, right) -> left + "," + right)
                .orElseThrow(() -> new IllegalStateException("validated quarantine preview has no selected IDs"));
        String material = String.join("|",
                trigger.event().eventId(),
                trigger.event().errorFingerprint(),
                String.valueOf(trigger.event().currentExecutionId()),
                APPLY_QUARANTINE,
                confirmationDigest,
                sortedIds);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK does not support SHA-256", exception);
        }
    }

    /**
     * Compares a trusted digest with an untrusted model value without an early-exit byte comparison.
     *
     * <p>The expected value is always lowercase hexadecimal. A null, uppercase, or otherwise malformed model value
     * cannot match it. This helper does not normalize either operand because normalization would weaken the required
     * canonical fingerprint representation.</p>
     *
     * @param expected Java-recomputed lowercase fingerprint
     * @param actual model-supplied repair fingerprint
     * @return whether the two UTF-8 values are byte-for-byte identical
     */
    private boolean constantEquals(String expected, String actual) {
        return actual != null && MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Compares an untrusted numeric preview field with a trusted positive identifier.
     *
     * <p>Only a decimal integer representation is accepted. The method has no authorization side effect; it merely
     * prevents an apply preview from being transplanted from another task or execution by changing number formatting.
     * Missing trusted IDs also fail closed.</p>
     *
     * @param value untrusted task or execution field
     * @param expected trusted trigger identifier
     * @return {@code true} only when the parsed values are equal
     */
    private boolean sameLong(Object value, Long expected) {
        try {
            return expected != null && expected > 0L && Long.parseLong(text(value)) == expected;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    /**
     * Returns a trimmed string view of an untrusted JSON value.
     *
     * <p>This normalization is used only for numeric parsing and prefix checks. It does not make missing fields valid,
     * alter the original map, or normalize the strict uppercase operation state and lowercase digest representations.</p>
     *
     * @param value untrusted preview value
     * @return trimmed string, or an empty string for null
     */
    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    /**
     * Normalizes the candidate action for a finite action comparison.
     *
     * <p>The action is not persisted or expanded here; this only lets the verifier recognize the documented spelling
     * used by the policy evaluator. Unknown values cannot enter this verifier as a quarantine action.</p>
     *
     * @param value planner action text
     * @return uppercase underscore-separated action code
     */
    private String code(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT).replace('-', '_');
    }

    /**
     * Creates a low-sensitive deterministic preview validation conflict.
     *
     * <p>The reason code deliberately contains no model payload, selected IDs, digest material, or downstream error.
     * The execution service converts it into a durable rejection before any quarantine apply request is attempted.</p>
     *
     * @param reasonCode stable validation reason
     * @return business conflict for the current immutable candidate
     */
    private PlatformBusinessException conflict(String reasonCode) {
        return new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT, reasonCode);
    }
}
