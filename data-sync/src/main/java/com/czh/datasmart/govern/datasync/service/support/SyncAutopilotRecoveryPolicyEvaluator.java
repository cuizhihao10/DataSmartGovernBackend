/**
 * @Author : Cui
 * @Date: 2026/08/11 00:10
 * @Description DataSmart Govern Backend - SyncAutopilotRecoveryPolicyEvaluator.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.datasync.support.SyncAutopilotExecutionMode;
import com.czh.datasmart.govern.datasync.support.SyncAutopilotRecoveryAction;
import com.czh.datasmart.govern.datasync.support.SyncAutopilotRecoveryCaseState;
import com.czh.datasmart.govern.datasync.support.SyncAutopilotRiskLevel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Evaluates task-local autopilot authorization without any transport or execution side effect.
 *
 * <p>The evaluator is deliberately deterministic: the same policy and low-sensitive facts always
 * produce the same state. It never calls a worker, Python runtime, HTTP endpoint, Kafka topic, or
 * mutable global execution policy. That separation lets callers persist an auditable decision
 * before a future integration decides how to consume AUTO_APPROVED cases.</p>
 */
@Component
public class SyncAutopilotRecoveryPolicyEvaluator {

    private static final Pattern SHA_256 = Pattern.compile("^[0-9a-fA-F]{64}$");
    private static final Pattern SAFE_AUTHORIZATION_ID = Pattern.compile("^[A-Za-z0-9._:-]{1,128}$");
    private static final Pattern SAFE_RECEIPT_ID = Pattern.compile("^[A-Za-z0-9._:-]{1,128}$");

    private final ObjectMapper objectMapper;

    /**
     * Creates the production evaluator with an isolated JSON mapper for policy parsing.
     *
     * <p>Construction wires parsing support only; it does not load a task, consult a remote service, mutate
     * state, or cache authorization. Each later evaluation receives the persisted policy text explicitly, which
     * keeps the authorization decision reproducible and scoped to the caller's task.</p>
     */
    public SyncAutopilotRecoveryPolicyEvaluator() {
        this(new ObjectMapper());
    }

