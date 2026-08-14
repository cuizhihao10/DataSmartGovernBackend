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
        Map<String, Object> autopilotRecoveryFacts,
        Map<String, Object> repairParameters,
        Map<String, Object> operatorHandoff,
        String modelFailureReasonCode,
        String modelFailureSource) {

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
        repairParameters = immutableMap(repairParameters);
        operatorHandoff = immutableMap(operatorHandoff);
    }

    /**
     * 保留新增模型故障分类字段之前的完整构造器，避免测试和模块内固定候选需要机械补空值。
     *
     * <p>正常候选、人工关注和普通业务失败没有模型传输故障分类，因此两个新字段明确设为 {@code null}。
     * 只有 Python 反序列化的 {@code RECOVERY_PLANNING_MODEL_FAILED} 响应会填充它们；Java 后续仍按固定
     * 白名单判定是否允许 Kafka 有界重投。</p>
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
            Map<String, Object> quarantinePreview,
            Map<String, Object> autopilotRecoveryFacts,
            Map<String, Object> repairParameters,
            Map<String, Object> operatorHandoff) {
        this(schemaVersion, eventId, status, reasonCode, action, riskLevel, idempotent, repairFingerprint,
                errorFingerprint, confidence, evidenceAvailable, evidenceAudit, evidenceScope, retrievalDecision,
                retrievalStrategy, retrievalAudit, strategyChanged, checkpointThreadId, payloadPolicy,
                quarantinePreview, autopilotRecoveryFacts, repairParameters, operatorHandoff, null, null);
    }

    /**
     * 为不建议隔离动作的调用方保留原恢复候选构造器。
     *
     * <p>旧版规划响应和普通重试候选没有隔离专用预览，构造器通过传入空预览 Map 保持同一不可变合同。
     * {@code APPLY_QUARANTINE} 候选仍会在后续被独立 Java 预览验证器拒绝，除非所有必填字段齐全。该构造器
     * 不执行 I/O、授权或策略评估。</p>
     *
     * @param schemaVersion 版本化 Python/Java 恢复候选 schema
     * @param eventId 不可变恢复触发标识
     * @param status 有限规划结果
     * @param reasonCode 低敏规划原因码
     * @param action 建议恢复动作
     * @param riskLevel 建议风险等级
     * @param idempotent 候选是否声明动作可安全重放
     * @param repairFingerprint 规划器修复指纹
     * @param errorFingerprint 与触发器绑定的错误指纹
     * @param confidence 0 到 1 之间的规划置信度
     * @param evidenceAvailable 是否定位到诊断证据
     * @param evidenceAudit 诊断证据摘要
     * @param evidenceScope 候选证据范围
     * @param retrievalDecision 检索决策摘要
     * @param retrievalStrategy 检索策略摘要
     * @param retrievalAudit 检索审计摘要
     * @param strategyChanged 策略变化的规划器说明
     * @param checkpointThreadId 低敏规划 checkpoint 引用
     * @param payloadPolicy 固定载荷分类
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
                retrievalStrategy, retrievalAudit, strategyChanged, checkpointThreadId, payloadPolicy,
                Map.of(), Map.of(), Map.of(), Map.of(), null, null);
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
                quarantinePreview, Map.of(), Map.of(), Map.of(), null, null);
    }

    /**
     * 保持已有携带重试事实的调用方兼容，并把新修复参数及人工处置包明确设为空。
     *
     * <p>旧响应只能继续执行原有重试或隔离动作；新增动作缺少 {@code repairParameters} 时会在独立
     * Java 验证器中失败关闭，不会因为兼容构造器而获得默认修复权限。</p>
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
            Map<String, Object> quarantinePreview,
            Map<String, Object> autopilotRecoveryFacts) {
        this(schemaVersion, eventId, status, reasonCode, action, riskLevel, idempotent, repairFingerprint,
                errorFingerprint, confidence, evidenceAvailable, evidenceAudit, evidenceScope, retrievalDecision,
                retrievalStrategy, retrievalAudit, strategyChanged, checkpointThreadId, payloadPolicy,
                quarantinePreview, autopilotRecoveryFacts, Map.of(), Map.of(), null, null);
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
