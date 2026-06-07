/**
 * @Author : Cui
 * @Date: 2026/06/07 15:34
 * @Description DataSmart Govern Backend - AgentToolActionPayloadReferenceVerifier.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionCommandProposalResponse;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 工具动作 payloadReference 写入前复核器。
 *
 * <p>proposal 阶段已经做了一层“不像内联 payload”的粗筛，但 writer 阶段还需要更接近服务端边界的复核：
 * 引用必须能绑定当前 run/session/tenant/project，不能是任意 URL、SQL、JSON、数组或疑似凭证片段。
 * 该组件仍然不会读取真实载荷；它只判断引用是否具备进入 outbox 的最低结构可信度。</p>
 *
 * <p>当前支持四类引用：</p>
 * <p>1. `agent-payload:{runId}/{payloadKey}`：新工具动作链路的受控载荷引用；</p>
 * <p>2. `agent-tool-audit://{sessionId}/{runId}/{auditId}/plan-arguments`：历史 ASYNC_TASK 审计参数引用；</p>
 * <p>3. `artifact-ref:{runId}/{artifactId}`：未来 artifact manifest/结果引用的预留协议；</p>
 * <p>4. `payload-ref:{tenantOrRunScope}/{...}`：未来统一 payload store 的预留协议，当前要求字符串内显式出现 runId。</p>
 */
@Component
public class AgentToolActionPayloadReferenceVerifier {

    private static final String STATUS_VERIFIED = "VERIFIED";
    private static final String STATUS_REJECTED = "REJECTED";
    private static final String STATUS_MISSING = "MISSING";
    private static final String AGENT_PAYLOAD_PREFIX = "agent-payload:";
    private static final String AGENT_TOOL_AUDIT_PREFIX = "agent-tool-audit://";
    private static final String ARTIFACT_REF_PREFIX = "artifact-ref:";
    private static final String PAYLOAD_REF_PREFIX = "payload-ref:";
    private static final String PLAN_ARGUMENTS = "plan-arguments";

    /**
     * 校验 payloadReference 是否适合进入 durable command outbox。
     *
     * @param proposal 已通过 proposal 的低敏结果。
     * @param accessContext 当前访问上下文；本阶段只用于补充审计说明，真实鉴权仍在后续 payload store。
     * @return 复核结果。`verifiedForWriter=false` 时 writer 必须停止。
     */
    public AgentToolActionPayloadReferenceVerificationResult verify(
            AgentToolActionCommandProposalResponse proposal,
            AgentRuntimeEventQueryAccessContext accessContext) {
        String reference = safeText(proposal == null ? null : proposal.payloadReference());
        if (reference == null) {
            return missing("PAYLOAD_REFERENCE_MISSING", "payloadReference 为空，writer 无法定位受控载荷。");
        }
        List<String> issueCodes = commonRiskIssues(reference);
        if (!issueCodes.isEmpty()) {
            return rejected(reference, "UNKNOWN", issueCodes,
                    "payloadReference 包含 URL、SQL、JSON、换行、疑似凭证或超过长度上限，不能进入 outbox。");
        }
        if (reference.startsWith(AGENT_PAYLOAD_PREFIX)) {
            return verifyAgentPayload(reference, proposal);
        }
        if (reference.startsWith(AGENT_TOOL_AUDIT_PREFIX)) {
            return verifyAgentToolAudit(reference, proposal);
        }
        if (reference.startsWith(ARTIFACT_REF_PREFIX)) {
            return verifyRunScopedReference(reference, ARTIFACT_REF_PREFIX, "ARTIFACT_REFERENCE", proposal);
        }
        if (reference.startsWith(PAYLOAD_REF_PREFIX)) {
            return verifyPayloadStoreReference(reference, proposal);
        }
        return rejected(reference, "UNKNOWN", List.of("PAYLOAD_REFERENCE_UNSUPPORTED_PREFIX"),
                "payloadReference 使用了当前 writer 不支持的协议前缀。");
    }

