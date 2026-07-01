/**
 * @Author : Cui
 * @Date: 2026-07-01 18:20
 * @Description DataSmart Govern Backend - GlobalExceptionHandlerTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.gateway.common;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 网关全局异常处理器状态码语义测试。
 *
 * <p>该测试重点保护真实 E2E 暴露出的一个生产问题：Spring Cloud Gateway 已经把
 * “没有可用下游实例”分类为 503，但通用异常处理器如果把它重新包装成 500，
 * 调用方就无法执行正确的重试、熔断和告警策略。</p>
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    /**
     * 下游服务不可用必须保留 503，同时隐藏框架异常中的内部服务名。
     *
     * <p>测试 reason 故意放入 agent-runtime 服务名；公开响应只能返回通用
     * {@code service unavailable}，不能把服务发现内部细节泄露给外部调用方。</p>
     */
    @Test
    void serviceUnavailableShouldKeepStatusWithoutLeakingInternalReason() {
        ResponseStatusException exception = new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Unable to find instance for agent-runtime"
        );

        ResponseEntity<ApiResponse<Void>> response = handler.handleResponseStatusException(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.value());
        assertThat(response.getBody().getMessage()).isEqualTo("service unavailable");
        assertThat(response.getBody().getMessage()).doesNotContain("agent-runtime");
    }

    /**
     * 未分类异常仍然必须走 500 兜底，避免“保留状态码”逻辑吞掉真实程序错误。
     */
    @Test
    void unknownExceptionShouldRemainInternalServerError() {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleException(new IllegalStateException("internal implementation detail"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
        assertThat(response.getBody().getMessage()).isEqualTo("internal server error");
    }
}
