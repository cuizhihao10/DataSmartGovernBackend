/**
 * @Author : Cui
 * @Date: 2026/06/11 23:20
 * @Description DataSmart Govern Backend - AgentToolActionApprovalFactEvaluateRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.controller.dto;

import lombok.Data;

/**
 * Agent 受控工具动作审批事实评估请求。
 *
 * <p>task-management dry-run dispatcher 会调用该请求来确认 `confirmationId` 是否确实是
 * permission-admin 已登记、未过期、已授权、且绑定当前 tenant/project/actor/session/run/command/tool 的事实。
 * 这一步让审批从“字符串看起来像 approval:xxx”升级为“服务端可回放事实”。</p>
 */
@Data
public class AgentToolActionApprovalFactEvaluateRequest {

    /** 需要回查的审批事实 ID，可为空；为空时服务端会返回 MISSING_ID，而不是假装审批通过。 */
    private String approvalFactId;

    /** 当前任务所属租户，用于校验审批事实不能跨租户复用。 */
    private Long tenantId;

    /** 当前任务所属项目，用于校验审批事实不能跨项目复用。 */
    private Long projectId;

    /** 当前受控动作代表的上游 actor。 */
    private String actorId;

    /** 当前 Agent session ID。 */
    private String sessionId;

    /** 当前 Agent run ID。 */
    private String runId;

    /** 当前受控工具动作 commandId。 */
    private String commandId;

    /** 当前工具编码。 */
    private String toolCode;

    /** task-management 当前携带的策略版本快照。 */
    private String requestedPolicyVersion;
}
