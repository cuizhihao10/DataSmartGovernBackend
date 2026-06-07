/**
 * @Author : Cui
 * @Date: 2026/06/07 13:39
 * @Description DataSmart Govern Backend - AgentToolActionIntakeProjectionController.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller;

import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionIntakeProjectionQueryResponse;
import com.czh.datasmart.govern.agent.service.runtime.AgentRuntimeEventProjectionQuery;
import com.czh.datasmart.govern.agent.service.runtime.AgentRuntimeEventQueryAccessContext;
import com.czh.datasmart.govern.agent.service.runtime.AgentRuntimeEventQueryAccessContextResolver;
import com.czh.datasmart.govern.agent.service.runtime.AgentToolActionIntakeProjectionService;
import com.czh.datasmart.govern.common.api.PlatformApiResponse;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 工具动作意图入口投影查询控制器。
 *
 * <p>业务定位：查询 Python Runtime 5.48 产生的 `tool_action_intake_recorded` 事件，用于解释 MCP `tools/call`
 * 或未来 A2A action、前端确认页重放等外部工具动作意图，在 DataSmart host-level intake 中是被接收、在
 * readiness 前被拒绝、等待审批、需要澄清、可执行还是被策略阻断。</p>
 *
 * <p>安全边界：本 controller 不解析 attributes、不读取 MCP 原始参数、不执行工具、不创建审批、不写 outbox。
 * 所有字段裁剪都交给 `AgentToolActionIntakeProjectionService`，确保响应只包含低敏控制面事实。</p>
 */
@RestController
@RequestMapping({"/agent-runtime/runtime-events", "/api/agent/runtime-events"})
@RequiredArgsConstructor
public class AgentToolActionIntakeProjectionController {

    private final AgentToolActionIntakeProjectionService toolActionIntakeProjectionService;
    private final AgentRuntimeEventQueryAccessContextResolver accessContextResolver;

    /**
     * 查询工具动作意图入口 runtime event 的强类型投影视图。
     *
     * <p>调用方不能传 eventType，因为专用接口固定读取 `tool_action_intake_recorded`。这样可以避免本接口被
     * 当成“通用事件查询第二入口”，也能保证返回字段始终经过工具动作入口专用白名单。</p>
     *
     * @param tenantId 可选租户过滤条件。最终仍会被 header 中的当前租户和数据范围收口。
     * @param projectId 可选项目过滤条件。PROJECT 范围用户只能看到授权项目。
     * @param actorId 可选触发主体过滤条件。
     * @param requestId 可选请求级追踪过滤条件。
     * @param runId 可选 Agent run 过滤条件。
     * @param sessionId 可选 Agent session 过滤条件。
     * @param severity 可选事件严重级别过滤条件。
     * @param afterSequence replay 游标，只返回 Java 控制面 replaySequence 更大的事件。
     * @param limit 返回数量上限，服务层会做规范化和最大值限制。
     * @param traceId 网关链路追踪 ID，会透传到响应。
     * @param currentTenantId 当前访问者租户 ID，来自网关或认证上下文。
     * @param currentActorId 当前访问者主体 ID。
     * @param currentActorRole 当前访问者角色，例如 PROJECT_OWNER、AUDITOR、PLATFORM_ADMIN。
     * @param dataScopeLevel 当前访问者数据范围，例如 SELF、PROJECT、TENANT、PLATFORM。
     * @param authorizedProjectIds PROJECT 范围下的授权项目集合。
     * @return 低敏强类型投影与本次查询窗口聚合。
     */
    @GetMapping("/tool-action-intake-snapshots")
    public PlatformApiResponse<AgentToolActionIntakeProjectionQueryResponse> queryToolActionIntakeSnapshots(
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
                toolActionIntakeProjectionService.querySnapshots(query, accessContext),
                traceId
        );
    }
}
