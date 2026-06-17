/**
 * @Author : Cui
 * @Date: 2026/06/17 00:00
 * @Description DataSmart Govern Backend - AgentToolActionResumeLocatorIndexRecord.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import java.time.Instant;

/**
 * 工具动作恢复 locator index 的单条记录。
 *
 * <p>这个记录解决的是“checkpoint/thread 到 Java 控制面事实定位符”的映射问题。
 * Python Runtime 的 checkpoint 只保存低敏执行图摘要；真实恢复前，Java 仍需要知道 commandId、outboxId、
 * approvalFactId、clarificationFactId、toolCode、policyVersion 等控制面定位符，才能回查 outbox、worker
 * receipt、permission-admin 审批事实和未来 clarification fact store。</p>
 *
 * <p>安全边界：</p>
 * <p>1. 这里保存的是低敏定位符，不保存 prompt、SQL、arguments、payload body、样本数据、模型输出、凭证或内部 endpoint；</p>
 * <p>2. approvalFactId/clarificationFactId 只用于 Java 服务端回查，不应该在 fact bundle 响应中原文回显；</p>
 * <p>3. 当前内存实现是短期索引，后续生产应升级 MySQL/audit durable projection，并加唯一键与保留期策略。</p>
 */
public record AgentToolActionResumeLocatorIndexRecord(
        /** Python/LangGraph/OpenClaw checkpoint ID，用于精确定位某次暂停点。 */
        String checkpointId,

        /** thread ID，通常对应同一会话或同一 run 的恢复游标。 */
        String threadId,

        /** Agent session ID，用于把 checkpoint 限定在一次会话边界内。 */
        String sessionId,

        /** Agent run ID，用于定位 outbox、worker receipt 和 runtime event projection。 */
        String runId,

        /** Java command outbox 的 commandId，用于查 outbox 记录和 worker receipt。 */
        String commandId,

        /** Java command outbox 的 outboxId；当存在时优先用于精确查询 outbox。 */
        String outboxId,

        /** permission-admin 审批事实 ID，只供服务端回查审批状态，不对外回显。 */
        String approvalFactId,

        /** 用户澄清事实 ID，当前先作为未来 clarification store 的预留定位符。 */
        String clarificationFactId,

        /** 工具编码，用于校验 outbox/approval/receipt 是否属于同一个工具动作。 */
        String toolCode,

        /** 策略版本快照，用于恢复时发现执行策略漂移。 */
        String requestedPolicyVersion,

        /** 租户边界，locator index 命中后仍必须与访问上下文求交集。 */
        String tenantId,

        /** 项目边界，避免一个 checkpoint/thread locator 被跨项目误用。 */
        String projectId,

        /** 操作者边界，SELF 范围或审批事实回查时需要使用。 */
        String actorId,

        /** 最近一次写入/合并时间，用于后续 TTL、清理和审计排序。 */
        Instant updatedAt
) {

    public AgentToolActionResumeLocatorIndexRecord {
        checkpointId = text(checkpointId);
        threadId = text(threadId);
        sessionId = text(sessionId);
        runId = text(runId);
        commandId = text(commandId);
        outboxId = text(outboxId);
        approvalFactId = text(approvalFactId);
        clarificationFactId = text(clarificationFactId);
        toolCode = text(toolCode);
        requestedPolicyVersion = text(requestedPolicyVersion);
        tenantId = text(tenantId);
        projectId = text(projectId);
        actorId = text(actorId);
        updatedAt = updatedAt == null ? Instant.now() : updatedAt;
    }

    /**
     * 判断记录是否具备索引价值。
     *
     * <p>checkpointId/threadId 至少要有一个，否则无法作为恢复入口；同时至少要有一个 Java 控制面定位符，
     * 否则记录只是在保存一个空 checkpoint，没有办法帮助后续 fact bundle 查询。</p>
     */
    public boolean indexable() {
        boolean hasCheckpointLocator = hasText(checkpointId) || hasText(threadId);
        boolean hasResumeLocator = hasText(sessionId)
                || hasText(runId)
                || hasText(commandId)
                || hasText(outboxId)
                || hasText(approvalFactId)
                || hasText(clarificationFactId)
                || hasText(toolCode)
                || hasText(requestedPolicyVersion);
        return hasCheckpointLocator && hasResumeLocator;
    }

    /**
     * 与新记录合并，非空字段优先使用新记录。
     *
     * <p>为什么需要合并：第一次 Python 请求可能只带 commandId，第二次可能补充 approvalFactId；
     * 如果直接覆盖，会把之前已经学到的低敏 locator 丢掉。合并后 index 可以渐进补全。</p>
     */
    public AgentToolActionResumeLocatorIndexRecord merge(AgentToolActionResumeLocatorIndexRecord incoming) {
        if (incoming == null) {
            return this;
        }
        return new AgentToolActionResumeLocatorIndexRecord(
                first(incoming.checkpointId, checkpointId),
                first(incoming.threadId, threadId),
                first(incoming.sessionId, sessionId),
                first(incoming.runId, runId),
                first(incoming.commandId, commandId),
                first(incoming.outboxId, outboxId),
                first(incoming.approvalFactId, approvalFactId),
                first(incoming.clarificationFactId, clarificationFactId),
                first(incoming.toolCode, toolCode),
                first(incoming.requestedPolicyVersion, requestedPolicyVersion),
                first(incoming.tenantId, tenantId),
                first(incoming.projectId, projectId),
                first(incoming.actorId, actorId),
                incoming.updatedAt == null ? Instant.now() : incoming.updatedAt
        );
    }

    private static String first(String value, String fallback) {
        return hasText(value) ? value.trim() : text(fallback);
    }

    private static String text(String value) {
        if (value == null) {
            return null;
        }
        String text = value.trim();
        return text.isEmpty() ? null : text;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
