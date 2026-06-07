/**
 * @Author : Cui
 * @Date: 2026/06/07 15:16
 * @Description DataSmart Govern Backend - AgentToolActionCommandOutboxWriterService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.config.AgentAsyncTaskCommandOutboxProperties;
import com.czh.datasmart.govern.agent.config.AgentRuntimeProperties;
import com.czh.datasmart.govern.agent.controller.dto.AgentAsyncTaskCommandOutboxRecordView;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionCommandOutboxWriteResponse;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionCommandProposalRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionCommandProposalResponse;
import com.czh.datasmart.govern.agent.event.command.AgentAsyncTaskCommandOutboxRecord;
import com.czh.datasmart.govern.agent.event.command.AgentAsyncTaskCommandOutboxStatus;
import com.czh.datasmart.govern.agent.event.command.AgentAsyncTaskCommandOutboxStore;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工具动作 command outbox writer。
 *
 * <p>本服务承接 5.52 的 command proposal，把“已经通过写入前预校验”的工具动作推进到 durable command outbox。
 * 它仍然不是工具执行器：不会读取 payloadReference 指向的真实参数，不会回查内部 endpoint，不会直接调用 worker，
 * 也不会把命令投递到 Kafka。它只创建一条可以被 dispatcher 可靠领取、重试、阻断和审计的 outbox 记录。</p>
 *
 * <p>为什么 writer 要和 proposal 分开：
 * 1. proposal 负责解释证据是否足够，适合前端确认页反复调用；
 * 2. writer 负责形成持久化命令事实，必须更关注幂等、容量、payload 大小和 outbox 存储；
 * 3. 两者分开后，后续可以继续插入 payload verifier、approval verifier、worker capacity lease 等组件，
 *    而不用推翻上一阶段 API。</p>
 */
@Service
@RequiredArgsConstructor
public class AgentToolActionCommandOutboxWriterService {

    private static final String STATE_ENQUEUED = "ENQUEUED";
    private static final String STATE_DUPLICATE_REUSED = "DUPLICATE_REUSED";
    private static final String STATE_BLOCKED_BY_PROPOSAL = "BLOCKED_BY_PROPOSAL";
    private static final String STATE_BLOCKED_BY_SERVER_VERIFICATION = "BLOCKED_BY_SERVER_VERIFICATION";
    private static final String COMMAND_SOURCE = "TOOL_ACTION_COMMAND_PROPOSAL";
    private static final String DEFAULT_TOPIC = "datasmart.agent.tool.async.commands";
    private static final String DEFAULT_CONSUMER = "task-management";
    private static final String TARGET_SERVICE = "agent-runtime";
    private static final String DISPATCH_CHANNEL = "KAFKA_COMMAND";

    /**
     * outbox 活跃积压状态。
     *
     * <p>PENDING 表示还没投递，PUBLISHING 表示正在投递，FAILED 表示等待重试；三者都会占用后台执行容量。
     * PUBLISHED 已经交给下游，BLOCKED 进入人工治理，因此不计入自动执行压力。</p>
     */
    private static final Set<AgentAsyncTaskCommandOutboxStatus> ACTIVE_BACKLOG_STATUSES = EnumSet.of(
            AgentAsyncTaskCommandOutboxStatus.PENDING,
            AgentAsyncTaskCommandOutboxStatus.PUBLISHING,
            AgentAsyncTaskCommandOutboxStatus.FAILED
    );

    private final AgentAsyncTaskCommandOutboxProperties outboxProperties;
    private final AgentRuntimeProperties runtimeProperties;
    private final AgentToolActionCommandProposalService proposalService;
    private final AgentToolActionPayloadReferenceVerifier payloadReferenceVerifier;
    private final AgentToolActionFactEvidenceVerifier factEvidenceVerifier;
    private final AgentAsyncTaskCommandOutboxStore outboxStore;
    private final ObjectMapper objectMapper;

