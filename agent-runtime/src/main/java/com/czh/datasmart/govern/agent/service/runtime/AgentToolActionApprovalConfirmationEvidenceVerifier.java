/**
 * @Author : Cui
 * @Date: 2026/06/28 22:20
 * @Description DataSmart Govern Backend - AgentToolActionApprovalConfirmationEvidenceVerifier.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionCommandProposalRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionCommandProposalResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * `tool-action-confirmation:` 审批确认事实强校验器。
 *
 * <p>主 {@link AgentToolActionFactEvidenceVerifier} 已经负责通用 factId 形态检查和 DAG confirmation 校验。
 * 本类只处理新落地的工具动作 payload 确认事实，避免主 verifier 随着审批、澄清、权限中心、ITSM 等事实源不断膨胀。
 * 这也是当前项目“解耦 + 单文件尽量低于 500 行”的具体落地方式。</p>
 *
 * <p>安全原则：只把低敏证据写入 acceptedEvidence，例如 confirmationId、metadata scope、payload body 可用性；
 * 不返回 payloadBody、工具参数、SQL、prompt、样本数据、模型输出、凭证或内部 endpoint。真正读取 payload body 的动作
 * 必须留到 executor，并且 executor 还要重新校验同样的 tenant/project/actor/run/tool/contract 绑定。</p>
 */
@Component
@RequiredArgsConstructor
public class AgentToolActionApprovalConfirmationEvidenceVerifier {

    public static final String CONFIRMATION_PREFIX = "tool-action-confirmation:";

    private final AgentToolActionApprovalConfirmationStore confirmationStore;

    /**
     * 如果请求携带 `tool-action-confirmation:`，则执行强回查校验。
     *
     * @param request writer 请求，提供 approvalConfirmationId 和 policyVersion。
     * @param proposal 当前 proposal，用于校验确认事实是否绑定同一 graph/contract/run/tool。
     * @param accessContext gateway 可信访问上下文，用于 tenant/project/SELF 范围校验。
     * @param accepted 低敏通过证据输出列表。
     * @param issues 阻断原因输出列表。
     */
    public void verifyIfPresent(AgentToolActionCommandProposalRequest request,
                                AgentToolActionCommandProposalResponse proposal,
                                AgentRuntimeEventQueryAccessContext accessContext,
                                List<String> accepted,
                                List<String> issues) {
        String confirmationId = safeText(request == null ? null : request.approvalConfirmationId());
        if (confirmationId == null || !confirmationId.startsWith(CONFIRMATION_PREFIX)) {
            return;
        }
        if (confirmationStore == null) {
            issues.add("TOOL_ACTION_APPROVAL_CONFIRMATION_STORE_NOT_CONFIGURED");
            return;
        }
        confirmationStore.findByConfirmationId(confirmationId)
                .ifPresentOrElse(
                        record -> verifyRecord(record, request, proposal, accessContext, accepted, issues),
                        () -> issues.add("TOOL_ACTION_APPROVAL_CONFIRMATION_RECORD_NOT_FOUND")
                );
    }

