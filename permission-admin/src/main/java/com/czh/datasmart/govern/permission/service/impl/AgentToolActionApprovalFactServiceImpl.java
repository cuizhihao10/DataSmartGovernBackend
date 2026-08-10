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

import java.time.LocalDateTime;
import java.util.ArrayList;
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
     * 在事实进入 Store 前验证不可替代的审批责任链。
     *
     * <p>来源服务守卫解决“谁能调用登记接口”，本方法解决“受信调用方提交的事实是否仍具备可审计语义”。
     * 因此 0/负数租户、项目不能被解释为平台公共范围；而 APPROVED 不能只是一个状态字符串，必须留下
     * 审批人、原因码和证据码。这样即使受信链路发生编程错误，也不会产生一条可被后续 Worker 当作授权的
     * 无责任主体事实。</p>
     *
     * @param request 已经通过 HTTP 基础反序列化的登记请求
     * @throws PlatformBusinessException 请求缺少范围、工具定位或 APPROVED 审计要素时抛出
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
