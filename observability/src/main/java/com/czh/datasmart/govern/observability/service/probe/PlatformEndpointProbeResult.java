/**
 * @Author : Cui
 * @Date: 2026/07/01 10:59
 * @Description DataSmartGovernBackend - PlatformEndpointProbeResult.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.observability.service.probe;

/**
 * 单次 HTTP 端点探针结果。
 *
 * <p>该对象只保存低敏技术摘要：HTTP 状态码、耗时和 issueCode。
 * 它刻意不保存响应正文、响应 Header、token、错误堆栈、内部 endpoint 重写细节或任何业务数据。
 * observability 是排障入口，但排障入口也必须遵守最小披露原则。</p>
 *
 * @param reachable 是否成功拿到 HTTP 响应。
 * @param statusCode HTTP 状态码；网络异常、超时或 DNS 失败时为空。
 * @param durationMs 探针耗时毫秒。
 * @param issueCode 低敏分类码，例如 OK、HTTP_500、TIMEOUT、NETWORK_ERROR。
 */
public record PlatformEndpointProbeResult(
        boolean reachable,
        Integer statusCode,
        long durationMs,
        String issueCode) {
}
