/**
 * @Author : Cui
 * @Date: 2026/07/08 19:10
 * @Description DataSmart Govern Backend - ObservabilityExceptionHandler.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.observability.common;

import com.czh.datasmart.govern.common.api.PlatformApiErrorDescriptor;
import com.czh.datasmart.govern.common.api.PlatformApiErrorDetail;
import com.czh.datasmart.govern.common.api.PlatformApiErrorDetailFactory;
import com.czh.datasmart.govern.common.api.PlatformApiResponse;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

import java.util.NoSuchElementException;

/**
 * observability 全局异常处理器。
 *
 * <p>可观测性模块负责健康快照、告警覆盖、闭环诊断和后续运维视图。
 * 它的错误提示需要能帮助实施/运维人员判断是探测目标不可达、配置不完整、权限不足，
 * 还是平台服务自身异常，而不能只返回一个 500。</p>
 *
 * <p>本处理器与其他 Spring MVC 微服务保持同一套结构化错误合同：
 * 业务错误返回具体低敏原因，参数错误返回字段级明细，未知错误返回模块级摘要和排障建议。</p>
 */
@Slf4j
@RestControllerAdvice
public class ObservabilityExceptionHandler {

    /**
     * 处理平台业务异常。
     */
    @ExceptionHandler(PlatformBusinessException.class)
    public ResponseEntity<PlatformApiResponse<PlatformApiErrorDetail>> handlePlatformBusinessException(
            PlatformBusinessException exception,
            HttpServletRequest request) {
        return toResponse(PlatformApiErrorDetailFactory.fromBusinessException(exception), request);
    }

    /**
     * 处理资源不存在异常。
     */
    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<PlatformApiResponse<PlatformApiErrorDetail>> handleNoSuchElementException(
            NoSuchElementException exception,
            HttpServletRequest request) {
        return toResponse(PlatformApiErrorDetailFactory.fromNoSuchElementException(exception), request);
    }

    /**
     * 处理请求体字段校验失败。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<PlatformApiResponse<PlatformApiErrorDetail>> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception,
            HttpServletRequest request) {
        return toResponse(PlatformApiErrorDetailFactory.fromMethodArgumentNotValid(exception), request);
    }

    /**
     * 处理查询参数、路径参数或表单参数绑定失败。
     */
    @ExceptionHandler(BindException.class)
    public ResponseEntity<PlatformApiResponse<PlatformApiErrorDetail>> handleBindException(
            BindException exception,
            HttpServletRequest request) {
        return toResponse(PlatformApiErrorDetailFactory.fromBindException(exception), request);
    }

    /**
     * 处理方法级参数约束失败。
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<PlatformApiResponse<PlatformApiErrorDetail>> handleConstraintViolation(
            ConstraintViolationException exception,
            HttpServletRequest request) {
        return toResponse(PlatformApiErrorDetailFactory.fromConstraintViolation(exception), request);
    }

    /**
     * 处理 JSON 请求体解析失败。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<PlatformApiResponse<PlatformApiErrorDetail>> handleHttpMessageNotReadable(
            HttpMessageNotReadableException exception,
            HttpServletRequest request) {
        return toResponse(PlatformApiErrorDetailFactory.fromHttpMessageNotReadable(exception), request);
    }

    /**
     * 处理缺少必填查询参数。
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<PlatformApiResponse<PlatformApiErrorDetail>> handleMissingServletRequestParameter(
            MissingServletRequestParameterException exception,
            HttpServletRequest request) {
        return toResponse(PlatformApiErrorDetailFactory.fromMissingRequestParameter(exception.getParameterName()), request);
    }

    /**
     * 处理参数类型转换失败。
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<PlatformApiResponse<PlatformApiErrorDetail>> handleMethodArgumentTypeMismatch(
            MethodArgumentTypeMismatchException exception,
            HttpServletRequest request) {
        return toResponse(PlatformApiErrorDetailFactory.fromMethodArgumentTypeMismatch(exception), request);
    }

    /**
     * 处理非法参数异常。
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<PlatformApiResponse<PlatformApiErrorDetail>> handleIllegalArgumentException(
            IllegalArgumentException exception,
            HttpServletRequest request) {
        return toResponse(PlatformApiErrorDetailFactory.fromIllegalArgumentException(exception), request);
    }

    /**
     * 处理状态冲突异常。
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<PlatformApiResponse<PlatformApiErrorDetail>> handleIllegalStateException(
            IllegalStateException exception,
            HttpServletRequest request) {
        return toResponse(PlatformApiErrorDetailFactory.fromIllegalStateException(exception), request);
    }

    /**
     * 保留 Spring 已经判定的 HTTP 状态。
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<PlatformApiResponse<PlatformApiErrorDetail>> handleResponseStatusException(
            ResponseStatusException exception,
            HttpServletRequest request) {
        return toResponse(PlatformApiErrorDetailFactory.fromResponseStatusException(exception), request);
    }

    /**
     * 兜底处理未预期异常。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<PlatformApiResponse<PlatformApiErrorDetail>> handleException(
            Exception exception,
            HttpServletRequest request) {
        log.error("observability 模块发生未预期异常，traceId={}, path={}",
                traceId(request), request.getRequestURI(), exception);
        return toResponse(PlatformApiErrorDetailFactory.internalError(
                "observability",
                "如果这是健康探测、告警覆盖、闭环诊断或运维视图失败，请查看 observability 日志、Prometheus 抓取状态和目标服务健康端点。"
        ), request);
    }

    /**
     * 将公共错误描述转换为平台统一响应。
     */
    private ResponseEntity<PlatformApiResponse<PlatformApiErrorDetail>> toResponse(
            PlatformApiErrorDescriptor descriptor,
            HttpServletRequest request) {
        return ResponseEntity.status(descriptor.httpStatus())
                .body(PlatformApiResponse.error(
                        descriptor.errorCode(),
                        descriptor.message(),
                        descriptor.detail(),
                        traceId(request)
                ));
    }

    /**
     * 从请求 Header 中读取 traceId。
     */
    private String traceId(HttpServletRequest request) {
        return request.getHeader(PlatformContextHeaders.TRACE_ID);
    }
}
