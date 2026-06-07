/**
 * @Author : Cui
 * @Date: 2026/06/07 14:04
 * @Description DataSmart Govern Backend - AgentToolActionIntakeDurableActionContractController.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller;

import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionIntakeDurableActionContractQueryResponse;
import com.czh.datasmart.govern.agent.service.runtime.AgentRuntimeEventProjectionQuery;
import com.czh.datasmart.govern.agent.service.runtime.AgentRuntimeEventQueryAccessContext;
import com.czh.datasmart.govern.agent.service.runtime.AgentRuntimeEventQueryAccessContextResolver;
import com.czh.datasmart.govern.agent.service.runtime.AgentToolActionIntakeDurableActionContractService;
import com.czh.datasmart.govern.common.api.PlatformApiResponse;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 工具动作入口 durable action 契约预览控制器。
 *
 * <p>该控制器面向智能网关确认页、运营排障台和后续 execution graph 条件节点。它从已经低敏化的
 * `tool_action_intake_recorded` 投影中推导“如果要进入 outbox，还缺哪些证据”，但不会写 outbox、
 * 不会创建审批、不读取工具参数，也不会调用 worker。</p>
 */
@RestController
@RequestMapping({"/agent-runtime/runtime-events", "/api/agent/runtime-events"})
@RequiredArgsConstructor
public class AgentToolActionIntakeDurableActionContractController {

    private final AgentToolActionIntakeDurableActionContractService durableActionContractService;
    private final AgentRuntimeEventQueryAccessContextResolver accessContextResolver;

    /**
     * 查询工具动作入口 durable action 契约预览。
     *
     * <p>路由命名仍挂在 runtime-events 下，是因为它的输入事实来自 runtime event projection；
     * 返回结果是执行前契约预览，而不是 outbox 写入结果。未来如果新增真实写入 outbox 的确认 API，
     * 应放到 async-command-outbox 或 tool-control 路由下，并要求显式确认、payloadReference 和策略版本。</p>
     */
    @GetMapping("/tool-action-intake-durable-action-contracts")
    public PlatformApiResponse<AgentToolActionIntakeDurableActionContractQueryResponse> queryDurableActionContracts(
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
                durableActionContractService.queryContracts(query, accessContext),
                traceId
        );
    }
}
