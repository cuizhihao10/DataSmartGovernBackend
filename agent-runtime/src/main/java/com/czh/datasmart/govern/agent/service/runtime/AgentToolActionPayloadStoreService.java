/**
 * @Author : Cui
 * @Date: 2026/06/11 00:00
 * @Description DataSmart Govern Backend - AgentToolActionPayloadStoreService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionCommandProposalRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionCommandProposalResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * 工具动作 payload store 应用服务。
 *
 * <p>该服务位于 writer/verifier 与底层 {@link AgentToolActionPayloadStore} 之间，负责把 `agent-payload:` 的
 * 业务语义收口在一个地方：引用解析、服务端登记、租户/项目/操作者绑定、过期策略和低敏 verdict 生成。
 * 这样后续如果把内存 store 替换成 MySQL、Redis、对象存储或加密 vault，不需要改动 outbox writer 和
 * task-management 消费契约。</p>
 *
 * <p>当前阶段只登记“payload envelope 元数据”，不物化真实工具参数。原因是现在的 command proposal 请求本身
 * 不允许携带原始 arguments，writer 也不能从事件投影中反向恢复敏感正文。先登记元数据可以把引用存在性、
 * 作用域和过期语义立住；后续 dedicated executor 接入时，再把真实 body 写入同一个 store 或其生产级实现。</p>
 */
@Service
@RequiredArgsConstructor
public class AgentToolActionPayloadStoreService {

    private static final String AGENT_PAYLOAD_PREFIX = "agent-payload:";
    private static final String STATUS_VERIFIED = "VERIFIED";
    private static final String STATUS_REJECTED = "REJECTED";
    private static final String STATUS_MISSING = "MISSING";
    private static final String STATUS_EXPIRED = "EXPIRED";
    private static final Duration DEFAULT_ENVELOPE_TTL = Duration.ofHours(2);

    private final AgentToolActionPayloadStore payloadStore;

    /**
     * 为 READY proposal 登记 `agent-payload:` 元数据 envelope。
     *
     * <p>writer 在正式复核前调用该方法。它不会信任 request 中的真实参数，因为 request 根本不允许携带参数正文；
     * 它只把 proposal 已经计算出的低敏事实写入 payload store：tenant/project/actor/run/tool/graph/contract/policy。
     * 如果引用结构与当前 run 不一致，方法会保守地不登记，让后续 verifier 以 fail-closed 方式阻断写入。</p>
     *
     * @param proposal command proposal 低敏结果。
     * @param request writer 原始请求，仅用于未来扩展 clientRequestId 等诊断信息，当前不读取敏感字段。
     * @param accessContext gateway 可信访问上下文，用于避免越权请求在登记阶段制造 payload fact。
     * @return 当前引用对应的 payload 记录；非 `agent-payload:` 或登记条件不满足时返回空。
     */
    public Optional<AgentToolActionPayloadRecord> ensureEnvelope(
            AgentToolActionCommandProposalResponse proposal,
            AgentToolActionCommandProposalRequest request,
            AgentRuntimeEventQueryAccessContext accessContext) {
        String reference = safeText(proposal == null ? null : proposal.payloadReference());
        if (reference == null || !reference.startsWith(AGENT_PAYLOAD_PREFIX)) {
            return Optional.empty();
        }
        ParsedAgentPayloadReference parsed = parse(reference);
        if (!parsed.valid() || !safeEquals(parsed.runId(), proposal.runId()) || contextDenied(proposal, accessContext)) {
            return Optional.empty();
        }
        Instant now = Instant.now();
        AgentToolActionPayloadRecord record = new AgentToolActionPayloadRecord(
                reference,
                parsed.runId(),
                parsed.payloadKey(),
                safeText(proposal.tenantId()),
                safeText(proposal.projectId()),
                safeText(proposal.actorId()),
                safeText(proposal.toolName()),
                safeText(proposal.graphId()),
                safeText(proposal.contractId()),
                safeText(proposal.payloadPolicy()),
                List.of(),
                List.of(),
                false,
                0,
                metadataDigest(proposal, parsed, request),
                now,
                now.plus(DEFAULT_ENVELOPE_TTL),
                null
        );
        payloadStore.append(record);
        return payloadStore.findByReference(reference);
    }

