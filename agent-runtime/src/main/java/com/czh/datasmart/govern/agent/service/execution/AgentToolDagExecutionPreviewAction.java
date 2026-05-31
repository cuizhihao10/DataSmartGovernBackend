/**
 * @Author : Cui
 * @Date: 2026/05/31 23:42
 * @Description DataSmart Govern Backend - AgentToolDagExecutionPreviewAction.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.execution;

/**
 * DAG-aware 执行预览动作。
 *
 * <p>枚举表达“下一步建议走哪条受控路径”，不是执行结果。
 * 之所以不用散落字符串，是为了后续前端按钮、Python Runtime loop policy、审计报表和自动调度器
 * 可以基于稳定机器值做判断，而不是解析中文说明。</p>
 */
public enum AgentToolDagExecutionPreviewAction {

    /** 可作为同步自动执行 dry-run 候选；真实执行仍需调用 auto-execute-sync 并再次经过服务端守卫。 */
    SYNC_AUTO_EXECUTE_CANDIDATE,

    /** 可作为异步 command dispatcher 候选；真实投递仍需 outbox、Kafka 和 task-management 幂等消费。 */
    ASYNC_COMMAND_DISPATCH_CANDIDATE,

    /** DAG 前置依赖尚未满足，不能推进当前节点。 */
    WAIT_DEPENDENCIES,

    /** 需要人工审批、草稿确认或失败复核。 */
    WAIT_HUMAN_ACTION,

    /** 参数不完整，需要用户、上下文检索或记忆补齐。 */
    WAIT_PARAMETER_COMPLETION,

    /** 工具已经执行中，调用方应等待结果或事件回写。 */
    ALREADY_EXECUTING,

    /** 工具已经成功、跳过或取消，不应重复执行。 */
    TERMINAL_OR_COMPLETED,

    /** 策略层阻断，当前不能自动推进。 */
    BLOCKED_BY_POLICY,

    /** 节点 ready，但当前版本尚未为该执行模式提供受控推进路径。 */
    UNSUPPORTED_EXECUTION_PATH
}
