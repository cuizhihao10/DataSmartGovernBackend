/**
 * @Author : Cui
 * @Date: 2026/07/05 01:22
 * @Description DataSmart Govern Backend - AgentRagCommandWorkerClient.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime.rag;

/**
 * Java agent-runtime 调用 Python RAG Command Worker 的边界接口。
 *
 * <p>dispatcher 只依赖该接口，不直接依赖 RestClient、HTTP 路由、Header 或服务发现细节。
 * 这样后续如果把 RAG worker 从同步 HTTP 切到 Kafka request/reply、gRPC、服务网格或独立 worker pool，
 * outbox 状态机和 receipt ingestion 都不用重写。</p>
 */
public interface AgentRagCommandWorkerClient {

    /**
     * 执行一次 RAG Command Worker 调用。
     *
     * @param request Java 控制面准备好的 worker 请求。请求中的 `arguments.question` 只允许作为短生命周期入参，
     *                实现类不得写入日志、异常消息、返回对象或 runtime event。
     * @return 低敏调用结果，供 dispatcher 决定 outbox 状态和后续 receipt 写入。
     */
    AgentRagCommandWorkerCallResult run(AgentRagCommandWorkerRunRequest request);
}
