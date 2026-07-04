/**
 * @Author : Cui
 * @Date: 2026/07/05 01:22
 * @Description DataSmart Govern Backend - AgentRagCommandWorkerCallResult.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime.rag;

/**
 * Java 调用 Python RAG Command Worker 的低敏调用结果。
 *
 * <p>它和 {@link AgentRagCommandWorkerRunResponse} 的职责不同：response 是 Python 的业务响应；
 * call result 是 Java HTTP 客户端视角下的投递结果，包含是否尝试调用、是否因配置跳过、HTTP 状态码、错误码等。
 * dispatcher 只需要这些低基数事实来决定 outbox 是进入 PUBLISHED、FAILED 还是后续 BLOCKED。</p>
 *
 * <p>安全要求：本对象不能保存请求 question、Python 原始响应正文、内部 URL、Authorization Header、
 * 异常堆栈或 answer 内容。所有 message 都是低敏短语，避免进入日志、runtime event 或诊断接口时造成泄露。</p>
 *
 * @param attempted 是否真正发起过 HTTP 调用。
 * @param skipped 是否因为客户端关闭、endpoint 无效或请求为空而主动跳过。
 * @param accepted Python worker 是否接受并处理了请求。
 * @param statusCode HTTP 状态码；未发起或无法获得时为 0。
 * @param response Python 返回的低敏业务响应。
 * @param errorCode Java 客户端侧低基数错误码。
 * @param message 低敏人类可读说明，不包含 URL、question、answer、token 或响应正文。
 */
public record AgentRagCommandWorkerCallResult(
        boolean attempted,
        boolean skipped,
        boolean accepted,
        int statusCode,
        AgentRagCommandWorkerRunResponse response,
        String errorCode,
        String message
) {

    public static AgentRagCommandWorkerCallResult skipped(String errorCode, String message) {
        return new AgentRagCommandWorkerCallResult(false, true, false, 0, null, errorCode, message);
    }

    public static AgentRagCommandWorkerCallResult failed(int statusCode, String errorCode, String message) {
        return new AgentRagCommandWorkerCallResult(true, false, false, statusCode, null, errorCode, message);
    }

    public static AgentRagCommandWorkerCallResult accepted(int statusCode,
                                                           AgentRagCommandWorkerRunResponse response,
                                                           String message) {
        return new AgentRagCommandWorkerCallResult(
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
