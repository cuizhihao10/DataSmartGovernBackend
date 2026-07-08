/**
 * @Author : Cui
 * @Date: 2026/07/08 19:10
 * @Description DataSmart Govern Backend - AgentRuntimeExceptionHandler.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.common;

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
 * agent-runtime 全局异常处理器。
 *
 * <p>agent-runtime 承载模型路由、工具调用、Skill 发布、工作区文件物化、MCP/A2A 适配、
 * Durable Agent Loop、工具执行审计、worker receipt 和多 Agent 编排控制面。
 * 这些能力的错误如果落成 Spring 默认 500，前端只能看到“系统异常”，无法判断是工具参数缺失、
 * 权限被拦截、会话状态冲突、worker 不可用，还是模型网关依赖异常。</p>
 *
 * <p>本处理器统一将异常转为 {@link PlatformApiResponse}：
 * 顶层 message 适合弹窗摘要，data.details 适合展示具体原因，
 * data.fieldErrors 适合表单定位，data.suggestions 适合给用户或实施人员下一步排障方向。</p>
 *
 * <p>安全边界：Agent 运行时可能处理 prompt、工具参数、文件路径、模型输出和外部协议 payload。
 * 未预期异常绝不能把这些内容直接回显给前端，完整堆栈只写入服务端日志。</p>
 */
@Slf4j
@RestControllerAdvice
public class AgentRuntimeExceptionHandler {

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
     * 处理历史代码或边界适配器直接抛出的非法参数异常。
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<PlatformApiResponse<PlatformApiErrorDetail>> handleIllegalArgumentException(
            IllegalArgumentException exception,
            HttpServletRequest request) {
        return toResponse(PlatformApiErrorDetailFactory.fromIllegalArgumentException(exception), request);
    }

    /**
     * 处理会话、工具、outbox、worker receipt 等状态冲突异常。
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
        log.error("agent-runtime 模块发生未预期异常，traceId={}, path={}",
                traceId(request), request.getRequestURI(), exception);
        return toResponse(PlatformApiErrorDetailFactory.internalError(
                "agent-runtime",
                "如果这是模型调用、工具执行、Skill 发布、MCP/A2A 适配或多 Agent 编排失败，请查看 agent-runtime 日志、Python Runtime 日志和对应 outbox/receipt 记录。"
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
