/**
 * @Author : Cui
 * @Date: 2026/06/02 19:10
 * @Description DataSmartGovernBackend - AgentSkillAdmissionPolicyService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.service;

import com.czh.datasmart.govern.permission.controller.dto.AgentSkillAdmissionEvaluateRequest;
import com.czh.datasmart.govern.permission.controller.dto.AgentSkillAdmissionPolicyView;

/**
 * Agent Skill 准入策略服务。
 *
 * <p>该接口是 permission-admin 面向 Agent Skill Marketplace 和 Python Runtime 的控制面边界。
 * Python 侧可以继续负责语义选择，但“是否允许启用 Skill”应逐步由 Java 权限中心统一判断。</p>
 */
public interface AgentSkillAdmissionPolicyService {

    /**
     * 评估某个 Skill 在当前请求上下文中是否允许启用。
     *
     * @param request Skill descriptor、主体权限、角色、租户开关和 workspace 风险上下文。
     * @return Skill 准入策略视图。
     */
    AgentSkillAdmissionPolicyView evaluate(AgentSkillAdmissionEvaluateRequest request);
}
