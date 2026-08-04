/**
 * @Author : Cui
 * @Date: 2026/08/05 00:00
 * @Description DataSmart Govern Backend - AgentSessionEndpointAccessResolver.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.session;

import com.czh.datasmart.govern.agent.config.AgentSessionTrustedAccessProperties;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;

/**
 * 统一解析 Agent 会话 HTTP 入口的访问主体。
 *
 * <p>普通 API 请求保留 Gateway 注入的用户主体，由 {@code AgentSessionService} 继续执行对象归属校验。
 * 内部 Python Runtime 请求只有同时命中服务白名单和共享凭证时才可进入；进入后不会信任它自报的
 * tenantId/projectId/actorId，而是从持久化会话恢复原用户主体，以保持 user + agent 双主体审计边界。</p>
 */
@Component
@RequiredArgsConstructor
public class AgentSessionEndpointAccessResolver {

    private final AgentSessionStore sessionStore;
    private final AgentSessionTrustedAccessProperties properties;

    /**
     * 解析读取会话附属事实时应使用的用户边界。
     *
     * <p>浏览器请求直接返回 Gateway 注入的 {@code requestAccess}，随后由业务服务做对象归属判断。
     * Python Runtime 等内部服务只有同时命中服务白名单和共享凭证时，才会改为从 session 恢复原所有者边界。
     * 这样内部服务可以继续模型工具循环，但不能通过伪造 Header 把自己变成任意用户。</p>
     *
     * @param sessionId 待读取事实所属的会话 ID
     * @param requestAccess Gateway 用户上下文；内部服务直连时可能为空字段
     * @param sourceService 调用服务声明，必须来自清理后的可信 Header
     * @param presentedToken 调用服务提交的共享凭证
     * @return 普通请求原上下文，或可信内部服务对应会话所有者上下文
     */
    public AgentSessionAccessContext resolveReadAccess(String sessionId,
                                                       AgentSessionAccessContext requestAccess,
                                                       String sourceService,
                                                       String presentedToken) {
        if (trusted(sourceService, presentedToken, properties.getAllowedReadSourceServices())) {
            return ownerAccess(sessionId);
        }
        return requestAccess;
    }

    /**
     * 解析低风险自动执行入口的用户边界。
     *
     * <p>该方法只解决“内部服务代表哪个用户继续当前会话”，并不直接批准执行。返回后仍必须通过
     * mutation ownership、delegation、风险级别、只读/幂等、沙箱与运行时保护等完整门禁。</p>
     *
     * @param sessionId 自动执行计划所属会话
     * @param requestAccess 当前请求携带的 Gateway 用户上下文
     * @param sourceService 内部调用服务名
     * @param presentedToken 内部共享凭证
     * @return 后续对象归属校验应使用的访问上下文
     */
    public AgentSessionAccessContext resolveAutomatedExecutionAccess(String sessionId,
                                                                     AgentSessionAccessContext requestAccess,
                                                                     String sourceService,
                                                                     String presentedToken) {
        if (trusted(sourceService, presentedToken,
                properties.getAllowedAutomatedExecutionSourceServices())) {
            return ownerAccess(sessionId);
        }
        return requestAccess;
    }

    /**
     * 从持久化 session 恢复最初发起用户的 tenant/project/actor 边界。
     *
     * <p>这里拒绝采用内部请求自报的身份字段。会话不存在时直接返回 NOT_FOUND，避免用空上下文继续执行。</p>
     */
    private AgentSessionAccessContext ownerAccess(String sessionId) {
        AgentSessionRecord session = sessionStore.findById(sessionId)
                .orElseThrow(() -> new PlatformBusinessException(PlatformErrorCode.NOT_FOUND,
                        "Agent 会话不存在，sessionId=" + sessionId));
        return new AgentSessionAccessContext(
                session.getTenantId(),
                session.getProjectId(),
                session.getActorId(),
                session.getActorRole()
        );
    }

    /**
     * 同时校验服务白名单和共享凭证。
     *
     * <p>两个条件缺一不可：只校验服务名可以被客户端伪造，只校验 token 则无法按服务用途分离读访问和自动执行。
     * token 使用 {@link MessageDigest#isEqual(byte[], byte[])} 比较，减少普通字符串早停比较产生的时序侧信道。</p>
     */
    private boolean trusted(String sourceService, String presentedToken, Set<String> allowedServices) {
        String configuredToken = text(properties.getSharedToken());
        String source = text(sourceService);
        String token = text(presentedToken);
        boolean sourceAllowed = source != null && allowedServices != null && allowedServices.stream()
                .anyMatch(allowed -> allowed != null && allowed.equalsIgnoreCase(source));
        return sourceAllowed && configuredToken != null && token != null && MessageDigest.isEqual(
                configuredToken.getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8)
        );
    }

    /**
     * 把外部字符串规范化为“有值文本”或 null。
     *
     * <p>统一处理空格和空字符串，可以保证空 Secret 永远不能被空请求 token 意外匹配。</p>
     */
    private String text(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
