/**
 * @Author : Cui
 * @Date: 2026/08/05 00:00
 * @Description DataSmart Govern Backend - SpecialistTurnFactTrustedServiceGuardTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.specialist;

import com.czh.datasmart.govern.agent.config.AgentSessionTrustedAccessProperties;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 专业 Agent 事实可信登记守卫测试。
 *
 * <p>测试重点是服务白名单与共享 token 必须同时成立。仅仅自报 source-service，或仅仅使用一个被允许的服务名，
 * 都不能把普通客户端变成事实登记者。</p>
 */
class SpecialistTurnFactTrustedServiceGuardTest {

    private SpecialistTurnFactTrustedServiceGuard guard;

    /** 构造一个只允许 Python Agent Runtime 登记事实的测试配置。 */
    @BeforeEach
    void setUp() {
        AgentSessionTrustedAccessProperties properties = new AgentSessionTrustedAccessProperties();
        properties.setSharedToken("trusted-secret");
        properties.setAllowedAutomatedExecutionSourceServices(java.util.Set.of("python-ai-runtime"));
        guard = new SpecialistTurnFactTrustedServiceGuard(properties);
    }

    /** 正确服务和正确凭证同时存在时允许登记。 */
    @Test
    void shouldAllowTrustedAgentRuntime() {
        assertDoesNotThrow(() -> guard.requireTrustedRegistration("python-ai-runtime", "trusted-secret"));
    }

    /** 错误服务、错误 token 和空 token 都必须 fail-closed。 */
    @Test
    void shouldRejectUntrustedRegistration() {
        assertThrows(PlatformBusinessException.class,
                () -> guard.requireTrustedRegistration("browser-client", "trusted-secret"));
        assertThrows(PlatformBusinessException.class,
                () -> guard.requireTrustedRegistration("python-ai-runtime", "wrong-secret"));
        assertThrows(PlatformBusinessException.class,
                () -> guard.requireTrustedRegistration("python-ai-runtime", " "));
    }
}
