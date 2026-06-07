/**
 * @Author : Cui
 * @Date: 2026/06/07 14:27
 * @Description DataSmart Govern Backend - AgentToolActionExecutionGraphPreviewController.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller;

import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionExecutionGraphQueryResponse;
import com.czh.datasmart.govern.agent.service.runtime.AgentRuntimeEventProjectionQuery;
import com.czh.datasmart.govern.agent.service.runtime.AgentRuntimeEventQueryAccessContext;
import com.czh.datasmart.govern.agent.service.runtime.AgentRuntimeEventQueryAccessContextResolver;
import com.czh.datasmart.govern.agent.service.runtime.AgentToolActionExecutionGraphPreviewService;
import com.czh.datasmart.govern.common.api.PlatformApiResponse;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 工具动作执行图预览控制器。
 *
 * <p>本控制器面向智能网关、前端确认页、审计台和未来 execution graph 编排器。它把低敏工具动作契约展示为图，
 * 让调用方可以看到入口、readiness、审批/澄清/预算、contract、outbox、worker receipt 的先后关系。
 * 该路由仍然是只读查询：不会执行工具、不会写 outbox、不会创建审批，也不会读取 payloadReference。</p>
 */
@RestController
@RequestMapping({"/agent-runtime/runtime-events", "/api/agent/runtime-events"})
@RequiredArgsConstructor
public class AgentToolActionExecutionGraphPreviewController {

    private final AgentToolActionExecutionGraphPreviewService executionGraphPreviewService;
    private final AgentRuntimeEventQueryAccessContextResolver accessContextResolver;

    /**
     * 查询工具动作执行图预览。
     *
     * <p>路径继续放在 runtime-events 下，是因为当前图的来源仍然是 runtime event projection 与 durable action contract preview。
     * 后续如果增加“正式写 outbox”的 API，应放到独立的 command/outbox 路由下，并要求显式确认、payloadReference 和幂等键。
     * 这样可以避免调用方误以为 GET 查询接口会触发真实副作用。</p>
     */
    @GetMapping("/tool-action-execution-graph-previews")
    public PlatformApiResponse<AgentToolActionExecutionGraphQueryResponse> queryExecutionGraphPreviews(
            @RequestParam(value = "tenantId", required = false) String tenantId,
            @RequestParam(value = "projectId", required = false) String projectId,
            @RequestParam(value = "actorId", required = false) String actorId,
            @RequestParam(value = "requestId", required = false) String requestId,
            @RequestParam(value = "runId", required = false) String runId,
            @RequestParam(value = "sessionId", required = false) String sessionId,
            @RequestParam(value = "severity", required = false) String severity,
            @RequestParam(value = "afterSequence", required = false) Long afterSequence,
            @RequestParam(value = "limit", required = false) Integer limit,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) String currentTenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) String currentActorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String currentActorRole,
            @RequestHeader(value = PlatformContextHeaders.DATA_SCOPE_LEVEL, required = false) String dataScopeLevel,
            @RequestHeader(value = PlatformContextHeaders.AUTHORIZED_PROJECT_IDS, required = false) String authorizedProjectIds) {
        AgentRuntimeEventProjectionQuery query = new AgentRuntimeEventProjectionQuery(
                tenantId,
                projectId,
                actorId,
                requestId,
                runId,
                sessionId,
                null,
                severity,
                limit,
                afterSequence
        );
        AgentRuntimeEventQueryAccessContext accessContext = accessContextResolver.resolve(
                currentTenantId,
                currentActorId,
                currentActorRole,
                traceId,
                dataScopeLevel,
                authorizedProjectIds
        );
        return PlatformApiResponse.success(
                executionGraphPreviewService.queryExecutionGraphs(query, accessContext),
                traceId
        );
    }
}
