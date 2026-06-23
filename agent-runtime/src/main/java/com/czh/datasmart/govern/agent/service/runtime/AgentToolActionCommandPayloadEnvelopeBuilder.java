/**
 * @Author : Cui
 * @Date: 2026/06/11 00:00
 * @Description DataSmart Govern Backend - AgentToolActionCommandPayloadEnvelopeBuilder.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.config.AgentAsyncTaskCommandOutboxProperties;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionCommandProposalRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionCommandProposalResponse;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具动作 command 低敏 payload envelope 构造器。
 *
 * <p>该类从 writer 中拆出，是为了让 writer 只负责“是否允许写、何时写、写到哪里”，而把跨服务命令信封的字段白名单
 * 单独集中管理。真实商用系统里，命令 payload 往往会随着 task-management、审计台、dispatcher 和 executor 的演进
 * 逐步增加低敏字段；如果都堆在 writer 里，writer 会变成一个难以维护的大类，也更容易因为 DTO 自动序列化泄露敏感字段。</p>
 *
 * <p>本 builder 的安全原则很明确：只构造 task-management 可消费的低敏信封，不输出工具实参、SQL、prompt、
 * 样本数据、模型输出、凭证、内部 endpoint 或 artifact 正文。payloadReference 只是引用，真实读取必须由后续
 * payload store/executor 在服务端完成。</p>
 */
@Component
@RequiredArgsConstructor
public class AgentToolActionCommandPayloadEnvelopeBuilder {

    private static final String COMMAND_SOURCE = "TOOL_ACTION_COMMAND_PROPOSAL";
    private static final String TARGET_SERVICE = "agent-runtime";
    private static final String DISPATCH_CHANNEL = "KAFKA_COMMAND";

    private final AgentAsyncTaskCommandOutboxProperties outboxProperties;
    private final ObjectMapper objectMapper;

    /**
     * 构造并序列化低敏 command payload。
     *
     * <p>对外只暴露 JSON 字符串，是因为 outbox record 需要保存稳定 payloadJson；内部仍使用白名单 Map 构造，
     * 防止未来 proposal DTO 新增展示字段时自动进入跨服务消息。该方法抛业务异常而不是返回 null，避免 writer
     * 在 payload 序列化失败时生成不完整命令。</p>
     */
    public String toPayloadJson(String commandId,
                                String commandSchemaVersion,
                                String auditReference,
                                AgentToolActionCommandProposalResponse proposal,
                                AgentToolActionCommandProposalRequest request,
                                AgentRuntimeEventQueryAccessContext accessContext,
                                AgentToolActionPayloadReferenceVerificationResult payloadReferenceVerification,
                                AgentToolActionFactEvidenceVerificationResult factEvidenceVerification) {
        try {
            return objectMapper.writeValueAsString(payload(
                    commandId,
                    commandSchemaVersion,
                    auditReference,
                    proposal,
                    request,
                    accessContext,
                    payloadReferenceVerification,
                    factEvidenceVerification
            ));
        } catch (JsonProcessingException exception) {
            throw new PlatformBusinessException(
                    PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                    "工具动作 command payload 序列化失败: " + exception.getMessage()
            );
        }
    }

