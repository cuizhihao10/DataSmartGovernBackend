/**
 * @Author : Cui
 * @Date: 2026/07/08 19:10
 * @Description DataSmart Govern Backend - PlatformApiErrorDetailFactory.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.common.api;

import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * 平台结构化错误详情构建器。
 *
 * <p>这个类是“后端不要只返回错误码”的公共落点。真实产品里的错误需要同时服务三类人：</p>
 * <p>1. 普通用户：要知道表单哪一项没有填、哪一步配置不完整、下一步该怎么修；</p>
 * <p>2. 运营/实施人员：要知道是数据源不可用、任务状态冲突、权限不足，还是下游依赖失败；</p>
 * <p>3. 开发/运维人员：要能通过 traceId 和服务端日志定位堆栈，但不能把堆栈直接暴露给页面。</p>
 *
 * <p>因此这里采用“低敏结构化详情”的策略：</p>
 * <p>1. 可预期错误，例如参数校验失败、资源不存在、业务状态冲突，会返回具体可修复原因；</p>
 * <p>2. 框架错误，例如 JSON 解析失败、参数类型转换失败，会返回输入格式修复建议；</p>
 * <p>3. 未预期异常只返回模块级摘要和排障建议，完整异常栈只进入服务端日志。</p>
 *
 * <p>注意：该类只负责从异常中提取“可展示事实”，不负责写日志、读取请求头或拼装具体响应 envelope。
 * 各微服务仍然在自己的全局异常处理器中决定如何取 traceId、如何记录日志、如何返回
 * {@link PlatformApiResponse} 或历史 {@code ApiResponse}。</p>
 */
public final class PlatformApiErrorDetailFactory {

    /**
     * 单条错误消息的最大外显长度。
     *
     * <p>业务异常 message 可能包含下游返回正文、SQL 片段、JDBC 驱动提示或很长的 JSON 路径。
     * 统一截断可以避免弹窗被超长文本撑爆，同时降低敏感信息被完整回显的概率。</p>
     */
    private static final int MAX_MESSAGE_LENGTH = 500;

    private PlatformApiErrorDetailFactory() {
    }

    /**
     * 构建平台业务异常的响应描述。
     *
     * <p>{@link PlatformBusinessException} 通常由 service 层主动抛出，已经携带了明确业务语义。
     * 例如无权限、资源不存在、状态冲突、外部依赖失败等。这里尊重异常中的错误码与 HTTP 状态，
     * 只负责把 message 清洗为低敏文本，并补充通用排查建议。</p>
     *
     * @param exception 平台业务异常
     * @return 结构化错误描述
     */
    public static PlatformApiErrorDescriptor fromBusinessException(PlatformBusinessException exception) {
        PlatformErrorCode errorCode = exception.getErrorCode();
        String message = safeMessage(exception.getMessage(), errorCode.getDefaultMessage());
        return PlatformApiErrorDescriptor.of(
                errorCode,
                message,
                PlatformApiErrorDetail.of(
                        List.of(message),
                        List.of(),
                        suggestionsFor(errorCode)
                )
        );
    }

    /**
     * 构建资源不存在异常的响应描述。
     *
     * @param exception Java 标准不存在异常
     * @return HTTP 404 对应的结构化错误描述
     */
    public static PlatformApiErrorDescriptor fromNoSuchElementException(NoSuchElementException exception) {
        String message = safeMessage(exception.getMessage(), PlatformErrorCode.NOT_FOUND.getDefaultMessage());
        return PlatformApiErrorDescriptor.of(
                PlatformErrorCode.NOT_FOUND,
                message,
                PlatformApiErrorDetail.of(
                        List.of(message),
                        List.of(),
                        List.of("请刷新列表后重试；如果资源已被删除、下线或归档，请从回收站、历史记录或审计入口确认。")
                )
        );
    }

