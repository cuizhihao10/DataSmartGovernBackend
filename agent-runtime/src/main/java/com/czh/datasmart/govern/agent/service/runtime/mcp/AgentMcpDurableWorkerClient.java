/**
 * @Author : Cui
 * @Date: 2026/07/03 16:46
 * @Description DataSmart Govern Backend - AgentMcpDurableWorkerClient.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime.mcp;

/**
 * Java agent-runtime 调用 Python MCP Durable Worker 的边界接口。
 *
 * <p>后续 dispatcher/outbox consumer 只依赖这个接口，不直接依赖 HTTP、RestClient、路由路径或认证 Header。这样做有两个
 * 重要好处：</p>
 * <p>1. 业务状态机保持稳定：outbox 只关心“投递成功、可重试失败、不可执行跳过、Python 返回 receipt 摘要”；</p>
 * <p>2. 通信方式可替换：当前是 HTTP 内部 API，未来可以替换成 Kafka request/reply、gRPC、服务网格或带 mTLS 的内部网关。</p>
 */
public interface AgentMcpDurableWorkerClient {

    /**
     * 执行一次 MCP Durable Worker 调用。
     *
     * @param request 已由 Java 控制面准备好的 worker 请求。请求中的 arguments 不应被实现类写入日志或结果对象。
     * @return 低敏调用结果，供 dispatcher 决定 outbox 状态、重试策略和后续 receipt 写入。
     */
    AgentMcpDurableWorkerCallResult run(AgentMcpDurableWorkerRunRequest request);
}
