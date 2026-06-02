/**
 * @Author : Cui
 * @Date: 2026/06/02 19:10
 * @Description DataSmartGovernBackend - AgentSkillAdmissionPolicyController.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.controller;

import com.czh.datasmart.govern.common.api.PlatformApiResponse;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import com.czh.datasmart.govern.permission.controller.dto.AgentSkillAdmissionEvaluateRequest;
import com.czh.datasmart.govern.permission.controller.dto.AgentSkillAdmissionPolicyView;
import com.czh.datasmart.govern.permission.service.AgentSkillAdmissionPolicyService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Agent Skill 准入策略控制器。
 *
 * <p>该 Controller 面向 Python Runtime、gateway、agent-runtime 和未来 Skill Marketplace。
 * 它不会选择 Skill，也不会执行 Skill；它只回答“某个已经被语义命中的 Skill，在当前主体、租户和
 * workspace 上下文中是否允许启用”。</p>
 *
 * <p>路径与工具预算策略保持一致：
 * - `/permissions/agent/**`：服务本地调试；
 * - `/api/permission/agent/**`：通过 gateway 访问时的兼容路径。</p>
 */
@RestController
@RequestMapping({"/permissions/agent/skill-admissions", "/api/permission/agent/skill-admissions"})
@RequiredArgsConstructor
public class AgentSkillAdmissionPolicyController {

    private final AgentSkillAdmissionPolicyService agentSkillAdmissionPolicyService;

    /**
     * 评估 Agent Skill 准入策略。
     *
     * <p>典型调用链：
     * 1. Python Runtime 或 gateway 根据用户目标命中候选 Skill；
     * 2. 调用本接口，传入 Skill descriptor 中的 requiredPermissions/riskLevel 和主体权限事实；
     * 3. permission-admin 返回 allowed/admissionStatus/policyVersion/rejectionReason；
     * 4. Python Runtime 把结果写入 Skill admission event 和智能网关治理摘要。</p>
     */
    @PostMapping("/evaluate")
    public PlatformApiResponse<AgentSkillAdmissionPolicyView> evaluate(
            @Valid @RequestBody AgentSkillAdmissionEvaluateRequest request,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        return PlatformApiResponse.success("Agent Skill 准入策略评估完成",
                agentSkillAdmissionPolicyService.evaluate(request),
                traceId);
    }
}