    /**
     * 构建请求体 Bean Validation 失败的响应描述。
     *
     * <p>该异常来自 {@code @RequestBody @Valid}，常见于创建数据源、创建同步任务、提交 Agent 工具动作等接口。
     * 这里会收集全部字段错误，而不是只返回第一条，这样前端弹窗可以一次性展示完整修复清单。</p>
     *
     * @param exception 请求体字段校验异常
     * @return HTTP 400 对应的结构化错误描述
     */
    public static PlatformApiErrorDescriptor fromMethodArgumentNotValid(MethodArgumentNotValidException exception) {
        List<PlatformApiErrorDetail.FieldErrorDetail> fieldErrors = fieldErrors(exception.getBindingResult().getFieldErrors());
        List<String> details = new ArrayList<>(fieldErrors.stream()
                .map(PlatformApiErrorDetailFactory::formatFieldError)
                .toList());
        details.addAll(globalErrors(exception.getBindingResult().getGlobalErrors()));
        return validationDescriptor(
                "请求参数校验失败，请根据错误明细修正后重试",
                details,
                fieldErrors,
                List.of("请检查必填项、字段长度、枚举值、数字范围和对象嵌套结构。")
        );
    }

    /**
     * 构建查询参数、表单参数或路径参数绑定失败的响应描述。
     *
     * @param exception 参数绑定异常
     * @return HTTP 400 对应的结构化错误描述
     */
    public static PlatformApiErrorDescriptor fromBindException(BindException exception) {
        List<PlatformApiErrorDetail.FieldErrorDetail> fieldErrors = fieldErrors(exception.getFieldErrors());
        List<String> details = new ArrayList<>(fieldErrors.stream()
                .map(PlatformApiErrorDetailFactory::formatFieldError)
                .toList());
        details.addAll(globalErrors(exception.getGlobalErrors()));
        return validationDescriptor(
                "请求参数绑定失败，请检查查询参数、路径参数或表单参数",
                details,
                fieldErrors,
                List.of("请确认 URL 参数类型、分页参数范围、时间格式和枚举值与接口要求一致。")
        );
    }

    /**
     * 构建方法级参数约束失败的响应描述。
     *
     * <p>该异常通常来自 {@code @RequestParam}、{@code @PathVariable} 或 controller 方法参数上的
     * {@code @Min/@NotBlank/@Pattern} 等约束。由于它不一定有 Spring BindingResult，
     * 这里从 Jakarta Validation 的 violation path 中提取字段名。</p>
     *
     * @param exception 方法参数约束异常
     * @return HTTP 400 对应的结构化错误描述
     */
    public static PlatformApiErrorDescriptor fromConstraintViolation(ConstraintViolationException exception) {
        List<PlatformApiErrorDetail.FieldErrorDetail> fieldErrors = exception.getConstraintViolations().stream()
                .sorted(Comparator.comparing(violation -> String.valueOf(violation.getPropertyPath())))
                .map(PlatformApiErrorDetailFactory::fieldError)
                .toList();
        List<String> details = fieldErrors.stream()
                .map(PlatformApiErrorDetailFactory::formatFieldError)
                .toList();
        return validationDescriptor(
                "请求参数约束校验失败，请检查接口参数",
                details,
                fieldErrors,
                List.of("请确认请求参数满足接口约束，例如非空、最小值、最大值、格式正则和枚举范围。")
        );
    }

    /**
     * 构建 JSON 请求体解析失败的响应描述。
     *
     * <p>这类错误通常发生在进入 Controller 之前，例如 JSON 少了引号、数组/对象结构传错、
     * 字段类型不匹配。mostSpecificCause 可能包含 Jackson 的字段路径，适合低敏截断后展示。</p>
     *
     * @param exception JSON 解析异常
     * @return HTTP 400 对应的结构化错误描述
     */
    public static PlatformApiErrorDescriptor fromHttpMessageNotReadable(HttpMessageNotReadableException exception) {
        String cause = exception.getMostSpecificCause() == null
                ? ""
                : safeMessage(exception.getMostSpecificCause().getMessage(), "");
        List<String> details = cause.isBlank()
                ? List.of("请求体 JSON 格式不正确，请检查字段类型、括号、逗号和引号。")
                : List.of("请求体 JSON 格式不正确。", cause);
        return new PlatformApiErrorDescriptor(
                PlatformErrorCode.BAD_REQUEST,
                HttpStatus.BAD_REQUEST.value(),
                "请求体 JSON 格式不正确，请检查字段类型、括号、逗号和引号",
                PlatformApiErrorDetail.of(
                        details,
                        List.of(),
                        List.of("请确认请求体是合法 JSON，并且字段类型与接口文档一致。")
                )
        );
    }

