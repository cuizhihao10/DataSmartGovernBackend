/**
 * @Author : Cui
 * @Date: 2026/06/16 00:00
 * @Description DataSmart Govern Backend - AgentToolActionApprovalFactEvaluator.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

/**
 * Agent 工具动作审批事实评估端口。
 *
 * <p>该接口是 agent-runtime 与 permission-admin 之间的解耦边界。
 * 恢复事实包服务只关心“审批事实是否已被控制面采信”，不关心评估来自 HTTP、Nacos/Feign、服务网格、
 * 本地测试 fake，还是未来的审计中心回放。因此这里先定义端口，再提供 HTTP 实现。</p>
 */
public interface AgentToolActionApprovalFactEvaluator {

    /**
     * 评估审批事实是否真实可用。
     *
     * @param request 审批事实低敏定位请求。
     * @return 远程或本地评估结果；实现应避免向上抛出包含内部 URL/token/payload 的原始异常。
     */
    AgentToolActionApprovalFactRemoteResult evaluate(AgentToolActionApprovalFactRemoteRequest request);
}
