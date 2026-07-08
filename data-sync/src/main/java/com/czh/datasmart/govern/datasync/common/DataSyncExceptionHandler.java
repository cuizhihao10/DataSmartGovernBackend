/**
 * @Author : Cui
 * @Date: 2026/07/08 18:50
 * @Description DataSmart Govern Backend - DataSyncExceptionHandler.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.common;

import com.czh.datasmart.govern.common.api.PlatformApiErrorDetail;
import com.czh.datasmart.govern.common.api.PlatformApiResponse;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * data-sync 全局异常处理器。
 *
 * <p>创建同步任务、元数据发现、字段映射建议、SQL 检查和执行器回调都属于强交互接口。
 * 如果可预期的业务异常没有在这里收口，Spring MVC 会把它们包装成默认错误响应，例如：</p>
 * <p>{@code {"timestamp":"...","status":500,"error":"Internal Server Error"}}</p>
 *
 * <p>这种响应对真实用户几乎没有帮助：用户不知道是源端数据源不可访问、目标端数据源用途错误、
 * scheduleConfig 缺失，还是 datasource-management 返回了参数校验失败。更糟糕的是，前端只能弹出
 * “HTTP 500”，会把一个可修复的配置问题误解为系统崩溃。</p>
 *
 * <p>本处理器的设计目标：</p>
 * <p>1. 平台业务异常按 {@link PlatformErrorCode#getHttpStatus()} 返回，不再全部漏成 500；</p>
 * <p>2. Bean Validation 字段错误全部返回，前端弹窗和表单都能展示“每一项错在哪里”；</p>
 * <p>3. JSON 格式、参数类型、缺少请求参数等框架异常返回 BAD_REQUEST；</p>
 * <p>4. 未预期异常只返回安全摘要和 traceId，堆栈留在日志中，避免泄漏 SQL、连接串或内部实现。</p>
 */
@Slf4j
@RestControllerAdvice
public class DataSyncExceptionHandler {

    /**
     * 处理平台业务异常。
     *
     * <p>Service 层主动抛出的 {@link PlatformBusinessException} 通常已经携带明确业务语义：
     * 参数校验失败、租户越权、任务状态冲突、外部依赖失败等。这里不重新解释异常，只负责把错误码、HTTP 状态、
     * message、traceId 和低敏详情统一包装成 {@link PlatformApiResponse}。</p>
     */
    @ExceptionHandler(PlatformBusinessException.class)
    public ResponseEntity<PlatformApiResponse<PlatformApiErrorDetail>> handlePlatformBusinessException(
            PlatformBusinessException exception,
            HttpServletRequest request) {
        PlatformErrorCode errorCode = exception.getErrorCode();
        PlatformApiErrorDetail detail = PlatformApiErrorDetail.ofDetails(List.of(safeMessage(exception.getMessage())));
        return ResponseEntity.status(errorCode.getHttpStatus())
                .body(PlatformApiResponse.error(errorCode, safeMessage(exception.getMessage()), detail, traceId(request)));
    }

    /**
     * 处理 JSON 请求体字段校验异常。
     *
     * <p>例如 {@code sourceDatasourceId} 为空、{@code maxTables} 超过上限、任务名称为空等。
     * 这里会收集所有字段错误，而不是只返回第一条，方便前端在一个弹窗里一次性展示完整修复清单。</p>
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<PlatformApiResponse<PlatformApiErrorDetail>> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception,
            HttpServletRequest request) {
        List<PlatformApiErrorDetail.FieldErrorDetail> fieldErrors = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new PlatformApiErrorDetail.FieldErrorDetail(
                        error.getField(),
                        safeMessage(error.getDefaultMessage())))
                .toList();
        List<String> details = fieldErrors.stream()
                .map(error -> error.field() + " " + error.message())
                .toList();
        String message = details.isEmpty() ? "请求参数校验失败" : "请求参数校验失败：" + String.join("；", details);
        PlatformApiErrorDetail detail = PlatformApiErrorDetail.of(
                details.isEmpty() ? List.of("请求参数校验失败，请检查必填项、长度和数值范围") : details,
                fieldErrors,
                List.of("请根据字段级错误修正表单后重试"));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(PlatformApiResponse.error(PlatformErrorCode.VALIDATION_ERROR, message, detail, traceId(request)));
    }

    /**
     * 处理 query/path 参数绑定异常。
     */
    @ExceptionHandler(BindException.class)
    public ResponseEntity<PlatformApiResponse<PlatformApiErrorDetail>> handleBindException(BindException exception,
                                                                                           HttpServletRequest request) {
        List<PlatformApiErrorDetail.FieldErrorDetail> fieldErrors = exception.getFieldErrors().stream()
                .map(error -> new PlatformApiErrorDetail.FieldErrorDetail(
                        error.getField(),
                        safeMessage(error.getDefaultMessage())))
                .toList();
        List<String> details = fieldErrors.stream()
                .map(error -> error.field() + " " + error.message())
                .toList();
        PlatformApiErrorDetail detail = PlatformApiErrorDetail.of(
                details.isEmpty() ? List.of("请求参数绑定失败，请检查查询参数或路径参数") : details,
                fieldErrors,
                List.of("请检查 URL 参数类型、枚举值和分页参数范围"));
        String message = details.isEmpty() ? "请求参数绑定失败" : "请求参数绑定失败：" + String.join("；", details);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(PlatformApiResponse.error(PlatformErrorCode.VALIDATION_ERROR, message, detail, traceId(request)));
    }

