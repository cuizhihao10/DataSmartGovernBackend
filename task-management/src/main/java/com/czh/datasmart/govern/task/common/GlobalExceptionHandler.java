package com.czh.datasmart.govern.task.common;

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
 * @Date: 2026/4/18 22:10
 * @Description DataSmart Govern Backend - GlobalExceptionHandler.java
 * @Version:1.0.0
 *
 * 任务模块全局异常处理器。
 *
 * <p>它可以理解成“协议层翻译器”：</p>
 * <p>1. Service 层更关心业务规则，因此通常抛出 Java 异常；</p>
 * <p>2. Controller 层更关心 HTTP 契约，因此最终需要返回统一 JSON；</p>
 * <p>3. 当前类负责把二者衔接起来，避免 Controller 到处出现重复 try/catch。</p>
 *
 * <p>本轮整改后，任务模块不再只返回一个错误码或一段短 message。
 * 可预期错误会把结构化明细放入 {@link ApiResponse#getData()}：
 * 前端可以展示 {@code details}、定位 {@code fieldErrors}，并把 {@code suggestions}
 * 作为弹窗中的“下一步处理建议”。这样创建任务、编辑任务、手工调度、批量导入等页面失败时，
 * 用户能看到具体原因，而不是只能看到 HTTP 400/500。</p>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 处理平台级业务异常。
     *
     * <p>任务模块后续逐步从 {@link IllegalArgumentException} / {@link IllegalStateException}
     * 迁移到 {@link PlatformBusinessException} 后，就可以保留更准确的平台错误码语义。
     * 当前响应 envelope 仍沿用本模块历史 {@code ApiResponse}，因此 code 暂时保持 HTTP 状态码，
     * 避免一次性破坏旧前端或脚本对 code 的判断。</p>
     *
     * @param exception 平台业务异常
     * @return 携带结构化明细的失败响应
     */
    @ExceptionHandler(PlatformBusinessException.class)
    public ResponseEntity<ApiResponse<PlatformApiErrorDetail>> handlePlatformBusinessException(
            PlatformBusinessException exception) {
        return toResponse(PlatformApiErrorDetailFactory.fromBusinessException(exception));
    }

    /**
     * 处理“资源不存在”异常。
     *
     * <p>典型场景是按任务 ID 查询，但数据库里已经不存在该任务。
     * 这类错误应返回 404，并提示用户刷新列表或查看任务是否已删除/归档。</p>
     */
    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<ApiResponse<PlatformApiErrorDetail>> handleNoSuchElementException(
            NoSuchElementException exception) {
        return toResponse(PlatformApiErrorDetailFactory.fromNoSuchElementException(exception));
    }

    /**
     * 处理非法参数异常。
     *
     * <p>例如传入不支持的优先级、非法任务状态、非法调度周期等。
     * 这类错误说明请求本身不满足业务或输入约束，返回 400 更合理。</p>
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<PlatformApiErrorDetail>> handleIllegalArgumentException(
            IllegalArgumentException exception) {
        return toResponse(PlatformApiErrorDetailFactory.fromIllegalArgumentException(exception));
    }

    /**
     * 处理业务状态冲突异常。
     *
     * <p>例如“已完成任务不能取消”、“未下线任务不能删除”、“非运行中任务不能标记完成”。
     * 这类错误不是参数格式错，而是当前资源状态不允许执行该动作，因此映射为 409。</p>
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiResponse<PlatformApiErrorDetail>> handleIllegalStateException(
            IllegalStateException exception) {
        return toResponse(PlatformApiErrorDetailFactory.fromIllegalStateException(exception));
    }

    /**
     * 处理 {@code @RequestBody} 对象校验失败。
     *
     * <p>Spring 会在进入 Controller 前执行 Bean Validation。
     * 这里会收集全部字段错误，保证前端一次弹窗就能展示完整修复清单。</p>
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<PlatformApiErrorDetail>> handleMethodArgumentNotValidException(
            MethodArgumentNotValidException exception) {
        return toResponse(PlatformApiErrorDetailFactory.fromMethodArgumentNotValid(exception));
    }

    /**
     * 处理 query/path/form 参数绑定失败。
     *
     * <p>例如分页参数不是数字、枚举值非法、日期格式不能转换等。
     * 这类错误通常意味着前端参数组装或用户输入格式需要修正。</p>
     */
    @ExceptionHandler(BindException.class)
    public ResponseEntity<ApiResponse<PlatformApiErrorDetail>> handleBindException(BindException exception) {
        return toResponse(PlatformApiErrorDetailFactory.fromBindException(exception));
    }

    /**
     * 处理简单参数约束异常。
     *
     * <p>它通常来自 {@code @RequestParam}、{@code @PathVariable} 等位置的约束校验。</p>
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<PlatformApiErrorDetail>> handleConstraintViolationException(
            ConstraintViolationException exception) {
        return toResponse(PlatformApiErrorDetailFactory.fromConstraintViolation(exception));
    }

    /**
     * 处理 JSON 请求体解析失败。
     *
     * <p>例如 JSON 括号不闭合、字符串未加引号、数组与对象结构传反、字段类型不匹配。
     * 这些错误发生在业务方法之前，因此必须在全局异常层统一翻译。</p>
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
     *
     * <p>未知异常不会把堆栈、SQL、连接串或内部类名直接暴露给调用方。
     * 服务端日志保留完整异常，前端只拿到模块级摘要、排障建议和统一 500 状态。</p>
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<PlatformApiErrorDetail>> handleException(Exception exception) {
        log.error("task-management 模块发生未预期异常", exception);
        return toResponse(PlatformApiErrorDetailFactory.internalError(
                "task-management",
                "如果这是任务创建、调度、批量导入或状态流转失败，请优先查看 task-management 日志与任务审计记录。"
        ));
    }

    /**
     * 将平台错误描述适配为 task-management 历史响应 envelope。
     *
     * @param descriptor 公共错误构建器生成的错误描述
     * @return 模块本地 ApiResponse 失败响应
     */
    private ResponseEntity<ApiResponse<PlatformApiErrorDetail>> toResponse(PlatformApiErrorDescriptor descriptor) {
        return ResponseEntity.status(descriptor.httpStatus())
                .body(ApiResponse.error(descriptor.httpStatus(), descriptor.message(), descriptor.detail()));
    }
}
