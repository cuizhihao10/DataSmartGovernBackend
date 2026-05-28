/**
 * @Author : Cui
 * @Date: 2026/05/13 22:40
 * @Description DataSmart Govern Backend - AgentSessionState.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.model;

/**
 * Agent 会话状态。
 *
 * <p>会话是用户与智能体交互的工作上下文，包含租户、项目、工作空间、操作者、工具绑定和多次运行记录。
 * 会话状态用于回答“这个上下文还能不能继续使用”，而运行状态用于回答“某一次执行推进到哪里了”。
 */
public enum AgentSessionState {

    /**
     * 会话可继续使用。
     * 用户可以继续追问、追加工具、发起新的 Agent Run。
     */
    ACTIVE,

    /**
     * 会话已暂停。
     * 后续可用于人工审批、风险冻结、预算超限、客户运维临时阻断等场景。
     */
    PAUSED,

    /**
     * 会话已关闭。
     * 关闭后不再接受新的运行，通常由用户手动结束、系统超时清理或合规策略触发。
     */
    CLOSED,

    /**
     * 会话已归档。
     * 归档强调“保留审计证据但不再参与在线交互”，后续可迁移到冷存储或审计中心。
     */
    ARCHIVED;

    /**
     * 判断会话是否允许创建新的运行。
     */
    public boolean canStartRun() {
        return this == ACTIVE;
    }
}
