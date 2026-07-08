/**
 * @Author : Cui
 * @Date: 2026/07/08 20:02
 * @Description DataSmart Govern Backend - GatewayBusinessWorkspaceHeaderSanitizingFilter.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.gateway.filter;

import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 普通业务路由的 workspace Header 最终清洗过滤器。
 *
 * <p>这个类解决的是“产品层级已经收敛，但历史身份上下文仍可能带 workspace”的运行时兜底问题。
 * DataSmart 早期把 tenant/project/workspace 都放进统一的 {@code X-DataSmart-*} Header；后来 FlashSync 数据同步产品
 * 明确收敛为“租户 -> 项目 -> 数据源/同步任务”，工作空间不再是用户可见的资源归属层级。</p>
 *
 * <p>为什么不能只依赖前面的认证过滤器删除 workspace：</p>
 * <p>1. OIDC/Keycloak claim、开发期令牌、可信上游网关、旧脚本都可能带 {@code X-DataSmart-Workspace-Id=workspace-a}；</p>
 * <p>2. Gateway 过滤链存在授权通过、授权 fail-open、公开路径、调试绕行等多条分支，任何一条分支遗漏清理都会让下游服务重新看到 workspace；</p>
 * <p>3. data-sync、datasource-management 等业务服务历史上曾把 workspace 当 Long 解析，旧值一旦进入下游就会出现
 * “X-DataSmart-Workspace-Id 必须是 Long 类型数字”的用户侧错误。</p>
 *
 * <p>因此本过滤器被放在授权之后、路由转发之前，作为最后一道“普通业务路由不得携带 workspace”的出口保护。
 * Agent Runtime 是唯一例外：它的 workspace 表示工具执行沙箱、文件区、长会话工作目录和运行时隔离，不等同于
 * FlashSync 产品里的业务层级，所以 {@code /api/agent/**} 与 {@code /api/internal/agent-runtime/**} 仍保留该 Header。</p>
 */
@Component
public class GatewayBusinessWorkspaceHeaderSanitizingFilter implements GlobalFilter, Ordered {

    /**
     * 执行最终 Header 清洗。
     *
     * @param exchange 当前网关交换对象，包含已认证、已授权后的请求上下文。
     * @param chain    后续过滤器链，最终会进入 Spring Cloud Gateway 的路由转发过滤器。
     * @return Reactive 完成信号。
     */
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (shouldKeepWorkspaceContext(path)) {
            return chain.filter(exchange);
        }

        ServerHttpRequest sanitizedRequest = exchange.getRequest()
                .mutate()
                .headers(headers -> {
                    /*
                     * WORKSPACE_ID 是本次用户可见问题的直接根因；WORKSPACE_RISK_LEVEL 则属于 Agent/Skill 运行时策略语义。
                     * 普通业务服务不应看到这两个 Header，否则会让“项目级资源归属”和“Agent 沙箱风险”两个概念重新混在一起。
                     */
                    headers.remove(PlatformContextHeaders.WORKSPACE_ID);
                    headers.remove(PlatformContextHeaders.WORKSPACE_RISK_LEVEL);
                })
                .build();
        return chain.filter(exchange.mutate().request(sanitizedRequest).build());
    }

    /**
     * 判断当前请求是否仍然需要 workspace 运行时上下文。
     *
     * <p>这里故意只给 Agent 路由开白名单，而不是给每个业务路由列黑名单。这样未来新增
     * {@code /api/sync/**}、{@code /api/datasource/**}、{@code /api/quality/**} 的子接口时，
     * 默认都会继承“无 workspace”的产品规则，不会因为忘记加黑名单而回退到旧层级。</p>
     */
    private boolean shouldKeepWorkspaceContext(String path) {
        return path != null
                && (path.startsWith("/api/agent/") || path.startsWith("/api/internal/agent-runtime/"));
    }

    /**
     * 过滤器顺序。
     *
     * <p>-80 让它晚于 Contract/OIDC/DevelopmentIdentity/Authorization 等上下文构建过滤器执行，
     * 但仍早于真正的路由转发。这样无论上游哪个过滤器分支留下了 workspace，出口处都会再次归一化。</p>
     */
    @Override
    public int getOrder() {
        return -80;
    }
}