    /**
     * 校验新工具动作链路的 agent-payload 引用。
     *
     * <p>该协议要求第一段就是当前 runId，这样同一租户下的不同 run 不能互相复用载荷引用。
     * 后续真实 payload store 还要继续按 tenant/project/actor/use-case 做二次鉴权。</p>
     */
    private AgentToolActionPayloadReferenceVerificationResult verifyAgentPayload(
            String reference,
            AgentToolActionCommandProposalResponse proposal) {
        String body = reference.substring(AGENT_PAYLOAD_PREFIX.length());
        String[] parts = body.split("/", -1);
        List<String> issues = new ArrayList<>();
        if (parts.length < 2) {
            issues.add("AGENT_PAYLOAD_REFERENCE_REQUIRES_RUN_AND_KEY");
        }
        addSegmentIssues(parts, issues);
        String runId = safeText(proposal.runId());
        if (runId == null || parts.length == 0 || !runId.equals(parts[0])) {
            issues.add("PAYLOAD_REFERENCE_RUN_ID_MISMATCH");
        }
        if (!issues.isEmpty()) {
            return rejected(reference, "AGENT_PAYLOAD", issues,
                    "agent-payload 引用必须绑定当前 runId，并包含安全 payload key。");
        }
        return verified(reference, "AGENT_PAYLOAD", List.of(
                "REFERENCE_PREFIX:agent-payload",
                "RUN_ID_BOUND:" + runId,
                "PAYLOAD_KEY_PRESENT"
        ));
    }

    /**
     * 校验历史 ASYNC_TASK 链路的 agent-tool-audit 引用。
     *
     * <p>这个协议已经被 task-management worker 使用。tool action writer 允许它存在，是为了后续兼容“外部工具动作
     * 复用已有审计参数快照”的场景，但必须确保 sessionId/runId 与当前 proposal 一致。</p>
     */
    private AgentToolActionPayloadReferenceVerificationResult verifyAgentToolAudit(
            String reference,
            AgentToolActionCommandProposalResponse proposal) {
        String body = reference.substring(AGENT_TOOL_AUDIT_PREFIX.length());
        String[] parts = body.split("/", -1);
        List<String> issues = new ArrayList<>();
        if (parts.length != 4) {
            issues.add("AGENT_TOOL_AUDIT_REFERENCE_REQUIRES_FOUR_SEGMENTS");
        }
        addSegmentIssues(parts, issues);
        if (parts.length == 4 && !PLAN_ARGUMENTS.equals(parts[3])) {
            issues.add("AGENT_TOOL_AUDIT_PAYLOAD_KIND_UNSUPPORTED");
        }
        if (parts.length >= 2 && !safeEquals(proposal.sessionId(), parts[0])) {
            issues.add("PAYLOAD_REFERENCE_SESSION_ID_MISMATCH");
        }
        if (parts.length >= 2 && !safeEquals(proposal.runId(), parts[1])) {
            issues.add("PAYLOAD_REFERENCE_RUN_ID_MISMATCH");
        }
        if (!issues.isEmpty()) {
            return rejected(reference, "AGENT_TOOL_AUDIT", issues,
                    "agent-tool-audit 引用必须绑定当前 session/run，并且 payloadKind 只能是 plan-arguments。");
        }
        return verified(reference, "AGENT_TOOL_AUDIT", List.of(
                "REFERENCE_PREFIX:agent-tool-audit",
                "SESSION_ID_BOUND:" + proposal.sessionId(),
                "RUN_ID_BOUND:" + proposal.runId(),
                "PAYLOAD_KIND:plan-arguments"
        ));
    }

    private AgentToolActionPayloadReferenceVerificationResult verifyRunScopedReference(
            String reference,
            String prefix,
            String referenceType,
            AgentToolActionCommandProposalResponse proposal) {
        String body = reference.substring(prefix.length());
        String[] parts = body.split("/", -1);
        List<String> issues = new ArrayList<>();
        if (parts.length < 2) {
            issues.add(referenceType + "_REQUIRES_RUN_AND_KEY");
        }
        addSegmentIssues(parts, issues);
        if (parts.length > 0 && !safeEquals(proposal.runId(), parts[0])) {
            issues.add("PAYLOAD_REFERENCE_RUN_ID_MISMATCH");
        }
        if (!issues.isEmpty()) {
            return rejected(reference, referenceType, issues,
                    referenceType + " 引用必须以当前 runId 作为第一段。");
        }
        return verified(reference, referenceType, List.of(
                "REFERENCE_PREFIX:" + prefix.replace(":", ""),
                "RUN_ID_BOUND:" + proposal.runId()
        ));
    }

