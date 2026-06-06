/**
 * @Author : Cui
 * @Date: 2026/06/06 00:00
 * @Description DataSmart Govern Backend - AgentToolExecutionReadinessProjectionController.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller;

import com.czh.datasmart.govern.agent.controller.dto.AgentToolExecutionReadinessProjectionQueryResponse;
import com.czh.datasmart.govern.agent.service.runtime.AgentRuntimeEventProjectionQuery;
import com.czh.datasmart.govern.agent.service.runtime.AgentRuntimeEventQueryAccessContext;
import com.czh.datasmart.govern.agent.service.runtime.AgentRuntimeEventQueryAccessContextResolver;
import com.czh.datasmart.govern.agent.service.runtime.AgentToolExecutionReadinessProjectionService;
import com.czh.datasmart.govern.common.api.PlatformApiResponse;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 工具执行准备度投影查询控制器。
 *
 * <p>该 controller 从通用 `AgentRuntimeEventProjectionController` 中拆出，是为了避免主 runtime event
 * controller 持续膨胀，也让“工具执行准备度”作为独立产品能力有清晰边界。它只负责 HTTP 参数、
 * gateway 上下文 Header 和 service 调用，不解析 attributes、不执行工具、不创建审批单。</p>
 *
 * <p>业务定位：查询 Python Runtime 5.36 产生的 `tool_execution_readiness_recorded` 事件，解释本轮
 * ToolPlan 为什么可执行、等待审批、需要澄清、仅展示草案、异步入队、被限流或被阻断。</p>
 *
 * <p>安全边界：返回字段由 `AgentToolExecutionReadinessProjectionService` 白名单裁剪，只包含工具名、决策、
 * 风险、执行模式、字段名、issue/reason code 和计数；不返回参数真实值、SQL、prompt、样本数据、payload
 * 明细、模型输出、凭证或内部 endpoint。</p>
 */
@RestController
@RequestMapping({"/agent-runtime/runtime-events", "/api/agent/runtime-events"})
@RequiredArgsConstructor
public class AgentToolExecutionReadinessProjectionController {

    private final AgentToolExecutionReadinessProjectionService readinessProjectionService;
    private final AgentRuntimeEventQueryAccessContextResolver accessContextResolver;

    /**
     * 查询工具执行准备度 runtime event 的强类型投影视图。
     *
     * <p>查询参数与通用 runtime event 查询保持一致：tenant/project/actor/request/run/session/severity 用于过滤，
     * `afterSequence` 用于断线续传或增量读取，`limit` 由查询对象统一限制默认值和最大值。</p>
     *
     * <p>调用方不能通过 query string 指定 eventType，因为本接口固定读取
     * `tool_execution_readiness_recorded`。固定事件类型可以避免专用接口被当作第二个通用事件查询入口，
     * 也能确保所有返回字段都经过 readiness 专用 DTO 裁剪。</p>
     */
    @GetMapping("/tool-execution-readiness-snapshots")
    public PlatformApiResponse<AgentToolExecutionReadinessProjectionQueryResponse> queryToolExecutionReadinessSnapshots(
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
                readinessProjectionService.querySnapshots(query, accessContext),
                traceId
        );
    }
}
