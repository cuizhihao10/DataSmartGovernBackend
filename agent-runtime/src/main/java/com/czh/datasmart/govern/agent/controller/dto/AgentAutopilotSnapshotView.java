/**
 * @Author : Cui
 * @Date: 2026/08/11 00:00
 * @Description DataSmart Govern Backend - AgentAutopilotSnapshotView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Public, low-sensitive projection of a durable AUTOPILOT authorization snapshot.
 *
 * <p>Input comes from the server-owned {@code autopilotAuthorization} value persisted in an Agent Run.
 * Output deliberately contains only the policy identifier, root Run locator, lifecycle state, recovery limits,
 * action boundaries, and issue/expiry times needed by the browser. It never exposes the durable authorization's
 * tenant/application/user/delegation fields, policy digest, tool arguments, prompt, SQL, token accounting, or
 * execution log body. Constructing this view has no persistence, authorization, or downstream execution side
 * effect; it is a response-boundary projection only.</p>
 */
public record AgentAutopilotSnapshotView(
        String policyId,
        String policyVersion,
        String executionMode,
        String state,
        String rootSessionId,
        String rootRunId,
        int maxRecoveryCycles,
        int maxTotalDurationMinutes,
        String maxAutomaticRiskLevel,
        List<String> allowedRecoveryActions,
        List<String> requireApprovalFor,
        String issuedAt,
        String expiresAt) {

    /**
     * Converts the durable authorization map into the browser-safe confirmation-response contract.
     *
     * <p>The map is trusted only after it has been read from the current Run through the session store. This
     * method still validates the fields required for a coherent public snapshot so a malformed database value
     * cannot be silently displayed as an active authorization. The result is immutable and is safe to persist
     * inside an idempotency receipt because it has no payload body or secret-bearing scope fields.</p>
     *
     * @param durableAuthorization server-owned JSONB-compatible authorization fields from one Agent Run
     * @return the restricted response DTO representing that durable authorization
     * @throws IllegalArgumentException when the stored value is absent, malformed, or not an AUTOPILOT grant
     */
    public static AgentAutopilotSnapshotView fromDurableAuthorization(Map<String, Object> durableAuthorization) {
        if (durableAuthorization == null || durableAuthorization.isEmpty()) {
            throw new IllegalArgumentException("Durable AUTOPILOT authorization is required");
        }
        String executionMode = requiredCode(durableAuthorization, "executionMode");
        if (!"AUTOPILOT".equals(executionMode)) {
            throw new IllegalArgumentException("Durable authorization is not an AUTOPILOT policy");
        }
        return new AgentAutopilotSnapshotView(
                requiredText(durableAuthorization, "policyId"),
                requiredText(durableAuthorization, "policyVersion"),
                executionMode,
                requiredCode(durableAuthorization, "state"),
                requiredText(durableAuthorization, "rootSessionId"),
                requiredText(durableAuthorization, "rootRunId"),
                boundedInteger(durableAuthorization, "maxRecoveryCycles", 1, 10),
                boundedInteger(durableAuthorization, "maxTotalDurationMinutes", 5, 1440),
                requiredCode(durableAuthorization, "maxAutomaticRiskLevel"),
                requiredCodes(durableAuthorization, "allowedRecoveryActions"),
                requiredCodes(durableAuthorization, "requireApprovalFor"),
                requiredText(durableAuthorization, "issuedAt"),
                requiredText(durableAuthorization, "expiresAt")
        );
    }

    /** Reads a required short text field without applying policy semantics beyond basic presence validation. */
    private static String requiredText(Map<String, Object> source, String fieldName) {
        Object value = source.get(fieldName);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException("Durable AUTOPILOT authorization is missing " + fieldName);
        }
        return value.toString().trim();
    }

    /** Reads a durable enum-like field as its canonical upper-case code for stable browser rendering. */
    private static String requiredCode(Map<String, Object> source, String fieldName) {
        return requiredText(source, fieldName).toUpperCase(Locale.ROOT);
    }

    /** Reads one persistence-compatible integer and rejects values outside the original authorization boundary. */
    private static int boundedInteger(Map<String, Object> source, String fieldName, int min, int max) {
        Object value = source.get(fieldName);
        int parsed;
        try {
            parsed = value instanceof Number number
                    ? number.intValue()
                    : Integer.parseInt(requiredText(source, fieldName));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Durable AUTOPILOT authorization has an invalid " + fieldName,
                    exception);
        }
        if (parsed < min || parsed > max) {
            throw new IllegalArgumentException("Durable AUTOPILOT authorization has an out-of-range " + fieldName);
        }
        return parsed;
    }

    /**
     * Copies one durable action list into a stable public list.
     *
     * <p>The authorization service owns allowlist validation. This response mapper only rejects a missing or
     * blank persisted action so a damaged snapshot cannot make the UI imply a broader automatic permission.</p>
     */
    private static List<String> requiredCodes(Map<String, Object> source, String fieldName) {
        Object value = source.get(fieldName);
        if (!(value instanceof List<?> values)) {
            throw new IllegalArgumentException("Durable AUTOPILOT authorization is missing " + fieldName);
        }
        return values.stream()
                .map(item -> item == null ? "" : item.toString().trim().toUpperCase(Locale.ROOT))
                .peek(item -> {
                    if (item.isBlank()) {
                        throw new IllegalArgumentException(
                                "Durable AUTOPILOT authorization has a blank " + fieldName + " entry");
                    }
                })
                .toList();
    }
}
