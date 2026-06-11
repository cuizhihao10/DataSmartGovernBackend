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
    private static final int MAX_CODE_COUNT = 20;
    private static final int MAX_CODE_LENGTH = 128;

    private final AgentToolActionApprovalFactStore factStore;

    @Override
    public AgentToolActionApprovalFactRegisterResponse register(AgentToolActionApprovalFactRegisterRequest request) {
        validateRegisterRequest(request);
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
                request.getProjectId(),
                text(request.getActorId()),
                text(request.getSessionId()),
                text(request.getRunId()),
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

    private void validateRegisterRequest(AgentToolActionApprovalFactRegisterRequest request) {
        if (request == null || blank(request.getApprovalFactId())) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST, "approvalFactId 不能为空");
        }
        if (!safeFactId(request.getApprovalFactId().trim())) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "approvalFactId 只能使用低敏短 ID，不能包含 URL、SQL、prompt 或凭证片段");
        }
        if (request.getTenantId() == null || request.getProjectId() == null) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "审批事实必须绑定 tenantId 和 projectId");
        }
        if (blank(request.getActorId()) || blank(request.getSessionId()) || blank(request.getRunId())
                || blank(request.getCommandId()) || blank(request.getToolCode())) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "审批事实必须绑定 actorId、sessionId、runId、commandId 和 toolCode");
        }
    }

    private String scopeIssue(AgentToolActionApprovalFactRecord record,
                              AgentToolActionApprovalFactEvaluateRequest request) {
        if (!Objects.equals(record.tenantId(), request.getTenantId())) {
            return "审批事实 tenantId 与当前任务不一致。";
        }
        if (!Objects.equals(record.projectId(), request.getProjectId())) {
            return "审批事实 projectId 与当前任务不一致。";
        }
        if (!same(record.actorId(), request.getActorId())) {
            return "审批事实 actorId 与当前任务不一致。";
        }
        if (!same(record.sessionId(), request.getSessionId()) || !same(record.runId(), request.getRunId())) {
            return "审批事实 sessionId/runId 与当前任务不一致。";
        }
        if (!same(record.commandId(), request.getCommandId())) {
            return "审批事实 commandId 与当前任务不一致。";
        }
        if (!same(record.toolCode(), request.getToolCode())) {
            return "审批事实 toolCode 与当前任务不一致。";
        }
        return null;
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
