package com.czh.datasmart.govern.gateway.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/**
 * @Author : Cui
 * @Date: 2026/4/18 22:20
 * @Description DataSmart Govern Backend - GlobalExceptionHandler.java
 * @Version:1.0.0
 *
 * 网关模块全局异常处理器。
 * 当前网关业务逻辑较轻，但仍保留统一异常出口，避免未来扩展契约接口或管理接口时风格失控。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 保留框架已经判定的 HTTP 状态码。
     *
     * <p>Spring Cloud Gateway 在服务发现找不到实例、下游暂时不可用或路由资源不存在时，
     * 会抛出 {@link ResponseStatusException} 的子类，并把 404、503 等状态写入异常。
     * 这些异常属于“网关已知道如何分类的基础设施结果”，不能再被兜底处理器统一改写成 500：
     * 500 表示网关自身发生未知故障，而 503 表示当前没有健康下游实例，两者的告警、重试、
     * 熔断和运维处理方式完全不同。</p>
     *
     * <p>响应正文只返回状态码对应的通用短语，不直接回传异常 reason。这样既能让调用方
     * 正确识别可重试状态，又不会把内部服务名、实例地址、路由规则或异常细节暴露给外部请求方。</p>
     *
     * @param exception Spring Web 或 Spring Cloud Gateway 已经附带状态码的异常
     * @return 保留原始 HTTP 状态码、使用低敏通用消息的统一错误响应
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiResponse<Void>> handleResponseStatusException(ResponseStatusException exception) {
        HttpStatusCode status = exception.getStatusCode();
        log.warn("gateway 请求按框架状态码结束，status={}, exceptionType={}",
                status.value(), exception.getClass().getSimpleName());
        return ResponseEntity.status(status)
                .body(ApiResponse.error(status.value(), resolveSafeMessage(status)));
    }

    /**
     * 兜底处理没有明确 HTTP 语义的未知异常。
     *
     * <p>只有未被框架和业务异常处理器识别的错误才进入这里，并统一返回 500。
     * 日志保留完整异常栈用于服务端排障，外部响应不暴露异常信息。</p>
     *
     * @param exception 未分类异常
     * @return HTTP 500 低敏错误响应
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleException(Exception exception) {
        log.error("gateway 模块发生未预期异常", exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(HttpStatus.INTERNAL_SERVER_ERROR.value(), "internal server error"));
    }

    /**
     * 把框架状态码转换为稳定、低敏的外部消息。
     *
     * <p>{@link HttpStatusCode} 也可能承载非标准状态码，因此不能假设一定能转换成
     * {@link HttpStatus}。标准状态码使用 Spring 的 reason phrase；非标准状态码只返回
     * {@code request failed}，避免拼接异常 reason 导致内部信息泄露。</p>
     *
     * @param status 框架判定的 HTTP 状态码
     * @return 不包含内部异常详情的消息
     */
    private String resolveSafeMessage(HttpStatusCode status) {
        HttpStatus standardStatus = HttpStatus.resolve(status.value());
        if (standardStatus == null) {
            return "request failed";
        }
        return standardStatus.getReasonPhrase().toLowerCase();
    }
}
