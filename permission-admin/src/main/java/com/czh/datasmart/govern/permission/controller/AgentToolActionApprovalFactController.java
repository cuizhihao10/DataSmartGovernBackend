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
import com.czh.datasmart.govern.permission.service.support.AgentApprovalFactTrustedRegistrationGuard;
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
    private final AgentApprovalFactTrustedRegistrationGuard trustedRegistrationGuard;

    /**
     * 登记一条审批事实。
     *
     * <p>该路由只供 agent-runtime、审批工作流或管理控制面登记。Gateway 的 SERVICE_ACCOUNT 路由策略是
     * 第一层校验，本控制器还会要求来源服务命中白名单且内部 token 匹配；普通客户端即使直接访问服务，
     * 也不能伪造 APPROVED 事实。通过服务身份校验后，审批事实本身仍保存原用户和资源范围。</p>
     */
    @PostMapping("/facts")
    public PlatformApiResponse<AgentToolActionApprovalFactRegisterResponse> register(
            @Valid @RequestBody AgentToolActionApprovalFactRegisterRequest request,
            @RequestHeader(value = PlatformContextHeaders.SOURCE_SERVICE, required = false) String sourceService,
            @RequestHeader(value = PlatformContextHeaders.INTERNAL_SERVICE_TOKEN, required = false) String internalToken,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        trustedRegistrationGuard.requireTrusted(sourceService, internalToken);
        /*
         * 基础受信服务身份只能证明“该调用来自平台内部”，不能证明它具有替代人工审批的资格。
         * 因此必须在把请求交给 service/store 之前，根据目标状态再做一次职责分离校验；否则
         * agent-runtime 之类的编排服务可以使用自己的共享凭据直接登记 APPROVED 事实。
         */
        trustedRegistrationGuard.requireDecisionAuthority(
                sourceService, request == null ? null : request.getStatus());
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
