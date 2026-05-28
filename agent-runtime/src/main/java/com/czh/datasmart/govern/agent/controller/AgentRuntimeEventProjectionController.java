/**
 * @Author : Cui
 * @Date: 2026/05/27 00:00
 * @Description DataSmart Govern Backend - AgentRuntimeEventProjectionController.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller;

import com.czh.datasmart.govern.agent.controller.dto.AgentRuntimeEventConsumerDiagnosticsView;
import com.czh.datasmart.govern.agent.controller.dto.AgentRuntimeEventProjectionQueryResponse;
import com.czh.datasmart.govern.agent.service.runtime.AgentRuntimeEventQueryAccessContext;
import com.czh.datasmart.govern.agent.service.runtime.AgentRuntimeEventQueryAccessContextResolver;
import com.czh.datasmart.govern.agent.service.runtime.AgentRuntimeEventProjectionQuery;
import com.czh.datasmart.govern.agent.service.runtime.AgentRuntimeEventProjectionQueryService;
import com.czh.datasmart.govern.common.api.PlatformApiResponse;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Agent runtime event 投影查询控制器。
 *
 * <p>该控制器是“Agent 运行事件中心”的最小 API 雏形。
 * 它查询的是 Java agent-runtime 已经从 Kafka 消费并投影到控制面的事件，不直接访问 Kafka，也不直接访问 Python Runtime。
 * 这样做的意义是：即使 Python Runtime 或 Kafka 短暂不可用，Java 控制面仍能展示自己已经接收到的热窗口事件。</p>
 *
 * <p>权限建议：
 * - 普通用户只能查询自己 actorId 范围内的事件；
 * - 项目 owner 可以查询项目范围；
 * - 平台运维可以查询诊断信息；
 * - 审计员可以查询高风险/approval/audit 事件。
 * 当前第一版先暴露服务内 API 契约，后续应由 gateway + permission-admin 加上 `AI_RUNTIME/VIEW_EVENTS`
 * 和 `AI_RUNTIME/DIAGNOSE` 之类的权限语义。</p>
 */
@RestController
@RequestMapping({"/agent-runtime/runtime-events", "/api/agent/runtime-events"})
@RequiredArgsConstructor
public class AgentRuntimeEventProjectionController {

    private final AgentRuntimeEventProjectionQueryService queryService;
    private final AgentRuntimeEventQueryAccessContextResolver accessContextResolver;

    /**
     * 查询 Java 控制面已消费的 Agent runtime event 投影。
     *
     * <p>查询参数说明：
     * - `runId`：运行详情页最常用；
     * - `sessionId`：排查 WebSocket/会话重连问题；
     * - `requestId`：从 gateway 或 HTTP 日志反查；
     * - `tenantId/projectId/actorId`：多租户、项目和操作者范围过滤；
     * - `eventType/severity`：按事件类型和严重级别过滤；
     * - `limit`：单次返回上限，默认 100，最大 1000。</p>
     */
    @GetMapping
    public PlatformApiResponse<AgentRuntimeEventProjectionQueryResponse> queryEvents(
            @RequestParam(value = "tenantId", required = false) String tenantId,
            @RequestParam(value = "projectId", required = false) String projectId,
            @RequestParam(value = "actorId", required = false) String actorId,
            @RequestParam(value = "requestId", required = false) String requestId,
            @RequestParam(value = "runId", required = false) String runId,
            @RequestParam(value = "sessionId", required = false) String sessionId,
            @RequestParam(value = "eventType", required = false) String eventType,
            @RequestParam(value = "severity", required = false) String severity,
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
                eventType,
                severity,
                limit
        );
        AgentRuntimeEventQueryAccessContext accessContext = accessContextResolver.resolve(
                currentTenantId,
                currentActorId,
                currentActorRole,
                traceId,
                dataScopeLevel,
                authorizedProjectIds
        );
        return PlatformApiResponse.success(queryService.query(query, accessContext), traceId);
    }

    /**
     * 查询 Agent runtime event consumer 诊断信息。
     *
     * <p>该接口不返回事件明细，只返回配置、计数和最近拒绝原因，适合运维确认：
     * consumer 是否启用、topic/groupId 是否正确、当前投影是否有数据、是否存在大量重复或拒绝。</p>
     */
    @GetMapping("/diagnostics")
    public PlatformApiResponse<AgentRuntimeEventConsumerDiagnosticsView> diagnostics(
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        return PlatformApiResponse.success(queryService.diagnostics(), traceId);
    }
}
