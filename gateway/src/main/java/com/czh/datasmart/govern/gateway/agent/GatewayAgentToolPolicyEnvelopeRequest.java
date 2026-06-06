/**
 * @Author : Cui
 * @Date: 2026/06/06 23:08
 * @Description DataSmart Govern Backend - GatewayAgentToolPolicyEnvelopeRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.gateway.agent;

import lombok.Data;

/**
 * gateway 调用 permission-admin 评估 Agent 工具策略的请求 DTO。
 *
 * <p>为什么 gateway 侧要保留一份 DTO，而不是直接依赖 permission-admin 模块的
 * {@code AgentToolBudgetPolicyEvaluateRequest}：
 * 1. gateway 和 permission-admin 是两个独立微服务，不能为了复用类而形成编译期反向耦合；
 * 2. 这里表达的是 HTTP 契约快照，字段名需要和 permission-admin 对齐，但类归属应属于调用方边界；
 * 3. 后续如果 permission-admin 内部 DTO 重构，只要 HTTP JSON 字段不变，gateway 不需要跟着改内部依赖。</p>
 *
 * <p>字段全部是低敏控制面事实：角色、套餐、workspace 风险和 worker backlog。
 * 该请求绝不能包含用户 prompt、SQL、工具参数、样本数据、模型输出、凭证或内部 endpoint。</p>
 */
@Data
public class GatewayAgentToolPolicyEnvelopeRequest {

    /**
     * 租户 ID。
     *
     * <p>用于 permission-admin 按租户套餐、租户策略版本和审计范围生成策略。
     * 本地开发缺失时可以为空或 0，但生产环境应由认证链路写入。</p>
     */
    private Long tenantId;

    /**
     * 项目 ID。
     *
     * <p>gateway 当前不读取 `/agent/plans` request body，因此无法直接读取业务 projectId。
     * 第一阶段会从已授权项目 Header 中取一个低敏代表值；后续如果 agent-runtime 先行接入，可以由
     * 更准确的项目上下文生成策略 envelope。</p>
     */
    private String projectId;

    /**
     * workspace 隔离键。
     */
    private String workspaceKey;

    /**
     * 调用主体角色编码，例如 PROJECT_OWNER、AUDITOR、SERVICE_ACCOUNT。
     */
    private String actorRole;

    /**
     * 租户套餐编码，例如 FREE、STANDARD、ENTERPRISE。
     */
    private String tenantPlanCode;

    /**
     * workspace 风险等级，例如 NORMAL、HIGH、CRITICAL。
     */
    private String workspaceRiskLevel;

    /**
     * worker 积压等级。
     *
     * <p>该字段把性能和可靠性纳入工具治理：即使用户有权限，worker 已经积压时也应收紧自动工具执行。</p>
     */
    private String workerBacklogLevel;

    /**
     * 本轮请求估计的最高工具风险等级。
     *
     * <p>gateway 现阶段不解析请求体，所以先由配置提供默认值。未来可以由 agent-runtime 的 ToolPlan 草案、
     * MCP tools/call 元数据或 A2A action 风险摘要提供更准确值。</p>
     */
    private String requestedToolRiskLevel;
}
