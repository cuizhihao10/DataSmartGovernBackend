/**
 * @Author : Cui
 * @Date: 2026/06/11 22:00
 * @Description DataSmart Govern Backend - AgentToolActionControlledTaskPayload.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.service.agent;

import java.time.LocalDateTime;
import java.util.List;

/**
 * `AGENT_TOOL_ACTION_CONTROLLED` 任务的低敏载荷快照。
 *
 * <p>该对象与历史 {@link AgentAsyncToolResolvedPayload} 刻意分开。历史对象代表“已经可以读取 plan-arguments
 * 并交给白名单工具适配器执行”的任务；本对象代表“新工具动作控制面命令已经进入 task-management，
 * 但当前只能做 dry-run/pre-check，不能执行真实副作用”。这种分离能防止维护者为了复用旧 worker，
 * 把 `agent-payload:` 引用误塞进 `agent-tool-audit://.../plan-arguments` 执行链路。</p>
 *
 * <p>字段全部来自 task.params 或任务主表的低敏信息：commandId、payloadReference、策略版本、证据摘要、
 * tenant/project/actor/run/tool 等。它不包含工具参数值、SQL、prompt、样本数据、模型输出、凭证或内部 endpoint。</p>
 *
 * @param taskId task-management 任务 ID。
 * @param taskStatus 当前任务状态，dry-run 认领后通常是 RUNNING。
 * @param taskType 当前任务类型，必须是 AGENT_TOOL_ACTION_CONTROLLED。
 * @param tenantId 任务所属租户。
 * @param projectId 任务所属项目。
 * @param actorId 发起或被代表的上游 actor ID；用于 permission-admin 审批事实作用域复核。
 * @param commandId agent-runtime writer 生成的 commandId。
 * @param commandType 跨服务命令类型，必须是 AGENT_TOOL_ACTION_CONTROLLED_COMMAND。
 * @param commandKind 消费侧归一化类别，必须是 TOOL_ACTION_CONTROLLED。
 * @param auditId 控制面审计引用，当前通常是 tool-action 摘要，不是历史工具审计 ID。
 * @param sessionId Agent session ID。
 * @param runId Agent run ID。
 * @param toolCode 工具编码。
 * @param targetService 新命令的目标控制面服务，当前必须是 agent-runtime。
 * @param targetEndpoint 新命令不能携带内部 endpoint，因此该字段必须为空。
 * @param workspaceId 当前阶段可为空，后续 payload store 或 executor 物化后再补强。
 * @param payloadReference `agent-payload:{runId}/{payloadKey}` 受控引用。
 * @param payloadReferenceType 引用类型摘要，必须是 AGENT_PAYLOAD。
 * @param payloadKey run 内部 payload key。
 * @param workerDispatchEnabled 必须为 false，表示旧 worker 不能执行该任务。
 * @param argumentNames 低敏参数名列表，当前通常为空。
 * @param sensitiveArgumentNames 敏感参数名列表，只包含字段名，不包含字段值。
 * @param confirmationId 人工审批、前端确认或外部审批事实 ID，可为空。
 * @param policyVersions 策略版本摘要。
 * @param delegationEvidence writer/verifier 接受的低敏证据摘要。
 * @param parsedAt 本地解析时间，用于诊断。
 */
public record AgentToolActionControlledTaskPayload(
        Long taskId,
        String taskStatus,
        String taskType,
        Long tenantId,
        Long projectId,
        String actorId,
        String commandId,
        String commandType,
        String commandKind,
        String auditId,
        String sessionId,
        String runId,
        String toolCode,
        String targetService,
        String targetEndpoint,
        Long workspaceId,
        String payloadReference,
        String payloadReferenceType,
        String payloadKey,
        Boolean workerDispatchEnabled,
        List<String> argumentNames,
        List<String> sensitiveArgumentNames,
        String confirmationId,
        List<String> policyVersions,
        List<String> delegationEvidence,
        LocalDateTime parsedAt
) {

    public AgentToolActionControlledTaskPayload {
        argumentNames = argumentNames == null ? List.of() : List.copyOf(argumentNames);
        sensitiveArgumentNames = sensitiveArgumentNames == null ? List.of() : List.copyOf(sensitiveArgumentNames);
        policyVersions = policyVersions == null ? List.of() : List.copyOf(policyVersions);
        delegationEvidence = delegationEvidence == null ? List.of() : List.copyOf(delegationEvidence);
        workerDispatchEnabled = Boolean.TRUE.equals(workerDispatchEnabled);
        parsedAt = parsedAt == null ? LocalDateTime.now() : parsedAt;
    }

    /**
     * 判断 agent-runtime writer/verifier 是否已经证明服务端登记记录存在。
     *
     * <p>该判断只看 delegationEvidence 中的低敏证据，不读取 payload body。
     * 如果缺少该证据，说明任务可能来自旧版本 writer、人工修复错误或异常消息，dry-run 必须 fail-closed。</p>
     */
    public boolean hasPayloadStoreEvidence() {
        return delegationEvidence.contains("AGENT_PAYLOAD_RECORD_FOUND")
                && delegationEvidence.contains("AGENT_PAYLOAD_METADATA_SCOPE_VERIFIED");
    }

    /**
     * 当前引用是否已经声明真实 payload body 可用。
     *
     * <p>5.57 只登记 envelope 元数据，因此常见证据是 `PAYLOAD_BODY_NOT_MATERIALIZED`。
     * 未来 executor 接入真实 payload store 后，可以把该证据升级为 `PAYLOAD_BODY_AVAILABLE`，
     * dry-run 就能把阻断原因从“缺少 body”推进到“等待专用执行器开放”。</p>
     */
    public boolean payloadBodyAvailable() {
        return delegationEvidence.contains("PAYLOAD_BODY_AVAILABLE");
    }
}
