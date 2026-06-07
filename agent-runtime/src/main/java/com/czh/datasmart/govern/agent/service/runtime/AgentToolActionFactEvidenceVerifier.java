/**
 * @Author : Cui
 * @Date: 2026/06/07 15:34
 * @Description DataSmart Govern Backend - AgentToolActionFactEvidenceVerifier.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionCommandProposalRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionCommandProposalResponse;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
        List<String> accepted = new ArrayList<>();
        List<String> issues = new ArrayList<>();
        verifyFactId("APPROVAL_CONFIRMATION", request == null ? null : request.approvalConfirmationId(), accepted, issues);
        verifyFactId("CLARIFICATION_FACT", request == null ? null : request.clarificationFactId(), accepted, issues);
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

    private String safeText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
