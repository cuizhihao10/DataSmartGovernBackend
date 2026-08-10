/**
 * @Author : Cui
 * @Date: 2026/08/05 00:00
 * @Description DataSmart Govern Backend - AgentApprovalFactTrustedRegistrationGuardTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.service.support;

import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.permission.config.AgentApprovalFactTrustProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 审批事实登记服务身份守卫的安全回归测试。
 *
 * <p>测试刻意覆盖“只匹配来源”和“只匹配 token”两类错误配置，确保校验永远使用逻辑与。</p>
 */
class AgentApprovalFactTrustedRegistrationGuardTest {

    /** 只有白名单服务和共享凭据同时正确时才允许登记，其余组合全部拒绝。 */
    @Test
    void shouldRequireBothAllowedServiceAndMatchingToken() {
        AgentApprovalFactTrustProperties properties = new AgentApprovalFactTrustProperties();
        properties.setSharedToken("approval-secret");
        AgentApprovalFactTrustedRegistrationGuard guard =
                new AgentApprovalFactTrustedRegistrationGuard(properties);

        assertDoesNotThrow(() -> guard.requireTrusted("agent-runtime", "approval-secret"));
        assertThrows(PlatformBusinessException.class,
                () -> guard.requireTrusted("browser-client", "approval-secret"));
        assertThrows(PlatformBusinessException.class,
                () -> guard.requireTrusted("agent-runtime", "wrong-secret"));
        assertThrows(PlatformBusinessException.class,
                () -> guard.requireTrusted("agent-runtime", null));
    }

    /**
     * 运行时能够提交待审批事实，但不能用同一内部凭据把动作直接提升为最终批准。
     *
     * <p>这是审批职责分离的核心回归：服务来源认证与审批决定权必须是两个独立判断，
     * 否则任一拥有“登记请求”权限的 Agent 运行时都能绕过用户确认。</p>
     */
    @Test
    void runtimeMayRegisterPendingButMayNotDecideApproval() {
        AgentApprovalFactTrustProperties properties = new AgentApprovalFactTrustProperties();
        properties.setSharedToken("approval-secret");
        AgentApprovalFactTrustedRegistrationGuard guard =
                new AgentApprovalFactTrustedRegistrationGuard(properties);

        assertDoesNotThrow(() -> guard.requireDecisionAuthority("agent-runtime", "PENDING"));
        assertThrows(PlatformBusinessException.class,
                () -> guard.requireDecisionAuthority("agent-runtime", "APPROVED"));
        assertThrows(PlatformBusinessException.class,
                () -> guard.requireDecisionAuthority("agent-runtime", "REJECTED"));
        assertDoesNotThrow(() -> guard.requireDecisionAuthority("approval-service", "APPROVED"));
        assertDoesNotThrow(() -> guard.requireDecisionAuthority("permission-admin", "REJECTED"));
    }
}
