package com.czh.datasmart.govern.task.common;

import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.NoSuchElementException;
import java.util.stream.Collectors;

/**
 * @Author : Cui
 * @Date: 2026/4/18 22:10
 * @Description DataSmart Govern Backend - GlobalExceptionHandler.java
 * @Version:1.0.0
 *
 * 任务模块全局异常处理器。
 * 它可以理解成“协议层翻译器”：
 * - Service 层更关心业务规则，因此通常抛出 Java 异常。
 * - Controller 层更关心 HTTP 契约，因此最终需要返回统一 JSON。
 * - 当前这个类负责把二者衔接起来。
 *
 * 这样做的价值很大：
 * 1. Controller 可以保持干净，不需要到处写 try/catch。
 * 2. Service 可以专注表达业务限制，而不用混杂响应拼装代码。
 * 3. 所有错误出口最终变成统一结构，便于前端、网关和监控系统消费。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 处理“资源不存在”异常。
     * 典型场景是按任务 ID 查询，但数据库里已经不存在该任务。
     * 这类错误应返回 404，而不是模糊地返回 500。
     */
    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoSuchElementException(NoSuchElementException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error(HttpStatus.NOT_FOUND.value(), exception.getMessage()));
    }

    /**
     * 处理非法参数异常。
     * 比如传入不支持的优先级、非法状态值等。
     * 这类错误说明请求本身不满足业务或输入约束，返回 400 更合理。
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgumentException(IllegalArgumentException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(HttpStatus.BAD_REQUEST.value(), exception.getMessage()));
    }

    /**
     * 处理业务状态冲突异常。
     * 例如“已完成任务不能取消”、“非运行中任务不能标记完成”。
     * 这类错误不是参数格式错，而是当前资源状态不允许执行该动作，因此映射为 409。
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalStateException(IllegalStateException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error(HttpStatus.CONFLICT.value(), exception.getMessage()));
    }

    /**
     * 处理 @RequestBody 对象校验失败。
     * 当前项目使用 Jakarta Bean Validation 对请求体做声明式校验，
     * 当字段不满足 @NotBlank、@Min、@Max 等规则时，Spring 会在进入 Controller 之前抛出该异常。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodArgumentNotValidException(
            MethodArgumentNotValidException exception) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(HttpStatus.BAD_REQUEST.value(), message));
    }

    /**
     * 处理简单参数约束异常。
     * 它通常来自 @RequestParam、@PathVariable 等位置的约束校验。
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolationException(
            ConstraintViolationException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(HttpStatus.BAD_REQUEST.value(), exception.getMessage()));
    }

    /**
     * 最终兜底异常处理。
     * 如果有未知异常没有被前面的规则覆盖，这里会统一返回 500，
     * 同时记录 error 日志，方便后续 observability 模块接入集中监控与告警。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleException(Exception exception) {
        log.error("task-management 模块发生未预期异常", exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(HttpStatus.INTERNAL_SERVER_ERROR.value(), "internal server error"));
    }
}
