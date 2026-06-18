/**
 * @Author : Cui
 * @Date: 2026/06/18 01:39
 * @Description DataSmart Govern Backend - AgentToolActionResumeGateGraphPreviewController.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller;

import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionResumeFactBundleQueryRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionResumeGateGraphQueryResponse;
import com.czh.datasmart.govern.agent.service.runtime.AgentRuntimeEventQueryAccessContext;
import com.czh.datasmart.govern.agent.service.runtime.AgentRuntimeEventQueryAccessContextResolver;
import com.czh.datasmart.govern.agent.service.runtime.AgentToolActionResumeGateGraphPreviewService;
import com.czh.datasmart.govern.common.api.PlatformApiResponse;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Agent 工具动作恢复门控图预览控制器。
 *
 * <p>本控制器面向 Python AI Runtime、智能网关、管理台和未来 OpenClaw-style runner。
 * 与 `/tool-action-resume-facts/bundles/query` 相比，本接口返回的是“图形化恢复门控”，更适合前端展示、审计解释
 * 和后续执行器按节点判断下一步动作。两者底层使用同一套事实验真逻辑，所以不会出现事实包说可用、图又说不可用的语义漂移。</p>
 *
 * <p>为什么使用 POST：请求体可能携带 approvalFactId、clarificationFactId 等只适合服务端回查的定位符。
 * 即使响应不会回显这些 ID，也不应该把它们放进 URL、浏览器历史或代理访问日志中。</p>
 *
 * <p>安全约束：该路由只读、preview-only。它不会执行工具、不会写 outbox、不会派发 worker、不会修改 checkpoint，
 * 也不会返回 prompt、SQL、工具参数、payload body、payloadReference、模型输出、凭证或内部 endpoint。</p>
 */
@RestController
@RequestMapping({"/agent-runtime/tool-action-resume-gates", "/api/agent/tool-action-resume-gates"})
@RequiredArgsConstructor
public class AgentToolActionResumeGateGraphPreviewController {

    private final AgentToolActionResumeGateGraphPreviewService previewService;
    private final AgentRuntimeEventQueryAccessContextResolver accessContextResolver;

    /**
     * 查询工具动作恢复门控图。
     *
     * <p>Header 中的租户、actor、角色和数据范围仍是权限收口来源；body 中的 tenant/project/actor 只能进一步缩小范围。
     * 这与 runtime event projection、resume fact bundle 和 worker receipt 查询的安全策略保持一致。</p>
     */
    @PostMapping("/graphs/preview")
    public PlatformApiResponse<AgentToolActionResumeGateGraphQueryResponse> preview(
            @RequestBody(required = false) AgentToolActionResumeFactBundleQueryRequest request,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) String currentTenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) String currentActorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String currentActorRole,
            @RequestHeader(value = PlatformContextHeaders.DATA_SCOPE_LEVEL, required = false) String dataScopeLevel,
            @RequestHeader(value = PlatformContextHeaders.AUTHORIZED_PROJECT_IDS, required = false)
            String authorizedProjectIds) {
        AgentRuntimeEventQueryAccessContext accessContext = accessContextResolver.resolve(
                currentTenantId,
                currentActorId,
                currentActorRole,
                traceId,
                dataScopeLevel,
                authorizedProjectIds
        );
        return PlatformApiResponse.success(previewService.query(request, accessContext), traceId);
    }
}
