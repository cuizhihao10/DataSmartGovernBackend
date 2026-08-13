/**
 * @Author : Cui
 * @Date: 2026/06/11 23:20
 * @Description DataSmart Govern Backend - AgentToolActionApprovalFactServiceImpl.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.service.impl;

import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import com.czh.datasmart.govern.permission.controller.dto.AgentToolActionApprovalFactEvaluateRequest;
import com.czh.datasmart.govern.permission.controller.dto.AgentToolActionApprovalFactEvaluationView;
import com.czh.datasmart.govern.permission.controller.dto.AgentToolActionApprovalFactRegisterRequest;
import com.czh.datasmart.govern.permission.controller.dto.AgentToolActionApprovalFactRegisterResponse;
import com.czh.datasmart.govern.permission.service.AgentToolActionApprovalFactService;
import com.czh.datasmart.govern.permission.service.support.AgentToolActionApprovalFactRecord;
import com.czh.datasmart.govern.permission.service.support.AgentToolActionApprovalFactStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Agent 受控工具动作审批事实服务第一版实现。
 *
 * <p>本实现的重点不是做完整审批流，而是先固定商业化 Agent Host 的关键安全语义：
 * approvalFactId 不能只是 task.params 里的一个字符串，它必须能在 permission-admin 服务端回查到事实；
 * 且该事实必须未过期、状态已批准、绑定当前 tenant/project/actor/session/run/command/tool 和策略版本。</p>
 */
@Service
@RequiredArgsConstructor
public class AgentToolActionApprovalFactServiceImpl implements AgentToolActionApprovalFactService {

    private static final Pattern SAFE_FACT_ID_PATTERN = Pattern.compile("[A-Za-z0-9:_.\\-]{1,160}");
    private static final Pattern SAFE_SUBJECT_ID_PATTERN = Pattern.compile("[A-Za-z0-9:_.\\-]{1,160}");
    private static final String ACTION_FINGERPRINT_PREFIX = "sha256:";
    private static final String ACTION_FINGERPRINT_VERSION = "approval-action-v1";
    private static final int MAX_CODE_COUNT = 20;
    private static final int MAX_CODE_LENGTH = 128;

    private final AgentToolActionApprovalFactStore factStore;

    @Override
    public AgentToolActionApprovalFactRegisterResponse register(AgentToolActionApprovalFactRegisterRequest request) {
        validateRegisterRequest(request);
        /*
         * Scope/version validation and lifecycle merging must happen inside the
         * store's single atomic write. A read here would create a TOCTOU window
         * in which two callers could both validate the same stale row.
         */
        AgentToolActionApprovalFactRecord record = factStore.save(toRecord(request));
        return new AgentToolActionApprovalFactRegisterResponse(
                record.approvalFactId(),
                record.status(),
                record.policyVersion(),
                "Agent 受控工具动作审批事实已登记"
        );
    }

    /**
     * Evaluates whether an approval fact can authorize the supplied controlled action.
     *
     * <p>The request's {@code actionFingerprint} is a compatibility-only field:
     * it can originate from a model or any caller, so this method never uses it
     * as proof of approval. Instead, it recalculates a fingerprint from the
     * approval fact's trusted action binding and the scope-verified action
     * fields in the evaluation request.</p>
     *
     * @param request current action context; its identifiers are untrusted until
     *                they match the durable approval fact
     * @return a fail-closed approval decision and low-sensitive audit codes
     */
    @Override
    public AgentToolActionApprovalFactEvaluationView evaluate(AgentToolActionApprovalFactEvaluateRequest request) {
        if (request == null || blank(request.getApprovalFactId())) {
            return waiting(null, "MISSING_ID", "当前受控工具动作缺少 approvalFactId，等待审批事实生成。",
                    List.of("APPROVAL_FACT_ID_MISSING"));
        }
        String approvalFactId = request.getApprovalFactId().trim();
        if (!safeFactId(approvalFactId)) {
            return blocked(approvalFactId, "INVALID_FACT_ID", "approvalFactId 不是安全低敏事实 ID。",
                    List.of("APPROVAL_FACT_ID_INVALID"));
        }
        return factStore.findById(approvalFactId)
                .map(record -> evaluateRecord(record, request))
                .orElseGet(() -> waiting(approvalFactId, "UNKNOWN", "permission-admin 未找到该审批事实，等待审批事实物化。",
                        List.of("APPROVAL_FACT_NOT_FOUND")));
    }