    /**
     * 构建缺少必填请求参数的响应描述。
     *
     * <p>这里故意只接收参数名，而不直接依赖 {@code MissingServletRequestParameterException}。
     * 原因是 gateway 使用 WebFlux/Reactive 栈，公共模块如果直接引用 Servlet 异常类，
     * 就会把 {@code jakarta.servlet} 编译依赖带到平台公共包里。参数名已经足够构造低敏错误详情，
     * 具体异常类型由各 MVC 微服务的全局异常处理器在本地适配。</p>
     *
     * @param parameterName 缺失的参数名
     * @return HTTP 400 对应的结构化错误描述
     */
    public static PlatformApiErrorDescriptor fromMissingRequestParameter(String parameterName) {
        String safeParameterName = safeMessage(parameterName, "unknown");
        String message = "缺少必填请求参数: " + safeParameterName;
        return new PlatformApiErrorDescriptor(
                PlatformErrorCode.VALIDATION_ERROR,
                HttpStatus.BAD_REQUEST.value(),
                message,
                PlatformApiErrorDetail.of(
                        List.of(message),
                        List.of(new PlatformApiErrorDetail.FieldErrorDetail(safeParameterName, "不能为空")),
                        List.of("请补齐必填查询参数后重试。")
                )
        );
    }

    /**
     * 构建参数类型转换失败的响应描述。
     *
     * <p>例如把字符串传给数字 ID、把非法文本传给枚举、把错误日期传给时间字段。
     * 这类错误对前端非常重要，因为它通常说明页面组件值或路由参数组装不正确。</p>
     *
     * @param exception 参数类型不匹配异常
     * @return HTTP 400 对应的结构化错误描述
     */
    public static PlatformApiErrorDescriptor fromMethodArgumentTypeMismatch(
            MethodArgumentTypeMismatchException exception) {
        String value = safeMessage(String.valueOf(exception.getValue()), "null");
        String field = safeMessage(exception.getName(), "unknown");
        String requiredType = exception.getRequiredType() == null
                ? "目标类型"
                : exception.getRequiredType().getSimpleName();
        String message = "参数 " + field + " 类型不正确，当前值为 " + value + "，期望类型为 " + requiredType;
        return new PlatformApiErrorDescriptor(
                PlatformErrorCode.BAD_REQUEST,
                HttpStatus.BAD_REQUEST.value(),
                message,
                PlatformApiErrorDetail.of(
                        List.of(message),
                        List.of(new PlatformApiErrorDetail.FieldErrorDetail(field, "类型不正确，期望 " + requiredType)),
                        List.of("请检查数字 ID、布尔值、日期时间或枚举值是否按接口要求传入。")
                )
        );
    }

    /**
     * 构建 Spring 已经携带 HTTP 状态的异常响应描述。
     *
     * <p>该方法适合普通业务服务。gateway 这类入口服务如果担心 reason 包含内部服务名，
     * 可以使用 {@link #fromHttpStatus(int, String, List, List)} 自行传入低敏 message。</p>
     *
     * @param exception 携带 HTTP 状态的 Spring 异常
     * @return 保留 HTTP 状态的结构化错误描述
     */
    public static PlatformApiErrorDescriptor fromResponseStatusException(ResponseStatusException exception) {
        int status = exception.getStatusCode().value();
        PlatformErrorCode errorCode = errorCodeForHttpStatus(status);
        String message = safeMessage(exception.getReason(), errorCode.getDefaultMessage());
        return new PlatformApiErrorDescriptor(
                errorCode,
                status,
                message,
                PlatformApiErrorDetail.of(
                        List.of(message),
                        List.of(),
                        suggestionsFor(errorCode)
                )
        );
    }