    /**
     * 校验 `agent-payload:` 引用是否存在且属于当前 writer 上下文。
     *
     * <p>该方法是 5.57 的核心增强：以前 verifier 只能证明字符串形态安全；现在当 payload store 可用时，
     * 还必须证明服务端确实登记过这条引用，并且登记记录与 proposal 和 gateway 访问上下文一致。
     * 返回值仍然只包含低敏元数据，不会携带 payloadBody。</p>
     */
    public AgentToolActionPayloadVerdict verifyReference(
            String payloadReference,
            AgentToolActionCommandProposalResponse proposal,
            AgentRuntimeEventQueryAccessContext accessContext) {
        String reference = safeText(payloadReference);
        if (reference == null || !reference.startsWith(AGENT_PAYLOAD_PREFIX)) {
            return rejected(reference, null, null, List.of("AGENT_PAYLOAD_REFERENCE_UNSUPPORTED"),
                    "当前 payload store verifier 只处理 agent-payload 引用。");
        }
        ParsedAgentPayloadReference parsed = parse(reference);
        if (!parsed.valid()) {
            return rejected(reference, parsed, null, parsed.issueCodes(),
                    "agent-payload 引用必须包含 runId 和安全 payloadKey。");
        }
        Optional<AgentToolActionPayloadRecord> optionalRecord = payloadStore.findByReference(reference);
        if (optionalRecord.isEmpty()) {
            return new AgentToolActionPayloadVerdict(
                    STATUS_MISSING,
                    false,
                    reference,
                    "AGENT_PAYLOAD",
                    parsed.runId(),
                    parsed.payloadKey(),
                    null,
                    null,
                    null,
                    null,
                    false,
                    0,
                    null,
                    List.of(),
                    List.of("AGENT_PAYLOAD_RECORD_NOT_FOUND"),
                    List.of("agent-payload 引用尚未由服务端 payload store 登记，不能写入 outbox。"),
                    List.of("请重新通过 writer/proposal 链路生成受控载荷引用，或先让服务端物化 payload envelope。")
            );
        }
        AgentToolActionPayloadRecord record = optionalRecord.get();
        if (record.expired(Instant.now())) {
            return rejected(reference, parsed, record, List.of("AGENT_PAYLOAD_RECORD_EXPIRED"),
                    "agent-payload 服务端登记记录已经过期，不能继续写入或执行。");
        }
        List<String> issues = bindingIssues(record, proposal, accessContext);
        if (!issues.isEmpty()) {
            return rejected(reference, parsed, record, issues,
                    "agent-payload 服务端登记记录存在，但与当前 proposal 或访问范围不一致。");
        }
        List<String> acceptedEvidence = new ArrayList<>();
        acceptedEvidence.add("REFERENCE_PREFIX:agent-payload");
        acceptedEvidence.add("RUN_ID_BOUND:" + record.runId());
        acceptedEvidence.add("PAYLOAD_KEY_PRESENT");
        acceptedEvidence.add("AGENT_PAYLOAD_RECORD_FOUND");
        acceptedEvidence.add("AGENT_PAYLOAD_METADATA_SCOPE_VERIFIED");
        acceptedEvidence.add("PAYLOAD_METADATA_DIGEST:" + record.metadataDigest());
        acceptedEvidence.add(Boolean.TRUE.equals(record.payloadBodyAvailable())
                ? "PAYLOAD_BODY_AVAILABLE"
                : "PAYLOAD_BODY_NOT_MATERIALIZED");
        return new AgentToolActionPayloadVerdict(
                STATUS_VERIFIED,
                true,
                reference,
                "AGENT_PAYLOAD",
                record.runId(),
                record.payloadKey(),
                record.tenantId(),
                record.projectId(),
                record.actorId(),
                record.toolName(),
                record.payloadBodyAvailable(),
                record.payloadSizeBytes(),
                record.metadataDigest(),
                acceptedEvidence,
                List.of(),
                List.of("agent-payload 已由服务端 payload store 登记，并通过 tenant/project/actor/run/tool 元数据复核。"),
                List.of("可以继续写入 outbox；真实执行前 executor 仍必须在服务端读取 payload body 并重新校验过期和权限。")
        );
    }

    private List<String> bindingIssues(AgentToolActionPayloadRecord record,
                                       AgentToolActionCommandProposalResponse proposal,
                                       AgentRuntimeEventQueryAccessContext accessContext) {
        List<String> issues = new ArrayList<>();
        if (!safeEquals(record.runId(), proposal.runId())) {
            issues.add("AGENT_PAYLOAD_RECORD_RUN_MISMATCH");
        }
        if (!safeEquals(record.tenantId(), proposal.tenantId())) {
            issues.add("AGENT_PAYLOAD_RECORD_TENANT_MISMATCH");
        }
        if (!safeEquals(record.projectId(), proposal.projectId())) {
            issues.add("AGENT_PAYLOAD_RECORD_PROJECT_MISMATCH");
        }
        if (!safeEquals(record.actorId(), proposal.actorId())) {
            issues.add("AGENT_PAYLOAD_RECORD_ACTOR_MISMATCH");
        }
        if (!safeEquals(record.toolName(), proposal.toolName())) {
            issues.add("AGENT_PAYLOAD_RECORD_TOOL_MISMATCH");
        }
        if (!safeEquals(record.graphId(), proposal.graphId())) {
            issues.add("AGENT_PAYLOAD_RECORD_GRAPH_MISMATCH");
        }
        if (!safeEquals(record.contractId(), proposal.contractId())) {
            issues.add("AGENT_PAYLOAD_RECORD_CONTRACT_MISMATCH");
        }
        if (contextDenied(proposal, accessContext)) {
            issues.add("AGENT_PAYLOAD_RECORD_CONTEXT_DENIED");
        }
        return issues;
    }

