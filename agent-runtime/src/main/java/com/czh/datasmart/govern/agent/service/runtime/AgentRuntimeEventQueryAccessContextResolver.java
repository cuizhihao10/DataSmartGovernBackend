/**
 * @Author : Cui
 * @Date: 2026/05/27 20:12
 * @Description DataSmart Govern Backend - AgentRuntimeEventQueryAccessContextResolver.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.common.context.PlatformAuthorizedProjectHeaderSupport;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import org.springframework.stereotype.Component;

/**
 * Agent 运行时事件查询上下文解析器。
 *
 * <p>该组件只做一件事：把 HTTP Header 中的字符串值转换成领域服务可理解的访问上下文。
 * 它不判断权限是否允许、不查询 permission-admin，也不访问投影仓储。这样做可以避免 Controller 里堆满
 * Header 解析逻辑，也避免权限收口规则分散在多个路由方法里。</p>
 */
@Component
public class AgentRuntimeEventQueryAccessContextResolver {

    /**
     * 根据 gateway 透传 Header 构造访问上下文。
     *
     * @param tenantIdHeader 租户 ID Header，来自 `X-DataSmart-Tenant-Id`
     * @param actorIdHeader 操作者 ID Header，来自 `X-DataSmart-Actor-Id`
     * @param actorRole 操作者角色 Header，来自 `X-DataSmart-Actor-Role`
     * @param traceId 链路追踪 ID，用于错误定位和审计串联
     * @param dataScopeLevel permission-admin 判定出的数据范围等级
     * @param authorizedProjectIdsHeader permission-admin 物化出的授权项目集合
     * @return 类型安全的 Agent 事件查询访问上下文
     */
    public AgentRuntimeEventQueryAccessContext resolve(String tenantIdHeader,
                                                       String actorIdHeader,
                                                       String actorRole,
                                                       String traceId,
                                                       String dataScopeLevel,
                                                       String authorizedProjectIdsHeader) {
        return new AgentRuntimeEventQueryAccessContext(
                parseLongHeader(tenantIdHeader, "X-DataSmart-Tenant-Id"),
                parseLongHeader(actorIdHeader, "X-DataSmart-Actor-Id"),
                actorRole,
                traceId,
                dataScopeLevel,
                PlatformAuthorizedProjectHeaderSupport.parse(authorizedProjectIdsHeader)
        );
    }

    /**
     * 解析 Long 型 Header。
     *
     * <p>这里选择在非数字 Header 上直接抛出业务异常，而不是静默当作 null。原因是 tenantId/actorId
     * 会直接影响事件查询数据范围，如果调用链上出现坏 Header，应该尽早暴露为 400，而不是悄悄退化成
     * “未知身份”导致排障困难。</p>
     */
    private Long parseLongHeader(String value, String headerName) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(value.trim());
        } catch (NumberFormatException ex) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "Agent 运行时事件查询收到非法平台 Header: " + headerName + "=" + value);
        }
    }
}