    /**
     * 将工具动作 proposal 写入 command outbox。
     *
     * @param request 复用 proposal 请求体；writer 会重新执行 proposal，而不是信任调用方缓存的 proposal 响应。
     * @param accessContext 当前访问上下文，用于 proposal 查询时继续做租户、项目、主体范围收口。
     * @return 写入结果；阻断时不会产生 outbox record。
     */
    public AgentToolActionCommandOutboxWriteResponse write(
            AgentToolActionCommandProposalRequest request,
            AgentRuntimeEventQueryAccessContext accessContext) {
        ensureEnabled();
        AgentToolActionCommandProposalResponse proposal = proposalService.propose(request, accessContext);
        if (!Boolean.TRUE.equals(proposal.outboxWriteAllowedByPreflight())) {
            return blockedResponse(proposal);
        }
        AgentToolActionPayloadReferenceVerificationResult payloadReferenceVerification =
                payloadReferenceVerifier.verify(proposal, accessContext);
        AgentToolActionFactEvidenceVerificationResult factEvidenceVerification =
                factEvidenceVerifier.verify(request, proposal);
        if (!Boolean.TRUE.equals(payloadReferenceVerification.verifiedForWriter())
                || !Boolean.TRUE.equals(factEvidenceVerification.verifiedForWriter())) {
            return verificationBlockedResponse(proposal, payloadReferenceVerification, factEvidenceVerification);
        }
        assertCanEnqueue(proposal);
        AgentAsyncTaskCommandOutboxRecord record = buildRecord(
                proposal,
                request,
                accessContext,
                payloadReferenceVerification,
                factEvidenceVerification
        );
        boolean appended = outboxStore.append(record);
        AgentAsyncTaskCommandOutboxRecord current = appended
                ? record
                : outboxStore.findByCommandId(record.commandId()).orElse(record);
        return new AgentToolActionCommandOutboxWriteResponse(
                appended ? STATE_ENQUEUED : STATE_DUPLICATE_REUSED,
                appended,
                !appended,
                current.commandId(),
                proposal.proposalId(),
                proposal.graphId(),
                proposal.contractId(),
                proposal.runId(),
                proposal.payloadReference(),
                proposal.proposalState(),
                summaryReasons(proposal, appended, payloadReferenceVerification, factEvidenceVerification),
                recommendedActions(appended, payloadReferenceVerification, factEvidenceVerification),
                AgentAsyncTaskCommandOutboxRecordView.from(current)
        );
    }

    /**
     * proposal 未通过时返回阻断响应。
     *
     * <p>这里不抛异常，是为了让前端确认页能展示 proposal 的缺失证据和建议动作。只有找不到图、越权、
     * outbox 关闭、payload 序列化失败等真正的服务端错误才会抛异常。</p>
     */
    private AgentToolActionCommandOutboxWriteResponse blockedResponse(AgentToolActionCommandProposalResponse proposal) {
        List<String> reasons = new ArrayList<>(proposal.summaryReasons());
        reasons.add("proposal 未达到 READY_FOR_OUTBOX_CONFIRMATION，本次不会写入 command outbox。");
        List<String> actions = new ArrayList<>(proposal.recommendedActions());
        actions.add("先补齐 proposal 的 missingEvidence/rejectedEvidence，再重新调用 writer。");
        return new AgentToolActionCommandOutboxWriteResponse(
                STATE_BLOCKED_BY_PROPOSAL,
                false,
                false,
                null,
                proposal.proposalId(),
                proposal.graphId(),
                proposal.contractId(),
                proposal.runId(),
                proposal.payloadReference(),
                proposal.proposalState(),
                List.copyOf(reasons),
                List.copyOf(actions),
                null
        );
    }

