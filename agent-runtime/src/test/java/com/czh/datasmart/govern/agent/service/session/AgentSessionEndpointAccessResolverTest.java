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

/**
 * 验证内部服务访问解析器不会把“服务身份”误当成“业务用户权限”。
 *
 * <p>受信服务只能从持久会话恢复原所有者范围；来源或 token 任一不匹配时必须保留请求原上下文。</p>
 */
class AgentSessionEndpointAccessResolverTest {

    private AgentSessionEndpointAccessResolver resolver;

    /** 创建一个固定归属的会话和仅供测试使用的内部共享凭据。 */
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

    /** 验证白名单服务携带正确凭据时可以代为延续原用户会话，但恢复的是所有者而非服务账号。 */
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

    /** 验证错误凭据或非白名单来源不能获得会话所有者身份，防止伪造内部 Header 提权。 */
    @Test
    void wrongTokenOrUntrustedServiceMustNotGainOwnerIdentity() {
        AgentSessionAccessContext requestAccess = new AgentSessionAccessContext(10L, 20L, "attacker", "ORDINARY_USER");

        assertEquals(requestAccess, resolver.resolveReadAccess(
                "session-001", requestAccess, "python-ai-runtime", "wrong-secret"));
        assertEquals(requestAccess, resolver.resolveReadAccess(
                "session-001", requestAccess, "browser-client", "trusted-secret"));
    }
}
