/**
 * @Author : Cui
 * @Date: 2026/08/05 00:00
 * @Description DataSmart Govern Backend - SpecialistTurnFactTrustedServiceGuard.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.specialist;

import com.czh.datasmart.govern.agent.config.AgentSessionTrustedAccessProperties;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;

/**
 * 专业 Agent turn 事实内部登记入口的可信服务守卫。
 *
 * <p>“请求来自某个服务”不能由客户端自己填写的 source-service 字符串证明，因此这里使用
 * 两个条件的 AND 关系：来源服务必须在 Agent Runtime 配置的白名单内，同时必须持有共享凭证。
 * 共享凭证只证明调用者是受信服务，不会替代 userId、tenantId、projectId 或 delegationId 的
 * 业务归属检查；这些边界仍由事实校验和查询 Service 负责。</p>
 *
 * <p>比较凭证时使用常量时间比较，避免把普通字符串比较的时间差暴露成不必要的侧信道。
 * 共享凭证为空时始终拒绝，确保本地误配置不会把公开 POST 接口变成事实伪造入口。</p>
 */
@Component
@RequiredArgsConstructor
public class SpecialistTurnFactTrustedServiceGuard {

    /** 复用 Agent Runtime 已有的内部服务白名单与共享 token 配置，避免出现两套安全契约。 */
    private final AgentSessionTrustedAccessProperties properties;

    /**
     * 要求调用者具备可信的专业 Agent Runtime 服务身份。
     *
     * @param sourceService Gateway 清理后注入的来源服务名
     * @param presentedToken Gateway 或服务网格注入的内部共享凭证
     * @throws PlatformBusinessException 来源不在白名单、凭证缺失或凭证不匹配时抛出 403
     */
    public void requireTrustedRegistration(String sourceService, String presentedToken) {
        String configuredToken = text(properties.getSharedToken());
        String source = text(sourceService);
        String token = text(presentedToken);
        Set<String> allowedServices = properties.getAllowedAutomatedExecutionSourceServices();
        boolean sourceAllowed = source != null
                && allowedServices != null
                && allowedServices.stream().anyMatch(item -> item != null && item.equalsIgnoreCase(source));
        boolean tokenMatches = configuredToken != null
                && token != null
                && MessageDigest.isEqual(
                configuredToken.getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8)
        );
        if (!sourceAllowed || !tokenMatches) {
            throw new PlatformBusinessException(
                    PlatformErrorCode.FORBIDDEN,
                    "只有受信的 Agent Runtime 服务才能登记专业 Agent turn 事实"
            );
        }
    }

    /** 把空白配置和空白 Header 统一转换为 null，避免空 token 意外通过校验。 */
    private String text(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
