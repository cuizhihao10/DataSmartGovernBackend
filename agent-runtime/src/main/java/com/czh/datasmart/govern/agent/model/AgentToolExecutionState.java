/**
 * @Author : Cui
 * @Date: 2026/05/13 23:42
 * @Description DataSmart Govern Backend - AgentToolExecutionState.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.model;

/**
 * Agent 工具执行审计状态。
 *
 * <p>该状态不是下游业务任务状态，而是 Agent Runtime 对“某个 run 准备或正在调用某个工具”的审计视角。
 * 先定义该状态，可以在真实工具适配器上线前就形成可追踪证据链。
 */
public enum AgentToolExecutionState {

    /**
     * 已生成工具调用计划，但尚未真正执行。
     * 低风险只读工具通常会先进入该状态。
     */
    PLANNED,

    /**
     * 工具调用需要人工确认或审批。
     * 高风险、严重风险、写操作或显式 `requiresApproval=true` 的工具应进入该状态。
     */
    WAITING_APPROVAL,

    /**
     * 工具正在执行。
     * 后续真实适配器调用 datasource-management、data-quality、task-management 时会使用该状态。
     */
    EXECUTING,

    /**
     * 工具执行成功。
     */
    SUCCEEDED,

    /**
     * 工具执行失败。
     */
    FAILED,

    /**
     * 工具调用被跳过。
     * 例如用户取消运行、审批拒绝、工具被禁用或权限二次校验失败。
     */
    SKIPPED,

    /**
     * 工具调用被取消。
     */
    CANCELLED
}
