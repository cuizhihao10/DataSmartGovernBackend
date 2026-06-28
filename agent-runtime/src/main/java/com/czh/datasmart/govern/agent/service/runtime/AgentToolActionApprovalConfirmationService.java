/**
 * @Author : Cui
 * @Date: 2026/06/28 22:20
 * @Description DataSmart Govern Backend - AgentToolActionApprovalConfirmationService.java
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
import java.util.HexFormat;
import java.util.Optional;

/**
 * 工具动作审批确认事实注册服务。
 *
 * <p>本服务解决的是 `agent-payload:` 物化之后、真实 outbox 写入之前的关键闭环：用户或审批流不能只把一个
 * confirmationId 字符串交给 writer，而必须先由 Host 回查 payload store，确认这个 payloadReference 已经服务端登记、
 * body 已物化、元数据作用域匹配，然后再生成 `tool-action-confirmation:` 服务端事实。</p>
 *
 * <p>为什么不直接把 payload body 放进确认事实：确认事实会进入 writer 复核、命令信封证据、审计说明和未来审批台，
 * 一旦复制正文就会把低敏控制面变成高敏数据扩散点。正确做法是确认 fact 只保存引用、大小、策略和摘要；
 * executor 真正执行时再在服务端读取 payload body 并重新校验权限、TTL 和策略版本。</p>
 */
@Service
@RequiredArgsConstructor
public class AgentToolActionApprovalConfirmationService {

    private static final String CONFIRMATION_PREFIX = "tool-action-confirmation:";
    private static final Duration DEFAULT_CONFIRMATION_TTL = Duration.ofMinutes(30);

    private final AgentToolActionPayloadStoreService payloadStoreService;
    private final AgentToolActionApprovalConfirmationStore confirmationStore;

    /**
     * 为已经物化的 `agent-payload:` 创建审批确认事实。
     *
     * <p>调用方通常是前端确认页、智能网关的人审节点，或未来 permission-admin 审批回调。方法会先补登记 payload envelope，
     * 再通过 payload store verifier 做服务端复核。只有 payload body 已经物化时才会生成确认事实，因为当前确认语义是
     * “我确认这份服务端草案可以进入真实执行前 outbox”，不是“我确认某个空 envelope 可以执行”。</p>
     *
     * @param request 原始 writer/proposal 请求，用于读取 policyVersion 和 clientRequestId。
     * @param proposal proposal 低敏响应，用于绑定 graph/contract/run/tool/tenant/project/actor。
     * @param accessContext gateway 可信访问上下文，用于确认人身份和数据范围复核。
     * @param ttl 确认事实有效期；为空时默认 30 分钟。
     * @return 确认事实；payload 不存在、未物化、越权或已过期时返回空。
     */
    public Optional<AgentToolActionApprovalConfirmationRecord> confirmMaterializedPayload(
            AgentToolActionCommandProposalRequest request,
            AgentToolActionCommandProposalResponse proposal,
            AgentRuntimeEventQueryAccessContext accessContext,
            Duration ttl) {
        if (proposal == null || payloadStoreService == null || confirmationStore == null) {
            return Optional.empty();
        }
        payloadStoreService.ensureEnvelope(proposal, request, accessContext);
        AgentToolActionPayloadVerdict verdict =
                payloadStoreService.verifyReference(proposal.payloadReference(), proposal, accessContext);
        if (!Boolean.TRUE.equals(verdict.readableForWriter()) || !Boolean.TRUE.equals(verdict.payloadBodyAvailable())) {
            return Optional.empty();
        }
        Instant now = Instant.now();
        Duration effectiveTtl = ttl == null ? DEFAULT_CONFIRMATION_TTL : ttl;
        String confirmationId = confirmationId(proposal, request, verdict);
        AgentToolActionApprovalConfirmationRecord record = new AgentToolActionApprovalConfirmationRecord(
                confirmationId,
                proposal.proposalId(),
                request == null ? null : safeText(request.clientRequestId()),
                verdict.payloadReference(),
                verdict.runId(),
                verdict.payloadKey(),
                verdict.tenantId(),
                verdict.projectId(),
                verdict.actorId(),
                confirmingActorId(proposal, accessContext),
                proposal.toolName(),
                proposal.graphId(),
                proposal.contractId(),
                request == null ? null : safeText(request.policyVersion()),
                proposal.payloadPolicy(),
                verdict.payloadBodyAvailable(),
                verdict.payloadSizeBytes(),
                verdict.metadataDigest(),
                verdict.acceptedEvidence(),
                true,
                AgentToolActionApprovalConfirmationStatus.CONFIRMED,
                now,
                now,
                now.plus(effectiveTtl)
        );
        confirmationStore.saveIfAbsent(record);
        return confirmationStore.findByConfirmationId(confirmationId);
    }

    /**
     * 构造稳定 confirmationId。
     *
     * <p>confirmationId 只使用低敏元数据生成摘要：proposalId、payloadReference、元数据摘要、策略版本和 clientRequestId。
     * 不把 payload body 放入摘要输入，是为了避免正文通过哈希侧信道进入确认事实；真实正文完整性后续应由服务端存储层、
     * 加密签名或对象存储版本号保证。</p>
     */
    private String confirmationId(AgentToolActionCommandProposalResponse proposal,
                                  AgentToolActionCommandProposalRequest request,
                                  AgentToolActionPayloadVerdict verdict) {
        String digest = sha256(String.join("\n",
                defaultText(proposal.proposalId()),
                defaultText(verdict.payloadReference()),
                defaultText(verdict.metadataDigest()),
                defaultText(request == null ? null : request.policyVersion()),
                defaultText(request == null ? null : request.clientRequestId())
        ));
        return CONFIRMATION_PREFIX + digest.substring(0, 24);
    }

    private String confirmingActorId(AgentToolActionCommandProposalResponse proposal,
                                     AgentRuntimeEventQueryAccessContext accessContext) {
        if (accessContext != null && accessContext.actorId() != null) {
            return String.valueOf(accessContext.actorId());
        }
        return safeText(proposal == null ? null : proposal.actorId());
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 JDK 缺少 SHA-256，无法生成工具动作确认事实 ID", exception);
        }
    }

    private String safeText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String defaultText(String value) {
        String text = safeText(value);
        return text == null ? "" : text;
    }
}
