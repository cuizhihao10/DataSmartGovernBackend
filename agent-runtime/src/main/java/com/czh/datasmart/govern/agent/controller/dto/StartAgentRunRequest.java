/**
 * @Author : Cui
 * @Date: 2026/05/13 22:43
 * @Description DataSmart Govern Backend - StartAgentRunRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * 发起一次 Agent 运行请求。
 *
 * <p>运行是会话内的一次执行尝试。用户可以在同一个会话里多次运行：
 * 第一次让 Agent 生成方案，第二次让 Agent 解释方案，第三次让 Agent 调用工具创建任务。
 * 将运行拆出来后，未来可以为每一次运行单独记录模型路由、工具调用、耗时、失败原因和人工审批结果。
 *
 * @param userInput 用户本轮输入。
 * @param workloadType 模型工作负载类型，例如 AGENT_REASONING、GOVERNANCE_QA、CODE_GENERATION。
 * @param requireHumanApproval 是否要求本轮默认进入人工确认语义。
 * @param variables 运行变量，未来可承载数据源 ID、规则 ID、任务 ID、审批单 ID 等结构化上下文。
 */
public record StartAgentRunRequest(
        @NotBlank(message = "userInput 不能为空")
        @Size(max = 8000, message = "userInput 最多 8000 个字符")
        String userInput,

        @Size(max = 64, message = "workloadType 最多 64 个字符")
        String workloadType,

        Boolean requireHumanApproval,

        @Size(max = 50, message = "variables 最多 50 个键")
        Map<@Size(max = 128, message = "变量名最多 128 个字符") String, Object> variables) {
}
