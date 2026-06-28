/**
 * @Author : Cui
 * @Date: 2026/06/07 15:34
 * @Description DataSmart Govern Backend - AgentToolActionFactEvidenceVerifier.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionCommandProposalRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionCommandProposalResponse;
import com.czh.datasmart.govern.agent.service.execution.confirmation.AgentRunToolDagConfirmationAccessSupport;
import com.czh.datasmart.govern.agent.service.execution.confirmation.AgentRunToolDagConfirmationRecord;
import com.czh.datasmart.govern.agent.service.execution.confirmation.AgentRunToolDagConfirmationStatus;
import com.czh.datasmart.govern.agent.service.execution.confirmation.AgentRunToolDagConfirmationStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 工具动作审批/澄清事实证据复核器。
 *
 * <p>proposal 阶段会把 approvalConfirmationId、clarificationFactId 当成低敏线索，但不能证明审批或澄清事实
 * 在服务端真实存在。当前组件先固定 writer 前的安全边界：fact id 必须是短文本、非 URL、非 JSON、非 SQL、
 * 非凭证片段，并且只使用允许字符。后续接入 confirmation store 或 permission-admin 后，可以在本组件中继续
 * 增加“按 ID 回查事实状态、过期时间、操作者、run/contract 绑定”的强复核。</p>
 */
@Component
public class AgentToolActionFactEvidenceVerifier {

    private static final String STATUS_VERIFIED_OR_NOT_REQUIRED = "VERIFIED_OR_NOT_REQUIRED";
    private static final String STATUS_REJECTED = "REJECTED";
    private static final String DAG_CONFIRMATION_PREFIX = "dag-confirmation:";

    /**
     * DAG selected-node confirmation 仓储。
     *
     * <p>当前系统里已经存在一类低敏确认事实：用户确认某次 Tool DAG dry-run 的某些节点可入箱。
     * 当 writer 请求携带 {@code dag-confirmation:...} 这类 ID 时，本 verifier 会回查该仓储，确认它不是
     * 调用方随手拼出来的字符串。普通 permission-admin 审批单、澄清单等未来事实源尚未接入时，仍只做形态校验，
     * 避免把一个局部仓储误当成所有审批事实的最终来源。</p>
     */
    private final AgentRunToolDagConfirmationStore confirmationStore;

    /**
     * confirmation 访问范围判断组件。
     *
     * <p>writer 不是只要知道 confirmationId 存在就能继续；还要确认这条事实属于当前租户、项目和操作者可见范围。
     * 这能防止跨项目复制 confirmationId 后绕过前端确认页或智能网关治理。</p>
     */
    private final AgentRunToolDagConfirmationAccessSupport confirmationAccessSupport;

    /**
     * 工具动作 payload 审批确认事实校验器。
     *
     * <p>`tool-action-confirmation:` 是当前阶段新增的强事实源：它不只是“ID 形态安全”，还要证明用户确认过的
     * payloadReference 已经由 Host 物化、仍在 TTL 内、并且绑定当前 tenant/project/actor/run/tool/graph/contract。
     * 将这部分逻辑拆到独立组件，是为了避免本类随着审批中心、澄清事实、ITSM 工单等事实源不断膨胀。</p>
     */
    private final AgentToolActionApprovalConfirmationEvidenceVerifier approvalConfirmationEvidenceVerifier;

    public AgentToolActionFactEvidenceVerifier() {
        this(null, new AgentRunToolDagConfirmationAccessSupport(), null);
    }

    public AgentToolActionFactEvidenceVerifier(AgentRunToolDagConfirmationStore confirmationStore,
                                               AgentRunToolDagConfirmationAccessSupport confirmationAccessSupport) {
        this(confirmationStore, confirmationAccessSupport, null);
    }

