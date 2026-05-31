/**
 * @Author : Cui
 * @Date: 2026/05/31 23:50
 * @Description DataSmart Govern Backend - AgentRuntimeEventDisplayView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.util.List;
import java.util.Map;

/**
 * Agent runtime event 的前端展示解释视图。
 *
 * <p>runtime event 的原始投影字段更像“机器事实”：eventType、stage、attributes、replaySequence 等字段
 * 适合审计、断线续传和自动化客户端消费，但前端动作时间线、智能网关审批面板和普通运维控制台不应该每次都硬编码
 * `eventType -> 中文标题 -> 按钮建议` 的映射。否则随着 Agent 事件越来越多，前端会逐渐变成第二套事件解释器。</p>
 *
 * <p>该 DTO 的职责是把事件转换成“可直接展示但不替代原始事实”的轻量解释层：
 * - 原始事件仍保留在 {@link AgentRuntimeEventProjectionView#attributes()} 中；
 * - display 只放标题、摘要、状态、是否需要关注、建议动作和低风险指标；
 * - display 不应该承载 SQL、prompt、工具参数、payload、样本数据或完整执行结果。</p>
 *
 * @param category 面向 UI 的事件分类，例如 DAG_DRY_RUN、TOOL_EXECUTION、APPROVAL、RUN、MEMORY、SYSTEM。
 * @param title 时间线卡片标题，适合直接作为主标题展示。
 * @param summary 一句话摘要，适合放在标题下方，帮助用户快速理解事件影响。
 * @param status 稳定状态码，前端可据此决定颜色、徽标和筛选条件。
 * @param iconKey 图标语义键，不绑定具体前端图标库，避免后端依赖 UI 框架。
 * @param requiresAttention 该事件是否建议用户、项目负责人或运维人员进一步查看。
 * @param replayPolicy 回放策略说明，帮助 WebSocket/HTTP replay 客户端决定如何处理该事件。
 * @param recommendedActions 面向用户或运维的下一步建议，必须是安全摘要，不包含敏感上下文。
 * @param metrics 可展示的低风险指标，例如候选数、阻断数、未命中数；不放高基数或敏感值。
 */
public record AgentRuntimeEventDisplayView(
        String category,
        String title,
        String summary,
        String status,
        String iconKey,
        boolean requiresAttention,
        String replayPolicy,
        List<String> recommendedActions,
        Map<String, Object> metrics
) {
}
