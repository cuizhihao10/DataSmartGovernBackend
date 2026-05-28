/**
 * @Author : Cui
 * @Date: 2026/05/06 22:10
 * @Description DataSmart Govern Backend - TaskActorContextResolver.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.controller.support;

import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import com.czh.datasmart.govern.common.context.PlatformAuthorizedProjectHeaderSupport;
import com.czh.datasmart.govern.task.controller.dto.TaskActorContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/**
 * 任务模块的平台操作人上下文解析器。
 *
 * <p>在 DataSmart Govern 的微服务边界里，业务服务不直接解析登录态，也不直接关心 JWT、Session 或网关认证细节。
 * 这些认证细节应由 gateway 统一完成，然后通过标准平台 Header 透传给下游服务。
 * task-management 只消费这些 Header，把它们转换成领域层能理解的 {@link TaskActorContext}。
 *
 * <p>单独拆出该组件有两个目的：
 * 1. 降低 Controller 行数和重复代码，避免每个路由都写一遍 Header 解析；
 * 2. 让后续接入签名校验、服务间调用白名单、traceId 标准化、actorType/sourceService 等字段时，不需要修改每个任务路由。
 */
@Component
public class TaskActorContextResolver {

    /**
     * 根据显式 Header 参数构造操作人上下文。
     *
     * <p>该方法服务于已经通过 `@RequestHeader` 声明 Header 的旧接口，保留它可以降低本轮迁移范围。
     * 后续如果 Controller 全面改成 `HttpServletRequest` 或统一拦截器注入，也可以继续复用本组件。
     */
    public TaskActorContext resolve(Long tenantId, Long actorId, String actorRole, String traceId) {
        return resolve(tenantId, actorId, actorRole, traceId, null, null);
    }

    /**
     * 根据显式 Header 参数构造操作人上下文。
     *
     * <p>该重载把 gateway 透传的数据范围也纳入任务上下文。
     * 这意味着 task-management 在未来接入 PROJECT 数据范围时，不需要再去 Controller 里重复拆解 Header。</p>
     */
    public TaskActorContext resolve(Long tenantId,
                                    Long actorId,
                                    String actorRole,
                                    String traceId,
                                    String dataScopeLevel,
                                    String authorizedProjectIdsHeader) {
        return new TaskActorContext(
                tenantId,
                actorId,
                actorRole,
                traceId,
                dataScopeLevel,
                PlatformAuthorizedProjectHeaderSupport.parse(authorizedProjectIdsHeader)
        );
    }

    /**
     * 从原始 HTTP 请求中读取平台 Header 并构造操作人上下文。
     *
     * <p>Header 在 HTTP 层都是字符串，进入领域层前需要做类型转换。
     * 租户 ID 和操作者 ID 如果不是合法数字，不能静默当成空值，否则会把错误上下文伪装成匿名请求，
     * 进而绕过本应生效的数据范围收口。
     */
    public TaskActorContext resolve(HttpServletRequest request) {
        if (request == null) {
            return resolve(null, null, null, null);
        }
        return resolve(
                parseLongHeader(request, PlatformContextHeaders.TENANT_ID),
                parseLongHeader(request, PlatformContextHeaders.ACTOR_ID),
                request.getHeader(PlatformContextHeaders.ACTOR_ROLE),
                request.getHeader(PlatformContextHeaders.TRACE_ID),
                request.getHeader(PlatformContextHeaders.DATA_SCOPE_LEVEL),
                request.getHeader(PlatformContextHeaders.AUTHORIZED_PROJECT_IDS)
        );
    }

    private Long parseLongHeader(HttpServletRequest request, String headerName) {
        String value = request.getHeader(headerName);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(value.trim());
        } catch (NumberFormatException ex) {
            throw new IllegalStateException("平台上下文 Header 不是合法数字: " + headerName + "=" + value, ex);
        }
    }
}
