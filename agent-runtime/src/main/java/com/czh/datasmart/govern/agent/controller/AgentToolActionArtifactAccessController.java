/**
 * @Author : Cui
 * @Date: 2026/06/24 17:53
 * @Description DataSmart Govern Backend - AgentToolActionArtifactAccessController.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller;

import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionArtifactAccessAuthorizationResponse;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionArtifactAccessAuthorizeRequest;
import com.czh.datasmart.govern.agent.service.runtime.AgentRuntimeEventQueryAccessContext;
import com.czh.datasmart.govern.agent.service.runtime.AgentRuntimeEventQueryAccessContextResolver;
import com.czh.datasmart.govern.agent.service.runtime.AgentToolActionArtifactAccessAuthorizationService;
import com.czh.datasmart.govern.common.api.PlatformApiResponse;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Agent 工具执行产物访问预授权控制器。
 *
 * <p>这个控制器刻意独立于 command worker receipt 写回控制器：receipt 写回回答“worker 的执行事实能否进入 runtime event”，
 * artifact access 回答“后续调用方是否可以继续尝试读取这份执行产物”。两个动作的调用方、风险和审计语义都不同，
 * 如果放在同一个 Controller 中，后续很容易把“写事实”和“读结果”耦合在一起，形成权限绕过点。</p>
 *
 * <p>当前路由不是文件下载接口。它只返回低敏 metadata-only 授权结果，不返回 stdout/stderr、artifact 正文、MinIO bucket/key、
 * 签名 URL、命令行、工具参数、SQL、prompt、模型输出、凭证或内部 endpoint。后续真正读取对象存储正文时，
 * 还必须走对象存储层自己的 ACL、DLP/恶意内容扫描、下载审计和保留期策略。</p>
 */
@RestController
@RequestMapping({"/agent-runtime/tool-action-artifacts", "/api/agent/tool-action-artifacts"})
@RequiredArgsConstructor
public class AgentToolActionArtifactAccessController {

    private final AgentToolActionArtifactAccessAuthorizationService authorizationService;
    private final AgentRuntimeEventQueryAccessContextResolver accessContextResolver;

    /**
     * 创建一次 artifact metadata-only 访问预授权。
     *
     * <p>使用 POST 而不是 GET，是因为请求体里包含 commandId、artifactReference、runId、sessionId 等访问判定上下文。
     * 虽然这些字段都属于低敏引用或 ID，但它们仍然不适合进入浏览器地址栏、代理缓存键或普通访问日志。
     * POST 还能为后续扩展 human-in-the-loop、下载审计意图、一次性访问票据等能力保留空间。</p>
     *
     * @param request 低敏 artifact 引用与范围过滤条件。
     * @param traceId gateway 透传的链路追踪 ID，会进入统一响应但不会影响授权范围。
     * @param currentTenantId 当前调用方所属租户，来自可信 gateway Header。
     * @param currentActorId 当前调用方 actor，来自可信 gateway Header。
     * @param currentActorRole 当前调用方角色，来自可信 gateway Header。
     * @param dataScopeLevel permission-admin 判定的数据范围，例如 SELF、PROJECT、TENANT、PLATFORM。
     * @param authorizedProjectIds PROJECT 范围下的授权项目集合。
     * @return metadata-only 预授权结果；authorized=true 也不代表正文已授权读取。
     */
    @PostMapping("/access-authorizations")
    public PlatformApiResponse<AgentToolActionArtifactAccessAuthorizationResponse> authorizeArtifactAccess(
            @RequestBody AgentToolActionArtifactAccessAuthorizeRequest request,
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
        AgentToolActionArtifactAccessAuthorizationResponse response =
                authorizationService.authorize(request, accessContext);
        return PlatformApiResponse.success(response, traceId);
    }
}