    @Autowired
    public AgentToolActionFactEvidenceVerifier(AgentRunToolDagConfirmationStore confirmationStore,
                                               AgentRunToolDagConfirmationAccessSupport confirmationAccessSupport,
                                               AgentToolActionApprovalConfirmationEvidenceVerifier
                                                       approvalConfirmationEvidenceVerifier) {
        this.confirmationStore = confirmationStore;
        this.confirmationAccessSupport = confirmationAccessSupport == null
                ? new AgentRunToolDagConfirmationAccessSupport()
                : confirmationAccessSupport;
        this.approvalConfirmationEvidenceVerifier = approvalConfirmationEvidenceVerifier;
    }

    /**
     * 校验请求中携带的人工事实 ID 是否适合进入 outbox 命令信封。
     *
     * @param request writer 请求体。
     * @param proposal proposal 响应，用于记录来源图和状态说明。
     * @return 低敏复核结果。`verifiedForWriter=false` 时 writer 必须停止。
     */
    public AgentToolActionFactEvidenceVerificationResult verify(
            AgentToolActionCommandProposalRequest request,
            AgentToolActionCommandProposalResponse proposal) {
        return verify(request, proposal, null);
    }

    /**
     * 校验请求携带的人工事实 ID 是否适合进入 outbox 命令信封。
     *
     * <p>相比双参重载，该方法额外接收 gateway 访问上下文，因此能够对 {@code dag-confirmation:...}
     * 做租户/项目/操作者范围复核。writer 应优先调用这个入口；双参入口保留给旧测试或不具备 header 上下文的内部调用。</p>
     *
     * @param request writer 请求体。
     * @param proposal proposal 响应，用于校验 confirmation 与当前 graph/run/session 的绑定关系。
     * @param accessContext 当前访问上下文，来自 gateway 可信 header。
     * @return 低敏复核结果。{@code verifiedForWriter=false} 时 writer 必须停止。
     */
    public AgentToolActionFactEvidenceVerificationResult verify(
            AgentToolActionCommandProposalRequest request,
            AgentToolActionCommandProposalResponse proposal,
            AgentRuntimeEventQueryAccessContext accessContext) {
        List<String> accepted = new ArrayList<>();
        List<String> issues = new ArrayList<>();
        verifyFactId("APPROVAL_CONFIRMATION", request == null ? null : request.approvalConfirmationId(), accepted, issues);
        verifyFactId("CLARIFICATION_FACT", request == null ? null : request.clarificationFactId(), accepted, issues);
        verifyDagConfirmationIfPresent(request, proposal, accessContext, accepted, issues);
        verifyToolActionApprovalConfirmationIfPresent(request, proposal, accessContext, accepted, issues);
        if (!issues.isEmpty()) {
            return new AgentToolActionFactEvidenceVerificationResult(
                    STATUS_REJECTED,
                    false,
                    List.copyOf(accepted),
                    List.copyOf(issues),
                    List.of("人工事实证据 ID 包含 URL、JSON、SQL、换行、疑似凭证或非法字符，不能进入 outbox。"),
                    List.of("请重新通过前端确认页、permission-admin 或澄清流程生成服务端事实 ID。")
            );
        }
        if (accepted.isEmpty()) {
            accepted.add("NO_APPROVAL_OR_CLARIFICATION_FACT_REQUIRED_BY_READY_PROPOSAL");
        }
        return new AgentToolActionFactEvidenceVerificationResult(
                STATUS_VERIFIED_OR_NOT_REQUIRED,
                true,
                List.copyOf(accepted),
                List.of(),
                List.of("审批/澄清事实 ID 已完成低敏形态复核；当前 READY proposal 不需要额外人工事实，或传入 ID 形态安全。"),
                List.of("后续 writer/dispatcher 仍应回查 confirmation store 或 permission-admin，确认事实存在、未过期且绑定当前 graph/contract。")
        );
    }

