/**
 * @Author : Cui
 * @Date: 2026/07/03 16:46
 * @Description DataSmart Govern Backend - AgentMcpDurableWorkerCallResult.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime.mcp;

/**
 * Java 调用 Python MCP Durable Worker 的低敏结果。
 *
 * <p>它和 {@link AgentMcpDurableWorkerRunResponse} 的职责不同：response 是 Python 的业务响应；call result 是 Java
 * HTTP 客户端视角下的投递结果，包含“是否尝试调用、是否因配置跳过、HTTP 状态、错误码”等信息。dispatcher 后续可根据
 * 该结果决定 outbox 记录进入 PUBLISHED、FAILED_RETRYABLE、BLOCKED 或 DEAD_LETTER，而不需要解析 RestClient 异常。</p>
 *
 * <p>安全要求：该对象不能保存请求参数、原始响应正文、Authorization Header、内部 URL 或远端堆栈。所有 message 都是
 * 面向运维的低敏短说明，避免被 runtime event、Prometheus label 或 API response 间接泄漏。</p>
 *
 * @param attempted 是否已经尝试发起 HTTP 调用。
 * @param skipped 是否因客户端关闭、baseUrl 缺失或请求为空而跳过。
 * @param accepted Python worker 是否接受请求。
 * @param statusCode HTTP 状态码；未发起或无法获得状态时为 0。
 * @param response Python 返回的低敏业务响应。
 * @param errorCode Java 客户端侧低基数错误码，便于重试、告警和聚合。
 * @param message 低敏人类可读说明，不包含 URL、参数、令牌或响应正文。
 */
public record AgentMcpDurableWorkerCallResult(
        boolean attempted,
        boolean skipped,
        boolean accepted,
        int statusCode,
        AgentMcpDurableWorkerRunResponse response,
        String errorCode,
        String message
) {

    public static AgentMcpDurableWorkerCallResult skipped(String errorCode, String message) {
        return new AgentMcpDurableWorkerCallResult(false, true, false, 0, null, errorCode, message);
    }

    public static AgentMcpDurableWorkerCallResult failed(int statusCode, String errorCode, String message) {
        return new AgentMcpDurableWorkerCallResult(true, false, false, statusCode, null, errorCode, message);
    }

    public static AgentMcpDurableWorkerCallResult accepted(int statusCode,
                                                           AgentMcpDurableWorkerRunResponse response,
                                                           String message) {
        return new AgentMcpDurableWorkerCallResult(
                true,
                false,
                response != null && Boolean.TRUE.equals(response.accepted()),
                statusCode,
                response,
                null,
                message
        );
    }
}
