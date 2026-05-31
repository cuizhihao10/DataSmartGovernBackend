/**
 * @Author : Cui
 * @Date: 2026/06/01 00:01
 * @Description DataSmart Govern Backend - AgentToolDagExecutionDryRunAction.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.execution;

/**
 * DAG-aware 执行干运行动作。
 *
 * <p>该枚举描述“本次 dry-run 会把节点放入什么拟执行通道”。它不是工具状态，也不是最终执行结果。
 * 之所以单独建模，而不是复用 previewAction，是因为 preview 负责回答“节点当前是否可以推进”，
 * dry-run 负责回答“本次请求准备如何推进这些节点”。两者分离后，未来可以支持用户只选部分 ready 节点、
 * 批量上限、租户配额、灰度策略、人工二次确认和后台 worker 接管，而不破坏原始 preview 语义。</p>
 */
public enum AgentToolDagExecutionDryRunAction {

    /** 将调用现有同步自动执行入口的 dryRun=true 模式做二次确认；当前接口不执行真实工具。 */
    SYNC_AUTO_EXECUTE_DRY_RUN,

    /** 将作为异步 outbox enqueue 的候选展示；当前接口不写 outbox、不投递 Kafka。 */
    ASYNC_OUTBOX_ENQUEUE_PREVIEW,

    /** 节点被上游 preview 判定为依赖、参数、审批、权限或策略阻断，不能进入拟执行批次。 */
    BLOCKED_BY_PREVIEW,

    /** 节点存在于 preview，但不在本次 nodeIds/auditIds 选择范围，或默认模式下不是可执行候选。 */
    NOT_SELECTED,

    /** 调用方显式请求的 nodeId 或 auditId 在当前 Run 的 preview 结果中不存在。 */
    REQUESTED_NODE_OR_AUDIT_NOT_FOUND,

    /** 节点被请求命中，但因为本次 maxNodes 上限被排除在有效 dry-run 批次之外。 */
    BATCH_LIMIT_REACHED
}
