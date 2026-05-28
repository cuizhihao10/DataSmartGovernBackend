/**
 * @Author : Cui
 * @Date: 2026/05/13 22:20
 * @Description DataSmart Govern Backend - ModelChatRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/**
 * 模型聊天/推理请求。
 *
 * <p>这是 Agent Runtime 的第一版模型调用契约。
 * 它故意不暴露具体 provider 的私有字段，例如 vLLM 的采样细节、OpenAI-compatible 的完整参数、
 * 或某个 Python Runtime 的内部上下文格式。原因是：业务调用方应该表达“我要做什么工作负载”，
 * 而不是关心底层到底用 Qwen、DeepSeek、Mistral，还是哪个推理框架。
 *
 * @param workloadType 工作负载类型，默认 AGENT_REASONING。
 * @param messages 对话消息列表。
 * @param temperature 采样温度。为空时由后续 Provider 使用默认值。
 * @param maxOutputTokens 最大输出 token。为空时由后续 Provider 使用默认值。
 */
public record ModelChatRequest(
        String workloadType,

        @Valid
        @NotEmpty(message = "messages 不能为空")
        @Size(max = 40, message = "单次最多提交 40 条消息")
        List<ModelMessage> messages,

        BigDecimal temperature,

        Integer maxOutputTokens) {
}
