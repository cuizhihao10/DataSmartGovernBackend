/**
 * @Author : Cui
 * @Date: 2026/07/08 20:05
 * @Description DataSmart Govern Backend - GatewayBusinessWorkspaceHeaderSanitizingFilterTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.gateway.filter;

import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 普通业务路由 workspace Header 最终清洗过滤器测试。
 *
 * <p>这些测试覆盖用户截图中的真实故障：浏览器走 gateway 请求 data-sync 元数据发现时，
 * OIDC/Keycloak 仍可能从历史 claim 中解析出 {@code workspace-a}。只要这个 Header 进入 data-sync，
 * 旧链路就可能把它按 Long 解析并返回 400。测试通过网关出口层断言该 Header 不会再进入普通业务服务。</p>
 */
class GatewayBusinessWorkspaceHeaderSanitizingFilterTest {

    /**
     * 数据同步属于项目级业务路由，不能继续携带 workspace Header。
     */
    @Test
    void shouldRemoveWorkspaceHeadersForSyncBusinessRoute() {
        GatewayBusinessWorkspaceHeaderSanitizingFilter filter = new GatewayBusinessWorkspaceHeaderSanitizingFilter();
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/sync/sync-tasks/metadata/objects/discover")
                        .header(PlatformContextHeaders.WORKSPACE_ID, "workspace-a")
                        .header(PlatformContextHeaders.WORKSPACE_RISK_LEVEL, "NORMAL")
                        .build()
        );
        RecordingGatewayFilterChain chain = new RecordingGatewayFilterChain();

        filter.filter(exchange, chain).block();

        assertThat(chain.called()).isTrue();
        assertThat(chain.exchange().getRequest().getHeaders().getFirst(PlatformContextHeaders.WORKSPACE_ID))
                .isNull();
        assertThat(chain.exchange().getRequest().getHeaders().getFirst(PlatformContextHeaders.WORKSPACE_RISK_LEVEL))
                .isNull();
    }

    /**
     * Agent 路由保留 workspace，因为这里的 workspace 是工具沙箱和会话工作目录，不是同步任务归属层级。
     */
    @Test
    void shouldKeepWorkspaceHeaderForAgentRuntimeRoute() {
        GatewayBusinessWorkspaceHeaderSanitizingFilter filter = new GatewayBusinessWorkspaceHeaderSanitizingFilter();
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/agent/plans")
                        .header(PlatformContextHeaders.WORKSPACE_ID, "workspace-a")
                        .header(PlatformContextHeaders.WORKSPACE_RISK_LEVEL, "NORMAL")
                        .build()
        );
        RecordingGatewayFilterChain chain = new RecordingGatewayFilterChain();

        filter.filter(exchange, chain).block();

        assertThat(chain.called()).isTrue();
        assertThat(chain.exchange().getRequest().getHeaders().getFirst(PlatformContextHeaders.WORKSPACE_ID))
                .isEqualTo("workspace-a");
        assertThat(chain.exchange().getRequest().getHeaders().getFirst(PlatformContextHeaders.WORKSPACE_RISK_LEVEL))
                .isEqualTo("NORMAL");
    }
}
