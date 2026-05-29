/**
 * @Author : Cui
 * @Date: 2026/05/29 22:06
 * @Description DataSmart Govern Backend - AgentRunToolAutoExecutionItemView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.util.List;

/**
 * 单个工具在 Run 级自动执行批次中的处理结果。
 *
 * <p>它刻意同时覆盖“执行了”和“没有执行”的情况。商业化 Agent 的自动执行接口不能只返回成功列表，
 * 否则调用方无法知道某个工具是因为审批、参数、风险、批次上限、请求白名单还是执行失败而没有继续推进。</p>
 *
 * @param auditId 工具审计 ID。
 * @param toolCode 工具编码。
 * @param policyDecision 执行前 policy 给出的决策。
 * @param action 自动执行器本批次采取的动作，例如 EXECUTED、DRY_RUN_CANDIDATE、SKIPPED、FAILED。
 * @param reason 本批次采取该动作的原因。
 * @param result 如果真实执行过，则返回工具执行结果；未执行时为空。
 * @param policyReasons policy 层给出的原因列表，便于前端或学习者追踪决策来源。
 */
public record AgentRunToolAutoExecutionItemView(String auditId,
                                                String toolCode,
                                                String policyDecision,
                                                String action,
                                                String reason,
                                                AgentToolExecutionResultView result,
                                                List<String> policyReasons) {
}
