/**
 * @Author : Cui
 * @Date: 2026/06/06 23:09
 * @Description DataSmart Govern Backend - GatewayAgentToolPolicyEnvelopeClient.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.gateway.agent;

import reactor.core.publisher.Mono;

/**
 * gateway 调用 Agent 工具治理策略中心的客户端协议。
 *
 * <p>单独抽出接口，是为了让过滤器只依赖“能评估策略”这个能力，而不绑定具体 HTTP 实现。
 * 后续如果从 HTTP 切换为服务发现、Redis 策略快照、边车策略引擎或本地缓存，只需要替换实现，不需要改
 * `GatewayAgentToolPolicyEnvelopeFilter` 的流程。</p>
 */
public interface GatewayAgentToolPolicyEnvelopeClient {

    /**
     * 评估本次 Agent 规划请求应使用的工具治理策略。
     *
     * @param request 低敏控制面请求，不包含 prompt、SQL、工具参数或模型输出
     * @param traceId 链路追踪 ID，会透传给 permission-admin 便于日志和审计关联
     * @return 工具预算与 readiness policy 的低敏视图
     */
    Mono<GatewayAgentToolPolicyEnvelopeView> evaluate(
            GatewayAgentToolPolicyEnvelopeRequest request,
            String traceId);
}