    /**
     * 处理方法级参数校验异常。
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<PlatformApiResponse<PlatformApiErrorDetail>> handleConstraintViolation(
            ConstraintViolationException exception,
            HttpServletRequest request) {
        List<PlatformApiErrorDetail.FieldErrorDetail> fieldErrors = exception.getConstraintViolations().stream()
                .map(violation -> new PlatformApiErrorDetail.FieldErrorDetail(
                        String.valueOf(violation.getPropertyPath()),
                        safeMessage(violation.getMessage())))
                .toList();
        List<String> details = fieldErrors.stream()
                .map(error -> error.field() + " " + error.message())
                .toList();
        PlatformApiErrorDetail detail = PlatformApiErrorDetail.of(
                details.isEmpty() ? List.of("请求参数约束校验失败") : details,
                fieldErrors,
                List.of("请检查请求参数是否满足接口约束"));
        String message = details.isEmpty() ? "请求参数约束校验失败" : "请求参数约束校验失败：" + String.join("；", details);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(PlatformApiResponse.error(PlatformErrorCode.VALIDATION_ERROR, message, detail, traceId(request)));
    }

    /**
     * 处理 JSON 解析异常。
     *
     * <p>常见原因包括 JSON 括号缺失、字符串没有引号、字段类型不匹配、数组/对象结构传错。
     * mostSpecificCause 可能包含 Jackson 的具体字段路径，但也可能比较长，因此这里做长度和控制字符清理。</p>
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<PlatformApiResponse<PlatformApiErrorDetail>> handleHttpMessageNotReadable(
            HttpMessageNotReadableException exception,
            HttpServletRequest request) {
        String cause = exception.getMostSpecificCause() == null
                ? null
                : safeMessage(exception.getMostSpecificCause().getMessage());
        PlatformApiErrorDetail detail = PlatformApiErrorDetail.of(
                cause == null || cause.isBlank()
                        ? List.of("请求体 JSON 格式不正确，请检查字段类型、括号、逗号和引号")
                        : List.of("请求体 JSON 格式不正确", cause),
                List.of(),
                List.of("请确认请求体是合法 JSON，并且字段类型与接口文档一致"));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(PlatformApiResponse.error(
                        PlatformErrorCode.BAD_REQUEST,
                        "请求体 JSON 格式不正确，请检查字段类型、括号、逗号和引号",
                        detail,
                        traceId(request)));
    }

    /**
     * 处理缺少必填查询参数。
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<PlatformApiResponse<PlatformApiErrorDetail>> handleMissingServletRequestParameter(
            MissingServletRequestParameterException exception,
            HttpServletRequest request) {
        String detailText = "缺少必填请求参数: " + exception.getParameterName();
        PlatformApiErrorDetail detail = PlatformApiErrorDetail.of(
                List.of(detailText),
                List.of(new PlatformApiErrorDetail.FieldErrorDetail(exception.getParameterName(), "不能为空")),
                List.of("请补齐必填查询参数后重试"));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(PlatformApiResponse.error(PlatformErrorCode.VALIDATION_ERROR, detailText, detail, traceId(request)));
    }

    /**
     * 处理路径变量或查询参数类型转换失败。
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<PlatformApiResponse<PlatformApiErrorDetail>> handleMethodArgumentTypeMismatch(
            MethodArgumentTypeMismatchException exception,
            HttpServletRequest request) {
        String detailText = "参数 " + exception.getName() + " 类型不正确，当前值为 " + safeMessage(String.valueOf(exception.getValue()));
        PlatformApiErrorDetail detail = PlatformApiErrorDetail.of(
                List.of(detailText),
                List.of(new PlatformApiErrorDetail.FieldErrorDetail(exception.getName(), "类型不正确")),
                List.of("请检查数字 ID、布尔值、日期时间或枚举值是否按接口要求传入"));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(PlatformApiResponse.error(PlatformErrorCode.BAD_REQUEST, detailText, detail, traceId(request)));
    }

    /**
     * 处理 Spring 主动抛出的带 HTTP 状态异常。
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<PlatformApiResponse<PlatformApiErrorDetail>> handleResponseStatusException(
            ResponseStatusException exception,
            HttpServletRequest request) {
        PlatformErrorCode errorCode = errorCodeForHttpStatus(exception.getStatusCode().value());
        String message = safeMessage(exception.getReason());
        PlatformApiErrorDetail detail = PlatformApiErrorDetail.ofDetails(List.of(message));
        return ResponseEntity.status(exception.getStatusCode())
                .body(PlatformApiResponse.error(errorCode, message, detail, traceId(request)));
    }

    /**
     * 处理少量历史代码中直接抛出的 IllegalArgumentException。
     *
     * <p>新代码优先抛 {@link PlatformBusinessException}，但在逐步迁移期，直接 IllegalArgumentException
     * 仍然应该被视为请求参数错误，而不是系统 500。</p>
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<PlatformApiResponse<PlatformApiErrorDetail>> handleIllegalArgumentException(
            IllegalArgumentException exception,
            HttpServletRequest request) {
        PlatformApiErrorDetail detail = PlatformApiErrorDetail.ofDetails(List.of(safeMessage(exception.getMessage())));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(PlatformApiResponse.error(PlatformErrorCode.BAD_REQUEST, safeMessage(exception.getMessage()), detail, traceId(request)));
    }

    /**
     * 处理少量历史代码中直接抛出的 IllegalStateException。
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<PlatformApiResponse<PlatformApiErrorDetail>> handleIllegalStateException(
            IllegalStateException exception,
            HttpServletRequest request) {
        PlatformApiErrorDetail detail = PlatformApiErrorDetail.ofDetails(List.of(safeMessage(exception.getMessage())));
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(PlatformApiResponse.error(PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                        safeMessage(exception.getMessage()), detail, traceId(request)));
    }

    /**
     * 兜底处理未预期异常。
     *
     * <p>这里不能把 exception.getMessage() 原样返回给前端，因为未预期异常可能包含 SQL 片段、连接地址、
     * 表结构、内部类名或堆栈线索。对外只返回“data-sync 内部异常”和 traceId；真正排障看服务端日志。</p>
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<PlatformApiResponse<PlatformApiErrorDetail>> handleException(Exception exception,
                                                                                       HttpServletRequest request) {
        log.error("data-sync 模块发生未预期异常，traceId={}, path={}", traceId(request), request.getRequestURI(), exception);
        PlatformApiErrorDetail detail = PlatformApiErrorDetail.of(
                List.of("data-sync 内部异常，请携带 traceId 联系排障或查看服务端日志"),
                List.of(),
                List.of("如果这是创建任务流程，请先确认源端/目标端数据源可用，并查看 data-sync 与 datasource-management 日志"));
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(PlatformApiResponse.error(PlatformErrorCode.INTERNAL_ERROR,
                        "data-sync 内部异常", detail, traceId(request)));
    }

    private PlatformErrorCode errorCodeForHttpStatus(int status) {
        return switch (status) {
            case 400 -> PlatformErrorCode.BAD_REQUEST;
            case 401 -> PlatformErrorCode.UNAUTHORIZED;
            case 403 -> PlatformErrorCode.FORBIDDEN;
            case 404 -> PlatformErrorCode.NOT_FOUND;
            case 409 -> PlatformErrorCode.BUSINESS_STATE_CONFLICT;
            case 429 -> PlatformErrorCode.RATE_LIMITED;
            case 502 -> PlatformErrorCode.EXTERNAL_DEPENDENCY_FAILED;
            case 504 -> PlatformErrorCode.DEPENDENCY_TIMEOUT;
            default -> status >= 500 ? PlatformErrorCode.INTERNAL_ERROR : PlatformErrorCode.BAD_REQUEST;
        };
    }

    private String traceId(HttpServletRequest request) {
        return request.getHeader(PlatformContextHeaders.TRACE_ID);
    }

    private String safeMessage(String message) {
        if (message == null || message.isBlank()) {
            return "请求处理失败";
        }
        String normalized = message.replaceAll("[\\r\\n\\t]+", " ").trim();
        return normalized.length() <= 500 ? normalized : normalized.substring(0, 500) + "...";
    }
}