    /**
     * Replays one durable approval fact against the action currently being evaluated.
     *
     * <p>All request fields arrive from a caller and are therefore compared to
     * the durable fact before they participate in fingerprint calculation. A
     * persisted fingerprint is an integrity copy of the server calculation,
     * never a value supplied by the requester. A missing or mismatched persisted
     * fingerprint fails closed: legacy rows remain available for audit, but they
     * must be registered again before they can authorize a controlled action.
     * This prevents a model from authorizing a different action merely by
     * repeating a chosen string.</p>
     *
     * @param record durable approval fact, which is authoritative after store lookup
     * @param request caller-provided current action context
     * @return an approved, waiting, or blocked decision for this exact action
     */
    private AgentToolActionApprovalFactEvaluationView evaluateRecord(AgentToolActionApprovalFactRecord record,
                                                                    AgentToolActionApprovalFactEvaluateRequest request) {
        List<String> issueCodes = new ArrayList<>();
        List<String> evidenceCodes = new ArrayList<>(record.evidenceCodes());
        evidenceCodes.add("APPROVAL_FACT_FOUND");
        String scopeIssue = scopeIssue(record, request);
        if (scopeIssue != null) {
            return blocked(record, "SCOPE_MISMATCH", scopeIssue, evidenceCodes, List.of("APPROVAL_FACT_SCOPE_MISMATCH"));
        }
        evidenceCodes.add("APPROVAL_FACT_SCOPE_VERIFIED");

        String authoritativeActionFingerprint = serverCalculatedActionFingerprint(record);
        String storedActionFingerprint = text(record.actionFingerprint());
        if (storedActionFingerprint == null) {
            return blocked(record, "ACTION_FINGERPRINT_MISSING",
                    "The approval fact has no persisted server-calculated action fingerprint.",
                    evidenceCodes, List.of("APPROVAL_FACT_ACTION_FINGERPRINT_MISSING"));
        }
        if (!sameFingerprint(authoritativeActionFingerprint, storedActionFingerprint)) {
            return blocked(record, "ACTION_FINGERPRINT_INTEGRITY_MISMATCH",
                    "The stored approval fact fingerprint is not the server-calculated action binding.",
                    evidenceCodes, List.of("APPROVAL_FACT_ACTION_FINGERPRINT_INTEGRITY_MISMATCH"));
        }
        String currentActionFingerprint = serverCalculatedActionFingerprint(request);
        if (!sameFingerprint(authoritativeActionFingerprint, currentActionFingerprint)) {
            return blocked(record, "ACTION_FINGERPRINT_MISMATCH",
                    "The server-calculated approval binding does not match the current action.",
                    evidenceCodes, List.of("APPROVAL_FACT_ACTION_FINGERPRINT_MISMATCH"));
        }
        evidenceCodes.add("APPROVAL_FACT_ACTION_FINGERPRINT_SERVER_VERIFIED");
        if (record.expiresAt() != null && record.expiresAt().isBefore(LocalDateTime.now())) {
            return blocked(record, "EXPIRED", "审批事实已过期，不能继续授权受控工具动作。",
                    evidenceCodes, List.of("APPROVAL_FACT_EXPIRED"));
        }
        String requestedPolicyVersion = text(request.getRequestedPolicyVersion());
        if (requestedPolicyVersion != null && text(record.policyVersion()) != null
                && !requestedPolicyVersion.equals(record.policyVersion())) {
            return blocked(record, "POLICY_VERSION_MISMATCH", "审批事实策略版本与当前任务快照不一致。",
                    evidenceCodes, List.of("APPROVAL_FACT_POLICY_VERSION_MISMATCH"));
        }
        if (requestedPolicyVersion != null) {
            evidenceCodes.add("APPROVAL_FACT_POLICY_VERSION_VERIFIED");
        }
        String status = normalizeStatus(record.status());
        if ("APPROVED".equals(status)) {
            evidenceCodes.add("APPROVAL_FACT_STATUS_APPROVED");
            return new AgentToolActionApprovalFactEvaluationView(
                    record.approvalFactId(),
                    true,
                    false,
                    "APPROVED",
                    "审批事实已批准且作用域匹配，受控工具动作可继续进入下一执行前检查。",
                    status,
                    record.policyVersion(),
                    record.expiresAt(),
                    evidenceCodes,
                    issueCodes
            );
        }
        if ("PENDING".equals(status)) {
            return waiting(record, "PENDING", "审批事实仍处于待处理状态，任务应 defer 等待审批完成。",
                    evidenceCodes, List.of("APPROVAL_FACT_PENDING"));
        }
        return blocked(record, "REJECTED", "审批事实不是 APPROVED，受控工具动作不能继续。",
                evidenceCodes, List.of("APPROVAL_FACT_REJECTED"));
    }

