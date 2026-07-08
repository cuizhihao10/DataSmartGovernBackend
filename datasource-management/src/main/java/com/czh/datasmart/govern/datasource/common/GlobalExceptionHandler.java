package com.czh.datasmart.govern.datasource.common;

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
 * @Author : Cui
 * @Date: 2026/4/18 21:55
 * @Description DataSmart Govern Backend - GlobalExceptionHandler.java
 * @Version:1.0.0
 *
 * 数据源模块全局异常处理器。
 *
 * <p>数据源模块的错误对用户非常敏感：创建数据源、连接测试、元数据发现、只读 SQL 检查等步骤
 * 一旦只返回 400/500，用户无法判断是账号密码错误、网络不可达、用途不匹配、元数据范围为空，
 * 还是请求字段没有填完整。</p>
 *
 * <p>因此本处理器统一把可展示错误详情放入 {@link ApiResponse#getData()}：
 * {@code details} 给弹窗展示，{@code fieldErrors} 给表单定位，{@code suggestions} 给用户下一步修复建议。
 * 未知异常仍然低敏返回，完整堆栈留在服务端日志。</p>
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
     *
     * <p>例如数据源 ID 不存在、数据源已删除、元数据对象已过期等。</p>
     */
    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<ApiResponse<PlatformApiErrorDetail>> handleNoSuchElementException(
            NoSuchElementException exception) {
        return toResponse(PlatformApiErrorDetailFactory.fromNoSuchElementException(exception));
    }

    /**
     * 非法参数异常。
     *
     * <p>例如数据源用途不是 SOURCE/TARGET、连接配置缺少主机或端口、元数据查询范围不合法。</p>
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<PlatformApiErrorDetail>> handleIllegalArgumentException(
            IllegalArgumentException exception) {
        return toResponse(PlatformApiErrorDetailFactory.fromIllegalArgumentException(exception));
    }

    /**
     * 非法状态异常。
     *
     * <p>例如对已删除数据源执行连接测试，或对非 ACTIVE 数据源执行受控 SQL。</p>
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiResponse<PlatformApiErrorDetail>> handleIllegalStateException(
            IllegalStateException exception) {
        return toResponse(PlatformApiErrorDetailFactory.fromIllegalStateException(exception));
    }

    /**
     * 处理请求体字段校验失败。
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
     * 最终兜底异常处理。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<PlatformApiErrorDetail>> handleException(Exception exception) {
        log.error("datasource-management 模块发生未预期异常", exception);
        return toResponse(PlatformApiErrorDetailFactory.internalError(
                "datasource-management",
                "如果这是数据源创建、连接测试、元数据发现或只读 SQL 检查失败，请查看 datasource-management 日志与连接审计。"
        ));
    }

    /**
     * 将平台错误描述适配为 datasource-management 历史响应 envelope。
     */
    private ResponseEntity<ApiResponse<PlatformApiErrorDetail>> toResponse(PlatformApiErrorDescriptor descriptor) {
        return ResponseEntity.status(descriptor.httpStatus())
                .body(ApiResponse.error(descriptor.httpStatus(), descriptor.message(), descriptor.detail()));
    }
}
