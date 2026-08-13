/**
 * @Author : Cui
 * @Date: 2026/07/10 00:00
 * @Description DataSmart Govern Backend - AgentToolDownstreamHttpSupport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.tool;

import com.czh.datasmart.govern.agent.config.AgentRuntimeProperties;
import com.czh.datasmart.govern.agent.service.session.AgentRunRecord;
import com.czh.datasmart.govern.agent.service.session.AgentSessionRecord;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Agent 业务工具下游 HTTP 公共支持。
 *
 * <p>该组件统一解决两个容易漂移的安全细节：下游服务地址必须来自受控注册表；每次调用必须继承当前
 * session 的租户、项目、操作者和 trace 边界。工具适配器只负责业务请求/响应映射，不重复拼装可信 Header。</p>
 */
@Component
@RequiredArgsConstructor
public class AgentToolDownstreamHttpSupport {

    /**
     * Python Runtime 当前由 Uvicorn 提供 HTTP/1.1 服务，不接受 Java HTTP 客户端发起的明文 h2c 升级。
     * 使用独立常量而不是按 URL 字符串猜测服务类型，避免部署地址变化后协议保护静默失效。
     */
    private static final String PYTHON_AI_RUNTIME_SERVICE = "python-ai-runtime";

    /**
     * Agent 同步工具属于控制面短调用：连接失败应快速释放执行线程，而一次 RAG 检索允许保留合理的模型处理窗口。
     */
    private static final Duration PYTHON_CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration PYTHON_READ_TIMEOUT = Duration.ofSeconds(60);

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
     * 基于调用方注入的 Spring Boot {@link RestClient.Builder} 创建一次服务范围内的客户端。
     *
     * <p>这里先 clone builder，原因是自动装配的 builder 会被多个工具适配器共享。若直接修改其 base URL
     * 或 request factory，一个 RAG 调用就可能把后续 data-sync、datasource-management 请求也改写成同一
     * 连接策略。clone 后仍会继承 Spring Boot 已安装的 JSON converter、观测与测试 request factory，
     * 但每次调用的目标地址和协议选择彼此隔离。</p>
     *
     * <p>Python Runtime 使用 {@link SimpleClientHttpRequestFactory} 强制走稳定的 HTTP/1.1 路径。
     * 默认 Apache/JDK 客户端在明文连接上可能发送 {@code Upgrade: h2c}；Uvicorn 拒绝升级时，带 body 的
     * POST 可能被拆成“无 body 请求 + 无效后续字节”，最终表现为 FastAPI 422。该设置不是绕过 422 校验，
     * 而是保证 Java 发出的 JSON body 能完整到达 Python 的既有请求合同。</p>
     *
     * @param template Spring Boot 管理的客户端构建器模板
     * @param targetService 受控工具目录中的目标服务编码
     * @return 仅服务于本次下游调用的 RestClient
     */
    public RestClient serviceClient(RestClient.Builder template, String targetService) {
        RestClient.Builder scopedBuilder = template.clone().baseUrl(baseUrl(targetService));
        if (PYTHON_AI_RUNTIME_SERVICE.equals(targetService)) {
            SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
            requestFactory.setConnectTimeout(PYTHON_CONNECT_TIMEOUT);
            requestFactory.setReadTimeout(PYTHON_READ_TIMEOUT);
            scopedBuilder.requestFactory(requestFactory);
        }
        return scopedBuilder.build();
    }

    /**
     * 将当前用户身份与项目范围透传给业务服务。
     *
     * <p>SOURCE_SERVICE 表达调用链由 Agent Host 代理发起；ACTOR_ID、ACTOR_ROLE、ACTOR_TYPE 和项目角色快照
     * 必须继续代表真实用户。不能把普通用户改写成 SERVICE_ACCOUNT，因为 data-sync 会把机器身份视为内部高权限主体。</p>
     *
     * <p>AGENT_ID、SESSION_ID、RUN_ID 和 DELEGATION_ID 补齐执行主体与授权证据链，供下游审计回答“哪个
     * Agent 代表哪个用户执行了哪次动作”。这些 Header 只增加可追溯性，不会让下游跳过自己的 RBAC 和资源归属校验。</p>
     */
    public void applyUserDelegationHeaders(HttpHeaders headers, AgentToolExecutionContext context) {
        applyUserDelegationHeaders(
                headers,
                context.session(),
                context.run(),
                context.traceId() == null ? context.audit().getTraceId() : context.traceId());
    }

