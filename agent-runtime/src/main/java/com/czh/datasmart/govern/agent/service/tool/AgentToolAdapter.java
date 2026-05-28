/**
 * @Author : Cui
 * @Date: 2026/05/14 19:12
 * @Description DataSmart Govern Backend - AgentToolAdapter.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.tool;

/**
 * Agent 工具适配器接口。
 *
 * <p>一个适配器负责执行一个或一组 toolCode。
 * 执行框架不会把所有工具写成一个巨大的 switch，而是通过该接口按 toolCode 选择适配器。
 * 这有利于后续把 datasource、data-quality、task-management、permission、asset 等工具拆到不同类中维护。
 *
 * <p>适配器的职责边界：
 * - 可以调用下游服务、构造请求体、解释响应；
 * - 不负责审批判断，审批已经在 Agent Runtime 控制面完成；
 * - 不负责审计状态写入，审计状态由 `AgentToolExecutionService` 统一推进；
 * - 不直接修改 Agent Run 状态，Run 状态由会话/状态协调器维护。
 */
public interface AgentToolAdapter {

    /**
     * 判断该适配器是否支持某个工具编码。
     */
    boolean supports(String toolCode);

    /**
     * 执行工具。
     *
     * @param context 工具执行上下文，包含会话、Run、审计记录、变量和 traceId。
     * @return 工具执行结果，交由执行框架统一写入审计状态。
     */
    AgentToolExecutionOutcome execute(AgentToolExecutionContext context);
}
