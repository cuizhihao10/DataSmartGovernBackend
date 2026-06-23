/**
 * @Author : Cui
 * @Date: 2026/06/17 00:00
 * @Description DataSmart Govern Backend - AgentToolActionWorkerReceiptIndexRecord.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import java.time.Instant;
import java.util.Locale;

/**
 * 受控工具动作 worker/dry-run receipt 的低敏索引记录。
 *
 * <p>这个记录不是 runtime event 原文，也不是 task-management 的完整任务回执。
 * 它只保存恢复预检必须使用的机器状态：commandId、run/session、租户/项目/actor、outcome、taskStatus、
 * preCheckPassed、sideEffectExecuted、errorCode 和 Java 控制面 replaySequence。</p>
 *
 * <p>为什么要从通用 runtime event projection 中再拆一个专用索引：
 * 通用 projection 适合时间线展示和回放，但恢复事实包查询真正需要的是“按 commandId 快速找到最新 receipt”。
 * 如果每次都扫描 run/session 的事件热窗口，数据量增长后会出现两个问题：
 * 1. 性能上，异常 run 或事件重放可能让一次恢复预检扫描大量无关事件；
 * 2. 产品语义上，receipt 是恢复执行前的事实源，应有独立可持久化、可审计、可迁移的模型。</p>
 *
 * <p>安全边界：
 * 1. 不保存 message，因为 message 即使经过清洗，也仍然可能包含业务诊断文本；
 * 2. 不保存 payloadKey、payload body、target endpoint、工具参数、SQL、prompt、样本数据、模型输出、凭证或内部 URL；
 * 3. toolCode 只作为低敏目录标识保存，后续可用于避免同一个 commandId 被错误地关联到不同工具动作。</p>
 */
public record AgentToolActionWorkerReceiptIndexRecord(
        /** runtime event projection 的 identityKey，用于幂等物化同一条 receipt。 */
        String eventIdentityKey,

        /** Java command outbox 的 commandId，是恢复事实包查询 worker receipt 的主要业务定位符。 */
        String commandId,

        /** 租户边界，查询时必须与 gateway/permission-admin 下发的数据范围求交集。 */
        String tenantId,

        /** 项目边界，用于 PROJECT 数据范围和项目负责人视图过滤。 */
        String projectId,

        /** actor 边界，用于 SELF/PROJECT_OWNER 等后续细粒度恢复策略。 */
        String actorId,

        /** Agent Run ID，用于把 receipt 限定在一次运行内，避免跨 run 误采信。 */
        String runId,

        /** Agent Session ID，用于会话级恢复和 timeline 诊断聚合。 */
        String sessionId,

        /** 工具编码，只作为低敏目录标识，不代表工具参数或目标地址。 */
        String toolCode,

        /** task-management 任务状态摘要，例如 RUNNING、FAILED，只用于排障和状态解释。 */
        String taskStatus,

        /** dry-run/worker receipt 结果，例如 DRY_RUN_PASSED、FAILED_PRECHECK。 */
        String outcome,

        /** true 表示执行前复核通过；false 表示缺少事实、等待条件或被执行前阻断。 */
        Boolean preCheckPassed,

        /** dry-run receipt 必须保持 false；真实 command-worker receipt 只有通过独立回执合同校验后才允许为 true。 */
        Boolean sideEffectExecuted,

        /** 低敏机器错误码，用于聚合和推荐动作，不保存人类 message。 */
        String errorCode,

        /** Java runtime event projection 分配的回放游标，用于确定最新 receipt。 */
        Long replaySequence,

        /** receipt 事件被 Java 控制面消费/接收的时间。 */
        Instant consumedAt,

        /** 索引记录写入或刷新时间，用于后续 TTL、归档和运维诊断。 */
        Instant indexedAt
) {

    public static final String PAYLOAD_POLICY =
            "LOW_SENSITIVE_RECEIPT_INDEX_NO_MESSAGE_NO_ARGUMENTS_NO_PROMPT_NO_SQL_NO_PAYLOAD_BODY";

    public AgentToolActionWorkerReceiptIndexRecord {
        eventIdentityKey = safeText(eventIdentityKey, 240);
        commandId = safeText(commandId, 180);
        tenantId = safeText(tenantId, 80);
        projectId = safeText(projectId, 80);
        actorId = safeText(actorId, 120);
        runId = safeText(runId, 180);
        sessionId = safeText(sessionId, 180);
        toolCode = safeText(toolCode, 180);
        taskStatus = safeText(taskStatus, 80);
        outcome = safeText(outcome, 120);
        preCheckPassed = Boolean.TRUE.equals(preCheckPassed);
        sideEffectExecuted = Boolean.TRUE.equals(sideEffectExecuted);
        errorCode = safeText(errorCode, 160);
        consumedAt = consumedAt == null ? Instant.now() : consumedAt;
        indexedAt = indexedAt == null ? Instant.now() : indexedAt;
    }

    /**
     * 判断记录是否具备索引价值。
     *
     * <p>commandId 是恢复事实包的核心定位符，没有 commandId 的 receipt 即使能展示在 timeline 中，
     * 也不能可靠回答“这条 command 是否已经被 worker/dry-run 看过”。</p>
     */
    public boolean indexable() {
        return hasText(eventIdentityKey) && hasText(commandId);
    }

    /**
     * 判断当前 receipt 是否代表执行前被明确拒绝。
     *
     * <p>恢复预检里要区分“还在等待某些条件”和“服务端已经明确拒绝”。
     * FAILED_PRECHECK 表示 dry-run 的执行前复核失败，应进入 rejectedFactTypes；
     * 其他未通过状态更可能是等待审批、等待 payload 或容量限制，因此保留 retryable 语义。</p>
     */
    public boolean rejectedBeforeExecution() {
        return !Boolean.TRUE.equals(preCheckPassed)
                && outcome != null
                && outcome.startsWith("FAILED");
    }

    /**
     * 按 replaySequence/consumedAt 选择更新的 receipt。
     *
     * <p>同一个 command 可能经历多次 dry-run 尝试或 Kafka 重放。恢复事实包应该以最新 Java 控制面游标为准；
     * 如果 replaySequence 缺失，则退化到 consumedAt 比较，仍然保持稳定的“较新记录优先”语义。</p>
     */
    public boolean newerThan(AgentToolActionWorkerReceiptIndexRecord other) {
        if (other == null) {
            return true;
        }
        long leftSequence = replaySequence == null ? -1L : replaySequence;
        long rightSequence = other.replaySequence() == null ? -1L : other.replaySequence();
        if (leftSequence != rightSequence) {
            return leftSequence > rightSequence;
        }
        return consumedAt != null && other.consumedAt() != null && consumedAt.isAfter(other.consumedAt());
    }

    private static String safeText(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String text = value.trim();
        if (looksSensitive(text)) {
            return null;
        }
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }

    private static boolean looksSensitive(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.contains("select ")
                || lower.contains("insert ")
                || lower.contains("update ")
                || lower.contains("delete ")
                || lower.contains("authorization:")
                || lower.contains("bearer ")
                || lower.contains("password")
                || lower.contains("token")
                || lower.contains("prompt:")
                || lower.contains("api_key")
                || lower.contains("apikey")
                || lower.contains("http://")
                || lower.contains("https://");
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
