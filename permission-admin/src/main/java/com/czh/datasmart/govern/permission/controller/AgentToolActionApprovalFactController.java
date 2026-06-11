/**
 * @Author : Cui
 * @Date: 2026/06/11 23:20
 * @Description DataSmart Govern Backend - AgentToolActionApprovalFactController.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.controller;

import com.czh.datasmart.govern.common.api.PlatformApiResponse;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import com.czh.datasmart.govern.permission.controller.dto.AgentToolActionApprovalFactEvaluateRequest;
import com.czh.datasmart.govern.permission.controller.dto.AgentToolActionApprovalFactEvaluationView;
import com.czh.datasmart.govern.permission.controller.dto.AgentToolActionApprovalFactRegisterRequest;
import com.czh.datasmart.govern.permission.controller.dto.AgentToolActionApprovalFactRegisterResponse;
import com.czh.datasmart.govern.permission.service.AgentToolActionApprovalFactService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Agent 受控工具动作审批事实控制器。
 *
 * <p>该 Controller 是 permission-admin 面向 Agent Host/tool action control plane 的审批事实入口。
 * 它的职责不是让工具执行，而是让 task-management 能在 dry-run/pre-check 阶段回查：
 * “这个 approvalFactId 是否真实存在、未过期、已授权、且绑定当前工具动作”。</p>
 *
 * <p>路径继续提供本地与 gateway 双前缀：
 * `/permissions/agent/tool-action-approvals/**` 方便服务内联调；
 * `/api/permission/agent/tool-action-approvals/**` 方便通过 gateway 进入。</p>
 */
@RestController
@RequestMapping({"/permissions/agent/tool-action-approvals", "/api/permission/agent/tool-action-approvals"})
@RequiredArgsConstructor
public class AgentToolActionApprovalFactController {

    private final AgentToolActionApprovalFactService approvalFactService;

    /**
     * 登记一条审批事实。
     *
     * <p>当前阶段主要给内部联调、测试和未来审批台使用。生产环境应把该路由限制给 agent-runtime、
     * 审批工作流服务或管理后台服务账号，不能让普通客户端直接伪造 APPROVED 事实。</p>
     */
    @PostMapping("/facts")
    public PlatformApiResponse<AgentToolActionApprovalFactRegisterResponse> register(
            @Valid @RequestBody AgentToolActionApprovalFactRegisterRequest request,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        return PlatformApiResponse.success("Agent 工具动作审批事实已登记",
                approvalFactService.register(request), traceId);
    }

    /**
     * 评估审批事实是否允许当前受控工具动作继续。
     *
     * <p>task-management dry-run 使用该接口时，只应依赖 approved/retryable/decision/issueCodes 等低敏字段。
     * 即使未来审批事实来自外部审批系统，接口也不应返回审批意见正文或外部工单详情。</p>
     */
    @PostMapping("/evaluate")
    public PlatformApiResponse<AgentToolActionApprovalFactEvaluationView> evaluate(
            @RequestBody AgentToolActionApprovalFactEvaluateRequest request,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        return PlatformApiResponse.success("Agent 工具动作审批事实评估完成",
                approvalFactService.evaluate(request), traceId);
    }
}
