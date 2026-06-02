/**
 * @Author : Cui
 * @Date: 2026/06/02 18:38
 * @Description DataSmartGovernBackend - AgentToolBudgetPolicyView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.controller.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Agent 工具调用预算策略视图。
 *
 * <p>该响应同时服务两类调用方：
 * 1. Python AI Runtime：读取 `toolCallBudget` 后可直接映射到 `ModelToolCallBudgetPolicy`；
 * 2. 管理后台/审计台：读取 notes、policyVersion、policySource，解释为什么本轮预算是这个值。</p>
 *
 * <p>不要在这里返回工具参数、prompt、SQL、样本数据或真实执行结果。
 * 策略中心只负责“预算边界”，不负责传播模型上下文和业务数据。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AgentToolBudgetPolicyView {

    /**
     * 策略是否允许本轮继续进入 Python Runtime。
     *
     * <p>当前第一版始终返回 true，因为这里只计算预算，不做访问授权。
     * 后续如果租户冻结、项目冻结、套餐过期或 workspace 被安全封禁，可以返回 false 并让 gateway 直接阻断。</p>
     */
    private Boolean allowed;

    /**
     * 策略来源。
     *
     * <p>当前为 IN_MEMORY_RULE，表示规则由 permission-admin 内置代码生成。
     * 后续迁移到数据库、租户套餐中心或 Redis quota 后，可以变为 TENANT_PLAN、PROJECT_POLICY、BACKLOG_QUOTA 等。</p>
     */
    private String policySource;

    /**
     * 策略版本。
     *
     * <p>版本用于把“Python Runtime 本轮使用的预算”和“Java 控制面当时生成的策略”关联起来。
     * 当前版本由角色、套餐、风险和积压维度拼接；未来可替换为数据库策略发布版本。</p>
     */
    private String policyVersion;

    /**
     * Python Runtime 可直接消费的预算字段。
     *
     * <p>字段名刻意使用 camelCase，对齐 `AgentRequest.variables["toolCallBudget"]`：
     * maxProposedToolCalls、maxAutoExecutableToolCalls、maxHighRiskToolCalls、
     * maxSingleArgumentsBytes、maxTotalArgumentsBytes。</p>
     */
    private Map<String, Integer> toolCallBudget;

    /**
     * 策略解释。
     *
     * <p>用于管理后台和审计台展示“为什么预算被放宽或收紧”。例如角色基线、套餐限制、
     * 高风险 workspace 收紧、worker backlog 收紧等。</p>
     */
    private List<String> notes;

    /**
     * 推荐动作。
     *
     * <p>当预算被明显收紧时，调用方可以把这些建议展示给用户或运营人员，例如拆分工具批次、等待 backlog 下降、
     * 或请求项目负责人审批更高预算。</p>
     */
    private List<String> recommendedActions;
}
