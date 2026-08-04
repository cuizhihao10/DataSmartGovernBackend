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

class AgentApprovalFactTrustedRegistrationGuardTest {

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
