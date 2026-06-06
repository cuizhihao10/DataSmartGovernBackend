/**
 * @Author : Cui
 * @Date: 2026/06/06 12:55
 * @Description DataSmart Govern Backend - AgentA2aTaskRuntimeEventContractPreviewResponse.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.time.Instant;
import java.util.List;

/**
 * A2A Task Runtime Event 契约只读预览响应。
 *
 * <p>5.28 已经固定了 A2A Task 的状态机；本响应继续向前一步，描述未来真实 task endpoint 在每个关键节点
 * 应该写入哪些低敏 runtime event，以及这些事件如何服务于 A2A streaming、push notification、任务查询、
 * 审计回放和低基数指标。</p>
 *
 * <p>它仍然不是执行接口：不会创建 task，不会写 runtime event projection，不会写 MySQL，不会投递 outbox，
 * 也不会触发 Python Runtime。它的价值在于先把“事件事实契约”固定下来，避免后续真实 `message:send`、
 * `tasks/cancel`、streaming 或 push notification 分别发明一套事件字段，导致 trace、前端和审计台互相不兼容。</p>
 *
 * @param schemaVersion DataSmart 对该预览响应的 schema 版本
 * @param generatedAt 生成时间；只表示本次控制面预览构建时刻
 * @param protocolFamily 当前协议族，固定为 A2A
 * @param protocolVersion 当前对齐的 A2A 主版本
 * @param previewOnly 是否只读预览。true 表示不会产生任何真实任务事件
 * @param eventPublishingEnabled 真实 task 事件发布是否已启用。当前为 false
 * @param taskEndpointEnabled 真实 task endpoint 是否已启用。当前为 false
 * @param payloadPolicy 事件载荷策略，明确只能保存低敏状态摘要
 * @param contracts 未来 task 生命周期中的事件契约列表
 * @param payloadFields 统一事件 payload 字段白名单，禁止在事件里自由塞入未治理字段
 * @param deliveryChannels 事件可以被哪些通道消费，例如内部 timeline、A2A stream、push、指标和审计
 * @param orderingAndReplayPolicy 排序、幂等、回放、补偿策略说明
 * @param persistencePolicy 事件、task history、artifact metadata、outbox、worker receipt 的持久化分层说明
 * @param nextSteps 下一步建议，避免直接跳到有副作用执行
 */
public record AgentA2aTaskRuntimeEventContractPreviewResponse(
        String schemaVersion,
        Instant generatedAt,
        String protocolFamily,
        String protocolVersion,
        boolean previewOnly,
        boolean eventPublishingEnabled,
        boolean taskEndpointEnabled,
        String payloadPolicy,
        List<AgentA2aTaskRuntimeEventContractView> contracts,
        List<AgentA2aTaskEventPayloadFieldView> payloadFields,
        List<AgentA2aTaskEventDeliveryChannelView> deliveryChannels,
        List<String> orderingAndReplayPolicy,
        List<String> persistencePolicy,
        List<String> nextSteps
) {
}
