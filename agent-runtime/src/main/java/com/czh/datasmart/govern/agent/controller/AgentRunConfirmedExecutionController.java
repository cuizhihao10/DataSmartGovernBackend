/**
 * @Author : Cui
 * @Date: 2026/07/10 00:00
 * @Description DataSmart Govern Backend - AgentRunConfirmedExecutionController.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller;

import com.czh.datasmart.govern.agent.controller.dto.AgentRunConfirmedExecutionRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentRunConfirmedExecutionResponse;
import com.czh.datasmart.govern.agent.service.AgentRunConfirmedExecutionService;
import com.czh.datasmart.govern.common.api.PlatformApiResponse;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 普通用户 Agent 计划确认执行入口。
 */
@RestController
@RequestMapping({"/agent-runtime/sessions", "/api/agent/sessions"})
@RequiredArgsConstructor
public class AgentRunConfirmedExecutionController {

    private final AgentRunConfirmedExecutionService confirmedExecutionService;

    @PostMapping("/{sessionId}/runs/{runId}/confirm-and-execute")
    public PlatformApiResponse<AgentRunConfirmedExecutionResponse> confirmAndExecute(
            @PathVariable String sessionId,
            @PathVariable String runId,
            @Valid @RequestBody AgentRunConfirmedExecutionRequest request,
            @RequestHeader(PlatformContextHeaders.TENANT_ID) Long tenantId,
            @RequestHeader(PlatformContextHeaders.PROJECT_ID) Long projectId,
            @RequestHeader(PlatformContextHeaders.ACTOR_ID) String actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_TYPE, required = false) String actorType,
            @RequestHeader(value = PlatformContextHeaders.AUTHORIZED_PROJECT_ROLES, required = false)
            String authorizedProjectRoles,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        return PlatformApiResponse.success(
                "Agent 计划确认执行完成",
                confirmedExecutionService.confirmAndExecute(
                        sessionId, runId, request, tenantId, projectId, actorId,
                        actorRole, actorType, authorizedProjectRoles, traceId),
                traceId
        );
    }
}
