/**
 * @Author : Cui
 * @Date: 2026/07/16 00:00
 * @Description DataSmart Govern Backend - AgentExecutionResultAnswerGenerator.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.answer;

import com.czh.datasmart.govern.agent.controller.dto.AgentToolExecutionResultView;

import java.util.List;

/**
 * 将真实工具执行事实转换成 Agent 二轮答复的扩展点。
 *
 * <p>接口刻意只接收状态、计数、低敏工具结果视图和下一步动作。未来接入在线模型、本地 vLLM 或企业
 * 模型网关时，可以新增实现并继续执行脱敏、超时、审计和回退策略；模型只能解释已经发生的事实，
 * 不能自行改变 Run 状态，也不能绕过工具控制面宣称任务成功。</p>
 */
public interface AgentExecutionResultAnswerGenerator {

    /**
     * 根据本次确认执行的最终事实生成面向用户的答复。
     *
     * @param runState Run 的最终状态
     * @param plannedCount 计划节点数
     * @param succeededCount 成功节点数
     * @param failedCount 失败节点数
     * @param toolResults 本次实际触发的低敏工具结果
     * @param nextActions 控制面给出的后续动作
     * @return 可直接进入 API 响应的低敏答复
     */
    AgentExecutionAssistantAnswer generate(
            String runState,
            int plannedCount,
            int succeededCount,
            int failedCount,
            List<AgentToolExecutionResultView> toolResults,
            List<String> nextActions);
}