    /**
     * Calculates the authoritative action fingerprint while a fact is registered.
     *
     * <p>The input is a registration request that has already passed
     * {@link #validateRegisterRequest(AgentToolActionApprovalFactRegisterRequest)}.
     * The output is a versioned SHA-256 digest of the normalized immutable scope
     * and action locator fields that permission-admin owns. The request's raw
     * {@code actionFingerprint} property is deliberately excluded: it can be
     * supplied by a model, browser, or upstream service and therefore cannot be
     * accepted as authorization proof. Persisting this server-derived value gives
     * later evaluation a durable integrity value to verify.</p>
     *
     * @param request validated registration payload containing the fact scope and
     *                controlled action locators
     * @return server-derived fingerprint for the exact approval fact binding
     */
    private String serverCalculatedActionFingerprint(AgentToolActionApprovalFactRegisterRequest request) {
        return calculateActionFingerprint(
                request.getApprovalFactId(),
                request.getTenantId() == null ? null : request.getTenantId().toString(),
                request.getApplicationId() == null ? null : request.getApplicationId().toString(),
                request.getProjectId() == null ? null : request.getProjectId().toString(),
                request.getUserId(),
                request.getActorId(),
                request.getAgentId(),
                request.getSessionId(),
                request.getRunId(),
                request.getDelegationId(),
                request.getCommandId(),
                request.getToolCode()
        );
    }

    /**
     * Converts a validated registration request into the durable approval fact.
     *
     * <p>The caller-supplied {@code actionFingerprint} is deliberately omitted.
     * The persisted value is calculated only from validated approval-fact and
     * action-binding fields, so a model-controlled string can neither create
     * nor alter an authorization binding.</p>
     *
     * @param request validated registration payload from the trusted registration route
     * @return durable record containing the server-calculated action fingerprint
     */
    private AgentToolActionApprovalFactRecord toRecord(AgentToolActionApprovalFactRegisterRequest request) {
        return new AgentToolActionApprovalFactRecord(
                request.getApprovalFactId().trim(),
                request.getTenantId(),
                request.getApplicationId(),
                request.getProjectId(),
                text(request.getUserId()),
                text(request.getActorId()),
                text(request.getAgentId()),
                text(request.getSessionId()),
                text(request.getRunId()),
                text(request.getDelegationId()),
                text(request.getCommandId()),
                text(request.getToolCode()),
                serverCalculatedActionFingerprint(request),
                text(request.getPolicyVersion()),
                normalizeStatus(request.getStatus()),
                request.getExpiresAt(),
                text(request.getApprovedByActorId()),
                safeCodes(request.getReasonCodes()),
                safeCodes(request.getEvidenceCodes()),
                LocalDateTime.now()
        );
    }