    /**
     * 构造 task-management 可消费的字段白名单。
     *
     * <p>这里同时保留 proposalId/graphId/contractId 等控制面字段，是为了让后续 executor、诊断台和人工补偿流程
     * 能把 task-management 任务追溯回 Agent 执行图。但这些字段都是低敏引用或 ID，不包含模型 prompt 和工具参数正文。</p>
     */
    private Map<String, Object> payload(String commandId,
                                        String commandSchemaVersion,
                                        String auditReference,
                                        AgentToolActionCommandProposalResponse proposal,
                                        AgentToolActionCommandProposalRequest request,
                                        AgentRuntimeEventQueryAccessContext accessContext,
                                        AgentToolActionPayloadReferenceVerificationResult payloadReferenceVerification,
                                        AgentToolActionFactEvidenceVerificationResult factEvidenceVerification) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", commandSchemaVersion);
        payload.put("proposalCommandSchemaVersion", proposal.commandSchemaVersion());
        payload.put("commandId", commandId);
        payload.put("idempotencyKey", proposal.idempotencyKey());
        payload.put("commandType", proposal.commandType());
        payload.put("auditId", auditReference);
        payload.put("toolCode", proposal.toolName());
        payload.put("targetService", TARGET_SERVICE);
        payload.put("targetEndpoint", null);
        payload.put("workspaceId", null);
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
        payload.put("argumentNames", List.of());
        payload.put("sensitiveArgumentNames", List.of());
        payload.put("confirmationId", request == null ? null : safeText(request.approvalConfirmationId()));
        payload.put("policyVersions", policyVersions(request == null ? null : request.policyVersion()));
        payload.put("delegationEvidence", delegationEvidence(payloadReferenceVerification, factEvidenceVerification));
        payload.put("policyVersion", request == null ? null : safeText(request.policyVersion()));
        payload.put("approvalConfirmationId", request == null ? null : safeText(request.approvalConfirmationId()));
        payload.put("clarificationFactId", request == null ? null : safeText(request.clarificationFactId()));
        payload.put("factEvidenceVerificationStatus", factEvidenceVerification.status());
        payload.put("factEvidenceAcceptedEvidence", factEvidenceVerification.acceptedEvidence());
        /*
         * 命令安全预检证据占位。
         *
         * 当前 tool-action writer 处理的是“工具动作 command”的低敏入箱，不等同于真实 shell/run-program 执行。
         * 因此默认声明 commandSafetyPrecheckRequired=false，并放入 NOT_APPLICABLE 摘要，保证历史工具命令不会被误阻断。
         * 后续如果某个 ToolPlan 真正表示“运行程序/执行命令”，writer 或专用 command builder 必须把 required 置为 true，
         * 并写入 safety-precheck 返回的低敏 allow verdict。worker pre-check 会在领取前复核该证据，缺失或携带高敏字段
         * 都会 fail-closed。
         */
        payload.put("commandSafetyPrecheckRequired", false);
        payload.put("commandSafetyPrecheck", commandSafetyPrecheckNotApplicable());
        payload.put("workerReceiptRequired", proposal.workerReceiptRequired());
        payload.put("workerReceiptMode", proposal.workerReceiptMode());
        payload.put("serverSideVerificationRequired", true);
        payload.put("requiredServerSideChecks", List.of(
                "PAYLOAD_REFERENCE_AUTHORIZATION",
                "POLICY_VERSION_REPLAY",
                "COMMAND_SAFETY_PRECHECK_EVIDENCE_IF_REQUIRED",
                "APPROVAL_OR_CLARIFICATION_FACT_LOOKUP",
                "WORKER_CAPACITY_LEASE",
                "IDEMPOTENCY_RECHECK",
                "RESULT_REDACTION_AND_RECEIPT"
        ));
        payload.put("traceId", accessContext == null ? null : accessContext.traceId());
        payload.put("priority", outboxProperties.getDefaultPriority());
        payload.put("maxRetryCount", outboxProperties.getDefaultMaxRetryCount());
        payload.put("maxDeferCount", outboxProperties.getDefaultMaxDeferCount());
        return payload;
    }

    /**
     * 构造“当前工具动作不适用命令安全预检”的低敏占位。
     *
     * <p>为什么不直接省略字段：省略字段容易让后续 worker/executor 分不清“历史 payload 不知道这回事”和“本次明确不适用”。
     * 用 NOT_APPLICABLE 可以把语义写清楚，同时仍然不包含 commandLine、工作目录、真实路径、stdout/stderr 或工具参数。</p>
     */
    private Map<String, Object> commandSafetyPrecheckNotApplicable() {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("required", false);
        evidence.put("decision", "NOT_APPLICABLE");
        evidence.put("payloadPolicy", "LOW_SENSITIVE_COMMAND_SAFETY_PRECHECK_NOT_APPLICABLE");
        evidence.put("reasonCodes", List.of("TOOL_ACTION_COMMAND_IS_NOT_RUN_PROGRAM"));
        evidence.put("issueCodes", List.of());
        evidence.put("commandLineReturned", false);
        evidence.put("pathValuesReturned", false);
        evidence.put("sideEffectExecuted", false);
        return evidence;
    }

    /**
     * 将单个策略版本转成 task-management 消费侧使用的列表字段。
     *
     * <p>虽然当前 tool action writer 只有一个 policyVersion，但列表结构能兼容未来同时记录网关路由策略、
     * permission-admin 工具预算策略、sandbox 策略和运行时保护策略。</p>
     */
    private List<String> policyVersions(String policyVersion) {
        String text = safeText(policyVersion);
        return text == null ? List.of() : List.of(text);
    }

    /**
     * 生成低敏委托证据摘要。
     *
     * <p>delegationEvidence 只合并 verifier 已经接受的低敏证据，例如引用前缀、run 绑定、服务端记录存在、
     * 审批事实 ID 形态等。它不能包含工具参数、SQL、prompt、样本数据或模型输出。</p>
     */
    private List<String> delegationEvidence(
            AgentToolActionPayloadReferenceVerificationResult payloadReferenceVerification,
            AgentToolActionFactEvidenceVerificationResult factEvidenceVerification) {
        List<String> evidence = new ArrayList<>();
        if (payloadReferenceVerification != null && payloadReferenceVerification.acceptedEvidence() != null) {
            evidence.addAll(payloadReferenceVerification.acceptedEvidence());
        }
        if (factEvidenceVerification != null && factEvidenceVerification.acceptedEvidence() != null) {
            evidence.addAll(factEvidenceVerification.acceptedEvidence());
        }
        return List.copyOf(evidence);
    }

    private String safeText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