    private boolean contextDenied(AgentToolActionCommandProposalResponse proposal,
                                  AgentRuntimeEventQueryAccessContext accessContext) {
        if (proposal == null || accessContext == null || !accessContext.hasIdentity()) {
            return false;
        }
        if (!safeEquals(proposal.tenantId(), String.valueOf(accessContext.tenantId()))) {
            return true;
        }
        if (accessContext.explicitProjectScope()
                && !accessContext.authorizedProjectIdsAsStrings().contains(safeText(proposal.projectId()))) {
            return true;
        }
        return "SELF".equals(accessContext.normalizedDataScopeLevel())
                && !safeEquals(proposal.actorId(), String.valueOf(accessContext.actorId()));
    }

    private ParsedAgentPayloadReference parse(String reference) {
        String body = reference == null ? "" : reference.substring(AGENT_PAYLOAD_PREFIX.length());
        String[] parts = body.split("/", -1);
        List<String> issues = new ArrayList<>();
        if (parts.length < 2) {
            issues.add("AGENT_PAYLOAD_REFERENCE_REQUIRES_RUN_AND_KEY");
        }
        for (String part : parts) {
            String text = safeText(part);
            if (text == null) {
                issues.add("AGENT_PAYLOAD_REFERENCE_EMPTY_SEGMENT");
                continue;
            }
            if (text.length() > 96) {
                issues.add("AGENT_PAYLOAD_REFERENCE_SEGMENT_TOO_LONG");
            }
            if (!safeSegment(text)) {
                issues.add("AGENT_PAYLOAD_REFERENCE_SEGMENT_UNSAFE_CHARACTERS");
            }
        }
        String runId = parts.length > 0 ? safeText(parts[0]) : null;
        String payloadKey = parts.length > 1 ? safeText(parts[1]) : null;
        return new ParsedAgentPayloadReference(runId, payloadKey, List.copyOf(issues));
    }

    private AgentToolActionPayloadVerdict rejected(String reference,
                                                   ParsedAgentPayloadReference parsed,
                                                   AgentToolActionPayloadRecord record,
                                                   List<String> issueCodes,
                                                   String reason) {
        return new AgentToolActionPayloadVerdict(
                STATUS_REJECTED,
                false,
                reference,
                "AGENT_PAYLOAD",
                record == null ? (parsed == null ? null : parsed.runId()) : record.runId(),
                record == null ? (parsed == null ? null : parsed.payloadKey()) : record.payloadKey(),
                record == null ? null : record.tenantId(),
                record == null ? null : record.projectId(),
                record == null ? null : record.actorId(),
                record == null ? null : record.toolName(),
                record != null && Boolean.TRUE.equals(record.payloadBodyAvailable()),
                record == null ? 0 : record.payloadSizeBytes(),
                record == null ? null : record.metadataDigest(),
                List.of(),
                issueCodes,
                List.of(reason),
                List.of("不要写入 outbox；请重新生成绑定当前 run/tenant/project/actor/tool 的服务端 payload envelope。")
        );
    }

    private String metadataDigest(AgentToolActionCommandProposalResponse proposal,
                                  ParsedAgentPayloadReference parsed,
                                  AgentToolActionCommandProposalRequest request) {
        return sha256(String.join("\n",
                defaultText(proposal.payloadReference()),
                defaultText(parsed.runId()),
                defaultText(parsed.payloadKey()),
                defaultText(proposal.tenantId()),
                defaultText(proposal.projectId()),
                defaultText(proposal.actorId()),
                defaultText(proposal.toolName()),
                defaultText(proposal.graphId()),
                defaultText(proposal.contractId()),
                defaultText(proposal.payloadPolicy()),
                defaultText(request == null ? null : request.policyVersion())
        ));
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 JDK 缺少 SHA-256，无法生成 agent-payload 元数据摘要", exception);
        }
    }

    private boolean safeSegment(String value) {
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

    private String defaultText(String value) {
        String text = safeText(value);
        return text == null ? "" : text;
    }

    private record ParsedAgentPayloadReference(
            String runId,
            String payloadKey,
            List<String> issueCodes
    ) {
        private boolean valid() {
            return issueCodes == null || issueCodes.isEmpty();
        }
    }
}
