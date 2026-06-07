/**
 * @Author : Cui
 * @Date: 2026/06/07 15:16
 * @Description DataSmart Govern Backend - AgentToolActionCommandOutboxController.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller;

import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionCommandOutboxWriteResponse;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionCommandProposalRequest;
import com.czh.datasmart.govern.agent.service.runtime.AgentRuntimeEventQueryAccessContext;
import com.czh.datasmart.govern.agent.service.runtime.AgentRuntimeEventQueryAccessContextResolver;
import com.czh.datasmart.govern.agent.service.runtime.AgentToolActionCommandOutboxWriterService;
import com.czh.datasmart.govern.common.api.PlatformApiResponse;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 工具动作 command outbox 控制器。
 *
 * <p>该控制器是 5.52 command proposal 之后的第一阶段正式 writer 入口。调用方仍然提交 proposal 请求体，
 * 服务端会重新生成 proposal 并在通过后写入 command outbox。这里不会 dispatch，不会读取 payloadReference，
 * 也不会触发 worker；真实副作用仍由 outbox dispatcher、task-management inbox 和 worker receipt 后续完成。</p>
 *
 * <p>为什么不和 `AgentAsyncTaskCommandOutboxController` 混在一起：后者服务已有 auditId 的 Run 级 ASYNC_TASK 工具；
 * 本控制器服务外部协议入口转化来的 tool action command。两者最终复用同一个 outbox store，但入口语义、
 * 安全证据和后续复核链路不同，拆开能降低耦合和误用风险。</p>
 */
@RestController
@RequestMapping({"/agent-runtime/tool-action-commands", "/api/agent/tool-action-commands"})
@RequiredArgsConstructor
public class AgentToolActionCommandOutboxController {

    private final AgentToolActionCommandOutboxWriterService writerService;
    private final AgentRuntimeEventQueryAccessContextResolver accessContextResolver;

    /**
     * 将已通过 proposal 的工具动作写入 command outbox。
     *
     * <p>POST 路由表达“创建 durable command 事实”。但请注意：这只是创建待投递 outbox record，
     * 不是执行工具，也不是 task-management 已经接单。响应中的 record 也是安全视图，不会返回 payloadJson。</p>
     */
    @PostMapping("/outbox/write")
    public PlatformApiResponse<AgentToolActionCommandOutboxWriteResponse> write(
            @RequestBody(required = false) AgentToolActionCommandProposalRequest request,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) String currentTenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) String currentActorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String currentActorRole,
            @RequestHeader(value = PlatformContextHeaders.DATA_SCOPE_LEVEL, required = false) String dataScopeLevel,
            @RequestHeader(value = PlatformContextHeaders.AUTHORIZED_PROJECT_IDS, required = false) String authorizedProjectIds) {
        AgentRuntimeEventQueryAccessContext accessContext = accessContextResolver.resolve(
                currentTenantId,
                currentActorId,
                currentActorRole,
                traceId,
                dataScopeLevel,
                authorizedProjectIds
        );
        return PlatformApiResponse.success(writerService.write(request, accessContext), traceId);
    }
}
