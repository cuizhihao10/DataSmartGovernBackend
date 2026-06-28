/**
 * @Author : Cui
 * @Date: 2026/06/28 21:45
 * @Description DataSmart Govern Backend - AgentToolActionSubmissionStatus.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

/**
 * Agent 受控工具真实提交事实状态。
 *
 * <p>这个枚举刻意只描述“真实副作用提交入口”的状态，而不描述完整任务生命周期。
 * 完整任务生命周期仍属于 task-management，例如 RUNNING、DEFERRED、FAILED、COMPLETED。
 * agent-runtime 这里只关心一个更窄但非常关键的问题：某个 commandId 是否已经尝试把
 * Agent 计划转化为真实下游副作用。</p>
 *
 * <p>为什么要单独建状态机：
 * 1. command outbox 只能说明“命令已投递给 worker”，不能说明下游副作用是否已经开始；
 * 2. worker receipt 只能说明“worker 已回写结果”，如果 agent-runtime 在真实提交后、receipt 前崩溃，
 *    仍需要一个服务端事实来阻止重放；
 * 3. data-quality/task-management 的下游任务状态属于业务模块事实，不应该反向塞回 Agent 内存缓存。</p>
 */
public enum AgentToolActionSubmissionStatus {

    /**
     * 已经登记提交意图，但还没有拿到下游明确结果。
     *
     * <p>看到该状态时不能再次调用下游，因为前一个调用可能仍在网络途中。
     * 当前阶段先 fail-closed：让 worker defer，并等待后续对账/人工确认能力补齐。
     * 后续如果下游提供稳定幂等查询接口，可以把该状态升级为可自动 reconciliation。</p>
     */
    SUBMITTING,

    /**
     * 下游明确返回已创建真实治理任务。
     *
     * <p>这是可复用的终态。重复请求命中该状态时，agent-runtime 可以直接返回低敏摘要，
     * 不再重复调用 data-quality，从而避免重复创建治理任务。</p>
     */
    SUBMITTED,

    /**
     * 下游明确拒绝创建治理任务。
     *
     * <p>这也是可复用的终态。重复请求命中该状态时，返回同一个拒绝结果即可，
     * 让 task-management 按失败或人工处理流程收口，而不是反复压测下游服务。</p>
     */
    REJECTED,

    /**
     * 下游调用状态未知。
     *
     * <p>例如 HTTP 超时、连接中断、响应体无法确认等场景。UNKNOWN 与 REJECTED 的区别非常重要：
     * REJECTED 表示下游明确没创建；UNKNOWN 表示“可能创建了，也可能没创建”。
     * 在 data-quality/task-management 具备下游幂等键和查询接口前，UNKNOWN 不能自动重试。</p>
     */
    UNKNOWN;

    /**
     * 当前状态是否可以直接还原为 API 响应。
     *
     * @return true 表示重复请求可以使用事实表里的低敏摘要返回，不需要也不允许再次打下游。
     */
    public boolean reusableResponse() {
        return this == SUBMITTED || this == REJECTED;
    }
}
