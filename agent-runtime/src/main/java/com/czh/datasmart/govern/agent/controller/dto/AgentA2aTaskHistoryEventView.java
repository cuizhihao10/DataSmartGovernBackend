/**
 * @Author : Cui
 * @Date: 2026/06/06 13:03
 * @Description DataSmart Govern Backend - AgentA2aTaskHistoryEventView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.time.Instant;

/**
 * A2A Task 历史事件低敏视图。
 *
 * <p>该视图来自 5.29 的 runtime event 契约，用于演示 `tasks/get(historyLength=N)` 或断线恢复时如何返回
 * 最近状态历史。它只包含 sequence、状态、阶段、事件类型、原因类别和 artifact 引用，不包含任何正文。</p>
 *
 * @param sequence 同一 task 内单调递增序号
 * @param eventType DataSmart runtime event 类型
 * @param a2aState 事件对应的 A2A TaskState
 * @param internalPhase DataSmart 内部阶段
 * @param streamEventKind 可映射的 A2A stream event 类型
 * @param occurredAt 事件发生时间
 * @param terminal 是否终态事件
 * @param reasonCode 状态原因类别
 * @param artifactRef artifact 引用；非 artifact 事件为空
 * @param lowSensitiveSummary 低敏摘要说明
 * @param replayNote 回放和断线恢复说明
 */
public record AgentA2aTaskHistoryEventView(
        long sequence,
        String eventType,
        String a2aState,
        String internalPhase,
        String streamEventKind,
        Instant occurredAt,
        boolean terminal,
        String reasonCode,
        String artifactRef,
        String lowSensitiveSummary,
        String replayNote
) {
}
