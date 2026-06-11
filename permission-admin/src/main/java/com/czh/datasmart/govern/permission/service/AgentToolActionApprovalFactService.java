/**
 * @Author : Cui
 * @Date: 2026/06/11 23:20
 * @Description DataSmart Govern Backend - AgentToolActionApprovalFactService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.service;

import com.czh.datasmart.govern.permission.controller.dto.AgentToolActionApprovalFactEvaluateRequest;
import com.czh.datasmart.govern.permission.controller.dto.AgentToolActionApprovalFactEvaluationView;
import com.czh.datasmart.govern.permission.controller.dto.AgentToolActionApprovalFactRegisterRequest;
import com.czh.datasmart.govern.permission.controller.dto.AgentToolActionApprovalFactRegisterResponse;

/**
 * Agent 受控工具动作审批事实服务。
 *
 * <p>该服务属于 permission-admin 的 approval and control service 能力。它不执行工具，也不替代 task-management
 * 状态机；它只回答“某个审批事实 ID 是否真实存在、是否仍有效、是否绑定当前受控动作”。</p>
 */
public interface AgentToolActionApprovalFactService {

    /**
     * 登记一条审批事实。
     *
     * @param request 低敏审批事实请求。
     * @return 登记结果。
     */
    AgentToolActionApprovalFactRegisterResponse register(AgentToolActionApprovalFactRegisterRequest request);

    /**
     * 评估审批事实是否允许当前受控工具动作继续进入下一阶段。
     *
     * @param request 当前任务上下文和审批事实 ID。
     * @return 审批事实评估结果。
     */
    AgentToolActionApprovalFactEvaluationView evaluate(AgentToolActionApprovalFactEvaluateRequest request);
}