    /**
     * 为不经过通用 ToolPlan 的受治理后台流程附加同一套双主体 Header。
     *
     * <p>Autopilot Recovery 在用户首次授权后由 Kafka 唤醒，没有当前 HTTP 请求对应的
     * {@link AgentToolExecutionContext}，但它仍必须代表原用户而不是伪装成服务账号。调用方先从
     * {@link com.czh.datasmart.govern.agent.service.session.AgentSessionStore} 重新加载 session/run，
     * 再使用本方法透传用户、Agent、delegation 和项目范围。下游 data-sync 仍会执行自己的 RBAC、
     * 资源归属与 Autopilot 策略复核。</p>
     *
     * @param headers 将被发送给下游服务的 HTTP Header
     * @param session 已从持久仓储恢复并完成范围校验的根会话
     * @param run 首次用户确认所绑定的根 Run
     * @param traceId 当前 Kafka 恢复轮次的稳定链路 ID
     */
    public void applyUserDelegationHeaders(HttpHeaders headers,
                                           AgentSessionRecord session,
                                           AgentRunRecord run,
                                           String traceId) {
        if (headers == null || session == null || run == null || session.getDelegation() == null) {
            throw new PlatformBusinessException(PlatformErrorCode.FORBIDDEN,
                    "Agent 下游调用缺少可信 session、run 或 delegation");
        }
        headers.set(PlatformContextHeaders.TENANT_ID, String.valueOf(session.getTenantId()));
        if (session.getApplicationId() != null) {
            headers.set(PlatformContextHeaders.APPLICATION_ID,
                    String.valueOf(session.getApplicationId()));
        }
        if (session.getProjectId() != null) {
            headers.set(PlatformContextHeaders.PROJECT_ID, String.valueOf(session.getProjectId()));
            headers.set(PlatformContextHeaders.AUTHORIZED_PROJECT_IDS, String.valueOf(session.getProjectId()));
        }
        if (session.getWorkspaceKey() != null && !session.getWorkspaceKey().isBlank()) {
            headers.set(PlatformContextHeaders.WORKSPACE_ID, session.getWorkspaceKey());
        }
        headers.set(PlatformContextHeaders.ACTOR_ID, session.getActorId());
        if (session.getActorRole() == null || session.getActorRole().isBlank()) {
            throw new PlatformBusinessException(PlatformErrorCode.FORBIDDEN,
                    "Agent 下游调用缺少真实用户角色，拒绝降级为服务账号");
        }
        headers.set(PlatformContextHeaders.ACTOR_ROLE, session.getActorRole().trim());
        headers.set(PlatformContextHeaders.ACTOR_TYPE,
                defaultText(session.getActorType(), "USER"));
        if (session.getAuthorizedProjectRoles() != null) {
            headers.set(PlatformContextHeaders.AUTHORIZED_PROJECT_ROLES,
                    session.getAuthorizedProjectRoles());
        }
        headers.set(PlatformContextHeaders.SOURCE_SERVICE, "agent-runtime");
        headers.set(PlatformContextHeaders.AGENT_ID, session.getAgentId());
        headers.set(PlatformContextHeaders.AGENT_SESSION_ID, session.getSessionId());
        headers.set(PlatformContextHeaders.AGENT_RUN_ID, run.getRunId());
        headers.set(PlatformContextHeaders.AGENT_DELEGATION_ID,
                session.getDelegation().getDelegationId());
        headers.set(PlatformContextHeaders.DATA_SCOPE_LEVEL, "PROJECT");
        if (traceId != null && !traceId.isBlank()) {
            headers.set(PlatformContextHeaders.TRACE_ID, traceId.trim());
        }
    }

    /**
     * 为 Java Agent Host -> Python Runtime 的直连工具调用附加服务间凭证。
     *
     * <p>该凭证不能跟随所有业务下游请求传播，否则会扩大秘密暴露面；调用方只在 RAG 这类
     * Python 专用入口上显式调用本方法。未配置时不伪造任何值，让 Python Runtime 继续 fail-closed。</p>
     */
    public void applyPythonRuntimeInternalServiceToken(HttpHeaders headers) {
        applyInternalServiceToken(headers);
    }

    /**
     * 为受保护的 Java/Python 或 Java/Java 内部控制面调用附加最小服务令牌。
     *
     * <p>Autopilot 会同时调用 Python 规划入口和 data-sync 内部 case API，两者复用同一个部署级
     * ``DATASMART_AGENT_RUNTIME_INTERNAL_SERVICE_TOKEN``。方法只写固定 Header，不记录或返回令牌；
     * 普通浏览器业务 API 不应调用本方法。</p>
     */
    public void applyInternalServiceToken(HttpHeaders headers) {
        String token = System.getenv("DATASMART_AGENT_RUNTIME_INTERNAL_SERVICE_TOKEN");
        if (token != null && !token.isBlank()) {
            headers.set(PlatformContextHeaders.INTERNAL_SERVICE_TOKEN, token);
        }
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
