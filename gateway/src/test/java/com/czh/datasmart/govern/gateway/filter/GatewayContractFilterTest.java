/**
 * @Author : Cui
 * @Date: 2026/07/08 18:29
 * @Description DataSmart Govern Backend - GatewayContractFilterTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.gateway.filter;

import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import com.czh.datasmart.govern.gateway.config.GatewayContextProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 网关平台上下文契约过滤器测试。
 *
 * <p>本测试重点保护 gateway 的第一道安全边界：外部请求可以携带一些“意图字段”，
 * 但不能让这些字段原样进入下游业务服务。项目切换器就是典型例子：
 * 前端需要告诉 gateway 当前选中的项目，但下游服务只能信任 gateway 校验后重建的项目 Header。</p>
 */
class GatewayContractFilterTest {

    /**
     * 外部传入的项目 Header 应被清理，同时作为待授权校验的内部 attribute 保留。
     *
     * <p>这个行为让后续 GatewayAuthorizationFilter 可以拿 permission-admin 返回的 authorizedProjectIds
     * 校验用户是否真的有权使用该项目。若这里直接把 Header 转发给业务服务，用户就可以手工改 Header
     * 尝试越权创建数据源或同步任务。</p>
     */
    @Test
    void shouldClearIncomingProjectHeaderButRememberRequestedProjectId() {
        GatewayContractFilter filter = new GatewayContractFilter(new GatewayContextProperties());
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/datasource/datasources")
                        .header(PlatformContextHeaders.TRACE_ID, "trace-contract-project")
                        .header(PlatformContextHeaders.PROJECT_ID, "101")
                        .header(PlatformContextHeaders.AUTHORIZED_PROJECT_IDS, "101,102")
                        .build()
        );
        RecordingGatewayFilterChain chain = new RecordingGatewayFilterChain();

        filter.filter(exchange, chain).block();

        assertThat(chain.called()).isTrue();
        assertThat(chain.exchange().getRequest().getHeaders().getFirst(PlatformContextHeaders.PROJECT_ID)).isNull();
        assertThat(chain.exchange().getRequest().getHeaders().getFirst(PlatformContextHeaders.AUTHORIZED_PROJECT_IDS)).isNull();
        String rawProjectId = chain.exchange().getAttribute(GatewayExchangeAttributeNames.REQUESTED_PROJECT_ID_RAW);
        Long requestedProjectId = chain.exchange().getAttribute(GatewayExchangeAttributeNames.REQUESTED_PROJECT_ID);
        assertThat(rawProjectId).isEqualTo("101");
        assertThat(requestedProjectId).isEqualTo(101L);
    }

    /**
     * 非法项目 Header 也不能透传，但原始值需要保留给授权过滤器返回清晰 400。
     */
    @Test
    void shouldRememberInvalidProjectHeaderAsRawValueOnly() {
        GatewayContractFilter filter = new GatewayContractFilter(new GatewayContextProperties());
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/sync/sync-tasks")
                        .header(PlatformContextHeaders.TRACE_ID, "trace-contract-invalid-project")
                        .header(PlatformContextHeaders.PROJECT_ID, "not-a-number")
                        .build()
        );
        RecordingGatewayFilterChain chain = new RecordingGatewayFilterChain();

        filter.filter(exchange, chain).block();

        assertThat(chain.called()).isTrue();
        assertThat(chain.exchange().getRequest().getHeaders().getFirst(PlatformContextHeaders.PROJECT_ID)).isNull();
        String rawProjectId = chain.exchange().getAttribute(GatewayExchangeAttributeNames.REQUESTED_PROJECT_ID_RAW);
        Long requestedProjectId = chain.exchange().getAttribute(GatewayExchangeAttributeNames.REQUESTED_PROJECT_ID);
        assertThat(rawProjectId).isEqualTo("not-a-number");
        assertThat(requestedProjectId).isNull();
    }
}
