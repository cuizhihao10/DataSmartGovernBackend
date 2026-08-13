/**
 * @Author : Cui
 * @Date: 2026/08/11 19:45
 * @Description DataSmart Govern Backend - AgentAutopilotRecoveryTriggerEvent.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.autopilot;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * agent-runtime 消费的 data-sync Autopilot Kafka 事件镜像。
 *
 * <p>Java 使用强类型 record 反序列化，可以在调用 Python 前明确检查每个身份、循环和指纹字段。
 * 事件只是一条“失败发生了”的通知，不能替代 session/run/authorization 的持久事实。</p>
 */
public record AgentAutopilotRecoveryTriggerEvent(
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
     * 对 Kafka 反序列化得到的可变集合建立第一层防御性副本。
     *
     * <p>缺失的 {@code issueCodes} 和 {@code authorizationSnapshot} 会分别规范为不可修改的空集合和空 Map；
     * 非空输入会被浅复制，输出 record 因而不能被监听线程之外的调用方直接改写。本构造器不验证事件来源、
     * session、授权或摘要，也不会去重 eventId 或触发恢复，信任边界仍由触发器验证服务负责。</p>
     *
     * <p>保留原始 eventId、指纹和授权快照字段可让后续服务复算证据；浅复制不保护嵌套 Map 值，且不提供
     * Kafka 投递幂等性，后者由持久 receipt 和状态机处理。</p>
     */
    public AgentAutopilotRecoveryTriggerEvent {
        issueCodes = issueCodes == null ? List.of() : List.copyOf(issueCodes);
        authorizationSnapshot = authorizationSnapshot == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(authorizationSnapshot));
    }
}
