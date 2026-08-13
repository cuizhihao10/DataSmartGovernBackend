/**
 * @Author : Cui
 * @Date: 2026/08/11 18:30
 * @Description DataSmart Govern Backend - SyncAutopilotRecoveryTriggerEvent.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * data-sync 发布给 Agent Runtime 的低敏恢复触发合同。
 *
 * <p>事件只描述“哪个受授权任务的哪次执行失败、这是第几轮、错误和上一轮修复指纹是什么”。它不携带
 * SQL、连接串、凭据、原始日志、样本行、prompt 或模型输出。Agent Runtime 消费后必须重新
 * 加载 root session/run 并再次验证授权，不能把收到事件等同于获得执行权限。</p>
 */
public record SyncAutopilotRecoveryTriggerEvent(
        String schemaVersion,
        String eventId,
        String rootSessionId,
        String rootRunId,
        Long tenantId,
        Long applicationId,
        Long projectId,
        String userId,
        String actorId,
        String agentId,
        String delegationId,
        Long syncTaskId,
        Long rootExecutionId,
        Long currentExecutionId,
        int cycle,
        int maxRecoveryCycles,
        String deadlineAt,
        String errorFingerprint,
        int repeatedErrorCount,
        String previousRepairFingerprint,
        List<String> issueCodes,
        Map<String, Object> authorizationSnapshot,
        String authorizationSnapshotDigest,
        String triggeredAt) {

    /**
     * Creates an immutable, transport-safe recovery trigger contract from already authorized facts.
     *
     * <p>The canonical constructor accepts identifiers, bounded recovery counters, safe fingerprints, issue
     * codes, and a whitelisted authorization snapshot. It makes defensive immutable copies of the collections
     * so later caller mutation cannot change what the outbox serializes. {@link LinkedHashMap} preserves field
     * order, allowing a consumer to recompute a stable snapshot digest without a false mismatch caused by an
     * unspecified map iteration order.</p>
     *
     * <p>Construction has no I/O, state transition, or policy evaluation and is repeatable for equivalent input.
     * Blank prior-repair fingerprints normalize to {@code null}, which gives the payload one stable absence
     * representation. This type is a security boundary rather than an execution grant: it intentionally carries
     * no SQL, credentials, source records, prompts, logs, or model output, and consumers must reauthorize.</p>
     */
    public SyncAutopilotRecoveryTriggerEvent {
        issueCodes = issueCodes == null ? List.of() : List.copyOf(issueCodes);
        authorizationSnapshot = authorizationSnapshot == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(authorizationSnapshot));
        if (previousRepairFingerprint != null && previousRepairFingerprint.isBlank()) {
            previousRepairFingerprint = null;
        }
    }
}