    private void verifyFactId(String factType,
                              String rawId,
                              List<String> accepted,
                              List<String> issues) {
        String factId = safeText(rawId);
        if (factId == null) {
            return;
        }
        List<String> riskIssues = factIdRiskIssues(factType, factId);
        if (riskIssues.isEmpty()) {
            accepted.add(factType + "_ID:" + factId);
        } else {
            issues.addAll(riskIssues);
        }
    }

    /**
     * 对明确属于 DAG selected-node 的确认事实做真实仓储回查。
     *
     * <p>为什么只识别 {@code dag-confirmation:} 前缀：商业产品里审批事实可能来自多个系统，
     * 例如 permission-admin 审批单、数据资产授权单、外部 ITSM 工单、用户澄清问答记录等。当前仓储只覆盖
     * agent-runtime 自己生成的 DAG selected-node confirmation，因此不能把所有短文本 ID 都强制拿到这里查。
     * 通过前缀分流，可以先把已落地的事实源做强复核，同时为后续 permission-admin verifier 保留扩展口。</p>
     */
    private void verifyDagConfirmationIfPresent(AgentToolActionCommandProposalRequest request,
                                                AgentToolActionCommandProposalResponse proposal,
                                                AgentRuntimeEventQueryAccessContext accessContext,
                                                List<String> accepted,
                                                List<String> issues) {
        String confirmationId = safeText(request == null ? null : request.approvalConfirmationId());
        if (confirmationId == null || !confirmationId.startsWith(DAG_CONFIRMATION_PREFIX)) {
            return;
        }
        if (confirmationStore == null) {
            accepted.add("DAG_CONFIRMATION_STORE_NOT_CONFIGURED_FOR_WRITER_VERIFIER");
            return;
        }
        Optional<AgentRunToolDagConfirmationRecord> optionalRecord =
                confirmationStore.findByConfirmationId(confirmationId);
        if (optionalRecord.isEmpty()) {
            issues.add("DAG_CONFIRMATION_RECORD_NOT_FOUND");
            return;
        }
        AgentRunToolDagConfirmationRecord record = optionalRecord.get();
        if (!Boolean.TRUE.equals(record.confirmed())) {
            issues.add("DAG_CONFIRMATION_NOT_CONFIRMED");
        }
        if (record.status() != AgentRunToolDagConfirmationStatus.CONFIRMED) {
            issues.add("DAG_CONFIRMATION_STATUS_NOT_CONFIRMED");
        }
        if (record.expiresAt() != null && record.expiresAt().isBefore(Instant.now())) {
            issues.add("DAG_CONFIRMATION_EXPIRED");
        }
        if (!safeEquals(record.sessionId(), proposal == null ? null : proposal.sessionId())) {
            issues.add("DAG_CONFIRMATION_SESSION_MISMATCH");
        }
        if (!safeEquals(record.runId(), proposal == null ? null : proposal.runId())) {
            issues.add("DAG_CONFIRMATION_RUN_MISMATCH");
        }
        if (!sameText(record.tenantId(), proposal == null ? null : proposal.tenantId())) {
            issues.add("DAG_CONFIRMATION_TENANT_MISMATCH");
        }
        if (!sameText(record.projectId(), proposal == null ? null : proposal.projectId())) {
            issues.add("DAG_CONFIRMATION_PROJECT_MISMATCH");
        }
        if (!safeEquals(record.actorId(), proposal == null ? null : proposal.actorId())) {
            issues.add("DAG_CONFIRMATION_ACTOR_MISMATCH");
        }
        if (accessContext != null && accessContext.hasIdentity()
                && !confirmationAccessSupport.canRead(record, accessContext)) {
            issues.add("DAG_CONFIRMATION_CONTEXT_SCOPE_DENIED");
        }
        if (!issues.isEmpty()) {
            return;
        }
        accepted.add("DAG_CONFIRMATION_RECORD_FOUND:" + confirmationId);
        accepted.add("DAG_CONFIRMATION_METADATA_SCOPE_VERIFIED");
        if (request != null && safeText(request.policyVersion()) != null) {
            if (record.policyVersions().isEmpty()) {
                accepted.add("DAG_CONFIRMATION_POLICY_VERSION_NOT_SNAPSHOTTED");
            } else if (record.policyVersions().contains(request.policyVersion().trim())) {
                accepted.add("DAG_CONFIRMATION_POLICY_VERSION_MATCHED");
            } else {
                issues.add("DAG_CONFIRMATION_POLICY_VERSION_MISMATCH");
            }
        }
    }

