/**
 * @Author : Cui
 * @Date: 2026/06/17 00:00
 * @Description DataSmart Govern Backend - AgentToolActionClarificationFactRecord.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * Agent 工具动作“用户澄清事实”记录。
 *
 * <p>在 Codex、Claude Code、LangGraph、OpenAI Agents 这类 Agent Host 中，
 * 人工澄清不是普通的聊天文本，它经常会成为“暂停点能否继续”的前置条件。
 * 例如模型准备创建任务草稿，但缺少项目 ID、执行时间、审批人或数据源范围时，
 * Runtime 会暂停并要求用户补充；用户补充后，继续执行入口不能只相信请求体里传了
 * {@code clarificationFactId}，而应由 Java 控制面回查“这个澄清事实是否真的已经登记、
 * 是否属于当前租户/项目/actor/run/session/command/tool、是否过期或撤销”。</p>
 *
 * <p>本记录只保存低敏控制面元数据，不保存澄清原文、prompt、SQL、工具参数、
 * 样本数据、模型输出、凭证或内部 endpoint。真实澄清内容后续如果需要留存，应进入
 * 独立的加密对象存储、审批系统或审计中心，并只在受控页面中按权限展示。</p>
 *
 * @param clarificationFactId 澄清事实 ID，由前端/网关/Agent Host 生成并在恢复预检时引用。
 * @param sessionId Agent 会话 ID，用于把澄清事实限定到一次对话窗口。
 * @param runId Agent run ID，用于防止旧 run 的澄清被错误复用到新 run。
 * @param commandId 受控工具动作 commandId；为空时表示该澄清仍处于较早的计划阶段。
 * @param toolCode 工具编码，用于校验澄清事实是否服务于同一类工具动作。
 * @param requestedPolicyVersion 工具执行策略版本，用于发现澄清事实与当前策略不一致。
 * @param tenantId 租户 ID，所有澄清事实必须有明确租户边界。
 * @param projectId 项目 ID，PROJECT 数据范围下必须命中授权项目集合。
 * @param actorId 登记该澄清事实的操作者 ID，用于 SELF/PROJECT_OWNER 等范围校验。
 * @param status 当前事实状态，当前支持 AVAILABLE、REVOKED、REJECTED。
 * @param evidenceCodes 低敏证据码，例如 USER_CLARIFICATION_CAPTURED，不包含原文。
 * @param issueCodes 低敏问题码，例如 CLARIFICATION_SCOPE_CHANGED，不包含原文。
 * @param expiresAt 事实过期时间；过期后恢复预检会按拒绝处理，避免旧澄清长期复用。
 * @param createdAt 首次登记时间。
 * @param updatedAt 最近更新时间。
 */
