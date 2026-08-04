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
}
