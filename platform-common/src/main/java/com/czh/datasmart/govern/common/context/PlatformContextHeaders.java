/**
 * @Author : Cui
 * @Date: 2026/04/25 22:30
 * @Description DataSmart Govern Backend - PlatformContextHeaders.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.common.context;

/**
 * 平台上下文 HTTP Header 常量。
 *
 * gateway 后续应该负责生成、校验和透传这些 Header；各业务微服务只消费这些上下文，不应该各自发明不同名称。
 * 统一 Header 的意义在于：
 * 1. 网关、服务、日志、审计、指标可以使用同一套字段关联请求；
 * 2. 服务间调用时可以稳定传递租户、操作者、traceId；
 * 3. 后续接入 OpenTelemetry 或集中式审计时，不需要逐模块做字段适配。
 */
public final class PlatformContextHeaders {

    public static final String TRACE_ID = "X-DataSmart-Trace-Id";
    public static final String TENANT_ID = "X-DataSmart-Tenant-Id";
    public static final String ACTOR_ID = "X-DataSmart-Actor-Id";
    public static final String ACTOR_ROLE = "X-DataSmart-Actor-Role";
    public static final String ACTOR_TYPE = "X-DataSmart-Actor-Type";
    public static final String SOURCE_SERVICE = "X-DataSmart-Source-Service";
    public static final String WORKSPACE_ID = "X-DataSmart-Workspace-Id";
    public static final String REQUEST_SOURCE = "X-DataSmart-Request-Source";

    private PlatformContextHeaders() {
        throw new UnsupportedOperationException("PlatformContextHeaders 是常量类，不允许实例化");
    }
}
