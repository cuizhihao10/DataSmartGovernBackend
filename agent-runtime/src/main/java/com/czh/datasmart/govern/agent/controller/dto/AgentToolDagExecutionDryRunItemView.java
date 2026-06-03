/**
 * @Author : Cui
 * @Date: 2026/06/01 00:00
 * @Description DataSmart Govern Backend - AgentToolDagExecutionDryRunItemView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.util.List;

/**
 * 单个 DAG 节点的 dry-run 明细。
 *
 * <p>该 DTO 不是执行日志，而是“拟执行计划项”。字段同时保留 previewAction 与 dryRunAction：
 * previewAction 说明上游只读预览如何判断节点状态；dryRunAction 说明本次 dry-run 将该节点纳入什么拟执行路径。
 * 这样设计可以让调用方区分“节点本身可执行”和“本次请求是否选择了它”，也方便后续审计解释为什么一个 ready 节点
 * 没有进入本次批次。</p>
 *
 * @param nodeId DAG 节点 ID；当请求目标不存在时，如果请求的是 nodeId，这里会回填该 nodeId。
 * @param auditId 工具审计 ID；当请求目标不存在时，如果请求的是 auditId，这里会回填该 auditId。
 * @param requestSelector 调用方命中的选择器说明，例如 {@code nodeId:metadata-read} 或 {@code auditId:audit-001}。
 * @param toolCode 工具编码；请求目标不存在时为空。
 * @param selected 当前节点是否被本次 dry-run 选择；NOT_SELECTED 和 NOT_FOUND 场景为 false。
 * @param previewAction 上游 DAG execution preview 的动作分类。
 * @param dryRunAction 本次 dry-run 的动作分类，用于前端按钮、Agent loop 策略和审计日志做稳定判断。
 * @param executionPath 如果后续真实推进，应调用的受控入口说明；当前接口不会调用该入口。
 * @param readyForExecution DAG 依赖和策略是否已经让节点进入 ready 候选。
 * @param targetWouldTriggerSideEffect 如果未来按该路径真实执行，是否可能产生下游副作用；dry-run 本身永远无副作用。
 * @param asyncDispatchable 异步 command 草案是否可下发。
 * @param asyncCommandId 异步 command ID；只有 ASYNC_TASK 且已经形成可下发草案时有值。
 * @param serviceAuthorizationDecision 服务间授权预检结论；用于解释 SERVICE_ACCOUNT 代表 actor 执行的授权状态。
 * @param serviceAuthorizationAllowed 服务间授权是否通过；null 表示没有授权预检上下文。
 * @param serviceAuthorizationPolicyVersions permission-admin 返回的策略版本集合；selected-node 确认入箱会把调用方看到的版本与最新版本对齐校验。
 * @param serviceAuthorizationDelegationEvidence 服务账号代表用户执行工具时的委托证据，例如授权中心生成的审计编号或委托链路摘要。
 * @param sandboxAllowed 工具调用沙箱是否允许该节点后续进入真实执行入口。
 * @param sandboxIsolationMode 沙箱建议隔离模式。
 * @param sandboxIssueCodes 沙箱问题码；dry-run 会把它透传出来，避免用户只看到“preview 阻断”却不知道具体安全原因。
 * @param sandboxReasons 沙箱低敏原因说明。
 * @param sandboxRecommendedActions 沙箱推荐动作。
 * @param riskLevel 工具风险等级，沿用 policy 口径。
 * @param readOnly 工具是否只读。
 * @param idempotent 工具是否幂等。
 * @param requiresApproval 工具是否需要审批。
 * @param reasons 节点级解释，说明为什么被纳入、阻断、未选中或未找到。
 * @param recommendedActions 节点级建议，说明下一步该补参数、处理依赖、发起审批还是进入受控执行入口。
 */
public record AgentToolDagExecutionDryRunItemView(
        String nodeId,
        String auditId,
        String requestSelector,
        String toolCode,
        Boolean selected,
        String previewAction,
        String dryRunAction,
        String executionPath,
        Boolean readyForExecution,
        Boolean targetWouldTriggerSideEffect,
        Boolean asyncDispatchable,
        String asyncCommandId,
        String serviceAuthorizationDecision,
        Boolean serviceAuthorizationAllowed,
        List<String> serviceAuthorizationPolicyVersions,
        List<String> serviceAuthorizationDelegationEvidence,
        Boolean sandboxAllowed,
        String sandboxIsolationMode,
        List<String> sandboxIssueCodes,
        List<String> sandboxReasons,
        List<String> sandboxRecommendedActions,
        String riskLevel,
        Boolean readOnly,
        Boolean idempotent,
        Boolean requiresApproval,
        List<String> reasons,
        List<String> recommendedActions
) {
}
