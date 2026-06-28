/**
 * @Author : Cui
 * @Date: 2026/06/28 22:20
 * @Description DataSmart Govern Backend - AgentToolActionApprovalConfirmationRecord.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import java.time.Instant;
import java.util.List;

/**
 * 工具动作审批确认事实记录。
 *
 * <p>该记录是 Agent Host 在真实执行前保存的“低敏确认事实”。它不保存 payloadBody、工具参数、prompt、SQL、
 * 样本数据、模型输出、凭证或内部 endpoint，只保存足以让 writer/executor 判断确认是否仍然有效的元数据。
 * 这类设计和 Codex/Claude Code 类 Agent 的安全思路一致：用户确认的是一个由 Host 管控的工具动作引用和摘要，
 * 不是把可执行正文复制给前端或模型。</p>
 *
 * @param confirmationId 服务端确认事实 ID，格式为 `tool-action-confirmation:{digest}`。
 * @param proposalId 来源 proposal ID，用于把确认页、writer 和运行时图关联起来。
 * @param clientRequestId 前端确认页或网关提交的幂等请求 ID，可为空。
 * @param payloadReference 已确认的 `agent-payload:` 引用。
 * @param runId Agent run ID，防止跨 run 复用确认。
 * @param payloadKey run 内 payload key，便于诊断但不包含正文。
 * @param tenantId 租户 ID 字符串，用于多租户隔离。
 * @param projectId 项目 ID 字符串，用于项目级数据范围隔离。
 * @param actorId proposal 对应的业务主体或服务账号。
 * @param confirmingActorId 实际执行确认动作的主体，优先来自 gateway 访问上下文。
 * @param toolName 工具名，不包含工具参数。
 * @param graphId 来源执行图 ID。
 * @param contractId durable action contract ID。
 * @param policyVersion 确认时的策略版本。
 * @param payloadPolicy payload 安全策略，例如 LOW_SENSITIVE_DRAFT_BODY。
 * @param payloadBodyAvailable 确认时服务端是否已物化 payload body。
 * @param payloadSizeBytes payload body 字节数；只用于容量和诊断，不代表正文可对外展示。
 * @param payloadMetadataDigest payload 元数据摘要，不是 payloadBody 正文摘要。
 * @param acceptedPayloadEvidence payload store verifier 已接受的低敏证据。
 * @param confirmed 是否已确认；和 status 双重表达是为了让后续审批中心迁移时兼容布尔字段。
 * @param status 确认状态，当前 writer 只接受 CONFIRMED。
 * @param createdAt 记录创建时间。
 * @param confirmedAt 确认时间。
 * @param expiresAt 过期时间；过期确认不能继续驱动 outbox 写入。
 */
public record AgentToolActionApprovalConfirmationRecord(
        String confirmationId,
        String proposalId,
        String clientRequestId,
        String payloadReference,
        String runId,
        String payloadKey,
        String tenantId,
        String projectId,
        String actorId,
        String confirmingActorId,
        String toolName,
        String graphId,
        String contractId,
        String policyVersion,
        String payloadPolicy,
        Boolean payloadBodyAvailable,
        Integer payloadSizeBytes,
        String payloadMetadataDigest,
        List<String> acceptedPayloadEvidence,
        Boolean confirmed,
        AgentToolActionApprovalConfirmationStatus status,
        Instant createdAt,
        Instant confirmedAt,
        Instant expiresAt
) {

    public AgentToolActionApprovalConfirmationRecord {
        payloadBodyAvailable = Boolean.TRUE.equals(payloadBodyAvailable);
        payloadSizeBytes = Math.max(0, payloadSizeBytes == null ? 0 : payloadSizeBytes);
        acceptedPayloadEvidence = acceptedPayloadEvidence == null ? List.of() : List.copyOf(acceptedPayloadEvidence);
        confirmed = Boolean.TRUE.equals(confirmed);
        status = status == null ? AgentToolActionApprovalConfirmationStatus.CONFIRMED : status;
        createdAt = createdAt == null ? Instant.now() : createdAt;
        confirmedAt = confirmedAt == null ? createdAt : confirmedAt;
    }

    /**
     * 判断确认事实是否已经超过有效期。
     *
     * <p>确认事实必须短 TTL，是为了避免用户在很久之前确认过的草案被 Agent loop、浏览器重放或网关重试重新执行。
     * 即使 confirmationId 字符串仍然存在，过期记录也必须在 writer 前 fail-closed。</p>
     */
    public boolean expired(Instant now) {
        Instant referenceTime = now == null ? Instant.now() : now;
        return expiresAt != null && expiresAt.isBefore(referenceTime);
    }
}
