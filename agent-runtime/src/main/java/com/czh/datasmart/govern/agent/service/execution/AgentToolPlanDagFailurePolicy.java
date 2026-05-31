/**
 * @Author : Cui
 * @Date: 2026/05/31 22:23
 * @Description DataSmart Govern Backend - AgentToolPlanDagFailurePolicy.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.execution;

/**
 * Agent ToolPlan DAG 节点失败策略。
 *
 * <p>失败策略回答“当前节点失败后，下游节点还能不能继续”。它不是异常类型，也不是任务状态。
 * 对真实 Agent 产品来说，这个字段非常关键：读取元数据失败通常会阻断后续规则生成；
 * 某个可选解释工具失败可能允许继续；写操作失败则通常需要人工复核。</p>
 */
public enum AgentToolPlanDagFailurePolicy {

    /** 默认策略：当前节点失败会阻塞依赖它的后续节点，并阻塞 Run 自动继续。 */
    BLOCK_RUN,

    /** 当前节点失败后允许下游继续，但下游应能处理缺失结果或降级输入。 */
    CONTINUE_ON_FAILURE,

    /** 当前节点失败后跳过依赖它的后续节点，适合可选分支或候选方案分支。 */
    SKIP_DEPENDENTS,

    /** 当前节点失败后进入人工复核，是否继续由人类或运营策略决定。 */
    MANUAL_REVIEW,

    /** 当前节点失败后先由执行器按幂等和重试策略补偿，补偿耗尽后再阻断。 */
    RETRY_THEN_BLOCK
}