    /**
     * 根据 HTTP 状态和外部安全消息构建响应描述。
     *
     * <p>gateway、认证入口、反向代理等边界层经常需要保留 HTTP 状态但隐藏内部 reason。
     * 该方法允许模块传入已经脱敏的 message、details 和 suggestions。</p>
     *
     * @param httpStatus HTTP 状态码
     * @param message 顶层低敏错误摘要
     * @param details 可展示错误明细
     * @param suggestions 修复建议
     * @return 结构化错误描述
     */
    public static PlatformApiErrorDescriptor fromHttpStatus(int httpStatus,
                                                            String message,
                                                            List<String> details,
                                                            List<String> suggestions) {
        PlatformErrorCode errorCode = errorCodeForHttpStatus(httpStatus);
        return new PlatformApiErrorDescriptor(
                errorCode,
                httpStatus,
                safeMessage(message, errorCode.getDefaultMessage()),
                PlatformApiErrorDetail.of(
                        details == null || details.isEmpty() ? List.of(errorCode.getDefaultMessage()) : sanitize(details),
                        List.of(),
                        suggestions == null || suggestions.isEmpty() ? suggestionsFor(errorCode) : sanitize(suggestions)
                )
        );
    }

    /**
     * 构建非法参数异常的响应描述。
     *
     * @param exception 非法参数异常
     * @return HTTP 400 对应的结构化错误描述
     */
    public static PlatformApiErrorDescriptor fromIllegalArgumentException(IllegalArgumentException exception) {
        String message = safeMessage(exception.getMessage(), PlatformErrorCode.BAD_REQUEST.getDefaultMessage());
        return PlatformApiErrorDescriptor.of(
                PlatformErrorCode.BAD_REQUEST,
                message,
                PlatformApiErrorDetail.of(
                        List.of(message),
                        List.of(),
                        List.of("请检查本次操作的请求参数、任务配置、对象映射或 SQL 设置是否满足业务规则。")
                )
        );
    }

    /**
     * 构建业务状态冲突异常的响应描述。
     *
     * @param exception 非法状态异常
     * @return HTTP 409 对应的结构化错误描述
     */
    public static PlatformApiErrorDescriptor fromIllegalStateException(IllegalStateException exception) {
        String message = safeMessage(exception.getMessage(), PlatformErrorCode.BUSINESS_STATE_CONFLICT.getDefaultMessage());
        return PlatformApiErrorDescriptor.of(
                PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                message,
                PlatformApiErrorDetail.of(
                        List.of(message),
                        List.of(),
                        List.of("请刷新资源详情确认当前状态；如果任务正在运行、已下线、已归档或等待调度，请按状态允许的动作继续。")
                )
        );
    }

    /**
     * 构建未预期异常的低敏响应描述。
     *
     * <p>该方法不会读取原始异常 message。未知异常可能包含 SQL、连接串、密钥、内部路径或堆栈片段，
     * 直接回显给前端会造成安全与合规风险。模块全局异常处理器应先记录完整 error 日志，
     * 然后使用本方法返回可展示的模块级摘要。</p>
     *
     * @param moduleDisplayName 模块显示名，例如 task-management、data-sync、agent-runtime
     * @param troubleshootingHint 面向用户或实施人员的下一步排查建议
     * @return HTTP 500 对应的结构化错误描述
     */
    public static PlatformApiErrorDescriptor internalError(String moduleDisplayName, String troubleshootingHint) {
        String module = safeMessage(moduleDisplayName, "当前服务");
        String message = module + " 内部异常";
        return new PlatformApiErrorDescriptor(
                PlatformErrorCode.INTERNAL_ERROR,
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                message,
                PlatformApiErrorDetail.of(
                        List.of(message + "，请携带 traceId、操作时间和页面路径联系排障，或查看服务端日志。"),
                        List.of(),
                        List.of(safeMessage(troubleshootingHint, "请查看服务端日志、链路追踪和对应模块健康状态。"))
                )
        );
    }