    /**
     * 服务端复核未通过时返回阻断响应。
     *
     * <p>和 proposal 阻断一样，这里不写 outbox。不同点在于 proposal 已经认为“证据大体足够”，
     * 但 writer 自己的服务端复核发现引用或事实 ID 不满足更严格的入箱条件。这个阶段必须 fail-closed，
     * 否则就会把危险引用写成 durable command，后续 dispatcher/worker 很难判断它本来就不该入箱。</p>
     */
    private AgentToolActionCommandOutboxWriteResponse verificationBlockedResponse(
            AgentToolActionCommandProposalResponse proposal,
            AgentToolActionPayloadReferenceVerificationResult payloadReferenceVerification,
            AgentToolActionFactEvidenceVerificationResult factEvidenceVerification) {
        List<String> reasons = new ArrayList<>();
        reasons.addAll(payloadReferenceVerification.summaryReasons());
        reasons.addAll(factEvidenceVerification.summaryReasons());
        reasons.add("writer 服务端复核未通过，本次不会写入 command outbox。");
        List<String> actions = new ArrayList<>();
        actions.addAll(payloadReferenceVerification.recommendedActions());
        actions.addAll(factEvidenceVerification.recommendedActions());
        actions.add("请重新生成绑定当前 run/session 的 payloadReference 或服务端事实 ID 后再调用 writer。");
        return new AgentToolActionCommandOutboxWriteResponse(
                STATE_BLOCKED_BY_SERVER_VERIFICATION,
                false,
                false,
                null,
                proposal.proposalId(),
                proposal.graphId(),
                proposal.contractId(),
                proposal.runId(),
                proposal.payloadReference(),
                proposal.proposalState(),
                List.copyOf(reasons),
                List.copyOf(actions),
                null
        );
    }

    /**
     * outbox 写入前容量保护。
     *
     * <p>5.52 proposal 只判断“证据是否足够”，并不代表后台执行容量足够。writer 在 append 前再次检查 run 级和租户级
     * 活跃积压，避免在高并发确认、模型循环或网关重试时不断制造新的后台命令。</p>
     */
    private void assertCanEnqueue(AgentToolActionCommandProposalResponse proposal) {
        if (!outboxProperties.isCapacityProtectionEnabled()) {
            return;
        }
        String runId = requireText(proposal.runId(), "runId");
        Long tenantId = parseLong(proposal.tenantId(), "tenantId");
        long runBacklog = outboxStore.countByRunAndStatuses(runId, ACTIVE_BACKLOG_STATUSES);
        long tenantBacklog = tenantId == null ? 0L : outboxStore.countByTenantAndStatuses(tenantId, ACTIVE_BACKLOG_STATUSES);
        if (runBacklog + 1 > Math.max(1, outboxProperties.getMaxActiveCommandsPerRun())) {
            throw new PlatformBusinessException(
                    PlatformErrorCode.RATE_LIMITED,
                    "当前工具动作 run 级 outbox 积压已接近上限，runId=" + runId
                            + ", activeBacklog=" + runBacklog
            );
        }
        if (tenantId != null && tenantBacklog + 1 > Math.max(1, outboxProperties.getMaxActiveCommandsPerTenant())) {
            throw new PlatformBusinessException(
                    PlatformErrorCode.RATE_LIMITED,
                    "当前租户工具动作 outbox 积压已接近上限，tenantId=" + tenantId
                            + ", activeBacklog=" + tenantBacklog
            );
        }
    }

