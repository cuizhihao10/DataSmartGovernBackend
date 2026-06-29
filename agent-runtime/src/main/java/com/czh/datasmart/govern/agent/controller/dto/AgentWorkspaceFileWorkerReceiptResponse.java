/**
 * @Author : Cui
 * @Date: 2026/06/29 00:00
 * @Description DataSmart Govern Backend - AgentWorkspaceFileWorkerReceiptResponse.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.util.List;

/**
 * Workspace 文件工具 worker 回执响应。
 *
 * <p>该响应是对通用 command worker receipt 响应的低敏增强：它增加了 payloadReference、payloadDigest、
 * operation、toolCode 和 evidence/issue code，方便 worker、调度器和运维系统确认“本次回执已经绑定到服务端
 * payload fact”。响应仍然不包含文件路径、文件正文、写入内容、contentReference 原值、stdout/stderr、
 * SQL、prompt、凭据或内部 endpoint。</p>
 *
 * @param accepted 回执是否已被 Java runtime 接收。
 * @param duplicate 是否为幂等重复回写。
 * @param identityKey runtime event projection 幂等键。
 * @param eventType 写入的 runtime event 类型。
 * @param outcome 归一化后的 worker outcome。
 * @param sideEffectExecuted 是否声明受控执行已经发生。
 * @param commandId command outbox 指令 ID。
 * @param payloadReference 服务端受控 payload 引用。
 * @param payloadDigest payloadReference 的低敏摘要，用于日志或排障关联。
 * @param toolCode workspace 文件工具编码。
 * @param operation READ/WRITE 操作。
 * @param artifactReferenceType 产物引用类型摘要。
 * @param artifactAvailable 是否声明产物可用。
 * @param evidenceCodes 低敏证据码。
 * @param issueCodes 低敏问题码。
 * @param recommendedActions 后续建议动作。
 * @param payloadPolicy 响应低敏策略。
 */
public record AgentWorkspaceFileWorkerReceiptResponse(
        Boolean accepted,
        Boolean duplicate,
        String identityKey,
        String eventType,
        String outcome,
        Boolean sideEffectExecuted,
        String commandId,
        String payloadReference,
        String payloadDigest,
        String toolCode,
        String operation,
        String artifactReferenceType,
        Boolean artifactAvailable,
        List<String> evidenceCodes,
        List<String> issueCodes,
        List<String> recommendedActions,
        String payloadPolicy
) {
    public AgentWorkspaceFileWorkerReceiptResponse {
        accepted = Boolean.TRUE.equals(accepted);
        duplicate = Boolean.TRUE.equals(duplicate);
        sideEffectExecuted = Boolean.TRUE.equals(sideEffectExecuted);
        artifactAvailable = Boolean.TRUE.equals(artifactAvailable);
        evidenceCodes = evidenceCodes == null ? List.of() : List.copyOf(evidenceCodes);
        issueCodes = issueCodes == null ? List.of() : List.copyOf(issueCodes);
        recommendedActions = recommendedActions == null ? List.of() : List.copyOf(recommendedActions);
    }
}
