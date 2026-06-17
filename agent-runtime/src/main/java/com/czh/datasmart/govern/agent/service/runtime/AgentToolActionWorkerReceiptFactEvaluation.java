/**
 * @Author : Cui
 * @Date: 2026/06/17 00:00
 * @Description DataSmart Govern Backend - AgentToolActionWorkerReceiptFactEvaluation.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionResumeFactReceiptSummaryView;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionResumeFactView;

/**
 * worker receipt 事实验真结果。
 *
 * <p>拆成独立 record，是为了让主 fact bundle service 只关心“拿到一个 fact 和可选摘要”，
 * 不再关心 receipt 来自专用索引还是 runtime event fallback，也不再承担 receipt 状态机判断。</p>
 */
public record AgentToolActionWorkerReceiptFactEvaluation(
        AgentToolActionResumeFactView fact,
        AgentToolActionResumeFactReceiptSummaryView summary
) {
}
