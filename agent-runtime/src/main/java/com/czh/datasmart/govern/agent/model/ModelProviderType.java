/**
 * @Author : Cui
 * @Date: 2026/05/13 22:18
 * @Description DataSmart Govern Backend - ModelProviderType.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.model;

/**
 * 模型提供方类型。
 *
 * <p>这里表达的是“接入协议/部署形态”，不是具体模型名字。
 * 例如 Qwen3.5、DeepSeek、Mistral 都可能通过 OpenAI-compatible API、vLLM、SGLang 或自研 Python 服务暴露。
 * 业务流程只依赖这个抽象，后续换模型或换推理框架时不需要重写 Agent 编排。
 */
public enum ModelProviderType {
    /**
     * 开发期占位 Provider，不会真实请求大模型。
     */
    DRY_RUN,

    /**
     * OpenAI-compatible 协议，很多 vLLM/SGLang/云厂商网关都支持类似接口。
     */
    OPENAI_COMPATIBLE,

    /**
     * 直接面向 vLLM 服务。
     */
    VLLM,

    /**
     * 直接面向 SGLang 服务。
     */
    SGLANG,

    /**
     * 面向未来 Python AI 服务或类 OpenClaw Runtime 的内部协议。
     */
    PYTHON_AGENT_SERVICE
}
