/**
 * @Author : Cui
 * @Date: 2026/06/06 15:20
 * @Description DataSmartGovernBackend - AgentToolExecutionReadinessPolicyView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.controller.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Agent 工具执行准备度策略视图。
 *
 * <p>这个 DTO 是 permission-admin 面向 Python Runtime 5.38 的标准合同。它和旧的
 * {@code toolCallBudget} 不完全相同：</p>
 *
 * <p>1. {@code toolCallBudget} 主要服务模型工具调用预算，回答“模型最多可以提出多少工具调用”；</p>
 * <p>2. {@code toolExecutionReadinessPolicy} 服务执行前治理，回答“已经形成 ToolPlan 后，
 * 哪些工具可以进入自动执行准备态，哪些必须等待审批、澄清、队列恢复或被阻断”。</p>
 *
 * <p>字段全部保持低敏：只包含策略来源、版本、角色、套餐、workspace 风险、worker backlog、
 * 预算数字和影响码。不要在这里加入 prompt、工具参数值、SQL、样本数据、模型输出、凭证、内部 endpoint
 * 或完整权限表达式，否则该对象会从“控制面策略摘要”退化成第二份敏感上下文载荷。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AgentToolExecutionReadinessPolicyView {

    /**
     * 策略来源。
     *
     * <p>当前值通常为 {@code permission-admin}，表示由权限与管理中心生成。Python Runtime 会把该值
     * 映射到 {@code trusted-control-plane} 来源，并在 readiness event 中记录为低敏审计证据。</p>
     */
    private String source;

    /**
     * 策略版本。
     *
     * <p>版本用于把 Java 控制面某一刻生成的策略，和 Python Runtime 某一轮 readiness 判断关联起来。
     * 未来策略迁移到数据库、租户套餐中心或灰度发布系统后，应使用正式发布版本替代当前内存规则版本。</p>
     */
    private String policyVersion;

    /**
     * 调用主体角色。
     *
     * <p>只返回角色编码，不返回用户完整权限清单。执行准备度只需要知道角色大类，用于解释预算为什么收紧；
     * 具体动作授权仍应由 permission-admin 的 action-level evaluate 或 worker pre-check 再次判断。</p>
     */
    private String actorRole;

    /**
     * 租户套餐编码。
     *
     * <p>套餐会影响工具预算上限。例如 FREE/TRIAL 更保守，ENTERPRISE 可以在角色基线内更宽松。
     * 该字段只说明套餐分类，不暴露合同、账单、额度明细或客户商业信息。</p>
     */
    private String tenantPlanCode;

    /**
     * workspace 风险等级。
     *
     * <p>风险等级越高，自动执行准备度越应该收紧。HIGH/CRITICAL workspace 通常意味着需要更多审批、
     * 更少同步自动执行和更严格的草案策略。</p>
     */
    private String workspaceRiskLevel;

    /**
     * worker backlog 等级。
     *
     * <p>这是把性能与可靠性纳入权限策略的关键字段。当 task-management、data-sync 或 data-quality
     * worker 积压升高时，即使用户有权限，也不应继续放大工具执行压力。</p>
     */
    private String workerBacklogLevel;

    /**
     * 单轮允许进入自动同步执行准备态的最大工具数量。
     *
     * <p>0 表示当前策略不允许同步自动执行，只能展示草案、等待审批、排队或转人工处理。</p>
     */
    private Integer maxAutoSyncTools;

    /**
     * 单轮允许进入异步队列准备态的最大工具数量。
     *
     * <p>该值不等同于真实 task-management 队列容量，只是 Python Runtime 在形成 readiness 时使用的
     * 前置预算。真实 worker 领取前仍要经过 Java outbox、限流、幂等和 pre-check。</p>
     */
    private Integer maxAsyncTools;

    /**
     * HIGH 风险工具是否默认要求人工审批。
     *
     * <p>商业化默认建议为 true。即使某些角色预算较高，高风险工具也应先进入审批或确认链路，
     * 防止模型建议直接产生不可逆副作用。</p>
     */
    private Boolean highRiskRequiresApproval;

    /**
     * CRITICAL 风险工具是否默认阻断。
     *
     * <p>CRITICAL 工具可能涉及全量导出、跨租户访问、敏感数据处理或破坏性操作。默认阻断可以防止
     * Python Runtime 在未经过平台管理员策略确认前推进执行。</p>
     */
    private Boolean criticalRiskBlocked;

    /**
     * 参数不完整但可生成草案时，是否允许停留在草案展示阶段。
     *
     * <p>该字段控制“不能执行”与“可以先展示草案”的差异。高风险或锁定 workspace 可以关闭该能力，
     * 避免系统生成看似可用但缺乏足够上下文的操作草案。</p>
     */
    private Boolean allowDraftWithoutAllParameters;

    /**
     * 策略影响码。
     *
     * <p>影响码用于机器聚合和前端解释，例如 READ_ONLY_ROLE_BLOCKS_AUTO_EXECUTION、
     * WORKER_BACKLOG_BLOCKS_TOOL_BUDGET。使用稳定英文码可以避免下游依赖中文说明文本。</p>
     */
    private List<String> influenceCodes;
}
