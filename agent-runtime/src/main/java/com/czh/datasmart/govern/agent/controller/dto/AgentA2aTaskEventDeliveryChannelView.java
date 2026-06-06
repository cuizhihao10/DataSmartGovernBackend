/**
 * @Author : Cui
 * @Date: 2026/06/06 12:55
 * @Description DataSmart Govern Backend - AgentA2aTaskEventDeliveryChannelView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.util.List;

/**
 * A2A Task 事件投递通道视图。
 *
 * <p>同一个 task event 可以被不同通道消费：内部 runtime timeline 服务审计，A2A streaming 服务实时状态，
 * push notification 服务异步 webhook，指标系统服务告警，task history 服务断线恢复。把通道契约单独列出来，
 * 可以避免某个通道为了自身方便篡改事件 payload，导致事实源不一致。</p>
 *
 * @param channelCode 通道编码
 * @param channelType 通道类型，例如 INTERNAL_PROJECTION、A2A_STREAM、A2A_PUSH、METRICS、AUDIT
 * @param enabled 当前是否启用。未启用通道也可以先定义契约
 * @param deliverySemantics 投递语义，例如 at-least-once、best-effort、query-only
 * @param replaySupported 是否支持断线或故障后的回放
 * @param payloadShape 该通道允许的 payload 形态
 * @param securityNotes 安全要求，例如签名、租户边界、低敏字段、幂等确认
 * @param operationalNotes 运维要求，例如重试、超时、低基数指标和告警
 */
public record AgentA2aTaskEventDeliveryChannelView(
        String channelCode,
        String channelType,
        boolean enabled,
        String deliverySemantics,
        boolean replaySupported,
        String payloadShape,
        List<String> securityNotes,
        List<String> operationalNotes
) {
}
