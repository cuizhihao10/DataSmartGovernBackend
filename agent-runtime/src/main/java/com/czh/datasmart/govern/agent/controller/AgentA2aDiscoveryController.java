/**
 * @Author : Cui
 * @Date: 2026/06/06 02:06
 * @Description DataSmart Govern Backend - AgentA2aDiscoveryController.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller;

import com.czh.datasmart.govern.agent.controller.dto.AgentA2aPublicAgentCardView;
import com.czh.datasmart.govern.agent.service.runtime.AgentExternalProtocolDiscoveryAuditContext;
import com.czh.datasmart.govern.agent.service.runtime.AgentExternalProtocolDiscoveryEventPublisher;
import com.czh.datasmart.govern.agent.service.runtime.AgentExternalProtocolDiscoveryService;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * A2A Agent 公开发现控制器。
 *
 * <p>A2A 的 Agent Card 通常通过 `/.well-known/agent-card.json` 被其他 Agent、网关或工具发现。
 * 与 DataSmart 内部管理 API 不同，这个端点不使用 `PlatformApiResponse` 包装，而是直接返回 Agent Card
 * JSON 对象。这样做是为了让未来的 A2A 客户端可以按协议读取，而不需要理解 DataSmart 内部响应格式。</p>
 *
 * <p>当前端点仍然是只读发现，不是 A2A task endpoint：
 * 它不会创建任务，不会执行工具，不会读取记忆正文，也不会返回任何 token、secret、内部 endpoint 或租户数据。
 * 生产部署时，网关层还需要补充 TLS、公网域名、缓存策略、签名校验、公钥轮换和租户可见性控制。</p>
 */
@RestController
@RequiredArgsConstructor
public class AgentA2aDiscoveryController {

    private final AgentExternalProtocolDiscoveryService discoveryService;
    private final AgentExternalProtocolDiscoveryEventPublisher discoveryEventPublisher;

    /**
     * 返回 A2A public Agent Card。
     *
     * <p>该路由用于“公开发现”，因此不提供 domain/riskLevel 过滤参数，避免外部 Agent 通过枚举参数推断内部能力目录。
     * 如果管理员需要按域或风险等级检查卡片效果，应使用 `/agent-runtime/protocol-adapters/a2a/agent-card`
     * 管理路径。公开根路径只暴露默认 READY Skill 集合。</p>
     *
     * @return A2A Agent Card 风格响应
     */
    @GetMapping("/.well-known/agent-card.json")
    public AgentA2aPublicAgentCardView getPublicAgentCard(
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) String tenantId,
            @RequestHeader(value = PlatformContextHeaders.WORKSPACE_ID, required = false) String workspaceId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) String actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.REQUEST_SOURCE, required = false) String requestSource,
            @RequestHeader(value = PlatformContextHeaders.SOURCE_SERVICE, required = false) String sourceService) {
        AgentA2aPublicAgentCardView response = discoveryService.buildA2aPublicAgentCard(null, null);
        discoveryEventPublisher.publishA2aAgentCard(
                new AgentExternalProtocolDiscoveryAuditContext(
                        traceId,
                        tenantId,
                        workspaceId,
                        actorId,
                        actorRole,
                        requestSource == null || requestSource.isBlank() ? "PUBLIC_WELL_KNOWN" : requestSource,
                        sourceService
                ),
                "PUBLIC_WELL_KNOWN",
                null,
                null,
                response
        );
        return response;
    }
}
