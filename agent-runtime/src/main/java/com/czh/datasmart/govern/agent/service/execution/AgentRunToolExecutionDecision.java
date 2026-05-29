/**
 * @Author : Cui
 * @Date: 2026/05/29 18:42
 * @Description DataSmart Govern Backend - AgentRunToolExecutionDecision.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.execution;

/**
 * Agent Run 级工具执行策略决策。
 *
 * <p>这个枚举不是工具审计记录本身的状态，而是“编排器看到当前状态后应该怎么做”的解释层。
 * 例如审计状态同样是 PLANNED，但如果工具是 SYNC 且参数完整，可以进入自动执行候选；
 * 如果工具是 ASYNC_TASK，则应交给异步执行器；如果是 DRAFT_ONLY，则应该只给用户展示草稿或建议。
 *
 * <p>把决策层单独抽出来有两个好处：
 * 1. 避免未来自动执行器、前端按钮、Python Runtime 分别写一套 if/else，导致策略漂移；
 * 2. 当前阶段先做只读预检，不直接触发真实工具调用，给后续“安全自动执行”留下清晰边界。
 */
public enum AgentRunToolExecutionDecision {

    /**
     * 当前工具可以作为“同步自动执行候选”。
     *
     * <p>候选不等于已经执行。真正执行前仍必须经过 {@code AgentToolExecutionGuard} 的硬校验，
     * 包括租户/项目/工作空间一致性、非只读审批、参数完整性和下游执行器能力检查。
     */
    AUTO_EXECUTABLE,

    /**
     * 工具计划仍在等待人工审批或确认。
     */
    WAITING_APPROVAL,

    /**
     * 工具计划缺少必要参数，需要用户补充、上下文补全或重新规划。
     */
    WAITING_PARAMETER_COMPLETION,

    /**
     * 工具应由异步执行器处理。
     *
     * <p>长耗时扫描、数据同步任务创建、批量质量检测等能力不适合在 Agent HTTP 请求线程中直接执行，
     * 应通过任务队列、Kafka command、调度中心或 task-management 创建受控任务。
     */
    WAITING_ASYNC_EXECUTOR,

    /**
     * 工具只能生成草稿或建议，不能由 Agent Runtime 自动提交执行。
     */
    DRAFT_ONLY_REVIEW,

    /**
     * 工具已经处于执行中。
     */
    ALREADY_EXECUTING,

    /**
     * 工具已经成功完成。
     */
    ALREADY_SUCCEEDED,

    /**
     * 工具执行失败，但从幂等性看可以进入自动或半自动重试候选。
     */
    FAILED_CAN_RETRY,

    /**
     * 工具执行失败且当前不建议自动继续，通常需要人工排查或显式重试。
     */
    FAILED_BLOCKS_RUN,

    /**
     * 工具被跳过，通常表示人工拒绝、策略禁止或前置条件不满足。
     */
    SKIPPED_TERMINAL,

    /**
     * 工具被取消，不应继续执行。
     */
    CANCELLED_TERMINAL,

    /**
     * 所属 Run 已是终态，因此即使工具本身看起来可执行，也不能再推进。
     */
    RUN_TERMINAL_BLOCKED,

    /**
     * 当前组合不符合已知策略，需要保守阻断并交给人工或后续策略补齐。
     */
    BLOCKED_BY_POLICY
}