    SyncAutopilotRecoveryPolicyEvaluator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Evaluates one task-local Autopilot policy against a low-sensitive recovery candidate.
     *
     * <p>The inputs are the persisted JSON authorization snapshot and a request containing IDs, enum values,
     * counters, timestamps, and fingerprints. The returned decision contains a case state, safe attention
     * reason when needed, and digests that bind the result to the policy without storing the policy body. The
     * evaluator performs no persistence, transport, model call, retry, or recovery execution; it is a pure
     * policy boundary except for using the current clock when {@code evaluatedAt} is absent.</p>
     *
     * <p>Its ordering is a safety policy: invalid scope/action/expiry becomes {@code REJECTED}; otherwise bad
     * evidence, expired budget, repeated error, unresolved risk, or insufficient confidence becomes
     * {@code ATTENTION_REQUIRED} before automatic recovery can be considered. Repeating equivalent input with
     * the same evaluation time yields the same decision. This method never treats policy JSON as permission to
     * run a worker; later case/receipt workflow retains state and execution authority.</p>
     *
     * @param autopilotPolicyJson persisted task-local authorization JSON, expected to be a bounded safe schema
     * @param request low-sensitive facts for the single proposed recovery decision
     * @return an immutable persistence-ready decision in an allowed, approval, rejection, or attention state
     */
    public SyncAutopilotRecoveryPolicyDecision evaluate(String autopilotPolicyJson,
                                                         SyncAutopilotRecoveryEvaluationRequest request) {
        LocalDateTime now = request == null || request.evaluatedAt() == null
                ? LocalDateTime.now(ZoneOffset.UTC)
                : request.evaluatedAt();
        String rawPolicyDigest = SyncAutopilotDigestSupport.sha256(autopilotPolicyJson);
        if (request == null) {
            return attention("INVALID_EVALUATION_REQUEST", rawPolicyDigest, rawPolicyDigest, 1,
                    now.plusSeconds(1));
        }

        ParsedPolicy policy;
        try {
            policy = parsePolicy(autopilotPolicyJson);
        } catch (RuntimeException exception) {
            return attention("INVALID_AUTOPILOT_POLICY", rawPolicyDigest, rawPolicyDigest, 1,
                    now.plusSeconds(1));
        }

        String authorizationDigest = SyncAutopilotDigestSupport.sha256(policy.authorizationId());
        String policyDigest = policyDigest(policy);
        LocalDateTime policyDeadlineAt = boundedPolicyDeadline(policy, now);
        LocalDateTime requestedDeadlineAt = request.deadlineAt();
        if (requestedDeadlineAt != null
                && isAfterUtcInstant(requestedDeadlineAt, policyDeadlineAt)) {
            // A caller may narrow the authorization window, but it must never be able to
            // extend the persisted authorization snapshot with a later timestamp.
            return rejected(authorizationDigest, policyDigest, policy.maxCycles(), policyDeadlineAt);
        }
        LocalDateTime deadlineAt = requestedDeadlineAt == null ? policyDeadlineAt : requestedDeadlineAt;

        if (request.executionMode() != SyncAutopilotExecutionMode.AUTOPILOT) {
            return rejected(authorizationDigest, policyDigest, policy.maxCycles(), deadlineAt);
        }
        boolean automaticallyAuthorized = policy.allowedActions().contains(request.action());
        boolean approvalAuthorized = policy.approvalActions().contains(request.action());
        if (!scopeMatches(policy, request) || (!automaticallyAuthorized && !approvalAuthorized)) {
            return rejected(authorizationDigest, policyDigest, policy.maxCycles(), deadlineAt);
        }
        if (!isAfterInstant(policy.expiresAt(), now)) {
            return rejected(authorizationDigest, policyDigest, policy.maxCycles(), deadlineAt);
        }
        if (policy.maxAutomaticRisk() != SyncAutopilotRiskLevel.LOW) {
            return attention("MAX_AUTOMATIC_RISK_MUST_BE_LOW", authorizationDigest, policyDigest,
                    policy.maxCycles(), deadlineAt);
        }
        if (!validFingerprint(request.repairFingerprint())) {
            return attention("MISSING_OR_INVALID_ACTION_FINGERPRINT", authorizationDigest, policyDigest,
                    policy.maxCycles(), deadlineAt);
        }
        if (!hasText(request.receiptId()) || !SAFE_RECEIPT_ID.matcher(request.receiptId().trim()).matches()) {
            return attention("MISSING_OR_INVALID_RECEIPT_ID", authorizationDigest, policyDigest,
                    policy.maxCycles(), deadlineAt);
        }
        if (!validFingerprint(request.lastErrorFingerprint())) {
            return attention("MISSING_OR_INVALID_ERROR_FINGERPRINT", authorizationDigest, policyDigest,
                    policy.maxCycles(), deadlineAt);
        }
        if (request.cycle() < 1 || request.cycle() > policy.maxCycles()) {
            return attention("AUTOPILOT_CYCLE_BUDGET_EXHAUSTED", authorizationDigest, policyDigest,
                    policy.maxCycles(), deadlineAt);
        }
        if (!isAfterUtcInstant(deadlineAt, now)) {
            return attention("AUTOPILOT_DEADLINE_EXCEEDED", authorizationDigest, policyDigest,
                    policy.maxCycles(), deadlineAt);
        }
        if (request.repeatedErrorCount() < 0 || request.repeatedErrorCount() >= policy.maxRepeatedErrorCount()) {
            return attention("REPEATED_ERROR_LIMIT_REACHED", authorizationDigest, policyDigest,
                    policy.maxCycles(), deadlineAt);
        }
        if (!request.evidenceAvailable()) {
            return attention("RECOVERY_EVIDENCE_MISSING", authorizationDigest, policyDigest,
                    policy.maxCycles(), deadlineAt);
        }
        if (request.action() == SyncAutopilotRecoveryAction.RETRY_EXECUTION
                && !request.automaticRetryFactsVerified()) {
            return attention("RECOVERY_AUTOMATIC_RETRY_FACTS_REQUIRED", authorizationDigest, policyDigest,
                    policy.maxCycles(), deadlineAt);
        }
        if (request.confidenceScore() < policy.minimumConfidence() || request.confidenceScore() > 100) {
            return attention("RECOVERY_CONFIDENCE_TOO_LOW", authorizationDigest, policyDigest,
                    policy.maxCycles(), deadlineAt);
        }
        if (request.riskLevel() == null) {
            return attention("RECOVERY_RISK_UNRESOLVED", authorizationDigest, policyDigest,
                    policy.maxCycles(), deadlineAt);
        }
        if (approvalAuthorized || !request.riskLevel().canBeAutomaticallyApproved()) {
            return new SyncAutopilotRecoveryPolicyDecision(
                    SyncAutopilotRecoveryCaseState.WAITING_APPROVAL,
                    null,
                    authorizationDigest,
                    policyDigest,
                    policy.maxCycles(),
                    deadlineAt
            );
        }
        if (request.riskLevel().canBeAutomaticallyApproved()) {
            if (!request.action().isAutomaticLowRiskWhitelisted()) {
                return rejected(authorizationDigest, policyDigest, policy.maxCycles(), deadlineAt);
            }
            return new SyncAutopilotRecoveryPolicyDecision(
                    SyncAutopilotRecoveryCaseState.AUTO_APPROVED,
                    null,
                    authorizationDigest,
                    policyDigest,
                    policy.maxCycles(),
                    deadlineAt
            );
        }
        return new SyncAutopilotRecoveryPolicyDecision(
                SyncAutopilotRecoveryCaseState.WAITING_APPROVAL,
                null,
                authorizationDigest,
                policyDigest,
                policy.maxCycles(),
                deadlineAt
        );
    }

