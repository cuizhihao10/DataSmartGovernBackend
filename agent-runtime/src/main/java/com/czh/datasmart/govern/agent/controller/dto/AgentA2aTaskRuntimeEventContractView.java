/**
 * @Author : Cui
 * @Date: 2026/06/06 12:55
 * @Description DataSmart Govern Backend - AgentA2aTaskRuntimeEventContractView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.util.List;

/**
 * 单个 A2A Task runtime event 契约。
 *
 * <p>这里的 event contract 是“未来真实事件应该长什么样”的控制面说明。每条契约都要绑定 A2A 状态、
 * DataSmart 内部阶段、触发来源、可见性和投递语义，这样后续 streaming、push、任务查询和审计回放可以消费
 * 同一套事实源，而不是各自解析不同格式。</p>
 *
 * @param eventType 事件类型，使用稳定小写点分命名，便于投影、筛选和兼容演进
 * @param schemaVersion 单个事件 payload schema 版本
 * @param stage DataSmart timeline 阶段名，用于前端和审计台展示
 * @param a2aState 该事件对应的 A2A TaskState；artifact/push 类事件可以沿用当前 task 状态
 * @param internalPhase DataSmart 内部治理阶段
 * @param trigger 触发动作或来源，例如提交、预检通过、用户补输入、审批通过、worker receipt
 * @param terminalEvent 是否终态事件；终态事件写入后不得再写 working 类事件
 * @param streamEventKind 对应 A2A streaming 的事件类型，如 TASK_STATUS_UPDATE 或 TASK_ARTIFACT_UPDATE
 * @param pushDeliveryEligible 是否允许进入 push notification。true 仍不代表当前已启用真实 push
 * @param retentionClass 保留等级，例如 HOT_TIMELINE、AUDIT_FACT 或 METRIC_ONLY
 * @param payloadFieldNames 允许出现的字段名白名单。字段含义由统一 payloadFields 描述
 * @param forbiddenPayloadSummary 禁止保存的载荷摘要，使用中文说明而不是输出敏感英文键
 * @param consumerNotes 消费方说明，提示前端、网关、Python Runtime、审计台或指标系统如何使用
 */
public record AgentA2aTaskRuntimeEventContractView(
        String eventType,
        String schemaVersion,
        String stage,
        String a2aState,
        String internalPhase,
        String trigger,
        boolean terminalEvent,
        String streamEventKind,
        boolean pushDeliveryEligible,
        String retentionClass,
        List<String> payloadFieldNames,
        String forbiddenPayloadSummary,
        List<String> consumerNotes
) {
}
