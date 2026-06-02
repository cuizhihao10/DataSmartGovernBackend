/**
 * @Author : Cui
 * @Date: 2026/06/02 18:38
 * @Description DataSmartGovernBackend - AgentToolBudgetPolicyService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.service;

import com.czh.datasmart.govern.permission.controller.dto.AgentToolBudgetPolicyEvaluateRequest;
import com.czh.datasmart.govern.permission.controller.dto.AgentToolBudgetPolicyView;

/**
 * Agent 工具调用预算策略服务。
 *
 * <p>该接口是 permission-admin 接入智能网关策略中心的第一层边界。
 * Python AI Runtime 不应该长期通过环境变量或用户请求自行决定工具预算；
 * 更成熟的做法是由 Java 控制面统一计算，并把结果作为可信治理变量传给 Python。</p>
 */
public interface AgentToolBudgetPolicyService {

    /**
     * 评估本轮 Agent 工具调用预算。
     *
     * @param request 租户、项目、角色、workspace 风险、worker backlog 等策略上下文。
     * @return 可直接映射到 Python Runtime `toolCallBudget` 的预算视图。
     */
    AgentToolBudgetPolicyView evaluate(AgentToolBudgetPolicyEvaluateRequest request);
}