    private AgentToolActionPayloadReferenceVerificationResult verifyPayloadStoreReference(
            String reference,
            AgentToolActionCommandProposalResponse proposal) {
        List<String> issues = new ArrayList<>();
        String body = reference.substring(PAYLOAD_REF_PREFIX.length());
        String[] parts = body.split("/", -1);
        String runId = safeText(proposal.runId());
        addSegmentIssues(parts, issues);
        if (runId == null || !body.contains(runId)) {
            issues.add("PAYLOAD_STORE_REFERENCE_MUST_INCLUDE_RUN_ID");
        }
        if (!issues.isEmpty()) {
            return rejected(reference, "PAYLOAD_STORE_REFERENCE", issues,
                    "payload-ref 引用当前必须显式包含 runId，后续统一 payload store 会进一步增强结构化解析。");
        }
        return verified(reference, "PAYLOAD_STORE_REFERENCE", List.of(
                "REFERENCE_PREFIX:payload-ref",
                "RUN_ID_VISIBLE:" + proposal.runId()
        ));
    }

    private void addSegmentIssues(String[] parts, List<String> issues) {
        for (String part : parts) {
            String text = safeText(part);
            if (text == null) {
                issues.add("PAYLOAD_REFERENCE_EMPTY_SEGMENT");
                continue;
            }
            if (text.length() > 96) {
                issues.add("PAYLOAD_REFERENCE_SEGMENT_TOO_LONG");
            }
            if (!isSafeSegment(text)) {
                issues.add("PAYLOAD_REFERENCE_SEGMENT_UNSAFE_CHARACTERS");
            }
        }
    }

    private List<String> commonRiskIssues(String reference) {
        List<String> issues = new ArrayList<>();
        String normalized = reference.toLowerCase(Locale.ROOT);
        if (reference.length() > 256) {
            issues.add("PAYLOAD_REFERENCE_TOO_LONG");
        }
        if (normalized.contains("http://") || normalized.contains("https://")) {
            issues.add("PAYLOAD_REFERENCE_URL_NOT_ALLOWED");
        }
        if (normalized.contains("\n") || normalized.contains("\r")) {
            issues.add("PAYLOAD_REFERENCE_CONTROL_CHARACTER_NOT_ALLOWED");
        }
        if (normalized.contains("select *") || normalized.contains("password=")) {
            issues.add("PAYLOAD_REFERENCE_INLINE_SECRET_OR_SQL");
        }
        if (normalized.contains("{") || normalized.contains("}") || normalized.contains("[")) {
            issues.add("PAYLOAD_REFERENCE_INLINE_PAYLOAD_NOT_ALLOWED");
        }
        return issues;
    }

    private AgentToolActionPayloadReferenceVerificationResult verified(
            String reference,
            String referenceType,
            List<String> acceptedEvidence) {
        return new AgentToolActionPayloadReferenceVerificationResult(
                STATUS_VERIFIED,
                true,
                reference,
                referenceType,
                acceptedEvidence,
                List.of(),
                List.of("payloadReference 已通过 writer 前结构复核，但真实参数仍必须由后续服务端 payload store 二次鉴权读取。"),
                List.of("继续进入 outbox writer；后续 worker/inbox 必须根据引用重新读取并校验参数快照。")
        );
    }

    private AgentToolActionPayloadReferenceVerificationResult missing(String issueCode, String reason) {
        return new AgentToolActionPayloadReferenceVerificationResult(
                STATUS_MISSING,
                false,
                null,
                null,
                List.of(),
                List.of(issueCode),
                List.of(reason),
                List.of("请重新生成 command proposal，并提交受控 payloadReference。")
        );
    }

    private AgentToolActionPayloadReferenceVerificationResult rejected(
            String reference,
            String referenceType,
            List<String> issueCodes,
            String reason) {
        return new AgentToolActionPayloadReferenceVerificationResult(
                STATUS_REJECTED,
                false,
                reference,
                referenceType,
                List.of(),
                List.copyOf(issueCodes),
                List.of(reason),
                List.of("不要写入 outbox；请重新生成绑定当前 run/session 的受控 payloadReference。")
        );
    }

    private boolean isSafeSegment(String value) {
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

    private String safeText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
