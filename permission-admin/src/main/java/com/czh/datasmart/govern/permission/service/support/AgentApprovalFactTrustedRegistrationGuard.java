/**
 * @Author : Cui
 * @Date: 2026/08/04 00:00
 * @Description DataSmart Govern Backend - AgentApprovalFactTrustedRegistrationGuard.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.service.support;

import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import com.czh.datasmart.govern.permission.config.AgentApprovalFactTrustProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Fail-closed guard for the internal approval-fact registration endpoint. */
@Component
@RequiredArgsConstructor
public class AgentApprovalFactTrustedRegistrationGuard {

    private final AgentApprovalFactTrustProperties properties;

    public void requireTrusted(String sourceService, String presentedToken) {
        String configuredToken = text(properties.getSharedToken());
        String source = text(sourceService);
        String token = text(presentedToken);
        boolean sourceAllowed = source != null && properties.getAllowedSourceServices().stream()
                .anyMatch(allowed -> allowed != null && allowed.equalsIgnoreCase(source));
        boolean tokenMatches = configuredToken != null && token != null && MessageDigest.isEqual(
                configuredToken.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8));
        if (!sourceAllowed || !tokenMatches) {
            throw new PlatformBusinessException(PlatformErrorCode.FORBIDDEN,
                    "仅受信任的 Agent Runtime、审批服务或管理控制面可以登记审批事实");
        }
    }

    private String text(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
