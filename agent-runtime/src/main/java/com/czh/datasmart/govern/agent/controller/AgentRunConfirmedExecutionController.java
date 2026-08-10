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

    /**
     * Gateway 在完成租户、应用和项目权限判定后重建的应用范围 Header。
     *
     * <p>应用 ID 不能从 projectId 推导：不同租户下项目编号可以相同，而一次 Agent 委托、
     * Specialist fact 和后确认执行必须落在同一个 tenant/application/project 三元范围内。
     * 因此 Controller 只读取 Gateway 注入的值，并交由服务层在需要声明任务已提交时 fail-closed 校验。</p>
     */
    private static final String APPLICATION_ID_HEADER = "X-DataSmart-Application-Id";

    private final AgentRunConfirmedExecutionService confirmedExecutionService;

    @PostMapping("/{sessionId}/runs/{runId}/confirm-and-execute")
    public PlatformApiResponse<AgentRunConfirmedExecutionResponse> confirmAndExecute(
            @PathVariable String sessionId,
            @PathVariable String runId,
            @Valid @RequestBody AgentRunConfirmedExecutionRequest request,
            @RequestHeader(PlatformContextHeaders.TENANT_ID) Long tenantId,
            @RequestHeader(value = APPLICATION_ID_HEADER, required = false) Long applicationId,
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
                        sessionId, runId, request, tenantId, applicationId, projectId, actorId,
                        actorRole, actorType, authorizedProjectRoles, traceId),
                traceId
        );
    }
}
