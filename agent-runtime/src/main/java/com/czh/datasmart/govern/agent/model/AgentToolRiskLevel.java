/**
 * @Author : Cui
 * @Date: 2026/05/13 23:18
 * @Description DataSmart Govern Backend - AgentToolRiskLevel.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.model;

/**
 * Agent 工具风险等级。
 *
 * <p>工具风险等级是商业化 Agent 产品必须具备的治理字段。
 * 大模型只负责规划和生成建议，不应该天然拥有执行所有工具的权力。
 * 通过风险等级，平台可以把“只读查询”“生成建议”“创建任务”“执行写操作”“导出敏感数据”等动作分层治理。
 */
public enum AgentToolRiskLevel {

    /**
     * 低风险。
     * 通常用于只读元数据、公开配置、非敏感运行状态查询。
     */
    LOW,

    /**
     * 中风险。
     * 通常用于生成建议、创建草稿、读取可能包含业务上下文但不直接暴露敏感值的数据。
     */
    MEDIUM,

    /**
     * 高风险。
     * 通常用于创建或修改任务、触发运行、生成可执行 SQL、访问异常样本、读取敏感数据摘要等。
     */
    HIGH,

    /**
     * 严重风险。
     * 通常用于批量恢复、跨项目访问、导出数据、执行写入 SQL、关闭 P1/P2 事故等必须审批或人工确认的动作。
     */
    CRITICAL
}
