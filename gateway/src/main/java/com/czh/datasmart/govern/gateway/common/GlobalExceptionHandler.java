package com.czh.datasmart.govern.gateway.common;

import com.czh.datasmart.govern.common.api.PlatformApiErrorDescriptor;
import com.czh.datasmart.govern.common.api.PlatformApiErrorDetail;
import com.czh.datasmart.govern.common.api.PlatformApiErrorDetailFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * @Author : Cui
 * @Date: 2026/4/18 22:20
 * @Description DataSmart Govern Backend - GlobalExceptionHandler.java
 * @Version:1.0.0
 *
 * 网关模块全局异常处理器。
 *
 * <p>gateway 是用户请求进入平台的第一层。入口层错误如果只返回一个 404/500/503，
 * 前端很难区分是登录态失效、路由不存在、下游服务未启动、权限被拒绝，还是网关自身异常。</p>
 *
 * <p>不过 gateway 也有特殊安全边界：Spring Cloud Gateway 的异常 reason 里可能包含
 * 下游服务名、服务发现细节、内部路由规则或实例地址。因此本处理器不会直接把 reason 回传给前端，
 * 而是保留 HTTP 状态，返回低敏 details 和排查 suggestions；完整异常仍进入服务端日志。</p>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 保留框架已经判定的 HTTP 状态码。
     *
     * <p>Spring Cloud Gateway 在服务发现找不到实例、下游暂时不可用或路由资源不存在时，
     * 会抛出 {@link ResponseStatusException} 的子类，并把 404、503 等状态写入异常。
     * 这些异常属于“网关已知道如何分类的基础设施结果”，不能再被兜底处理器统一改写成 500：
     * 500 表示网关自身发生未知故障，而 503 表示当前没有健康下游实例，两者的告警、重试、
     * 熔断和运维处理方式完全不同。</p>
     *
     * <p>响应正文不直接回传异常 reason。这样既能让调用方正确识别可重试状态，
     * 又不会把内部服务名、实例地址、路由规则或异常细节暴露给外部请求方。</p>
     *
     * @param exception Spring Web 或 Spring Cloud Gateway 已经附带状态码的异常
     * @return 保留原始 HTTP 状态码、使用低敏通用消息和结构化排查建议的统一错误响应
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiResponse<PlatformApiErrorDetail>> handleResponseStatusException(
            ResponseStatusException exception) {
        HttpStatusCode status = exception.getStatusCode();
        String safeMessage = resolveSafeMessage(status);
        log.warn("gateway 请求按框架状态码结束，status={}, exceptionType={}",
                status.value(), exception.getClass().getSimpleName());
        PlatformApiErrorDescriptor descriptor = PlatformApiErrorDetailFactory.fromHttpStatus(
                status.value(),
                safeMessage,
                List.of("gateway 请求未能完成，HTTP 状态为 " + status.value() + "：" + safeMessage),
                suggestionsForGatewayStatus(status.value())
        );
        return toResponse(descriptor);
    }

    /**
     * 处理入口层主动抛出的非法参数异常。
     *
     * <p>例如项目 Header 格式非法、认证上下文缺失、路由管理接口参数不合法等。
     * 这些错误应直接返回 400，并把可修复原因放入 details。</p>
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<PlatformApiErrorDetail>> handleIllegalArgumentException(
            IllegalArgumentException exception) {
        return toResponse(PlatformApiErrorDetailFactory.fromIllegalArgumentException(exception));
    }

    /**
     * 处理入口层状态冲突异常。
     *
     * <p>例如授权缓存状态、会话状态或 gateway 内部控制面状态不允许当前操作。</p>
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiResponse<PlatformApiErrorDetail>> handleIllegalStateException(
            IllegalStateException exception) {
        return toResponse(PlatformApiErrorDetailFactory.fromIllegalStateException(exception));
    }

    /**
     * 兜底处理没有明确 HTTP 语义的未知异常。
     *
     * <p>只有未被框架和业务异常处理器识别的错误才进入这里，并统一返回 500。
     * 日志保留完整异常栈用于服务端排障，外部响应不暴露异常信息。</p>
     *
     * @param exception 未分类异常
     * @return HTTP 500 低敏错误响应
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<PlatformApiErrorDetail>> handleException(Exception exception) {
        log.error("gateway 模块发生未预期异常", exception);
        return toResponse(PlatformApiErrorDetailFactory.internalError(
                "gateway",
                "请检查 gateway 路由配置、认证状态、permission-admin 授权响应、Nacos 服务发现和下游服务健康状态。"
        ));
    }

    /**
     * 把框架状态码转换为稳定、低敏的外部消息。
     *
     * <p>{@link HttpStatusCode} 也可能承载非标准状态码，因此不能假设一定能转换成
     * {@link HttpStatus}。标准状态码使用 Spring 的 reason phrase；非标准状态码只返回
     * {@code request failed}，避免拼接异常 reason 导致内部信息泄露。</p>
     *
     * @param status 框架判定的 HTTP 状态码
     * @return 不包含内部异常详情的消息
     */
    private String resolveSafeMessage(HttpStatusCode status) {
        HttpStatus standardStatus = HttpStatus.resolve(status.value());
        if (standardStatus == null) {
            return "request failed";
        }
        return standardStatus.getReasonPhrase().toLowerCase();
    }

    /**
     * 根据 gateway 常见状态返回低敏排查建议。
     *
     * @param status HTTP 状态码
     * @return 面向前端弹窗展示的排查建议
     */
    private List<String> suggestionsForGatewayStatus(int status) {
        return switch (status) {
            case 401 -> List.of("请重新登录，确认 access token 未过期，并确保请求经过 gateway 统一入口。");
            case 403 -> List.of("请确认当前账号已加入目标项目，且 permission-admin 已授予访问该路由的权限。");
            case 404 -> List.of("请确认前端请求路径与 gateway 路由前缀一致，目标微服务已注册对应接口。");
            case 503 -> List.of("请确认下游微服务已启动、已注册到 Nacos，且 gateway 的 lb:// 路由可以发现健康实例。");
            case 504 -> List.of("请检查下游服务响应时间、网络连接、超时配置和当前系统负载。");
            default -> List.of("请携带请求路径、操作时间和错误状态联系排障，服务端日志会保留完整异常信息。");
        };
    }

    /**
     * 将平台错误描述适配为 gateway 历史响应 envelope。
     */
    private ResponseEntity<ApiResponse<PlatformApiErrorDetail>> toResponse(PlatformApiErrorDescriptor descriptor) {
        return ResponseEntity.status(descriptor.httpStatus())
                .body(ApiResponse.error(descriptor.httpStatus(), descriptor.message(), descriptor.detail()));
    }
}