    /**
     * 对工具动作 payload 审批确认事实做强校验。
     *
     * <p>通用 {@link #verifyFactId(String, String, List, List)} 只能证明 ID 没有 URL/JSON/SQL/换行/凭证片段风险；
     * 但 `tool-action-confirmation:` 这种事实已经由本模块落地了服务端 store，因此必须进一步回查确认记录。
     * 如果 Spring 运行时没有注入专用校验器，说明当前部署还处于迁移或测试模式，这里采取 fail-closed，避免把
     * 只做形态校验的 confirmationId 写入 outbox。</p>
     */
    private void verifyToolActionApprovalConfirmationIfPresent(
            AgentToolActionCommandProposalRequest request,
            AgentToolActionCommandProposalResponse proposal,
            AgentRuntimeEventQueryAccessContext accessContext,
            List<String> accepted,
            List<String> issues) {
        String confirmationId = safeText(request == null ? null : request.approvalConfirmationId());
        if (confirmationId == null
                || !confirmationId.startsWith(AgentToolActionApprovalConfirmationEvidenceVerifier.CONFIRMATION_PREFIX)) {
            return;
        }
        if (approvalConfirmationEvidenceVerifier == null) {
            issues.add("TOOL_ACTION_APPROVAL_CONFIRMATION_VERIFIER_NOT_CONFIGURED");
            return;
        }
        approvalConfirmationEvidenceVerifier.verifyIfPresent(request, proposal, accessContext, accepted, issues);
    }

    private List<String> factIdRiskIssues(String factType, String factId) {
        List<String> issues = new ArrayList<>();
        String normalized = factId.toLowerCase(Locale.ROOT);
        if (factId.length() > 128) {
            issues.add(factType + "_ID_TOO_LONG");
        }
        if (normalized.contains("http://") || normalized.contains("https://")) {
            issues.add(factType + "_ID_URL_NOT_ALLOWED");
        }
        if (normalized.contains("\n") || normalized.contains("\r")) {
            issues.add(factType + "_ID_CONTROL_CHARACTER_NOT_ALLOWED");
        }
        if (normalized.contains("{") || normalized.contains("}") || normalized.contains("[")
                || normalized.contains("select *") || normalized.contains("password=")) {
            issues.add(factType + "_ID_INLINE_PAYLOAD_OR_SECRET_NOT_ALLOWED");
        }
        if (!isSafeFactId(factId)) {
            issues.add(factType + "_ID_UNSAFE_CHARACTERS");
        }
        return issues;
    }

    /**
     * 限定 fact id 的字符集合。
     *
     * <p>允许冒号是为了支持未来 `confirmation:xxx`、`approval:tenant:xxx` 这类命名空间；
     * 不允许斜杠、问号、井号等 URL 常见字符，避免 fact id 被误用成外部地址。</p>
     */
    private boolean isSafeFactId(String value) {
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            boolean allowed = Character.isLetterOrDigit(current)
                    || current == '-'
                    || current == '_'
                    || current == '.'
                    || current == ':';
            if (!allowed) {
                return false;
            }
        }
        return true;
    }

    private boolean safeEquals(String left, String right) {
        String normalizedLeft = safeText(left);
        String normalizedRight = safeText(right);
        return normalizedLeft != null && normalizedLeft.equals(normalizedRight);
    }

    private boolean sameText(Long left, String right) {
        String normalizedRight = safeText(right);
        return left != null && normalizedRight != null && String.valueOf(left).equals(normalizedRight);
    }

    private String safeText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
