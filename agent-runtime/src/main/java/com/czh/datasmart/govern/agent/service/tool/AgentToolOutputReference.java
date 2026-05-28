/**
 * @Author : Cui
 * @Date: 2026/05/24 23:35
 * @Description DataSmart Govern Backend - AgentToolOutputReference.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.tool;

/**
 * Agent 工具输出引用。
 *
 * <p>这个对象描述“后续工具想读取前序工具输出中的哪一段数据”。
 * 在没有它之前，后续工具只能按 `toolCode` 读取同一 Run 内最近一次成功输出。
 * 这种方式适合简单串行 demo，但在真实商业化 Agent 工作流里会很快遇到问题：</p>
 *
 * <p>1. 同一个工具可能在一个 Run 内执行多次，例如分别读取订单表和客户表元数据；</p>
 * <p>2. 工具可能并行执行，所谓“最近一次”在并发场景下并不稳定；</p>
 * <p>3. 大输出不能总是整体传递，后续工具往往只需要其中一个字段或一个子对象；</p>
 * <p>4. 审计复盘时需要明确知道后续工具到底引用了哪次输出、哪条路径。</p>
 *
 * @param toolCode 来源工具编码，例如 datasource.metadata.read 或 quality.rule.suggest。
 * @param auditId 可选来源审计 ID。提供后会优先按具体审计记录定位，避免多次同类工具输出歧义。
 * @param jsonPath 输出路径。当前支持轻量路径语法，例如 `metadata`、`suggestion.suggestions[0]`。
 */
public record AgentToolOutputReference(String toolCode,
                                       String auditId,
                                       String jsonPath) {
}
