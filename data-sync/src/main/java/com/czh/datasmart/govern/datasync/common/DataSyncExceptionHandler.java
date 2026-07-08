/**
 * @Author : Cui
 * @Date: 2026/07/08 18:50
 * @Description DataSmart Govern Backend - DataSyncExceptionHandler.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.common;

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
 * data-sync 全局异常处理器。
 *
 * <p>数据同步是当前产品最核心的业务链路，创建向导、数据源用途筛选、元数据自动发现、对象映射、
 * 字段映射、SQL 检查、预检查、分片执行、脏数据落库、失败分片重试和回放修复都可能产生可修复错误。
 * 如果这些错误只表现为 HTTP 500 或一个业务码，用户无法判断应该修改哪一步配置。</p>
 *
 * <p>本处理器的职责是把异常翻译成统一的 {@link PlatformApiResponse}：
 * 顶层 message 用于弹窗摘要，data.details 用于逐条说明原因，
 * data.fieldErrors 用于表单定位，data.suggestions 用于提示下一步修复方式。</p>
 *
 * <p>安全边界：可预期错误返回具体低敏原因；未知异常不回显堆栈、SQL 正文、连接串、token、
 * 样本数据或内部 endpoint，完整堆栈只写入服务端日志，并通过 traceId 串联排障。</p>
 */
@Slf4j
@RestControllerAdvice
public class DataSyncExceptionHandler {

    /**
     * 处理平台业务异常。
     *
     * <p>Service 层主动抛出的 {@link PlatformBusinessException} 通常已经包含明确业务语义，
     * 例如参数校验失败、数据源用途错误、任务状态冲突、外部依赖失败等。
     * 这里不重新解释异常，只做统一响应包装。</p>
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
     * 处理 JSON 请求体字段校验异常。
     *
     * <p>创建同步任务、对象映射、字段映射、SQL 检查和预检查请求都有较复杂的 DTO。
     * 一次性返回全部字段错误，能避免用户“修一个字段、再弹一个字段”的低效体验。</p>
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<PlatformApiResponse<PlatformApiErrorDetail>> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception,
            HttpServletRequest request) {
        return toResponse(PlatformApiErrorDetailFactory.fromMethodArgumentNotValid(exception), request);
    }

    /**
     * 处理 query/path 参数绑定异常。
     */
    @ExceptionHandler(BindException.class)
    public ResponseEntity<PlatformApiResponse<PlatformApiErrorDetail>> handleBindException(
            BindException exception,
            HttpServletRequest request) {
        return toResponse(PlatformApiErrorDetailFactory.fromBindException(exception), request);
    }

    /**
     * 处理方法级参数约束异常。
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<PlatformApiResponse<PlatformApiErrorDetail>> handleConstraintViolation(
            ConstraintViolationException exception,
            HttpServletRequest request) {
        return toResponse(PlatformApiErrorDetailFactory.fromConstraintViolation(exception), request);
    }

    /**
     * 处理 JSON 解析异常。
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
     * 处理路径变量或查询参数类型转换失败。
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<PlatformApiResponse<PlatformApiErrorDetail>> handleMethodArgumentTypeMismatch(
            MethodArgumentTypeMismatchException exception,
            HttpServletRequest request) {
        return toResponse(PlatformApiErrorDetailFactory.fromMethodArgumentTypeMismatch(exception), request);
    }

    /**
     * 处理 Spring 主动抛出的带 HTTP 状态异常。
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<PlatformApiResponse<PlatformApiErrorDetail>> handleResponseStatusException(
            ResponseStatusException exception,
            HttpServletRequest request) {
        return toResponse(PlatformApiErrorDetailFactory.fromResponseStatusException(exception), request);
    }

    /**
     * 处理少量历史代码中直接抛出的 IllegalArgumentException。
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<PlatformApiResponse<PlatformApiErrorDetail>> handleIllegalArgumentException(
            IllegalArgumentException exception,
            HttpServletRequest request) {
        return toResponse(PlatformApiErrorDetailFactory.fromIllegalArgumentException(exception), request);
    }

    /**
     * 处理少量历史代码中直接抛出的 IllegalStateException。
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<PlatformApiResponse<PlatformApiErrorDetail>> handleIllegalStateException(
            IllegalStateException exception,
            HttpServletRequest request) {
        return toResponse(PlatformApiErrorDetailFactory.fromIllegalStateException(exception), request);
    }

    /**
     * 兜底处理未预期异常。
     *
     * <p>这里不能把 exception.getMessage() 原样返回给前端，因为未知异常可能包含 SQL 片段、连接地址、
     * 表结构、内部类名或堆栈线索。对外只返回 data-sync 内部异常和修复建议；真正排障看服务端日志。</p>
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<PlatformApiResponse<PlatformApiErrorDetail>> handleException(
            Exception exception,
            HttpServletRequest request) {
        log.error("data-sync 模块发生未预期异常，traceId={}, path={}",
                traceId(request), request.getRequestURI(), exception);
        return toResponse(PlatformApiErrorDetailFactory.internalError(
                "data-sync",
                "如果这是创建任务、元数据发现、SQL 检查、预检查或离线 worker 执行失败，请确认源端/目标端数据源可用，并查看 data-sync 与 datasource-management 日志。"
        ), request);
    }

    /**
     * 将公共错误描述转换为 data-sync 平台响应。
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