    /**
     * 构造 outbox 记录。
     *
     * <p>记录中的 payloadJson 是低敏命令信封，只包含 proposal、graph、contract、payloadReference 和服务端复核要求。
     * 它不包含工具参数值、prompt、SQL、样本数据、模型输出、凭证或内部 endpoint。</p>
     */
    private AgentAsyncTaskCommandOutboxRecord buildRecord(
            AgentToolActionCommandProposalResponse proposal,
            AgentToolActionCommandProposalRequest request,
            AgentRuntimeEventQueryAccessContext accessContext,
            AgentToolActionPayloadReferenceVerificationResult payloadReferenceVerification,
            AgentToolActionFactEvidenceVerificationResult factEvidenceVerification) {
        Instant now = Instant.now();
        String commandId = commandId(proposal);
        String payloadJson = toJson(payload(
                commandId,
                proposal,
                request,
                accessContext,
                payloadReferenceVerification,
                factEvidenceVerification
        ));
        int payloadBytes = payloadJson.getBytes(StandardCharsets.UTF_8).length;
        if (payloadBytes > Math.max(1, outboxProperties.getMaxPayloadBytes())) {
            throw new PlatformBusinessException(
                    PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                    "工具动作 command payload 超过安全上限，proposalId=" + proposal.proposalId()
                            + ", payloadBytes=" + payloadBytes
            );
        }
        return AgentAsyncTaskCommandOutboxRecord.pending(
                commandId,
                requireText(proposal.idempotencyKey(), "idempotencyKey"),
                defaultText(proposal.commandSchemaVersion(), outboxProperties.getSchemaVersion()),
                requireText(proposal.commandType(), "commandType"),
                defaultText(runtimeProperties.getAsyncTaskCommandTopic(), DEFAULT_TOPIC),
                defaultText(runtimeProperties.getAsyncTaskCommandConsumerService(), DEFAULT_CONSUMER),
                requireText(proposal.sessionId(), "sessionId"),
                requireText(proposal.runId(), "runId"),
                auditReference(proposal),
                requireText(proposal.toolName(), "toolName"),
                TARGET_SERVICE,
                null,
                parseLong(proposal.tenantId(), "tenantId"),
                parseLong(proposal.projectId(), "projectId"),
                null,
                proposal.actorId(),
                accessContext == null ? null : accessContext.traceId(),
                requireText(proposal.payloadReference(), "payloadReference"),
                payloadJson,
                payloadBytes,
                now
        );
    }