public record AgentToolActionClarificationFactRecord(
        String clarificationFactId,
        String sessionId,
        String runId,
        String commandId,
        String toolCode,
        String requestedPolicyVersion,
        String tenantId,
        String projectId,
        String actorId,
        String status,
        List<String> evidenceCodes,
        List<String> issueCodes,
        Instant expiresAt,
        Instant createdAt,
        Instant updatedAt
) {

    public static final String STATUS_AVAILABLE = "AVAILABLE";
    public static final String STATUS_REVOKED = "REVOKED";
    public static final String STATUS_REJECTED = "REJECTED";

    /**
     * record 紧凑构造器负责做轻量归一化。
     *
     * <p>这里不做“请求是否合法”的强校验，因为 record 既会被 Controller 登记服务使用，
     * 也会被单元测试和未来 MySQL mapper 还原使用。业务校验放在 registration service，
     * record 只保证下游拿到的列表不可变、状态大写、空白字符串不会保留噪音。</p>
     */
    public AgentToolActionClarificationFactRecord {
        clarificationFactId = text(clarificationFactId);
        sessionId = text(sessionId);
        runId = text(runId);
        commandId = text(commandId);
        toolCode = text(toolCode);
        requestedPolicyVersion = text(requestedPolicyVersion);
        tenantId = text(tenantId);
        projectId = text(projectId);
        actorId = text(actorId);
        status = status == null || status.isBlank()
                ? STATUS_AVAILABLE
                : status.trim().toUpperCase(Locale.ROOT);
        evidenceCodes = evidenceCodes == null ? List.of() : List.copyOf(evidenceCodes);
        issueCodes = issueCodes == null ? List.of() : List.copyOf(issueCodes);
    }

    /**
     * 判断当前记录是否具备进入索引的最小条件。
     *
     * <p>澄清事实不同于 locator index，必须至少具备事实 ID、租户、项目、actor、run/session
     * 中的核心边界。否则它即使被写入内存，也无法在恢复预检中安全验真。</p>
     */
    public boolean indexable() {
        return hasText(clarificationFactId)
                && hasText(tenantId)
                && hasText(projectId)
                && hasText(actorId)
                && (hasText(runId) || hasText(sessionId));
    }

    /**
     * 判断事实在指定时间点是否已经过期。
     *
     * <p>过期事实按“已找到但不可采信”处理，而不是简单当作不存在。
     * 这样管理员在诊断恢复失败时可以区分“用户从未补充”和“用户补充过但已经过期”。</p>
     */
    public boolean expiredAt(Instant now) {
        return expiresAt != null && now != null && !expiresAt.isAfter(now);
    }

    /**
     * 判断事实是否处于可采信状态。
     *
     * <p>AVAILABLE 只表示事实状态未被撤销或拒绝；真正是否可用于当前恢复，还必须由 evaluator
     * 继续检查租户、项目、actor、run、session、command、tool 和 policyVersion。</p>
     */
    public boolean statusAvailable() {
        return STATUS_AVAILABLE.equals(status);
    }

    /**
     * 合并同一 factId 的重复登记。
     *
     * <p>上游可能因为网络重试或前端重新提交而多次登记同一澄清事实。
     * 这里采用“后写覆盖状态和时间，非空字段优先补齐”的语义，保证幂等重放不会产生多条事实。
     * 注意：这里仍然不合并或保存任何用户澄清原文。</p>
     */
    public AgentToolActionClarificationFactRecord merge(AgentToolActionClarificationFactRecord incoming) {
        if (incoming == null) {
            return this;
        }
        return new AgentToolActionClarificationFactRecord(
                firstText(incoming.clarificationFactId, clarificationFactId),
                firstText(incoming.sessionId, sessionId),
                firstText(incoming.runId, runId),
                firstText(incoming.commandId, commandId),
                firstText(incoming.toolCode, toolCode),
                firstText(incoming.requestedPolicyVersion, requestedPolicyVersion),
                firstText(incoming.tenantId, tenantId),
                firstText(incoming.projectId, projectId),
                firstText(incoming.actorId, actorId),
                firstText(incoming.status, status),
                mergeCodes(evidenceCodes, incoming.evidenceCodes),
                mergeCodes(issueCodes, incoming.issueCodes),
                incoming.expiresAt == null ? expiresAt : incoming.expiresAt,
                createdAt == null ? incoming.createdAt : createdAt,
                incoming.updatedAt == null ? updatedAt : incoming.updatedAt
        );
    }

    private static List<String> mergeCodes(List<String> left, List<String> right) {
        List<String> merged = new java.util.ArrayList<>();
        if (left != null) {
            left.stream().filter(AgentToolActionClarificationFactRecord::hasText).forEach(merged::add);
        }
        if (right != null) {
            right.stream()
                    .filter(AgentToolActionClarificationFactRecord::hasText)
                    .filter(code -> !merged.contains(code))
                    .forEach(merged::add);
        }
        return List.copyOf(merged);
    }

    private static String firstText(String first, String fallback) {
        return hasText(first) ? first.trim() : text(fallback);
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