    /**
     * Calculates the maximum deadline that this authorization snapshot can grant.
     *
     * <p>Unattended recovery is bounded by both the policy's absolute expiry and its maximum
     * duration measured from the evaluation instant. The earlier instant wins. The conversion
     * to UTC-local time preserves the existing PostgreSQL {@link LocalDateTime} contract while
     * avoiding arithmetic in the server's default timezone. This helper is pure and never trusts
     * a caller-provided deadline.</p>
     *
     * @param policy parsed and bounded authorization policy
     * @param evaluatedAt UTC-local evaluation instant
     * @return earliest safe UTC-local deadline
     */
    private LocalDateTime boundedPolicyDeadline(ParsedPolicy policy, LocalDateTime evaluatedAt) {
        LocalDateTime durationDeadline = evaluatedAt.plusSeconds(policy.maxDurationSeconds());
        LocalDateTime expiryDeadline = policy.expiresAt()
                .withOffsetSameInstant(ZoneOffset.UTC)
                .toLocalDateTime();
        return isAfterUtcInstant(durationDeadline, expiryDeadline) ? expiryDeadline : durationDeadline;
    }

    /**
     * Parses and validates the small policy schema needed by this evaluator.
     *
     * <p>The parser accepts only an object with an identifier, tenant scope, expiry, bounded budgets, risk
     * ceiling, and enum action lists. It resolves documented aliases for backward compatibility, rejects type
     * confusion and out-of-range values, and retains no raw JSON. Parsing is side-effect free and deterministic;
     * any invalid or unexpected input throws so {@link #evaluate(String, SyncAutopilotRecoveryEvaluationRequest)}
     * can fail closed to an attention decision instead of accidentally authorizing work.</p>
     *
     * @param autopilotPolicyJson persisted task-local policy text to parse
     * @return canonical in-memory policy used only for the current evaluation
     * @throws IllegalArgumentException when the policy is absent, malformed, unsafe, or incomplete
     */
    private ParsedPolicy parsePolicy(String autopilotPolicyJson) {
        if (!hasText(autopilotPolicyJson)) {
            throw new IllegalArgumentException("autopilotPolicy is required");
        }
        try {
            JsonNode root = objectMapper.readTree(autopilotPolicyJson);
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException("autopilotPolicy must be a JSON object");
            }
            String authorizationId = requiredText(root, "authorizationId", "policyId");
            if (!SAFE_AUTHORIZATION_ID.matcher(authorizationId).matches()) {
                throw new IllegalArgumentException("authorizationId is not low-sensitive identifier text");
            }
            Long tenantId = requiredLong(root, "tenantId");
            Long taskId = nullableLong(root, "taskId");
            Long projectId = nullableLong(root, "projectId");
            OffsetDateTime expiresAt = parseDateTime(requiredText(root, "expiresAt"));
            int maxCycles = boundedInt(root, "maxCycles", "maxRecoveryCycles", 5, 1, 10);
            int maxDurationSeconds = root.has("maxTotalDurationMinutes")
                    ? boundedInt(root, "maxTotalDurationMinutes", null, 120, 5, 1440) * 60
                    : boundedInt(root, "maxDurationSeconds", null, 7200, 1, 86_400);
            int maxRepeatedErrorCount = boundedInt(root, "maxRepeatedErrorCount", null, 3, 1, 10);
            int minimumConfidence = boundedInt(root, "minimumConfidence", null, 70, 0, 100);
            SyncAutopilotRiskLevel maxAutomaticRisk = SyncAutopilotRiskLevel.valueOf(
                    requiredText(root, "maxAutomaticRisk", "maxAutomaticRiskLevel").toUpperCase(Locale.ROOT));
            JsonNode allowedNode = root.has("allowedRecoveryActions")
                    ? root.path("allowedRecoveryActions") : root.path("allowedActions");
            Set<SyncAutopilotRecoveryAction> allowedActions = parseActions(allowedNode, true);
            Set<SyncAutopilotRecoveryAction> approvalActions = parseActions(root.path("requireApprovalFor"), false);
            if (tenantId <= 0 || (taskId != null && taskId <= 0) || allowedActions.isEmpty()) {
                throw new IllegalArgumentException("autopilotPolicy contains an invalid scope or action list");
            }
            return new ParsedPolicy(authorizationId, tenantId, projectId, taskId, expiresAt, maxCycles,
                    maxDurationSeconds, maxRepeatedErrorCount, minimumConfidence, maxAutomaticRisk,
                    allowedActions, approvalActions);
        } catch (Exception exception) {
            if (exception instanceof IllegalArgumentException) {
                throw (IllegalArgumentException) exception;
            }
            throw new IllegalArgumentException("Cannot parse autopilotPolicy", exception);
        }
    }

    /**
     * Converts a JSON array of documented recovery-action names into a closed enum set.
     *
     * <p>When the field is required, absence, a non-array value, or an empty list is invalid. Optional approval
     * actions instead become an empty set. Every element must be textual and resolve through the server enum,
     * preventing arbitrary operation names from entering an authorization decision. The method is pure and has
     * no persistence or execution side effect.</p>
     *
     * @param actions JSON node containing the action list
     * @param required whether absence/emptiness must reject the policy
     * @return immutable-in-practice enum set used during this evaluation
     * @throws IllegalArgumentException when a required list is missing or an element is not a known enum name
     */
    private Set<SyncAutopilotRecoveryAction> parseActions(JsonNode actions, boolean required) {
        if (actions == null || !actions.isArray() || actions.isEmpty()) {
            if (!required) {
                return EnumSet.noneOf(SyncAutopilotRecoveryAction.class);
            }
            throw new IllegalArgumentException("allowedActions must be a non-empty array");
        }
        Set<SyncAutopilotRecoveryAction> result = EnumSet.noneOf(SyncAutopilotRecoveryAction.class);
        for (JsonNode action : actions) {
            if (!action.isTextual()) {
                throw new IllegalArgumentException("allowedActions must contain enum names only");
            }
            result.add(SyncAutopilotRecoveryAction.valueOf(action.asText().trim().toUpperCase(Locale.ROOT)));
        }
        return result;
    }

    /**
     * Checks whether a policy was issued for the same tenant/project/task scope as the candidate.
     *
     * <p>A policy may intentionally omit {@code taskId}, in which case it applies to its tenant/project scope;
     * a present task ID must match exactly. This is a pure, idempotent authorization-boundary check and does not
     * mutate a case. A false result is handled as rejection, not as a reason to widen scope or fall back to a
     * different policy.</p>
     *
     * @param policy canonical persisted authorization scope
     * @param request candidate facts whose ownership is being checked
     * @return {@code true} only when all required scope components match
     */
    private boolean scopeMatches(ParsedPolicy policy, SyncAutopilotRecoveryEvaluationRequest request) {
        return Objects.equals(policy.tenantId(), request.tenantId())
                && Objects.equals(policy.projectId(), request.projectId())
                && (policy.taskId() == null || Objects.equals(policy.taskId(), request.syncTaskId()));
    }

    /**
     * Builds a stable digest of the canonical policy fields that influenced an evaluation.
     *
     * <p>Action sets are sorted before hashing so JSON ordering does not change the binding. The resulting digest
     * is deterministic, has no state side effect, and is stored instead of raw authorization JSON. It supports
     * auditing and case identity, but is not an encryption mechanism or a substitute for rechecking current
     * persisted ownership at the service boundary.</p>
     *
     * @param policy validated canonical policy
     * @return lowercase SHA-256 digest of the policy's authorization-relevant fields
     */
    private String policyDigest(ParsedPolicy policy) {
        String actions = policy.allowedActions().stream()
                .map(Enum::name)
                .sorted()
                .reduce((left, right) -> left + "," + right)
                .orElse("");
        String approvalActions = policy.approvalActions().stream()
                .map(Enum::name)
                .sorted()
                .reduce((left, right) -> left + "," + right)
                .orElse("");
        return SyncAutopilotDigestSupport.sha256(policy.authorizationId() + "|" + policy.tenantId() + "|"
                + policy.projectId() + "|" + policy.taskId() + "|" + policy.expiresAt().toInstant() + "|"
                + policy.maxCycles() + "|" + policy.maxDurationSeconds() + "|"
                + policy.maxRepeatedErrorCount() + "|" + policy.minimumConfidence() + "|"
                + policy.maxAutomaticRisk() + "|" + actions + "|" + approvalActions);
    }

    /**
     * Produces a non-executable rejection decision for an authorization-boundary failure.
     *
     * <p>This helper has no state or transport side effect. It deliberately returns the same bounded lifecycle
     * metadata that a permitted decision would carry, allowing the case service to audit the policy context
     * without exposing raw policy content. Repeated calls with the same inputs are idempotent.</p>
     *
     * @param authorizationDigest digest of the authorization identifier
     * @param policyDigest digest of the canonical policy
     * @param maxCycles policy recovery budget
     * @param deadlineAt policy-derived deadline for the case record
     * @return immutable decision whose state is {@code REJECTED}
     */
    private SyncAutopilotRecoveryPolicyDecision rejected(String authorizationDigest,
                                                          String policyDigest,
                                                          int maxCycles,
                                                          LocalDateTime deadlineAt) {
        return new SyncAutopilotRecoveryPolicyDecision(
                SyncAutopilotRecoveryCaseState.REJECTED,
                null,
                authorizationDigest,
                policyDigest,
                maxCycles,
                deadlineAt
        );
    }

    /**
     * Produces a non-executable attention decision when a safety guard cannot be satisfied.
     *
     * <p>The reason is a stable low-sensitive code rather than raw policy, error, or evidence content. This
     * helper is pure and idempotent; it only describes the state the case service may persist. It neither grants
     * approval nor triggers escalation, so a later human or governed workflow remains responsible for action.</p>
     *
     * @param reason stable reason code explaining why automation must stop
     * @param authorizationDigest digest of the authorization identifier
     * @param policyDigest digest of the canonical policy
     * @param maxCycles policy recovery budget
     * @param deadlineAt policy-derived deadline for the case record
     * @return immutable decision whose state is {@code ATTENTION_REQUIRED}
     */
    private SyncAutopilotRecoveryPolicyDecision attention(String reason,
                                                           String authorizationDigest,
                                                           String policyDigest,
                                                           int maxCycles,
                                                           LocalDateTime deadlineAt) {
        return new SyncAutopilotRecoveryPolicyDecision(
                SyncAutopilotRecoveryCaseState.ATTENTION_REQUIRED,
                reason,
                authorizationDigest,
                policyDigest,
                maxCycles,
                deadlineAt
        );
    }

    /**
     * Reads one required nonblank textual policy field, optionally accepting a documented legacy alias.
     *
     * <p>The method trims only after it has verified that the JSON value is text. It does not coerce numbers,
     * objects, or arrays into strings, avoiding a type-confusion route into authorization parsing. It is pure,
     * repeatable, and signals an invalid policy with a field-specific exception rather than inventing a default.</p>
     *
     * @param root parsed policy object
     * @param field preferred schema field name
     * @param alias optional backward-compatible field name
     * @return trimmed required text from the preferred field or alias
     * @throws IllegalArgumentException when neither field supplies nonblank text
     */
    private String requiredText(JsonNode root, String field, String alias) {
        JsonNode value = root.get(field);
        if ((value == null || value.isNull()) && alias != null) {
            value = root.get(alias);
        }
        if (value == null || !value.isTextual() || !hasText(value.asText())) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.asText().trim();
    }

    private String requiredText(JsonNode root, String field) {
        return requiredText(root, field, null);
    }

    /**
     * Reads a required integer identifier without converting text or floating-point JSON values.
     *
     * <p>Identifier presence is checked separately from business range checks because different policy fields
     * own different bounds. The helper is pure and idempotent; it does not create a default identifier or alter
     * request scope, so an absent value fails closed before a policy can authorize another tenant/task.</p>
     *
     * @param root parsed policy object
     * @param field required integer field name
     * @return long value represented by the JSON integer
     * @throws IllegalArgumentException when the field is absent or not convertible to a long
     */
    private Long requiredLong(JsonNode root, String field) {
        Long value = nullableLong(root, field);
        if (value == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }

    /**
     * Reads an optional integer field while preserving the distinction between absent and invalid values.
     *
     * <p>{@code null} means the policy omitted the optional scope component; non-integral input is rejected
     * rather than coerced. The method is pure and side-effect free, allowing its caller to apply the correct
     * scope semantics without granting a broader fallback value.</p>
     *
     * @param root parsed policy object
     * @param field optional integer field name
     * @return the long value, or {@code null} only when the field is absent/null
     * @throws IllegalArgumentException when a present field is not an integer
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
     * Reads a bounded integer budget or threshold, using a safe default only when it is omitted.
     *
     * <p>The optional alias supports a documented schema rename. A present value must be an integer within the
     * supplied inclusive range, so policy JSON cannot make retry loops unbounded or silently truncate a value.
     * This method is pure and idempotent; it returns a number for the evaluator but does not itself update a
     * case, schedule work, or retry anything.</p>
     *
     * @param root parsed policy object
     * @param field preferred field name
     * @param alias optional legacy field name
     * @param fallback safe value for an omitted field
     * @param min smallest accepted explicit value
     * @param max largest accepted explicit value
     * @return fallback or validated explicit integer
     * @throws IllegalArgumentException when a present value is non-integral or outside the safety range
     */
    private int boundedInt(JsonNode root, String field, String alias, int fallback, int min, int max) {
        JsonNode value = root.get(field);
        if ((value == null || value.isNull()) && alias != null) {
            value = root.get(alias);
        }
        if (value == null || value.isNull()) {
            return fallback;
        }
        if (!value.canConvertToInt()) {
            throw new IllegalArgumentException(field + " must be an integer");
        }
        int parsed = value.intValue();
        if (parsed < min || parsed > max) {
            throw new IllegalArgumentException(field + " is outside the supported safety range");
        }
        return parsed;
    }

    /**
     * Parses an expiry timestamp from offset-aware ISO text or a legacy UTC-local ISO text.
     *
     * <p>Offset-aware text keeps its offset until the evaluator compares absolute instants. Discarding that
     * offset would turn {@code 17:00+08:00} into {@code 17:00 UTC}, silently extending an authorization by eight
     * hours. Legacy local text is accepted for compatibility but explicitly bound to UTC because the durable
     * request/deadline contract uses UTC-local {@link LocalDateTime} values. The helper has no clock or
     * persistence side effect and fails closed for unreadable input.</p>
     *
     * @param value required ISO-8601 timestamp text
     * @return offset-aware expiry with legacy local values interpreted as UTC
     * @throws RuntimeException when the timestamp cannot be parsed in either supported representation
     */
    private OffsetDateTime parseDateTime(String value) {
        try {
            return OffsetDateTime.parse(value);
        } catch (RuntimeException ignored) {
            return LocalDateTime.parse(value).atOffset(ZoneOffset.UTC);
        }
    }

    /**
     * Compares a policy expiry with a UTC-local evaluation time on the absolute UTC timeline.
     *
     * <p>Policies can be supplied with an arbitrary ISO-8601 offset, while existing command DTOs intentionally
     * retain {@link LocalDateTime} for PostgreSQL compatibility. Treating the request timestamp as UTC and both
     * values as instants preserves that contract without allowing a policy's local clock representation to alter
     * authorization duration.</p>
     *
     * @param candidate policy timestamp that must be later than the evaluation time
     * @param evaluatedAt UTC-local evaluation timestamp
     * @return {@code true} only when the policy instant occurs strictly after the evaluation instant
     */
    private boolean isAfterInstant(OffsetDateTime candidate, LocalDateTime evaluatedAt) {
        return candidate.toInstant().isAfter(evaluatedAt.toInstant(ZoneOffset.UTC));
    }

    /**
     * Compares two persisted UTC-local deadlines without inheriting the host JVM's default time zone.
     *
     * <p>Both values originate from the data-sync persistence contract, where {@link LocalDateTime} represents a
     * UTC wall-clock value. Explicit conversion avoids the common mistake of calling {@code now()} in a regional
     * server time zone and treating it as the same physical moment as a stored deadline.</p>
     *
     * @param candidate deadline that must remain in the future
     * @param evaluatedAt UTC-local evaluation timestamp
     * @return {@code true} only when the deadline instant is strictly after the evaluation instant
     */
    private boolean isAfterUtcInstant(LocalDateTime candidate, LocalDateTime evaluatedAt) {
        return candidate.toInstant(ZoneOffset.UTC).isAfter(evaluatedAt.toInstant(ZoneOffset.UTC));
    }

    /**
     * Verifies that a supplied correlation fingerprint is exactly a SHA-256 hexadecimal value.
     *
     * <p>This is a pure input-boundary check, not a recomputation of the underlying error or repair fact. It
     * prevents raw prose, SQL, URLs, and arbitrary identifiers from entering the low-sensitive policy contract;
     * a false result makes the evaluator stop at attention rather than automatically recover.</p>
     *
     * @param value candidate fingerprint text
     * @return {@code true} only for a nonblank 64-character hexadecimal SHA-256 value
     */
    private boolean validFingerprint(String value) {
        return hasText(value) && SHA_256.matcher(value.trim()).matches();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private record ParsedPolicy(
            String authorizationId,
            Long tenantId,
            Long projectId,
            Long taskId,
            OffsetDateTime expiresAt,
            int maxCycles,
            int maxDurationSeconds,
            int maxRepeatedErrorCount,
            int minimumConfidence,
            SyncAutopilotRiskLevel maxAutomaticRisk,
            Set<SyncAutopilotRecoveryAction> allowedActions,
            Set<SyncAutopilotRecoveryAction> approvalActions
    ) {
    }
}