    /**
     * 根据 HTTP 状态码映射平台错误码。
     *
     * @param status HTTP 状态码
     * @return 平台统一错误码
     */
    public static PlatformErrorCode errorCodeForHttpStatus(int status) {
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

    /**
     * 清洗可展示消息。
     *
     * @param message 原始消息
     * @return 清洗后的消息
     */
    public static String safeMessage(String message) {
        return safeMessage(message, "请求处理失败");
    }

    /**
     * 清洗可展示消息，并在原始消息为空时使用兜底文本。
     *
     * @param message 原始消息
     * @param fallback 兜底文本
     * @return 清洗后的消息
     */
    public static String safeMessage(String message, String fallback) {
        String resolved = message == null || message.isBlank() ? fallback : message;
        String normalized = resolved == null ? "请求处理失败" : resolved.replaceAll("[\\r\\n\\t]+", " ").trim();
        return normalized.length() <= MAX_MESSAGE_LENGTH
                ? normalized
                : normalized.substring(0, MAX_MESSAGE_LENGTH) + "...";
    }

    private static PlatformApiErrorDescriptor validationDescriptor(String message,
                                                                  List<String> details,
                                                                  List<PlatformApiErrorDetail.FieldErrorDetail> fieldErrors,
                                                                  List<String> suggestions) {
        List<String> resolvedDetails = details == null || details.isEmpty()
                ? List.of(message)
                : sanitize(details);
        return new PlatformApiErrorDescriptor(
                PlatformErrorCode.VALIDATION_ERROR,
                HttpStatus.BAD_REQUEST.value(),
                message,
                PlatformApiErrorDetail.of(resolvedDetails, fieldErrors, suggestions)
        );
    }

    private static List<PlatformApiErrorDetail.FieldErrorDetail> fieldErrors(List<FieldError> errors) {
        return errors.stream()
                .sorted(Comparator.comparing(FieldError::getField))
                .map(error -> new PlatformApiErrorDetail.FieldErrorDetail(
                        safeMessage(error.getField(), "unknown"),
                        safeMessage(error.getDefaultMessage(), "字段值不合法")
                ))
                .toList();
    }

    private static PlatformApiErrorDetail.FieldErrorDetail fieldError(ConstraintViolation<?> violation) {
        return new PlatformApiErrorDetail.FieldErrorDetail(
                safeMessage(String.valueOf(violation.getPropertyPath()), "unknown"),
                safeMessage(violation.getMessage(), "参数不满足约束")
        );
    }

    private static List<String> globalErrors(List<ObjectError> errors) {
        return errors.stream()
                .map(error -> safeMessage(error.getDefaultMessage(), "请求对象不满足约束"))
                .toList();
    }

    private static String formatFieldError(PlatformApiErrorDetail.FieldErrorDetail error) {
        return error.field() + " " + error.message();
    }

    private static List<String> sanitize(List<String> values) {
        return values.stream()
                .map(PlatformApiErrorDetailFactory::safeMessage)
                .filter(value -> !value.isBlank())
                .toList();
    }

    private static List<String> suggestionsFor(PlatformErrorCode errorCode) {
        return switch (errorCode) {
            case VALIDATION_ERROR, BAD_REQUEST -> List.of("请根据错误明细修正请求参数后重试。");
            case UNAUTHORIZED -> List.of("请重新登录，确认访问令牌未过期，并检查请求是否经过 gateway 入口。");
            case FORBIDDEN, TENANT_SCOPE_DENIED -> List.of("请确认当前账号已加入目标项目，并具备执行该操作的角色或权限策略。");
            case NOT_FOUND -> List.of("请刷新列表或详情页，确认资源是否已删除、归档、下线或切换到了其他项目。");
            case BUSINESS_STATE_CONFLICT, DUPLICATE_OPERATION -> List.of("请刷新资源状态，确认当前状态是否允许执行该动作，避免重复提交或并发操作。");
            case RATE_LIMITED -> List.of("请稍后重试；如持续出现，请联系管理员调整限流、配额或并发配置。");
            case EXTERNAL_DEPENDENCY_FAILED, DEPENDENCY_TIMEOUT -> List.of("请检查下游服务、数据源连接、网络、Nacos 注册状态或任务执行器健康状态。");
            case INTERNAL_ERROR -> List.of("请携带 traceId、操作时间和页面路径联系排障，服务端日志会保留完整异常堆栈。");
            case SUCCESS -> List.of();
            case SENSITIVE_DATA_REQUIRES_APPROVAL -> List.of("请提交审批、切换具备更高权限的角色，或降低本次操作涉及的敏感数据范围。");
        };
    }
}
