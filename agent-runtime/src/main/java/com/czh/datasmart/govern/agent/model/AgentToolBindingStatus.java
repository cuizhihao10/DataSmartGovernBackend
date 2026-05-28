/**
 * @Author : Cui
 * @Date: 2026/05/13 22:40
 * @Description DataSmart Govern Backend - AgentToolBindingStatus.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.model;

/**
 * Agent 工具绑定状态。
 *
 * <p>工具绑定不是简单的“接口地址列表”。它表达某个会话被允许调用哪些平台能力、以什么权限调用、
 * 是否只读、是否需要审批。先定义绑定状态，可以为后续工具热插拔、审批启用、风险冻结和审计追踪留出空间。
 */
public enum AgentToolBindingStatus {

    /**
     * 工具已绑定且可被运行计划引用。
     */
    ENABLED,

    /**
     * 工具被禁用。
     * 例如某个下游服务故障、连接器维护、用户权限变化或安全策略临时阻断。
     */
    DISABLED,

    /**
     * 工具等待审批。
     * 高风险工具，例如可执行 SQL、批量任务恢复、跨项目导出，后续应先进入该状态。
     */
    PENDING_APPROVAL
}
