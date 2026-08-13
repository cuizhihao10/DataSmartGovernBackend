/**
 * @Author : Cui
 * @Date: 2026/08/11 20:05
 * @Description DataSmart Govern Backend - AgentAutopilotRecoveryPlanResponse.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.autopilot;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Python Autopilot Recovery 协调器返回的低敏候选合同。
 *
 * <p>``evidenceAudit`` 和 ``retrievalAudit`` 只包含摘要、引用 ID、来源类型、时间和 digest；不包含
 * RAG answer、文档正文、原始日志或模型输出。所有字段在进入 Java 策略前还会再次验证。</p>
 */
public record AgentAutopilotRecoveryPlanResponse(
        String schemaVersion,
        String eventId,
        String status,
        String reasonCode,
        String action,
        String riskLevel,
        boolean idempotent,
        String repairFingerprint,
        String errorFingerprint,
        double confidence,
        boolean evidenceAvailable,
        Map<String, Object> evidenceAudit,
        Map<String, Object> evidenceScope,
        String retrievalDecision,
        String retrievalStrategy,
        Map<String, Object> retrievalAudit,
        boolean strategyChanged,
        String checkpointThreadId,
        String payloadPolicy,
        Map<String, Object> quarantinePreview,
        Map<String, Object> autopilotRecoveryFacts) {

    /**
     * 为 Python 返回的三个审计 Map 建立不可修改的第一层快照。
     *
     * <p>输入 Map 可以为空，空值会规范为 {@link Map#of()}；输出 record 保存原有插入顺序的浅复制。
     * 本构造器没有 I/O、副作用或权限判定，不能把模型建议变成执行许可。它只防止调用方替换第一层审计字段，
     * 不会深复制嵌套对象，因此证据验证器仍必须在使用时重新校验 digest、范围和时间。</p>
     *
     * <p>构造本身不去重或缓存响应；同一合同反复反序列化会得到等价的浅冻结视图，实际恢复幂等性仍由
     * eventId、case receipt 和 data-sync 状态机保证。</p>
     */
    public AgentAutopilotRecoveryPlanResponse {
        evidenceAudit = immutableMap(evidenceAudit);
        evidenceScope = immutableMap(evidenceScope);
        retrievalAudit = immutableMap(retrievalAudit);
        quarantinePreview = immutableMap(quarantinePreview);
        autopilotRecoveryFacts = immutableMap(autopilotRecoveryFacts);
    }

    /**
     * Preserves the original recovery-candidate constructor for callers that do not propose quarantine.
     *
     * <p>Older planner responses and ordinary retry candidates have no quarantine-specific preview. They retain the
     * same immutable contract by receiving an empty preview map; an {@code APPLY_QUARANTINE} candidate is still
     * rejected later unless the independent Java preview verifier receives every required field. This constructor
     * does not perform I/O, authorization, or policy evaluation.</p>
     *
     * @param schemaVersion versioned Python/Java recovery-candidate schema
     * @param eventId immutable recovery-trigger identifier
     * @param status finite planner outcome
     * @param reasonCode low-sensitive planner reason
     * @param action proposed recovery action
     * @param riskLevel proposed risk level
     * @param idempotent whether the candidate claims a replay-safe action
     * @param repairFingerprint planner repair fingerprint
     * @param errorFingerprint trigger-bound error fingerprint
     * @param confidence planner confidence between zero and one
     * @param evidenceAvailable whether diagnostic evidence was located
     * @param evidenceAudit diagnostic evidence summary
     * @param evidenceScope candidate evidence scope
     * @param retrievalDecision retrieval decision summary
     * @param retrievalStrategy retrieval strategy summary
     * @param retrievalAudit retrieval audit summary
     * @param strategyChanged planner explanation for a changed strategy
     * @param checkpointThreadId low-sensitive planner checkpoint reference
     * @param payloadPolicy fixed payload classification
     */
    public AgentAutopilotRecoveryPlanResponse(
            String schemaVersion,
            String eventId,
            String status,
            String reasonCode,
            String action,
            String riskLevel,
            boolean idempotent,
            String repairFingerprint,
            String errorFingerprint,
            double confidence,
            boolean evidenceAvailable,
            Map<String, Object> evidenceAudit,
            Map<String, Object> evidenceScope,
            String retrievalDecision,
            String retrievalStrategy,
            Map<String, Object> retrievalAudit,
            boolean strategyChanged,
            String checkpointThreadId,
            String payloadPolicy) {
        this(schemaVersion, eventId, status, reasonCode, action, riskLevel, idempotent, repairFingerprint,
                errorFingerprint, confidence, evidenceAvailable, evidenceAudit, evidenceScope, retrievalDecision,
                retrievalStrategy, retrievalAudit, strategyChanged, checkpointThreadId, payloadPolicy, Map.of());
    }

    /**
     * 保留现有调用方使用的响应构造器，并在 Java 源码边界将新增的重试事实设为可选。
     * 缺少该映射时会有意判定为不具备资格：当 {@code RETRY_EXECUTION} 缺少这些事实时，执行策略和
     * data-sync 控制平面都会采取默认不自动执行的保守处理。
     */
    public AgentAutopilotRecoveryPlanResponse(
            String schemaVersion,
            String eventId,
            String status,
            String reasonCode,
            String action,
            String riskLevel,
            boolean idempotent,
            String repairFingerprint,
            String errorFingerprint,
            double confidence,
            boolean evidenceAvailable,
            Map<String, Object> evidenceAudit,
            Map<String, Object> evidenceScope,
            String retrievalDecision,
            String retrievalStrategy,
            Map<String, Object> retrievalAudit,
            boolean strategyChanged,
            String checkpointThreadId,
            String payloadPolicy,
            Map<String, Object> quarantinePreview) {
        this(schemaVersion, eventId, status, reasonCode, action, riskLevel, idempotent, repairFingerprint,
                errorFingerprint, confidence, evidenceAvailable, evidenceAudit, evidenceScope, retrievalDecision,
                retrievalStrategy, retrievalAudit, strategyChanged, checkpointThreadId, payloadPolicy,
                quarantinePreview, Map.of());
    }

    /**
     * 复制一个可选审计 Map 并禁止通过返回引用修改第一层字段。
     *
     * <p>该纯函数不解释证据内容、不校验权限，也不写入缓存；它的输出只为后续验证提供稳定的容器边界。
     * 嵌套值仍按原引用保留，因而不能替代 {@code evidenceDigest} 的复算或跨服务的幂等控制。</p>
     *
     * @param value Python 合同中的可选审计字段
     * @return 空的不可修改 Map，或保留插入顺序的不可修改浅副本
     */
    private static Map<String, Object> immutableMap(Map<String, Object> value) {
        return value == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(value));
    }
}