    /**
     * Applies the request validation required before a server fingerprint is created.
     *
     * <p>The legacy {@code actionFingerprint} member is intentionally neither
     * validated nor persisted: it is an untrusted caller value and cannot be
     * used to authorize an action. The validated scope, delegation, command,
     * and tool fields are the only fingerprint inputs. The method also requires
     * positive tenant/application/project identifiers and an auditable APPROVED
     * decision with an approver, reason codes, and evidence codes. This keeps a
     * trusted caller's programming error from creating an anonymous authorization.</p>
     *
     * @param request registration request received after HTTP deserialization
     * @throws PlatformBusinessException when the fact lacks a safe, auditable binding
     */
    private void validateRegisterRequest(AgentToolActionApprovalFactRegisterRequest request) {
        if (request == null || blank(request.getApprovalFactId())) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST, "approvalFactId 不能为空");
        }
        if (!safeFactId(request.getApprovalFactId().trim())) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "approvalFactId 只能使用低敏短 ID，不能包含 URL、SQL、prompt 或凭证片段");
        }
        if (request.getTenantId() == null || request.getTenantId() <= 0
                || request.getApplicationId() == null || request.getApplicationId() <= 0
                || request.getProjectId() == null || request.getProjectId() <= 0) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "审批事实必须绑定正数 tenantId、applicationId 和 projectId");
        }
        if (blank(request.getUserId()) || blank(request.getActorId()) || blank(request.getAgentId())
                || blank(request.getSessionId()) || blank(request.getRunId()) || blank(request.getDelegationId())
                || blank(request.getCommandId()) || blank(request.getToolCode())) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "审批事实必须绑定 userId、actorId、agentId、sessionId、runId、delegationId、commandId 和 toolCode");
        }
        validateScopeIdentifiers(request);
        validateApprovedAuditEvidence(request);
    }

    /**
     * 为可执行的 APPROVED 事实保留最小的人类或受控策略审计证据。
     *
     * <p>系统不强制某一种固定原因码，以便未来的项目 Owner 审批、合规平台审批和策略自动批准使用不同代码；
     * 但三项责任要素都不可为空。后续 evaluate 只会批准这些已经通过登记校验的事实，因此这里是防止
     * “仅把 status 改为 APPROVED”绕过审批链的最后一道本地约束。</p>
     *
     * @param request 原始登记请求
     * @throws PlatformBusinessException APPROVED 缺少审批人、原因或证据时抛出
     */
    private void validateApprovedAuditEvidence(AgentToolActionApprovalFactRegisterRequest request) {
        if (!"APPROVED".equals(normalizeStatus(request.getStatus()))) {
            return;
        }
        if (blank(request.getApprovedByActorId())
                || safeCodes(request.getReasonCodes()).isEmpty()
                || safeCodes(request.getEvidenceCodes()).isEmpty()) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "APPROVED 审批事实必须包含审批人、低敏原因码和低敏证据码");
        }
    }

    private String scopeIssue(AgentToolActionApprovalFactRecord record,
                              AgentToolActionApprovalFactEvaluateRequest request) {
        if (!Objects.equals(record.tenantId(), request.getTenantId())) {
            return "审批事实 tenantId 与当前任务不一致。";
        }
        if (!Objects.equals(record.applicationId(), request.getApplicationId())) {
            return "审批事实 applicationId 与当前任务不一致。";
        }
        if (!Objects.equals(record.projectId(), request.getProjectId())) {
            return "审批事实 projectId 与当前任务不一致。";
        }
        if (!same(record.userId(), request.getUserId())) {
            return "审批事实 userId 与当前任务不一致。";
        }
        if (!same(record.actorId(), request.getActorId())) {
            return "审批事实 actorId 与当前任务不一致。";
        }
        if (!same(record.agentId(), request.getAgentId())) {
            return "审批事实 agentId 与当前任务不一致。";
        }
        if (!same(record.sessionId(), request.getSessionId()) || !same(record.runId(), request.getRunId())) {
            return "审批事实 sessionId/runId 与当前任务不一致。";
        }
        if (!same(record.delegationId(), request.getDelegationId())) {
            return "审批事实 delegationId 与当前任务不一致。";
        }
        if (!same(record.commandId(), request.getCommandId())) {
            return "审批事实 commandId 与当前任务不一致。";
        }
        if (!same(record.toolCode(), request.getToolCode())) {
            return "审批事实 toolCode 与当前任务不一致。";
        }
        return null;
    }

    /**
     * Validates the identifiers that form the immutable dual-subject boundary.
     *
     * <p>The approval endpoint only accepts low-sensitive locators, never raw
     * prompts, SQL, tool arguments, credentials, or external URLs. Restricting
     * these identifiers to a compact allowlist makes accidental sensitive-data
     * persistence less likely and keeps every scope field safe for audit indexes
     * and diagnostic correlation.</p>
     *
     * @param request trusted-service registration payload after null checks
     * @throws PlatformBusinessException when a scope identifier is oversized or
     *                                   contains a value outside the audit-safe format
     */
    private void validateScopeIdentifiers(AgentToolActionApprovalFactRegisterRequest request) {
        if (!safeSubjectId(request.getUserId()) || !safeSubjectId(request.getActorId())
                || !safeSubjectId(request.getAgentId()) || !safeSubjectId(request.getSessionId())
                || !safeSubjectId(request.getRunId()) || !safeSubjectId(request.getDelegationId())
                || !safeSubjectId(request.getCommandId()) || !safeSubjectId(request.getToolCode())) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "审批事实的用户、Agent、委托、会话、运行和工具定位字段必须是低敏短 ID");
        }
    }

    private AgentToolActionApprovalFactEvaluationView waiting(String approvalFactId,
                                                             String decision,
                                                             String reason,
                                                             List<String> issueCodes) {
        return new AgentToolActionApprovalFactEvaluationView(
                approvalFactId, false, true, decision, reason, null, null, null, List.of(), issueCodes);
    }

    private AgentToolActionApprovalFactEvaluationView waiting(AgentToolActionApprovalFactRecord record,
                                                             String decision,
                                                             String reason,
                                                             List<String> evidenceCodes,
                                                             List<String> issueCodes) {
        return new AgentToolActionApprovalFactEvaluationView(
                record.approvalFactId(), false, true, decision, reason, record.status(), record.policyVersion(),
                record.expiresAt(), evidenceCodes, issueCodes);
    }

    private AgentToolActionApprovalFactEvaluationView blocked(String approvalFactId,
                                                             String decision,
                                                             String reason,
                                                             List<String> issueCodes) {
        return new AgentToolActionApprovalFactEvaluationView(
                approvalFactId, false, false, decision, reason, null, null, null, List.of(), issueCodes);
    }

    private AgentToolActionApprovalFactEvaluationView blocked(AgentToolActionApprovalFactRecord record,
                                                             String decision,
                                                             String reason,
                                                             List<String> evidenceCodes,
                                                             List<String> issueCodes) {
        return new AgentToolActionApprovalFactEvaluationView(
                record.approvalFactId(), false, false, decision, reason, record.status(), record.policyVersion(),
                record.expiresAt(), evidenceCodes, issueCodes);
    }

    /**
     * Calculates the authoritative fingerprint from a durable approval fact.
     *
     * <p>This path uses only fact fields that were validated and persisted by
     * permission-admin. It never reads a fingerprint supplied by an approval
     * workflow, model, or other caller, because that value cannot establish an
     * authorization boundary on its own.</p>
     *
     * @param record durable approval fact used as the authority for evaluation
     * @return versioned SHA-256 digest of the fact's immutable action binding
     */
    private String serverCalculatedActionFingerprint(AgentToolActionApprovalFactRecord record) {
        return calculateActionFingerprint(
                record.approvalFactId(),
                record.tenantId() == null ? null : record.tenantId().toString(),
                record.applicationId() == null ? null : record.applicationId().toString(),
                record.projectId() == null ? null : record.projectId().toString(),
                record.userId(),
                record.actorId(),
                record.agentId(),
                record.sessionId(),
                record.runId(),
                record.delegationId(),
                record.commandId(),
                record.toolCode()
        );
    }

    /**
     * Calculates the action-side fingerprint after the request has matched the fact scope.
     *
     * <p>The request is untrusted when it enters the controller. Calling this
     * method only after {@link #scopeIssue(AgentToolActionApprovalFactRecord,
     * AgentToolActionApprovalFactEvaluateRequest)} returns {@code null} makes
     * the fields safe to compare with the fact-derived fingerprint.</p>
     *
     * @param request current action context that has already passed scope validation
     * @return versioned SHA-256 digest of the current action binding
     */
    private String serverCalculatedActionFingerprint(AgentToolActionApprovalFactEvaluateRequest request) {
        return calculateActionFingerprint(
                request.getApprovalFactId(),
                request.getTenantId() == null ? null : request.getTenantId().toString(),
                request.getApplicationId() == null ? null : request.getApplicationId().toString(),
                request.getProjectId() == null ? null : request.getProjectId().toString(),
                request.getUserId(),
                request.getActorId(),
                request.getAgentId(),
                request.getSessionId(),
                request.getRunId(),
                request.getDelegationId(),
                request.getCommandId(),
                request.getToolCode()
        );
    }

    /**
     * Produces a deterministic, versioned SHA-256 digest for one action binding.
     *
     * <p>Each field is name and length prefixed before hashing. This avoids
     * ambiguous concatenation and lets future versions add fields without
     * changing the interpretation of previously persisted facts. Inputs come
     * only from a validated registration fact or a scope-verified evaluation
     * request; raw actionFingerprint request values are deliberately absent.</p>
     *
     * @param approvalFactId durable fact locator
     * @param tenantId tenant boundary
     * @param applicationId application boundary
     * @param projectId project boundary
     * @param userId delegated human identity
     * @param actorId acting human identity
     * @param agentId executing agent identity
     * @param sessionId agent session identity
     * @param runId agent run identity
     * @param delegationId delegation proof locator
     * @param commandId controlled command locator
     * @param toolCode controlled tool identifier
     * @return SHA-256 digest prefixed with {@code sha256:}
     */
    private String calculateActionFingerprint(String approvalFactId,
                                              String tenantId,
                                              String applicationId,
                                              String projectId,
                                              String userId,
                                              String actorId,
                                              String agentId,
                                              String sessionId,
                                              String runId,
                                              String delegationId,
                                              String commandId,
                                              String toolCode) {
        StringBuilder canonical = new StringBuilder(ACTION_FINGERPRINT_VERSION);
        appendCanonicalActionField(canonical, "approvalFactId", approvalFactId);
        appendCanonicalActionField(canonical, "tenantId", tenantId);
        appendCanonicalActionField(canonical, "applicationId", applicationId);
        appendCanonicalActionField(canonical, "projectId", projectId);
        appendCanonicalActionField(canonical, "userId", userId);
        appendCanonicalActionField(canonical, "actorId", actorId);
        appendCanonicalActionField(canonical, "agentId", agentId);
        appendCanonicalActionField(canonical, "sessionId", sessionId);
        appendCanonicalActionField(canonical, "runId", runId);
        appendCanonicalActionField(canonical, "delegationId", delegationId);
        appendCanonicalActionField(canonical, "commandId", commandId);
        appendCanonicalActionField(canonical, "toolCode", toolCode);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return ACTION_FINGERPRINT_PREFIX + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is mandatory in the JDK runtime.", exception);
        }
    }

    /**
     * Appends an unambiguous canonical field to the fingerprint preimage.
     *
     * <p>The value is normalized with the same low-sensitive text rules used
     * by the scope comparison. The explicit length distinguishes null values
     * and prevents field-boundary collisions before the digest is calculated.</p>
     *
     * @param canonical mutable action-fingerprint preimage
     * @param name stable field name controlled by permission-admin
     * @param value validated or scope-verified field value
     */
    private void appendCanonicalActionField(StringBuilder canonical, String name, String value) {
        String normalizedValue = text(value);
        canonical.append('\n').append(name).append('=');
        if (normalizedValue == null) {
            canonical.append(-1);
            return;
        }
        canonical.append(normalizedValue.length()).append(':').append(normalizedValue);
    }

    /**
     * Compares two server-calculated fingerprints without reintroducing caller input.
     *
     * <p>The values are ASCII SHA-256 outputs. A constant-time comparison avoids
     * turning this authorization check into an observable prefix oracle.</p>
     *
     * @param expected fingerprint derived from the durable approval fact
     * @param actual fingerprint derived from the current scope-verified action
     * @return {@code true} only when both authoritative bindings are identical
     */
    private boolean sameFingerprint(String expected, String actual) {
        return expected != null && actual != null && MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8)
        );
    }

    private boolean safeFactId(String value) {
        if (!SAFE_FACT_ID_PATTERN.matcher(value).matches()) {
            return false;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        return !lower.contains("select ")
                && !lower.contains("insert ")
                && !lower.contains("authorization:")
                && !lower.contains("bearer ")
                && !lower.contains("password")
                && !lower.contains("prompt:")
                && !lower.contains("token");
    }

    /** Checks one compact audit locator after its mandatory-value validation has passed. */
    private boolean safeSubjectId(String value) {
        return value != null && SAFE_SUBJECT_ID_PATTERN.matcher(value.trim()).matches();
    }

    private List<String> safeCodes(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .filter(value -> !blank(value))
                .map(String::trim)
                .filter(value -> value.length() <= MAX_CODE_LENGTH)
                .filter(this::safeCode)
                .distinct()
                .limit(MAX_CODE_COUNT)
                .toList();
    }

    private boolean safeCode(String value) {
        return value.matches("[A-Za-z0-9_.:\\-]{1,128}");
    }

    private String normalizeStatus(String status) {
        String normalized = status == null ? "PENDING" : status.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "APPROVED", "PENDING", "REJECTED" -> normalized;
            default -> "PENDING";
        };
    }

    private boolean same(String left, String right) {
        String normalizedLeft = text(left);
        String normalizedRight = text(right);
        return normalizedLeft != null && normalizedLeft.equals(normalizedRight);
    }

    private String text(String value) {
        return blank(value) ? null : value.trim();
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
