/**
 * @Author : Cui
 * @Date: 2026/4/18 21:30
 * @Description DataSmart Govern Backend - GlobalExceptionHandler.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.quality.common;

import com.czh.datasmart.govern.common.api.PlatformApiErrorDescriptor;
import com.czh.datasmart.govern.common.api.PlatformApiErrorDetail;
import com.czh.datasmart.govern.common.api.PlatformApiErrorDetailFactory;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
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
 * 数据质量模块全局异常处理器。
 *
 * <p>数据质量模块的失败往往包含多维原因：规则参数不完整、字段不存在、数据源只读 SQL 被安全策略拒绝、
 * 质量扫描执行器不可用、异常样本采集失败等。只返回一个错误码会让用户无法判断应该改规则、改数据源，
 * 还是等待执行器恢复。</p>
 *
 * <p>本处理器统一把可展示的错误明细放入 {@link ApiResponse#getData()}。
 * 对前端而言，顶层 message 用于弹窗标题，details 用于逐条展示，fieldErrors 用于表单定位，
 * suggestions 用于告诉用户下一步应该怎么修复。</p>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 处理平台级业务异常。
     */
    @ExceptionHandler(PlatformBusinessException.class)
    public ResponseEntity<ApiResponse<PlatformApiErrorDetail>> handlePlatformBusinessException(
            PlatformBusinessException exception) {
        return toResponse(PlatformApiErrorDetailFactory.fromBusinessException(exception));
    }

    /**
     * 资源不存在异常。
     * 例如按规则 ID 查询不到规则，或质量报告已被归档。
     */
    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<ApiResponse<PlatformApiErrorDetail>> handleNoSuchElementException(
            NoSuchElementException exception) {
        return toResponse(PlatformApiErrorDetailFactory.fromNoSuchElementException(exception));
    }

    /**
     * 非法参数异常。
     * 常见于传入不支持的规则类型、比较运算符、严重级别或扫描范围。
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<PlatformApiErrorDetail>> handleIllegalArgumentException(
            IllegalArgumentException exception) {
        return toResponse(PlatformApiErrorDetailFactory.fromIllegalArgumentException(exception));
    }

    /**
     * 非法状态异常。
     * 例如尝试执行已删除规则，或对不允许的状态做操作。
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiResponse<PlatformApiErrorDetail>> handleIllegalStateException(
            IllegalStateException exception) {
        return toResponse(PlatformApiErrorDetailFactory.fromIllegalStateException(exception));
    }

    /**
     * 处理请求体字段校验失败。
     * 这类异常通常由 Bean Validation 在进入 Controller 前抛出。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<PlatformApiErrorDetail>> handleMethodArgumentNotValidException(
            MethodArgumentNotValidException exception) {
        return toResponse(PlatformApiErrorDetailFactory.fromMethodArgumentNotValid(exception));
    }

    /**
     * 处理查询参数、路径参数或表单参数绑定失败。
     */
    @ExceptionHandler(BindException.class)
    public ResponseEntity<ApiResponse<PlatformApiErrorDetail>> handleBindException(BindException exception) {
        return toResponse(PlatformApiErrorDetailFactory.fromBindException(exception));
    }

    /**
     * 处理简单参数约束异常。
     * 常见于路径参数和查询参数的约束校验。
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<PlatformApiErrorDetail>> handleConstraintViolationException(
            ConstraintViolationException exception) {
        return toResponse(PlatformApiErrorDetailFactory.fromConstraintViolation(exception));
    }

    /**
     * 处理 JSON 请求体解析失败。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<PlatformApiErrorDetail>> handleHttpMessageNotReadable(
            HttpMessageNotReadableException exception) {
        return toResponse(PlatformApiErrorDetailFactory.fromHttpMessageNotReadable(exception));
    }

    /**
     * 处理缺少必填查询参数。
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<PlatformApiErrorDetail>> handleMissingServletRequestParameter(
            MissingServletRequestParameterException exception) {
        return toResponse(PlatformApiErrorDetailFactory.fromMissingRequestParameter(exception.getParameterName()));
    }

    /**
     * 处理路径变量或查询参数类型转换失败。
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<PlatformApiErrorDetail>> handleMethodArgumentTypeMismatch(
            MethodArgumentTypeMismatchException exception) {
        return toResponse(PlatformApiErrorDetailFactory.fromMethodArgumentTypeMismatch(exception));
    }

    /**
     * 保留 Spring 已经判定的 HTTP 状态。
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiResponse<PlatformApiErrorDetail>> handleResponseStatusException(
            ResponseStatusException exception) {
        return toResponse(PlatformApiErrorDetailFactory.fromResponseStatusException(exception));
    }

    /**
     * 兜底异常处理。
     * 未知异常不会直接暴露堆栈给调用方，而是统一返回 500，并在服务端记录日志。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<PlatformApiErrorDetail>> handleException(Exception exception) {
        log.error("data-quality 模块发生未预期异常", exception);
        return toResponse(PlatformApiErrorDetailFactory.internalError(
                "data-quality",
                "如果这是规则配置、质量扫描、SQL 生成或异常样本采集失败，请查看 data-quality 日志与质量执行记录。"
        ));
    }

    /**
     * 将平台错误描述适配为 data-quality 历史响应 envelope。
     */
    private ResponseEntity<ApiResponse<PlatformApiErrorDetail>> toResponse(PlatformApiErrorDescriptor descriptor) {
        return ResponseEntity.status(descriptor.httpStatus())
                .body(ApiResponse.error(descriptor.httpStatus(), descriptor.message(), descriptor.detail()));
    }
}
