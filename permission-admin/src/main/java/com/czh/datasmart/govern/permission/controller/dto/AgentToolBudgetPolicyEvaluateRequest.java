/**
 * @Author : Cui
 * @Date: 2026/06/02 18:38
 * @Description DataSmartGovernBackend - AgentToolBudgetPolicyEvaluateRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.controller.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Agent 工具调用预算策略评估请求。
 *
 * <p>这个 DTO 是 permission-admin 面向智能网关的第一版“策略中心契约”。
 * Python AI Runtime 当前已经支持从请求变量读取 `toolCallBudget`，但真正商业化时，预算不应该由前端或模型侧随意决定，
 * 而应该由 Java 控制面根据租户、项目、角色、workspace 风险和运行容量统一计算。</p>
 *
 * <p>字段设计刻意保留多个维度，而不是只传 actorRole：
 * 1. 角色决定“人或服务账号能做多大动作”；
 * 2. 租户套餐决定“客户购买的容量边界”；
 * 3. workspace 风险决定“当前空间是否允许自动推进更多工具”；
 * 4. worker backlog 决定“当前后端执行系统是否承压，需要临时收紧预算”。</p>
 */
@Data
public class AgentToolBudgetPolicyEvaluateRequest {

    /**
     * 租户 ID。
     *
     * <p>当前第一版内存规则不会直接访问租户表，但保留 tenantId 是为了后续接 tenant-plan、合同套餐、
     * 私有化部署限额和审计日志。为空时表示平台默认或本地联调用例。</p>
     */
    private Long tenantId;

    /**
     * 项目 ID 或业务域 ID。
     *
     * <p>真实数据治理平台里，同一租户内的不同项目可能有不同风险等级和执行资源。
     * 当前字段只参与策略版本摘要，后续可以映射到项目等级、项目配额、项目冻结状态和负责人审批策略。</p>
     */
    private String projectId;

    /**
     * workspace 隔离键。
     *
     * <p>Agent 的长期记忆、缓存、产物和工具执行都已经按 workspace 隔离。
     * 工具预算也应理解 workspace：例如默认项目空间可以稍宽，临时沙箱或高敏空间要更保守。</p>
     */
    private String workspaceKey;

    /**
     * 调用主体角色编码。
     *
     * <p>必填。推荐值来自 {@code PermissionRoleCode}，例如 ORDINARY_USER、PROJECT_OWNER、OPERATOR、
     * AUDITOR、TENANT_ADMINISTRATOR、PLATFORM_ADMINISTRATOR、SERVICE_ACCOUNT。</p>
     */
    @NotBlank(message = "actorRole 不能为空")
    private String actorRole;

    /**
     * 租户套餐编码。
     *
     * <p>推荐值：FREE、STANDARD、ENTERPRISE、PLATFORM_INTERNAL。
     * 第一版规则把未知套餐当作 STANDARD，避免因为新套餐尚未注册导致 Agent 预算被异常放大。</p>
     */
    private String tenantPlanCode;

    /**
     * workspace 风险等级。
     *
     * <p>推荐值：LOW、NORMAL、HIGH、CRITICAL。
     * 风险越高，自动推进工具数量、高风险工具数量和参数体积预算越应收紧。</p>
     */
    private String workspaceRiskLevel;

    /**
     * worker 积压等级。
     *
     * <p>推荐值：LOW、NORMAL、HIGH、CRITICAL。
     * 这是把“实时容量保护”纳入策略中心的预留入口：当 task-management、data-sync 或 data-quality worker
     * 积压升高时，智能网关应减少一次模型响应能推进的工具数量。</p>
     */
    private String workerBacklogLevel;

    /**
     * 本轮模型主要请求的最高工具风险等级。
     *
     * <p>推荐值：LOW、MEDIUM、HIGH、CRITICAL。
     * 当前规则只做温和收紧；后续可以按工具成本、目标服务、数据量估计和审批策略做更细的预算计算。</p>
     */
    private String requestedToolRiskLevel;
}
