/**
 * @Author : Cui
 * @Date: 2026/07/02 00:00
 * @Description DataSmart Govern Backend - AgentExecutionSessionProjectionController.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller;

import com.czh.datasmart.govern.agent.controller.dto.AgentExecutionSessionProjectionQueryResponse;
import com.czh.datasmart.govern.agent.service.runtime.AgentExecutionSessionProjectionService;
import com.czh.datasmart.govern.agent.service.runtime.AgentRuntimeEventProjectionQuery;
import com.czh.datasmart.govern.agent.service.runtime.AgentRuntimeEventQueryAccessContext;
import com.czh.datasmart.govern.agent.service.runtime.AgentRuntimeEventQueryAccessContextResolver;
import com.czh.datasmart.govern.common.api.PlatformApiResponse;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 受控多 Agent 执行会话投影查询控制器。
 *
 * <p>本控制器独立于 `AgentRuntimeEventProjectionController`，是为了避免原通用 runtime event 控制器继续膨胀。
 * 它仍然挂在同一组 runtime-events 路径下，产品语义上属于“Java 控制面读取 Python Agent 运行事实”。</p>
 *
 * <p>路径含义：</p>
 * <p>1. `/agent-runtime/runtime-events/agent-execution-session-snapshots`：服务内部直连调试路径；</p>
 * <p>2. `/api/agent/runtime-events/agent-execution-session-snapshots`：经 gateway 暴露给管理台或审计台的统一路径。</p>
 *
 * <p>安全边界：该接口固定查询 `agent_execution_session_recorded` 事件，返回强类型低敏视图，不返回 prompt、
 * SQL、工具参数、样本数据、模型输出、token、内部 endpoint、memoryId 或异常堆栈。所有查询仍会经过
 * `AgentRuntimeEventProjectionAccessSupport` 的租户、项目、本人/角色数据范围收口。</p>
 */
@RestController
@RequestMapping({"/agent-runtime/runtime-events", "/api/agent/runtime-events"})
@RequiredArgsConstructor
public class AgentExecutionSessionProjectionController {

    private final AgentExecutionSessionProjectionService executionSessionProjectionService;
    private final AgentRuntimeEventQueryAccessContextResolver accessContextResolver;

    /**
     * 查询受控多 Agent 执行会话 runtime event 的强类型投影视图。
     *
     * <p>典型使用场景：</p>
     * <p>1. Agent 运行详情页展示当前会话是否等待审批、等待控制面反馈、可进入二轮或可交 Java outbox；</p>
     * <p>2. 审计员回放某次高风险数据同步/ETL 请求中哪些 Agent 参与、谁处于 standby、谁触发 handoff；</p>
     * <p>3. 后续真实多 Agent runner 在恢复执行前读取 resumeAction 和 sideEffectBoundary，确认不能绕过 Java 控制面。</p>
     */
    @GetMapping("/agent-execution-session-snapshots")
    public PlatformApiResponse<AgentExecutionSessionProjectionQueryResponse> queryAgentExecutionSessionSnapshots(
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
                executionSessionProjectionService.querySnapshots(query, accessContext),
                traceId
        );
    }
}
