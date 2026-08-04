/**
 * @Author : Cui
 * @Date: 2026/08/04 00:00
 * @Description DataSmart Govern Backend - AgentApprovalFactTrustProperties.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;

/** Trusted service identities allowed to register Agent approval facts. */
@Data
@Component
@ConfigurationProperties(prefix = "datasmart.permission.agent-approval-facts.trusted-registration")
public class AgentApprovalFactTrustProperties {
    private String sharedToken = "";
    private Set<String> allowedSourceServices = new LinkedHashSet<>(
            Set.of("agent-runtime", "approval-service", "permission-admin"));
}
