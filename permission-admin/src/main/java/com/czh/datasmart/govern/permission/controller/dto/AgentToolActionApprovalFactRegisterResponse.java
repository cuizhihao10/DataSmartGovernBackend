/**
 * @Author : Cui
 * @Date: 2026/06/11 23:20
 * @Description DataSmart Govern Backend - AgentToolActionApprovalFactRegisterResponse.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.controller.dto;

/**
 * Agent 受控工具动作审批事实登记响应。
 *
 * @param approvalFactId 已登记的审批事实 ID。
 * @param status 当前审批事实状态。
 * @param policyVersion 绑定的策略版本。
 * @param message 人读说明。
 */
public record AgentToolActionApprovalFactRegisterResponse(
        String approvalFactId,
        String status,
        String policyVersion,
        String message
) {
}
