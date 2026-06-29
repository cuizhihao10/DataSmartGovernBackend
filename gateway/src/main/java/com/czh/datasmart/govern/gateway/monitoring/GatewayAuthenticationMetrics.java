/**
 * @Author : Cui
 * @Date: 2026/06/29 23:59
 * @Description DataSmart Govern Backend - GatewayAuthenticationMetrics.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.gateway.monitoring;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * 网关认证链路指标记录器。
 *
 * <p>认证中心接入后，平台需要能回答一些非常基础但商业上很关键的问题：
 * 1. JWT 解析成功和失败的比例是多少；
 * 2. 最常见的失败原因是缺 tenantId、缺 actorId、缺角色，还是 principal 类型异常；
 * 3. 服务账号、普通用户和 Agent 身份的认证流量是否出现异常波动。</p>
 *
 * <p>本类只记录低基数标签，避免把 actorId、tenantId、workspaceId 这类高基数字段放进 Prometheus 标签，
 * 否则高并发、多租户环境下会造成指标基数爆炸。</p>
 */
@Component
@RequiredArgsConstructor
public class GatewayAuthenticationMetrics {

    private static final String METRIC_PREFIX = "datasmart.gateway.authentication";

    private final MeterRegistry meterRegistry;

    /**
     * 记录一次认证解析结果。
     *
     * @param outcome 结果，例如 RESOLVED、REJECTED、UNSUPPORTED_PRINCIPAL。
     * @param authenticationType 身份来源类型，例如 OIDC_JWT、ANONYMOUS。
     * @param actorType 主体类型，例如 USER、SERVICE_ACCOUNT。为空时归一化为 UNKNOWN。
     * @param primaryIssueCode 主要 issueCode。只记录一个低基数原因，避免把完整 issue 列表做成指标标签。
     */
    public void recordAuthenticationOutcome(String outcome,
                                            String authenticationType,
                                            String actorType,
                                            String primaryIssueCode) {
        Counter.builder(metric("outcome"))
                .description("Gateway authentication outcome count")
                .tag("outcome", normalize(outcome))
                .tag("auth_type", normalize(authenticationType))
                .tag("actor_type", normalize(actorType))
                .tag("primary_issue", normalize(primaryIssueCode))
                .register(meterRegistry)
                .increment();
    }

    private String metric(String suffix) {
        return METRIC_PREFIX + "." + suffix;
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return "UNKNOWN";
        }
        return value.trim().replace('-', '_').replace(' ', '_').toUpperCase(Locale.ROOT);
    }
}
