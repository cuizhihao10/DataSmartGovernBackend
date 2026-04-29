/**
 * @Author : Cui
 * @Date: 2026/04/25 23:00
 * @Description DataSmart Govern Backend - PermissionAdminExceptionHandler.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.common;

import com.czh.datasmart.govern.common.api.PlatformApiResponse;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * permission-admin 全局异常处理器。
 *
 * <p>新模块直接使用 platform-common 的统一响应和错误码，不再复制模块本地 ApiResponse。
 * 这能让后续 gateway、前端、审计中心和运维工具使用同一种错误结构解析权限模块响应。
 */
@Slf4j
@RestControllerAdvice
public class PermissionAdminExceptionHandler {

    /**
     * 处理平台业务异常。
     */
    @ExceptionHandler(PlatformBusinessException.class)
    public ResponseEntity<PlatformApiResponse<Void>> handlePlatformBusinessException(PlatformBusinessException exception,
                                                                                     HttpServletRequest request) {
        return ResponseEntity.status(exception.getErrorCode().getHttpStatus())
                .body(PlatformApiResponse.error(exception.getErrorCode(), exception.getMessage(), traceId(request)));
    }

    /**
     * 处理请求体参数校验异常。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<PlatformApiResponse<Void>> handleMethodArgumentNotValid(MethodArgumentNotValidException exception,
                                                                                  HttpServletRequest request) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .orElse("请求参数校验失败");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(PlatformApiResponse.error(PlatformErrorCode.VALIDATION_ERROR, message, traceId(request)));
    }

    /**
     * 处理查询参数绑定异常。
     */
    @ExceptionHandler(BindException.class)
    public ResponseEntity<PlatformApiResponse<Void>> handleBindException(BindException exception,
                                                                         HttpServletRequest request) {
        String message = exception.getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .orElse("请求参数绑定失败");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(PlatformApiResponse.error(PlatformErrorCode.VALIDATION_ERROR, message, traceId(request)));
    }

    /**
     * 兜底异常处理。
     *
     * <p>权限模块一旦出现未预期异常，不能把堆栈直接暴露给调用方。
     * 对外返回统一错误码，对内通过日志保留异常详情。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<PlatformApiResponse<Void>> handleException(Exception exception,
                                                                     HttpServletRequest request) {
        log.error("permission-admin 模块发生未预期异常", exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(PlatformApiResponse.error(PlatformErrorCode.INTERNAL_ERROR, "权限中心内部异常", traceId(request)));
    }

    /**
     * 从请求 Header 中读取 traceId。
     */
    private String traceId(HttpServletRequest request) {
        return request.getHeader(PlatformContextHeaders.TRACE_ID);
    }
}
