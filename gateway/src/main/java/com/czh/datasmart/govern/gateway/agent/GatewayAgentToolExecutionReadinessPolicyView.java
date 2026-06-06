/**
 * @Author : Cui
 * @Date: 2026/06/06 23:08
 * @Description DataSmart Govern Backend - GatewayAgentToolExecutionReadinessPolicyView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.gateway.agent;

import lombok.Data;

import java.util.List;

/**
 * gateway 侧低敏工具执行准备度策略视图。
 *
 * <p>该类镜像 permission-admin 响应中的 `toolExecutionReadinessPolicy`，用于生成
 * `X-DataSmart-Tool-Policy-Envelope`。它不是执行器配置，也不是权限明细，只是一份“执行前治理摘要”。</p>
 *
 * <p>安全边界：
 * - 允许：source、policyVersion、actorRole、tenantPlanCode、workspaceRiskLevel、workerBacklogLevel、
 *   maxAutoSyncTools、maxAsyncTools、布尔策略开关和 influenceCodes；
 * - 禁止：prompt、SQL、工具参数值、样本数据、模型输出、credentials、internal endpoint、artifact 正文。</p>
 */
@Data
public class GatewayAgentToolExecutionReadinessPolicyView {

    /**
     * 策略来源，例如 permission-admin 或 gateway-local-fallback。
     */
    private String source;

    /**
     * 策略版本，用于把 Java 控制面策略与 Python readiness event 关联起来。
     */
    private String policyVersion;

    /**
     * 角色编码，只保留角色大类，不携带权限清单。
     */
    private String actorRole;

    /**
     * 租户套餐编码。
     */
    private String tenantPlanCode;

    /**
     * workspace 风险等级。
     */
    private String workspaceRiskLevel;

    /**
     * worker backlog 等级。
     */
    private String workerBacklogLevel;

    /**
     * 允许进入同步自动执行准备态的最大工具数量。
     */
    private Integer maxAutoSyncTools;

    /**
     * 允许进入异步队列准备态的最大工具数量。
     */
    private Integer maxAsyncTools;

    /**
     * HIGH 风险工具是否必须人工审批。
     */
    private Boolean highRiskRequiresApproval;

    /**
     * CRITICAL 风险工具是否默认阻断。
     */
    private Boolean criticalRiskBlocked;

    /**
     * 参数不完整时是否允许先生成草案。
     */
    private Boolean allowDraftWithoutAllParameters;

    /**
     * 稳定机器影响码，供前端、projection、审计和指标聚合使用。
     */
    private List<String> influenceCodes;
}
