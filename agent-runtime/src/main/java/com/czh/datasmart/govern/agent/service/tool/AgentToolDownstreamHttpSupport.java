/**
 * @Author : Cui
 * @Date: 2026/07/10 00:00
 * @Description DataSmart Govern Backend - AgentToolDownstreamHttpSupport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.tool;

import com.czh.datasmart.govern.agent.config.AgentRuntimeProperties;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

/**
 * Agent 业务工具下游 HTTP 公共支持。
 *
 * <p>该组件统一解决两个容易漂移的安全细节：下游服务地址必须来自受控注册表；每次调用必须继承当前
 * session 的租户、项目、操作者和 trace 边界。工具适配器只负责业务请求/响应映射，不重复拼装可信 Header。</p>
 */
@Component
@RequiredArgsConstructor
public class AgentToolDownstreamHttpSupport {

    private final AgentRuntimeProperties properties;

    public String baseUrl(String targetService) {
        String baseUrl = properties.getToolServiceBaseUrls().get(targetService);
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                    "未配置 Agent 工具下游服务地址，targetService=" + targetService);
        }
        return baseUrl.trim();
    }

    /**
     * 将当前用户身份与项目范围透传给业务服务。
     *
     * <p>SOURCE_SERVICE 表达调用链由 Agent Host 代理发起；ACTOR_ID、ACTOR_ROLE、ACTOR_TYPE 和项目角色快照
     * 必须继续代表真实用户。不能把普通用户改写成 SERVICE_ACCOUNT，因为 data-sync 会把机器身份视为内部高权限主体。</p>
     */
    public void applyUserDelegationHeaders(HttpHeaders headers, AgentToolExecutionContext context) {
        headers.set(PlatformContextHeaders.TENANT_ID, String.valueOf(context.session().getTenantId()));
        if (context.session().getProjectId() != null) {
            headers.set(PlatformContextHeaders.PROJECT_ID, String.valueOf(context.session().getProjectId()));
            headers.set(PlatformContextHeaders.AUTHORIZED_PROJECT_IDS, String.valueOf(context.session().getProjectId()));
        }
        headers.set(PlatformContextHeaders.ACTOR_ID, context.session().getActorId());
        headers.set(PlatformContextHeaders.ACTOR_ROLE, delegatedActorRole(context));
        headers.set(PlatformContextHeaders.ACTOR_TYPE,
                defaultText(context.session().getActorType(), "USER"));
        if (context.session().getAuthorizedProjectRoles() != null) {
            headers.set(PlatformContextHeaders.AUTHORIZED_PROJECT_ROLES,
                    context.session().getAuthorizedProjectRoles());
        }
        headers.set(PlatformContextHeaders.SOURCE_SERVICE, "agent-runtime");
        headers.set(PlatformContextHeaders.DATA_SCOPE_LEVEL, "PROJECT");
        headers.set(PlatformContextHeaders.TRACE_ID,
                context.traceId() == null ? context.audit().getTraceId() : context.traceId());
    }

    public long numericActorId(AgentToolExecutionContext context) {
        String actorId = context.session().getActorId();
        if (actorId == null || actorId.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(actorId.trim());
        } catch (NumberFormatException exception) {
            String digits = actorId.replaceAll("[^0-9]", "");
            if (digits.isBlank()) {
                return 0L;
            }
            try {
                return Long.parseLong(digits);
            } catch (NumberFormatException ignored) {
                return 0L;
            }
        }
    }

    public String delegatedActorRole(AgentToolExecutionContext context) {
        String actorRole = context.session().getActorRole();
        if (actorRole == null || actorRole.isBlank()) {
            throw new PlatformBusinessException(PlatformErrorCode.FORBIDDEN,
                    "Agent 下游调用缺少真实用户角色，拒绝降级为服务账号");
        }
        return actorRole.trim();
    }

    public String delegatedActorType(AgentToolExecutionContext context) {
        return defaultText(context.session().getActorType(), "USER");
    }

    private String defaultText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
