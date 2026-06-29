/**
 * @Author : Cui
 * @Date: 2026/06/29 23:59
 * @Description DataSmart Govern Backend - LoggingGatewayAuthenticationAuditSink.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.gateway.authentication;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 基于结构化日志的认证审计 sink。
 *
 * <p>这是当前 gateway 的默认审计落点。它不是最终商业审计中心，但已经比“只返回 HTTP 403”更接近生产：
 * 运维、安全和研发可以通过 traceId、outcome、authType、tenantId、actorId、role、issueCodes 还原一次
 * OIDC/JWT 身份解析为什么成功或失败。后续接入 Kafka/SIEM 时，建议保留这条日志作为本地应急排障证据。</p>
 */
@Slf4j
@Component
public class LoggingGatewayAuthenticationAuditSink implements GatewayAuthenticationAuditSink {

    /**
     * 写入低敏结构化认证审计日志。
     *
     * <p>日志字段刻意保持为控制面摘要，不包含 Authorization header、JWT token、完整 claim、
     * 用户邮箱/手机号或任何业务正文。这样即使日志进入集中式检索系统，也不会把认证凭据扩散出去。</p>
     */
    @Override
    public void emit(GatewayAuthenticationAuditEvent event) {
        log.info("gateway.authentication.audit eventId={} traceId={} outcome={} authType={} tenantId={} "
                        + "actorId={} actorRole={} actorType={} workspaceId={} requestPath={} requestSource={} "
                        + "issueCodes={} payloadPolicy={}",
                event.eventId(),
                event.traceId(),
                event.outcome(),
                event.authenticationType(),
                event.tenantId(),
                event.actorId(),
                event.actorRole(),
                event.actorType(),
                event.workspaceId(),
                event.requestPath(),
                event.requestSource(),
                event.issueCodes(),
                event.payloadPolicy());
    }
}