    /**
     * 校验确认事实和当前 writer 上下文的一致性。
     *
     * <p>这里的每个分支都对应真实产品中的一类绕过风险：跨 run 复用、跨项目复制 confirmationId、确认后 payload 过期、
     * 策略版本变化、用未物化 payload 冒充可执行草案等。任何一个条件不满足都必须阻断 outbox 写入。</p>
     */
    private void verifyRecord(AgentToolActionApprovalConfirmationRecord record,
                              AgentToolActionCommandProposalRequest request,
                              AgentToolActionCommandProposalResponse proposal,
                              AgentRuntimeEventQueryAccessContext accessContext,
                              List<String> accepted,
                              List<String> issues) {
        if (!Boolean.TRUE.equals(record.confirmed())) {
            issues.add("TOOL_ACTION_APPROVAL_CONFIRMATION_NOT_CONFIRMED");
        }
        if (record.status() != AgentToolActionApprovalConfirmationStatus.CONFIRMED) {
            issues.add("TOOL_ACTION_APPROVAL_CONFIRMATION_STATUS_NOT_CONFIRMED");
        }
        if (record.expired(Instant.now())) {
            issues.add("TOOL_ACTION_APPROVAL_CONFIRMATION_EXPIRED");
        }
        if (!safeEquals(record.payloadReference(), proposal == null ? null : proposal.payloadReference())) {
            issues.add("TOOL_ACTION_APPROVAL_PAYLOAD_REFERENCE_MISMATCH");
        }
        if (!safeEquals(record.runId(), proposal == null ? null : proposal.runId())) {
            issues.add("TOOL_ACTION_APPROVAL_RUN_MISMATCH");
        }
        if (!safeEquals(record.tenantId(), proposal == null ? null : proposal.tenantId())) {
            issues.add("TOOL_ACTION_APPROVAL_TENANT_MISMATCH");
        }
        if (!safeEquals(record.projectId(), proposal == null ? null : proposal.projectId())) {
            issues.add("TOOL_ACTION_APPROVAL_PROJECT_MISMATCH");
        }
        if (!safeEquals(record.actorId(), proposal == null ? null : proposal.actorId())) {
            issues.add("TOOL_ACTION_APPROVAL_ACTOR_MISMATCH");
        }
        if (!safeEquals(record.toolName(), proposal == null ? null : proposal.toolName())) {
            issues.add("TOOL_ACTION_APPROVAL_TOOL_MISMATCH");
        }
        if (!safeEquals(record.graphId(), proposal == null ? null : proposal.graphId())) {
            issues.add("TOOL_ACTION_APPROVAL_GRAPH_MISMATCH");
        }
        if (!safeEquals(record.contractId(), proposal == null ? null : proposal.contractId())) {
            issues.add("TOOL_ACTION_APPROVAL_CONTRACT_MISMATCH");
        }
        if (request != null && safeText(request.policyVersion()) != null
                && !safeEquals(record.policyVersion(), request.policyVersion())) {
            issues.add("TOOL_ACTION_APPROVAL_POLICY_VERSION_MISMATCH");
        }
        if (!Boolean.TRUE.equals(record.payloadBodyAvailable()) || record.payloadSizeBytes() <= 0) {
            issues.add("TOOL_ACTION_APPROVAL_PAYLOAD_BODY_NOT_AVAILABLE");
        }
        verifyAccessContext(record, accessContext, issues);
        if (!issues.isEmpty()) {
            return;
        }
        accepted.add("TOOL_ACTION_APPROVAL_CONFIRMATION_RECORD_FOUND:" + record.confirmationId());
        accepted.add("TOOL_ACTION_APPROVAL_METADATA_SCOPE_VERIFIED");
        accepted.add("TOOL_ACTION_APPROVAL_PAYLOAD_REFERENCE_MATCHED");
        accepted.add("TOOL_ACTION_APPROVAL_PAYLOAD_BODY_AVAILABLE");
        if (safeText(record.policyVersion()) != null) {
            accepted.add("TOOL_ACTION_APPROVAL_POLICY_VERSION_MATCHED");
        }
        if (safeText(record.payloadMetadataDigest()) != null) {
            accepted.add("TOOL_ACTION_APPROVAL_PAYLOAD_METADATA_DIGEST:" + record.payloadMetadataDigest());
        }
    }

    /**
     * 校验 gateway 可信访问上下文。
     *
     * <p>proposal 字段证明“确认事实和图/工具绑定一致”；accessContext 证明“当前请求方仍然有权使用这条事实”。
     * 两者都需要满足，否则跨项目复制 confirmationId 或 SELF 范围越权都可能绕过前端确认页。</p>
     */
    private void verifyAccessContext(AgentToolActionApprovalConfirmationRecord record,
                                     AgentRuntimeEventQueryAccessContext accessContext,
                                     List<String> issues) {
        if (accessContext == null || !accessContext.hasIdentity()) {
            return;
        }
        if (!safeEquals(record.tenantId(), String.valueOf(accessContext.tenantId()))) {
            issues.add("TOOL_ACTION_APPROVAL_CONTEXT_TENANT_MISMATCH");
        }
        if (accessContext.explicitProjectScope()
                && !accessContext.authorizedProjectIdsAsStrings().contains(safeText(record.projectId()))) {
            issues.add("TOOL_ACTION_APPROVAL_CONTEXT_PROJECT_DENIED");
        }
        if ("SELF".equals(accessContext.normalizedDataScopeLevel())
                && !safeEquals(record.actorId(), String.valueOf(accessContext.actorId()))) {
            issues.add("TOOL_ACTION_APPROVAL_CONTEXT_ACTOR_DENIED");
        }
    }

    private boolean safeEquals(String left, String right) {
        String normalizedLeft = safeText(left);
        String normalizedRight = safeText(right);
        return normalizedLeft != null && normalizedLeft.equals(normalizedRight);
    }

    private String safeText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
