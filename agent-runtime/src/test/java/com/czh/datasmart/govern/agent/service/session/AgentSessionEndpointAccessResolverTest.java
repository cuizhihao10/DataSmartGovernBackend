/**
 * @Author : Cui
 * @Date: 2026/08/05 00:00
 * @Description DataSmart Govern Backend - AgentSessionEndpointAccessResolverTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.session;

import com.czh.datasmart.govern.agent.config.AgentSessionTrustedAccessProperties;
import com.czh.datasmart.govern.agent.model.WorkspaceIsolationLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentSessionEndpointAccessResolverTest {

    private AgentSessionEndpointAccessResolver resolver;

    @BeforeEach
    void setUp() {
        AgentSessionMemoryStore store = new AgentSessionMemoryStore();
        store.save(new AgentSessionRecord(
                "session-001", 10L, 20L, null, "owner-001", "WEB", "安全访问测试",
                WorkspaceIsolationLevel.PROJECT, "tenant:10:project:20", LocalDateTime.now()
        ));
        AgentSessionTrustedAccessProperties properties = new AgentSessionTrustedAccessProperties();
        properties.setSharedToken("trusted-secret");
        resolver = new AgentSessionEndpointAccessResolver(store, properties);
    }

    @Test
    void trustedInternalServiceShouldRecoverOwnerBoundaryFromSession() {
        AgentSessionAccessContext resolved = resolver.resolveReadAccess(
                "session-001",
                new AgentSessionAccessContext(null, null, null, null),
                "python-ai-runtime",
                "trusted-secret"
        );

        assertEquals(10L, resolved.tenantId());
        assertEquals(20L, resolved.projectId());
        assertEquals("owner-001", resolved.actorId());
    }

    @Test
    void wrongTokenOrUntrustedServiceMustNotGainOwnerIdentity() {
        AgentSessionAccessContext requestAccess = new AgentSessionAccessContext(10L, 20L, "attacker", "ORDINARY_USER");

        assertEquals(requestAccess, resolver.resolveReadAccess(
                "session-001", requestAccess, "python-ai-runtime", "wrong-secret"));
        assertEquals(requestAccess, resolver.resolveReadAccess(
                "session-001", requestAccess, "browser-client", "trusted-secret"));
    }
}
