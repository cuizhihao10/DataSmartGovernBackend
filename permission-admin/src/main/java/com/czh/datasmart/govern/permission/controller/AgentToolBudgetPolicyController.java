/**
 * @Author : Cui
 * @Date: 2026/06/02 18:38
 * @Description DataSmartGovernBackend - AgentToolBudgetPolicyController.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.controller;

import com.czh.datasmart.govern.common.api.PlatformApiResponse;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import com.czh.datasmart.govern.permission.controller.dto.AgentToolBudgetPolicyEvaluateRequest;
import com.czh.datasmart.govern.permission.controller.dto.AgentToolBudgetPolicyView;
import com.czh.datasmart.govern.permission.service.AgentToolBudgetPolicyService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Agent 工具预算策略控制器。
 *
 * <p>该 Controller 是 permission-admin 面向智能网关的策略入口。
 * 它不会执行工具、不会调用模型、不会改写 Python Runtime 请求；它只回答一件事：
 * 在当前租户、项目、角色、workspace 风险和执行容量下，Python Runtime 本轮应使用什么工具调用预算。</p>
 *
 * <p>路径设计继续提供两组前缀：
 * - `/permissions/agent/**`：服务本地调试；
 * - `/api/permission/agent/**`：通过 gateway 访问时的兼容路径。</p>
 */
@RestController
@RequestMapping({"/permissions/agent/tool-budget-policies", "/api/permission/agent/tool-budget-policies"})
@RequiredArgsConstructor
public class AgentToolBudgetPolicyController {

    private final AgentToolBudgetPolicyService agentToolBudgetPolicyService;

    /**
     * 评估 Agent 工具预算策略。
     *
     * <p>典型调用链：
     * 1. 前端或 gateway 生成 Agent 请求上下文；
     * 2. Java 控制面调用本接口得到 `toolCallBudget`；
     * 3. gateway 或 agent-runtime 把预算注入 Python `AgentRequest.variables`；
     * 4. Python Runtime 的 `ModelToolCallBudgetPolicyProvider` 读取预算并执行 guard。</p>
     */
    @PostMapping("/evaluate")
    public PlatformApiResponse<AgentToolBudgetPolicyView> evaluate(
            @Valid @RequestBody AgentToolBudgetPolicyEvaluateRequest request,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        return PlatformApiResponse.success("Agent 工具预算策略评估完成",
                agentToolBudgetPolicyService.evaluate(request),
                traceId);
    }
}
