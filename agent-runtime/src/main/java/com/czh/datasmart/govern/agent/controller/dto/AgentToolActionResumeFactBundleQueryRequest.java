/**
 * @Author : Cui
 * @Date: 2026/06/16 00:00
 * @Description DataSmart Govern Backend - AgentToolActionResumeFactBundleQueryRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.util.List;

/**
 * Agent 工具动作恢复事实包查询请求。
 *
 * <p>该请求面向 Python AI Runtime、智能网关、内部运维台和未来 Agent Host。
 * 它的目的不是让调用方“提交事实值并要求继续执行”，而是提供低敏定位字段，让 Java 控制面回查：
 * 审批事实是否已由 permission-admin 采信、command outbox 是否已经写入、worker/dry-run receipt 是否已经出现。</p>
 *
 * <p>字段安全要求：
 * 1. 可以携带 checkpointId/threadId/runId/sessionId/commandId/outboxId 这类控制面定位符；
 * 2. approvalFactId 只用于服务端回查，不会从响应中回显；
 * 3. 不允许携带 prompt、SQL、工具 arguments、payload body、样本数据、模型输出、凭证或内部 endpoint。</p>
 */
public record AgentToolActionResumeFactBundleQueryRequest(
        /**
         * Python/OpenClaw/LangGraph 风格 checkpoint ID。
         *
         * <p>当前 Java 侧尚未拥有 durable checkpoint store，因此该字段暂时只进入 requestedLocator，
         * 作为后续从 checkpoint 反查全部事实的契约预留。</p>
         */
        String checkpointId,

        /**
         * Agent thread ID，通常是 LangGraph checkpointer 的稳定恢复游标。
         *
         * <p>与 checkpointId 一样，当前阶段不直接查 Java store；后续接 Redis/MySQL checkpoint 后，
         * 可以用 threadId 找到最近暂停点和所需恢复事实。</p>
         */
        String threadId,

        /** 当前 Agent session ID，用于限定 receipt 查询范围。 */
        String sessionId,

        /** 当前 Agent run ID，用于查询 outbox 和 runtime event projection。 */
        String runId,

        /** 受控工具动作 commandId，用于精确定位 outbox record 和 worker receipt。 */
        String commandId,

        /** command outbox 记录 ID；当调用方已知 outboxId 时优先使用它定位。 */
        String outboxId,

        /** 审批事实 ID；仅发送到 permission-admin 验真，不会从本接口响应回显。 */
        String approvalFactId,

        /**
         * 澄清事实 ID。
         *
         * <p>该字段现在会被 Java 控制面用于回查 {@code AgentToolActionClarificationFactStore}。
         * 调用方传入 factId 并不等于澄清已被采信；服务端仍会校验事实是否已登记、是否在当前租户/项目/actor/run/session/command/tool
         * 范围内、是否过期、是否撤销以及策略版本是否匹配。响应中不会回显该 ID 原文。</p>
         */
        String clarificationFactId,

        /** 工具编码，用于审批事实与 outbox/receipt 的工具边界校验。 */
        String toolCode,

        /** 请求方持有的策略版本快照，用于发现审批事实与当前执行策略不一致。 */
        String requestedPolicyVersion,

        /** 可选租户过滤；最终仍会与 gateway/permission-admin Header 数据范围求交集。 */
        Long tenantId,

        /** 可选项目过滤；最终仍会与 gateway/permission-admin Header 数据范围求交集。 */
        Long projectId,

        /** 可选 actor 过滤；最终仍会与 gateway/permission-admin Header 数据范围求交集。 */
        String actorId,

        /**
         * 本次调用希望回查的事实类型。
         *
         * <p>推荐由 Python checkpoint.resumeRequirements 映射而来，例如：
         * APPROVAL_CONFIRMATION_FACT、OUTBOX_WRITE_CONFIRMATION、WORKER_RECEIPT_PROJECTION。
         * 如果为空，服务端会根据 request 中已有定位字段推断，并在完全无法推断时返回默认三类核心事实状态。</p>
         */
        List<String> requiredFactTypes,

        /** 是否返回 outbox 低敏摘要。默认 true，但不会返回 payloadJson、payloadReference、targetEndpoint 或 lastError。 */
        Boolean includeOutboxSummary,

        /** 是否返回 worker/dry-run receipt 低敏摘要。默认 true，但不会返回 message 原文或 payload body。 */
        Boolean includeReceiptSummary
) {
}
