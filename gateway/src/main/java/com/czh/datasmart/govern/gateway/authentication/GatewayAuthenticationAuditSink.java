/**
 * @Author : Cui
 * @Date: 2026/06/29 23:59
 * @Description DataSmart Govern Backend - GatewayAuthenticationAuditSink.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.gateway.authentication;

/**
 * 网关认证审计事件输出端口。
 *
 * <p>当前项目还处在单机可运行与商业化闭环收敛之间，gateway 暂时没有独立审计表。
 * 因此这里先定义一个很薄的端口，让认证审计事件可以被日志 sink 消费；后续如果要接 Kafka、SIEM、
 * observability 模块或 permission-admin 审计中心，只需要新增一个实现类，不需要改动 OIDC 过滤器主链路。</p>
 */
public interface GatewayAuthenticationAuditSink {

    /**
     * 输出一条认证审计事件。
     *
     * <p>实现类必须遵守 {@link GatewayAuthenticationAuditEvent} 的低敏约束，不能为了排障方便把 token、
     * 完整 claim、用户联系方式、凭据、prompt、SQL 或模型输出重新拼进日志、消息或审计正文。</p>
     *
     * @param event 已经由 gateway 统一净化过的低敏认证审计事件。
     */
    void emit(GatewayAuthenticationAuditEvent event);
}
