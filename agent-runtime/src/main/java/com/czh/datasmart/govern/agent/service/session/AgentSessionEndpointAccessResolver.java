/**
 * @Author : Cui
 * @Date: 2026/08/05 00:00
 * @Description DataSmart Govern Backend - AgentSessionEndpointAccessResolver.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.session;

import com.czh.datasmart.govern.agent.config.AgentSessionTrustedAccessProperties;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;

/**
 * 统一解析 Agent 会话 HTTP 入口的访问主体。
 *
 * <p>普通 API 请求保留 Gateway 注入的用户主体，由 {@code AgentSessionService} 继续执行对象归属校验。
 * 内部 Python Runtime 请求只有同时命中服务白名单和共享凭证时才可进入；进入后不会信任它自报的
 * tenantId/projectId/actorId，而是从持久化会话恢复原用户主体，以保持 user + agent 双主体审计边界。</p>
 */
@Component
@RequiredArgsConstructor
public class AgentSessionEndpointAccessResolver {

    private final AgentSessionStore sessionStore;
    private final AgentSessionTrustedAccessProperties properties;

    public AgentSessionAccessContext resolveReadAccess(String sessionId,
                                                       AgentSessionAccessContext requestAccess,
                                                       String sourceService,
                                                       String presentedToken) {
        if (trusted(sourceService, presentedToken, properties.getAllowedReadSourceServices())) {
            return ownerAccess(sessionId);
        }
        return requestAccess;
    }

    public AgentSessionAccessContext resolveAutomatedExecutionAccess(String sessionId,
                                                                     AgentSessionAccessContext requestAccess,
                                                                     String sourceService,
                                                                     String presentedToken) {
        if (trusted(sourceService, presentedToken,
                properties.getAllowedAutomatedExecutionSourceServices())) {
            return ownerAccess(sessionId);
        }
        return requestAccess;
    }

    private AgentSessionAccessContext ownerAccess(String sessionId) {
        AgentSessionRecord session = sessionStore.findById(sessionId)
                .orElseThrow(() -> new PlatformBusinessException(PlatformErrorCode.NOT_FOUND,
                        "Agent 会话不存在，sessionId=" + sessionId));
        return new AgentSessionAccessContext(
                session.getTenantId(),
                session.getProjectId(),
                session.getActorId(),
                session.getActorRole()
        );
    }

    private boolean trusted(String sourceService, String presentedToken, Set<String> allowedServices) {
        String configuredToken = text(properties.getSharedToken());
        String source = text(sourceService);
        String token = text(presentedToken);
        boolean sourceAllowed = source != null && allowedServices != null && allowedServices.stream()
                .anyMatch(allowed -> allowed != null && allowed.equalsIgnoreCase(source));
        return sourceAllowed && configuredToken != null && token != null && MessageDigest.isEqual(
                configuredToken.getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8)
        );
    }

    private String text(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