    /**
     * 构造低敏命令信封。
     *
     * <p>这里使用白名单 Map，而不是直接序列化 proposal 响应。原因是 proposal 后续可能新增展示字段，
     * 例如前端解释、诊断说明或聚合统计；这些字段不一定应该进入服务间命令。白名单能防止 DTO 演进时意外扩大
     * command payload。</p>
     */
    private Map<String, Object> payload(String commandId,
                                        AgentToolActionCommandProposalResponse proposal,
                                        AgentToolActionCommandProposalRequest request,
                                        AgentRuntimeEventQueryAccessContext accessContext,
                                        AgentToolActionPayloadReferenceVerificationResult payloadReferenceVerification,
                                        AgentToolActionFactEvidenceVerificationResult factEvidenceVerification) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", defaultText(proposal.commandSchemaVersion(), outboxProperties.getSchemaVersion()));
        payload.put("commandId", commandId);
        payload.put("idempotencyKey", proposal.idempotencyKey());
        payload.put("commandType", proposal.commandType());
        payload.put("dispatchChannel", DISPATCH_CHANNEL);
        payload.put("source", COMMAND_SOURCE);
        payload.put("proposalId", proposal.proposalId());
        payload.put("graphId", proposal.graphId());
        payload.put("contractId", proposal.contractId());
        payload.put("sourceEventIdentityKey", proposal.sourceEventIdentityKey());
        payload.put("sourceReplaySequence", proposal.sourceReplaySequence());
        payload.put("tenantId", proposal.tenantId());
        payload.put("projectId", proposal.projectId());
        payload.put("actorId", proposal.actorId());
        payload.put("requestId", proposal.requestId());
        payload.put("runId", proposal.runId());
        payload.put("sessionId", proposal.sessionId());
        payload.put("toolName", proposal.toolName());
        payload.put("payloadReference", proposal.payloadReference());
        payload.put("payloadPolicy", proposal.payloadPolicy());
        payload.put("payloadReferenceVerificationStatus", payloadReferenceVerification.status());
        payload.put("payloadReferenceType", payloadReferenceVerification.referenceType());
        payload.put("payloadReferenceAcceptedEvidence", payloadReferenceVerification.acceptedEvidence());
        payload.put("policyVersion", request == null ? null : safeText(request.policyVersion()));
        payload.put("approvalConfirmationId", request == null ? null : safeText(request.approvalConfirmationId()));
        payload.put("clarificationFactId", request == null ? null : safeText(request.clarificationFactId()));
        payload.put("factEvidenceVerificationStatus", factEvidenceVerification.status());
        payload.put("factEvidenceAcceptedEvidence", factEvidenceVerification.acceptedEvidence());
        payload.put("workerReceiptRequired", proposal.workerReceiptRequired());
        payload.put("workerReceiptMode", proposal.workerReceiptMode());
        payload.put("serverSideVerificationRequired", true);
        payload.put("requiredServerSideChecks", List.of(
                "PAYLOAD_REFERENCE_AUTHORIZATION",
                "POLICY_VERSION_REPLAY",
                "APPROVAL_OR_CLARIFICATION_FACT_LOOKUP",
                "WORKER_CAPACITY_LEASE",
                "IDEMPOTENCY_RECHECK",
                "RESULT_REDACTION_AND_RECEIPT"
        ));
        payload.put("traceId", accessContext == null ? null : accessContext.traceId());
        return payload;
    }

    private List<String> summaryReasons(AgentToolActionCommandProposalResponse proposal,
                                        boolean appended,
                                        AgentToolActionPayloadReferenceVerificationResult payloadReferenceVerification,
                                        AgentToolActionFactEvidenceVerificationResult factEvidenceVerification) {
        List<String> reasons = new ArrayList<>();
        reasons.add("工具 `" + proposal.toolName() + "` 的 command proposal 已通过写入前预校验。");
        reasons.addAll(payloadReferenceVerification.summaryReasons());
        reasons.addAll(factEvidenceVerification.summaryReasons());
        if (appended) {
            reasons.add("已首次写入 command outbox，等待 dispatcher 后续投递到 task-management。");
        } else {
            reasons.add("commandId 已存在，本次按幂等规则复用已有 outbox 记录，没有重复创建。");
        }
        reasons.add("本次写入的 payload 仍然只是低敏命令信封，不包含工具实参、SQL、prompt 或内部 endpoint。");
        return List.copyOf(reasons);
    }

    private List<String> recommendedActions(boolean appended,
                                            AgentToolActionPayloadReferenceVerificationResult payloadReferenceVerification,
                                            AgentToolActionFactEvidenceVerificationResult factEvidenceVerification) {
        List<String> actions = new ArrayList<>();
        if (appended) {
            actions.add("下一步可手动 dispatch-once 或开启 dispatcher，由 task-management inbox 继续去重和接单。");
        } else {
            actions.add("如果需要重新执行，请先确认已有 outbox 记录状态，再走补偿、重放或死信治理流程。");
        }
        actions.addAll(payloadReferenceVerification.recommendedActions());
        actions.addAll(factEvidenceVerification.recommendedActions());
        actions.add("后续应补齐 payloadReference 服务端读取、approval fact 回查和 worker receipt 回写。");
        return List.copyOf(actions);
    }

    private String commandId(AgentToolActionCommandProposalResponse proposal) {
        return "taoc_" + sha256(proposal.proposalId() + ":" + proposal.payloadReference()).substring(0, 24);
    }

    private String auditReference(AgentToolActionCommandProposalResponse proposal) {
        return "tool-action:" + sha256(proposal.graphId() + ":" + proposal.contractId()).substring(0, 24);
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 JDK 缺少 SHA-256，无法生成工具动作 outbox commandId", exception);
        }
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new PlatformBusinessException(
                    PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                    "工具动作 command payload 序列化失败: " + exception.getMessage()
            );
        }
    }

    private Long parseLong(String value, String fieldName) {
        String text = safeText(value);
        if (text == null) {
            return null;
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException exception) {
            throw new PlatformBusinessException(
                    PlatformErrorCode.BAD_REQUEST,
                    "工具动作 command outbox 字段 " + fieldName + " 必须是数字: " + value
            );
        }
    }

    private String requireText(String value, String fieldName) {
        String text = safeText(value);
        if (text == null) {
            throw new PlatformBusinessException(
                    PlatformErrorCode.BAD_REQUEST,
                    "工具动作 command outbox 缺少必填字段: " + fieldName
            );
        }
        return text;
    }

    private String defaultText(String value, String fallback) {
        String text = safeText(value);
        return text == null ? fallback : text;
    }

    private String safeText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private void ensureEnabled() {
        if (!outboxProperties.isEnabled()) {
            throw new PlatformBusinessException(
                    PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                    "Agent 异步命令 outbox 当前未启用，不能写入工具动作 command"
            );
        }
    }
}
