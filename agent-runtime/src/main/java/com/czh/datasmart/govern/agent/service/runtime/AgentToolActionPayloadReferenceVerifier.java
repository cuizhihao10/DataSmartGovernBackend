/**
 * @Author : Cui
 * @Date: 2026/06/07 15:34
 * @Description DataSmart Govern Backend - AgentToolActionPayloadReferenceVerifier.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionCommandProposalResponse;
import com.czh.datasmart.govern.agent.service.audit.AgentToolExecutionAuditRecord;
import com.czh.datasmart.govern.agent.service.audit.AgentToolExecutionAuditStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 工具动作 payloadReference 写入前复核器。
 *
 * <p>proposal 阶段已经做了一层“不像内联 payload”的粗筛，但 writer 阶段还需要更接近服务端边界的复核：
 * 引用必须能绑定当前 run/session/tenant/project，不能是任意 URL、SQL、JSON、数组或疑似凭证片段。
 * 该组件仍然不会读取真实载荷；它只判断引用是否具备进入 outbox 的最低可信度。
 * 对 `agent-payload:` 来说，5.57 之后如果 payload store 服务可用，还会回查服务端登记事实，
 * 让 writer 不再只相信调用方传来的字符串。</p>
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
     * 工具执行审计仓储。
     *
     * <p>这里不是为了读取 planArguments 原文，而是为了在 writer 写入前确认历史
     * {@code agent-tool-audit://.../plan-arguments} 引用确实存在，并且 audit 记录绑定的
     * session/run/tenant/project/actor 与当前 proposal 和访问上下文一致。这样可以避免调用方随手拼一个
     * 看似合法的 audit 引用，就把不存在或越权的 payloadReference 写进 durable outbox。</p>
     *
     * <p>该字段允许为空，是为了保持单元测试和早期迁移代码可以继续使用无参构造器；在 Spring 运行时，
     * {@link #AgentToolActionPayloadReferenceVerifier(AgentToolExecutionAuditStore, AgentToolActionPayloadStoreService)}
     * 会注入真实仓储。</p>
     */
    private final AgentToolExecutionAuditStore auditStore;

    /**
     * `agent-payload:` 服务端登记与判定服务。
     *
     * <p>该服务只返回低敏 verdict，不返回 payload body。它的存在让新工具动作链路可以从“结构合法”
     * 进一步升级为“服务端登记存在、作用域匹配、未过期”。如果为空，则保留早期结构校验模式，方便旧测试和迁移代码。</p>
     */
    private final AgentToolActionPayloadStoreService payloadStoreService;

    public AgentToolActionPayloadReferenceVerifier() {
        this(null, null);
    }

    public AgentToolActionPayloadReferenceVerifier(AgentToolExecutionAuditStore auditStore) {
        this(auditStore, null);
    }

    @Autowired
    public AgentToolActionPayloadReferenceVerifier(AgentToolExecutionAuditStore auditStore,
                                                   AgentToolActionPayloadStoreService payloadStoreService) {
        this.auditStore = auditStore;
        this.payloadStoreService = payloadStoreService;
    }

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
            return verifyAgentPayload(reference, proposal, accessContext);
        }
        if (reference.startsWith(AGENT_TOOL_AUDIT_PREFIX)) {
            return verifyAgentToolAudit(reference, proposal, accessContext);
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
     * 如果 payload store 服务已注入，本方法还会要求服务端已经登记过对应 envelope，并按
     * tenant/project/actor/tool/graph/contract 做低敏元数据复核。真实 payload body 仍不会在这里读取。</p>
     */
    private AgentToolActionPayloadReferenceVerificationResult verifyAgentPayload(
            String reference,
            AgentToolActionCommandProposalResponse proposal,
            AgentRuntimeEventQueryAccessContext accessContext) {
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
        if (payloadStoreService != null) {
            return fromPayloadStoreVerdict(payloadStoreService.verifyReference(reference, proposal, accessContext));
        }
        return verified(reference, "AGENT_PAYLOAD", List.of(
                "REFERENCE_PREFIX:agent-payload",
                "RUN_ID_BOUND:" + runId,
                "PAYLOAD_KEY_PRESENT",
                "PAYLOAD_STORE_NOT_CONFIGURED_FOR_WRITER_VERIFIER"
        ));
    }

    /**
     * 将 payload store verdict 转换成 writer 当前使用的通用复核结果。
     *
     * <p>这里不把 verdict 的 metadataDigest、payloadSize、参数名等扩展字段额外展开到 outbox writer DTO，
     * 只复用 acceptedEvidence/issueCodes/reasons/actions。这样可以避免 writer payload 在每一轮治理增强时不断膨胀，
     * 同时仍然保留足够的低敏证据说明“为什么这条引用被允许或拒绝”。</p>
     */
    private AgentToolActionPayloadReferenceVerificationResult fromPayloadStoreVerdict(
            AgentToolActionPayloadVerdict verdict) {
        return new AgentToolActionPayloadReferenceVerificationResult(
                verdict.status(),
                verdict.readableForWriter(),
                verdict.payloadReference(),
                verdict.referenceType(),
                verdict.acceptedEvidence(),
                verdict.issueCodes(),
                verdict.summaryReasons(),
                verdict.recommendedActions()
        );
    }

    /**
     * 校验历史 ASYNC_TASK 链路的 agent-tool-audit 引用。
     *
     * <p>这个协议已经被 task-management worker 使用。tool action writer 允许它存在，是为了后续兼容“外部工具动作
     * 复用已有审计参数快照”的场景，但必须确保 sessionId/runId 与当前 proposal 一致。</p>
     */
    private AgentToolActionPayloadReferenceVerificationResult verifyAgentToolAudit(
            String reference,
            AgentToolActionCommandProposalResponse proposal,
            AgentRuntimeEventQueryAccessContext accessContext) {
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
        return verifyAuditRecordBinding(reference, proposal, accessContext, parts);
    }

    /**
     * 对历史工具审计载荷引用做“存在性 + 归属”复核。
     *
     * <p>这一层是 5.55 相比 5.54 的关键增强：5.54 只证明字符串结构像一个受控引用，
     * 但无法证明 auditId 真的存在。现在如果仓储可用，就会读取 audit 记录的低敏元数据，
     * 校验它是否属于当前 session/run，并与 gateway 访问上下文中的 tenant/project/actor 对齐。</p>
     *
     * <p>注意：方法只使用 auditId、sessionId、runId、tenantId、projectId、actorId 等元数据，
     * 不访问 {@code planArguments}、SQL、prompt、样本数据或工具结果。真实参数读取仍然必须由 worker
     * 或 payload resolver 在执行前按需完成。</p>
     */
    private AgentToolActionPayloadReferenceVerificationResult verifyAuditRecordBinding(
            String reference,
            AgentToolActionCommandProposalResponse proposal,
            AgentRuntimeEventQueryAccessContext accessContext,
            String[] parts) {
        if (auditStore == null) {
            return verified(reference, "AGENT_TOOL_AUDIT", List.of(
                    "REFERENCE_PREFIX:agent-tool-audit",
                    "SESSION_ID_BOUND:" + proposal.sessionId(),
                    "RUN_ID_BOUND:" + proposal.runId(),
                    "PAYLOAD_KIND:plan-arguments",
                    "AUDIT_STORE_NOT_CONFIGURED_FOR_WRITER_VERIFIER"
            ));
        }
        String auditId = parts[2];
        Optional<AgentToolExecutionAuditRecord> optionalRecord = auditStore.findById(auditId);
        if (optionalRecord.isEmpty()) {
            return rejected(reference, "AGENT_TOOL_AUDIT", List.of("AGENT_TOOL_AUDIT_RECORD_NOT_FOUND"),
                    "agent-tool-audit 引用指向的审计记录不存在，不能写入 outbox。");
        }
        AgentToolExecutionAuditRecord record = optionalRecord.get();
        List<String> issues = new ArrayList<>();
        if (!safeEquals(record.getSessionId(), proposal.sessionId())) {
            issues.add("AGENT_TOOL_AUDIT_RECORD_SESSION_MISMATCH");
        }
        if (!safeEquals(record.getRunId(), proposal.runId())) {
            issues.add("AGENT_TOOL_AUDIT_RECORD_RUN_MISMATCH");
        }
        if (!sameText(record.getTenantId(), proposal.tenantId())) {
            issues.add("AGENT_TOOL_AUDIT_RECORD_TENANT_MISMATCH");
        }
        if (!sameText(record.getProjectId(), proposal.projectId())) {
            issues.add("AGENT_TOOL_AUDIT_RECORD_PROJECT_MISMATCH");
        }
        if (!safeEquals(record.getActorId(), proposal.actorId())) {
            issues.add("AGENT_TOOL_AUDIT_RECORD_ACTOR_MISMATCH");
        }
        if (accessContext != null && accessContext.hasIdentity()) {
            if (!sameLong(record.getTenantId(), accessContext.tenantId())) {
                issues.add("AGENT_TOOL_AUDIT_RECORD_CONTEXT_TENANT_MISMATCH");
            }
            if (accessContext.explicitProjectScope()
                    && !accessContext.authorizedProjectIdsAsStrings().contains(String.valueOf(record.getProjectId()))) {
                issues.add("AGENT_TOOL_AUDIT_RECORD_CONTEXT_PROJECT_DENIED");
            }
            if ("SELF".equals(accessContext.normalizedDataScopeLevel())
                    && !safeEquals(record.getActorId(), String.valueOf(accessContext.actorId()))) {
                issues.add("AGENT_TOOL_AUDIT_RECORD_CONTEXT_ACTOR_DENIED");
            }
        }
        if (!issues.isEmpty()) {
            return rejected(reference, "AGENT_TOOL_AUDIT", issues,
                    "agent-tool-audit 审计记录存在，但与当前 proposal 或访问范围不一致。");
        }
        return verified(reference, "AGENT_TOOL_AUDIT", List.of(
                "REFERENCE_PREFIX:agent-tool-audit",
                "SESSION_ID_BOUND:" + proposal.sessionId(),
                "RUN_ID_BOUND:" + proposal.runId(),
                "PAYLOAD_KIND:plan-arguments",
                "AUDIT_RECORD_FOUND:" + auditId,
                "AUDIT_METADATA_SCOPE_VERIFIED"
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

    private boolean sameText(Long left, String right) {
        String normalizedRight = safeText(right);
        return left != null && normalizedRight != null && String.valueOf(left).equals(normalizedRight);
    }

    private boolean sameLong(Long left, Long right) {
        return left != null && left.equals(right);
    }

    private String safeText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
