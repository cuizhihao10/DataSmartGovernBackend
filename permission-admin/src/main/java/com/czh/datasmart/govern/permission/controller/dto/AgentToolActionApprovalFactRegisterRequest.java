/**
 * @Author : Cui
 * @Date: 2026/06/11 23:20
 * @Description DataSmart Govern Backend - AgentToolActionApprovalFactRegisterRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.controller.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Agent 受控工具动作审批事实登记请求。
 *
 * <p>该 DTO 服务 permission-admin 的第一阶段审批事实能力。它不是完整审批流表单，也不是前端审批页模型；
 * 它只登记“某个受控工具动作已经产生一个可被服务端回查的审批事实”。后续真实审批中心、外部工单系统、
 * 企业微信/钉钉审批、合规复核台都可以把审批结果转换成这个低敏事实。</p>
 *
 * <p>安全边界：这里登记的是 ID、状态、租户/项目/actor/run/tool 绑定、策略版本和低敏证据码。
 * 不允许放入工具实参、payload body、SQL、prompt、样本数据、模型输出、凭证、内部 endpoint 或 artifact 正文。</p>
 */
@Data
public class AgentToolActionApprovalFactRegisterRequest {

    /** 审批事实 ID，例如 approval:human-001；后续 task-management 只会拿该 ID 回查服务端事实。 */
    @NotBlank(message = "approvalFactId 不能为空")
    private String approvalFactId;

    /** 审批事实所属租户；用于多租户隔离和防止跨租户复用审批。 */
    private Long tenantId;

    /** 审批事实所属项目；用于防止一个项目的审批被另一个项目复用。 */
    private Long projectId;

    /** 被代表的上游 actor，通常是发起 Agent 会话的人类用户或服务主体。 */
    private String actorId;

    /** Agent session ID，作为审批事实与会话上下文的绑定维度。 */
    private String sessionId;

    /** Agent run ID，作为审批事实与具体运行轮次的绑定维度。 */
    private String runId;

    /** 工具动作 commandId，用于确保审批只授权这一条受控命令。 */
    private String commandId;

    /** 工具编码，例如 datasource.metadata.read；后续可按工具维度做审批策略和审计查询。 */
    private String toolCode;

    /** 审批事实覆盖的策略版本；task-management 会与入箱时策略版本做一致性复核。 */
    private String policyVersion;

    /** 审批状态：APPROVED、PENDING、REJECTED。过期状态通常由 expiresAt 动态推导。 */
    private String status;

    /** 审批事实过期时间。为空表示当前内存阶段不自动过期，生产环境应强制设置。 */
    private LocalDateTime expiresAt;

    /** 审批人 actorId。这里只保存 ID，不保存审批意见正文或外部工单详情。 */
    private String approvedByActorId;

    /** 低敏原因码，例如 HUMAN_APPROVED、PROJECT_OWNER_APPROVED。 */
    private List<String> reasonCodes;

    /** 低敏证据码，例如 FRONTEND_CONFIRMATION_RECORDED、POLICY_VERSION_VERIFIED。 */
    private List<String> evidenceCodes;
}
