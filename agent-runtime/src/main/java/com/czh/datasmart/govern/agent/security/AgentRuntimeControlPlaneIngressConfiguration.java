/**
 * @Author : Cui
 * @Date: 2026/08/12 00:00
 * @Description DataSmart Govern Backend - AgentRuntimeControlPlaneIngressConfiguration.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.security;

import com.czh.datasmart.govern.agent.config.AgentRuntimeControlPlaneIngressProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Registers the Agent Runtime deployment-ingress guard only in explicitly enabled environments.
 *
 * <p>The registration is conditional rather than relying only on an {@code if} inside the filter.
 * A source-test or local single-module JVM therefore has no new servlet filter in its chain, while
 * a Compose or production deployment gets the guard before controllers receive trusted identity
 * headers. The filter itself still checks the flag as a second line of defense for direct tests or
 * accidental manual registration.</p>
 */
@Configuration(proxyBeanMethods = false)
public class AgentRuntimeControlPlaneIngressConfiguration {

    /**
     * Registers one early servlet filter for all paths; the filter narrows itself to public aliases.
     *
     * <p>Registering against {@code /*} avoids fragile assumptions about servlet mapping order. Its
     * own path predicate makes Actuator and internal worker endpoints pass through untouched. Those
     * internal endpoints remain a separately governed machine-protocol surface; this configuration
     * does not weaken or replace their service-account and network controls. The high order ensures
     * a rejected public request cannot reach an MVC controller first.</p>
     *
     * @param properties deployment settings that contain the expected secret and protected aliases
     * @return servlet registration used only when the explicit enabled property is true
     */
    @Bean
    @ConditionalOnProperty(
            prefix = "datasmart.agent-runtime.control-plane-ingress",
            name = "enabled",
            havingValue = "true"
    )
    public FilterRegistrationBean<AgentRuntimeControlPlaneIngressFilter> agentRuntimeControlPlaneIngressFilterRegistration(
            AgentRuntimeControlPlaneIngressProperties properties) {
        FilterRegistrationBean<AgentRuntimeControlPlaneIngressFilter> registration =
                new FilterRegistrationBean<>(new AgentRuntimeControlPlaneIngressFilter(properties));
        registration.setName("agentRuntimeControlPlaneIngressFilter");
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
        return registration;
    }
}
