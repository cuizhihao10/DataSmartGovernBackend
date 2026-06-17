/**
 * @Author : Cui
 * @Date: 2026/06/17 00:00
 * @Description DataSmart Govern Backend - AgentToolActionClarificationFactController.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller;

import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionClarificationFactUpsertRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionClarificationFactView;
import com.czh.datasmart.govern.agent.service.runtime.AgentRuntimeEventQueryAccessContext;
import com.czh.datasmart.govern.agent.service.runtime.AgentRuntimeEventQueryAccessContextResolver;
import com.czh.datasmart.govern.agent.service.runtime.AgentToolActionClarificationFactRegistrationService;
import com.czh.datasmart.govern.common.api.PlatformApiResponse;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Agent 工具动作澄清事实控制器。
 *
 * <p>本控制器服务于 Human-in-the-loop 恢复链路：当 Agent 因缺少用户确认或补充信息而暂停时，
 * 受控页面、智能网关或 Python Runtime 可以调用本接口登记一个低敏“澄清事实”。
 * 后续恢复预检通过 fact bundle 查询该事实是否存在、是否仍在同一租户/项目/actor/run/session/command/tool 范围内。</p>
 *
 * <p>安全约束：
 * 1. 该接口只登记低敏元数据，不接收澄清原文；
 * 2. tenantId、actorId 和项目范围以可信 Header 为准，请求体只能进一步收口；
 * 3. 登记成功不代表工具获批，也不代表可以真实 resume，只代表 CLARIFICATION_FACT 这个前置事实可以被服务端回查。</p>
 */
@RestController
@RequestMapping({"/agent-runtime/tool-action-clarification-facts", "/api/agent/tool-action-clarification-facts"})
@RequiredArgsConstructor
public class AgentToolActionClarificationFactController {

    private final AgentToolActionClarificationFactRegistrationService registrationService;
    private final AgentRuntimeEventQueryAccessContextResolver accessContextResolver;

    /**
     * 登记或更新澄清事实。
     *
     * <p>路由语义使用 POST `/facts`，而不是把 factId 放在 URL：
     * factId 虽然属于低敏定位符，但仍然是恢复控制面的内部引用，放在 body 中更容易避免代理日志、
     * 浏览器历史或普通访问日志记录完整值。</p>
     *
     * <p>返回结果用于调用方确认登记成功。后续真正判断能否继续执行，应调用
     * `/tool-action-resume-facts/bundles/query`，而不是直接把本接口的成功响应当作执行许可。</p>
     */
    @PostMapping("/facts")
    public PlatformApiResponse<AgentToolActionClarificationFactView> upsert(
            @RequestBody AgentToolActionClarificationFactUpsertRequest request,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) String currentTenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) String currentActorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String currentActorRole,
            @RequestHeader(value = PlatformContextHeaders.DATA_SCOPE_LEVEL, required = false) String dataScopeLevel,
            @RequestHeader(value = PlatformContextHeaders.AUTHORIZED_PROJECT_IDS, required = false)
            String authorizedProjectIds) {
        AgentRuntimeEventQueryAccessContext accessContext = accessContextResolver.resolve(
                currentTenantId,
                currentActorId,
                currentActorRole,
                traceId,
                dataScopeLevel,
                authorizedProjectIds
        );
        return PlatformApiResponse.success(
                "澄清事实已登记，后续恢复预检可按 clarificationFactId 回查",
                registrationService.upsert(request, accessContext),
                traceId
        );
    }
}
