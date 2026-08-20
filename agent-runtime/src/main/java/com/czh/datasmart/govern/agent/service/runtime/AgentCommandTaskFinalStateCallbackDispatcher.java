/**
 * @Author : Cui
 * @Date: 2026/08/19 00:00
 * @Description DataSmart Govern Backend - AgentCommandTaskFinalStateCallbackDispatcher.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.controller.dto.AgentCommandTaskFinalStateCallbackDispatchRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentCommandTaskFinalStateCallbackDispatchResponse;

/**
 * 最终态 callback 的 Java 副作用端口。
 *
 * <p>worker 依赖这个端口而不直接拼 HTTP 请求，是为了强制复用现有 dispatch service 的重新对账、低敏 body、
 * task-management 幂等键和服务账号 Header。这样 Java 控制面仍是事实判断与副作用发起的唯一权威，测试也能隔离网络。</p>
 */
public interface AgentCommandTaskFinalStateCallbackDispatcher {

    /**
     * 基于当前 Java receipt 事实重新对账后，向 task-management 发起一次受控 callback。
     *
     * @param request callback 所需的低敏关联字段；自动 worker 必须显式传入 {@code dryRun=false}。
     * @param accessContext 内部服务账号访问上下文，仅用于对账范围收口，不来自外部请求。
     * @param traceId 本轮 worker 的低敏链路标识。
     * @return 下游接受、拒绝或暂时不可用的低敏结果。
     */
    default AgentCommandTaskFinalStateCallbackDispatchResponse dispatch(
            AgentCommandTaskFinalStateCallbackDispatchRequest request,
            AgentRuntimeEventQueryAccessContext accessContext,
            String traceId) {
        return dispatch(request, accessContext, traceId, null);
    }

    /**
     * 带 durable job 预期的投递入口。
     *
     * <p>后台 worker 必须使用本方法，使 dispatch service 在 HTTP 副作用之前校验 replay、任务租约关联和
     * 幂等键仍未变化。人工 dry-run/显式补偿入口可以使用三参数重载，不会伪造后台 job 预期。</p>
     */
    AgentCommandTaskFinalStateCallbackDispatchResponse dispatch(
            AgentCommandTaskFinalStateCallbackDispatchRequest request,
            AgentRuntimeEventQueryAccessContext accessContext,
            String traceId,
            AgentCommandTaskFinalStateCallbackDispatchExpectation expectation);
}
