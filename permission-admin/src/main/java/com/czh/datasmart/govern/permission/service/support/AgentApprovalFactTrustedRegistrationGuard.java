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

/**
 * 审批事实内部登记接口的 fail-closed 服务身份守卫。
 *
 * <p>该守卫只解决“谁可以写审批事实”，不判断用户是否原本有权操作业务资源。完成服务身份校验后，
 * 后续流程仍需保存并核对 userId、sessionId、runId、delegationId、工具和资源范围。</p>
 */
@Component
@RequiredArgsConstructor
public class AgentApprovalFactTrustedRegistrationGuard {

    private final AgentApprovalFactTrustProperties properties;

    /**
     * 要求调用方同时满足来源服务白名单和共享凭据校验。
     *
     * <p>两个条件使用逻辑与，任意配置缺失、Header 缺失或不匹配都会拒绝。token 使用固定时序比较，
     * 避免普通字符串比较泄露明显的前缀匹配时间差。</p>
     *
     * @param sourceService 调用方通过内部 Header 声明的服务身份
     * @param presentedToken 调用方提交的内部共享凭据
     * @throws PlatformBusinessException 来源或凭据不可信时抛出 FORBIDDEN
     */
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

    /** 统一清理 Header 和配置文本；空白内容返回 null，确保校验不会把空字符串视为合法凭据。 */
    private String text(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
