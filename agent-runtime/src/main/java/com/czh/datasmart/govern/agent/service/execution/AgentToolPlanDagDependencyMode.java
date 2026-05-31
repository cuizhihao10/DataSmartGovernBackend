/**
 * @Author : Cui
 * @Date: 2026/05/31 22:24
 * @Description DataSmart Govern Backend - AgentToolPlanDagDependencyMode.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.execution;

/**
 * ToolPlan DAG 依赖解析模式。
 *
 * <p>当前项目历史上已经支持线性 toolPlans 列表。为了不破坏旧 Python Runtime，
 * Java 控制面在没有显式依赖提示时，会把列表顺序解释成保守的串行依赖；一旦任意节点提供 dependsOn，
 * 就切换为显式 DAG 模式，让没有依赖的节点可以并行。</p>
 */
public enum AgentToolPlanDagDependencyMode {

    /** 使用 governanceHints 中的 dependsOn/dependencies/after 等显式依赖提示。 */
    EXPLICIT,

    /** 兼容旧线性 ToolPlan：第 N 个工具默认依赖第 N-1 个工具。 */
    LEGACY_SEQUENCE
}
