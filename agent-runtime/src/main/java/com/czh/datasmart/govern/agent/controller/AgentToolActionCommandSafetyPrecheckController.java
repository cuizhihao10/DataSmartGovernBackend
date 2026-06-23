/**
 * @Author : Cui
 * @Date: 2026-06-23 01:37
 * @Description DataSmart Govern Backend - AgentToolActionCommandSafetyPrecheckController.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller;

import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionCommandSafetyPrecheckRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionCommandSafetyPrecheckResponse;
import com.czh.datasmart.govern.agent.service.runtime.AgentRuntimeEventQueryAccessContext;
import com.czh.datasmart.govern.agent.service.runtime.AgentRuntimeEventQueryAccessContextResolver;
import com.czh.datasmart.govern.agent.service.runtime.AgentToolActionCommandSafetyPrecheckService;
import com.czh.datasmart.govern.common.api.PlatformApiResponse;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Agent 工具动作命令安全预检控制器。
 *
 * <p>该控制器位于 command proposal / outbox writer 之前，面向未来的 run-program、shell、MCP tools/call、
 * A2A action 和 OpenClaw-style worker 提供统一 safe-cmd 预检入口。</p>
 *
 * <p>为什么不直接复用 {@link AgentToolActionCommandProposalController}：
 * proposal 关注“某个 ToolPlan/ExecutionGraph 是否具备写 outbox 的证据”，而本控制器关注“命令文本、
 * 路径、联网、写入、预算本身是否安全”。两者最终会在 durable action runner 中汇合，但 API 职责必须分开，
 * 否则 proposal 会变成一个巨大且难以审计的全能入口。</p>
 */
@RestController
@RequestMapping({"/agent-runtime/tool-action-commands", "/api/agent/tool-action-commands"})
@RequiredArgsConstructor
public class AgentToolActionCommandSafetyPrecheckController {

    private final AgentToolActionCommandSafetyPrecheckService precheckService;
    private final AgentRuntimeEventQueryAccessContextResolver accessContextResolver;

    /**
     * 执行命令安全预检。
     *
     * <p>POST 语义表示调用方提交了一次“命令执行意图”供 Java Host 审查。该路由不会启动进程，不会写
     * outbox，不会创建审批，不会联网，也不会返回命令正文或真实路径。响应只包含低敏 verdict、问题码、
     * 路径分类、预算裁剪结果和下一步建议。</p>
     */
    @PostMapping("/safety-precheck")
    public PlatformApiResponse<AgentToolActionCommandSafetyPrecheckResponse> precheck(
            @RequestBody(required = false) AgentToolActionCommandSafetyPrecheckRequest request,
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
        return PlatformApiResponse.success(precheckService.precheck(request, accessContext), traceId);
    }
}
