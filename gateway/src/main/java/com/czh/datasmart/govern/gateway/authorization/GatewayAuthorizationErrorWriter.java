/**
 * @Author : Cui
 * @Date: 2026/05/24 02:39
 * @Description DataSmart Govern Backend - GatewayAuthorizationErrorWriter.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.gateway.authorization;

import com.czh.datasmart.govern.common.api.PlatformApiErrorDetail;
import com.czh.datasmart.govern.common.api.PlatformApiResponse;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 网关授权错误响应写出器。
 *
 * <p>授权过滤器的核心职责应该是“编排授权流程”：读取上下文、调用 permission-admin、处理缓存和决定放行或拒绝。
 * 如果把 JSON 序列化、错误码选择、Retry-After Header 写入等响应细节继续放在过滤器里，
 * 过滤器会快速膨胀成难以维护的大类，也不利于后续新增更多网关保护策略。
 *
 * <p>因此本类专门承担网关授权链路的错误响应输出：
 * 1. 普通权限拒绝返回平台统一 403；
 * 2. 内部服务端点角色或 token 拒绝返回 403；
 * 3. 内部服务端点限流返回 429，并补充 Retry-After，方便调用方做退避重试。
 */
@Component
@RequiredArgsConstructor
public class GatewayAuthorizationErrorWriter {

    /**
     * Jackson 序列化器。
     *
     * <p>网关是响应链路的最后一道门，如果序列化失败，仍然要返回一个最小 JSON，
     * 避免客户端收到空响应而无法定位 traceId 或错误类型。
     */
    private final ObjectMapper objectMapper;

    /**
     * 写出权限拒绝响应。
     *
     * @param response 当前网关响应对象。
     * @param traceId 本次请求追踪 ID，用于前端、日志和审计排障关联。
     * @param message 面向调用方的拒绝原因。
     */
    public Mono<Void> writeForbidden(ServerHttpResponse response, String traceId, String message) {
        response.setStatusCode(HttpStatus.FORBIDDEN);
        return writeError(response, traceId, PlatformErrorCode.FORBIDDEN, message);
    }

    /**
     * 写出请求合同错误响应。
     *
     * <p>网关授权链路里有一类错误并不是“当前身份没有权限”，而是“调用方传来的控制面参数格式不合法”。
     * 例如项目切换器传入的 {@code X-DataSmart-Project-Id=abc}，这种情况应该让前端修正请求，
     * 而不是展示无权限页面。因此单独提供 400 输出，避免把格式问题和权限问题混在一起。</p>
     *
     * @param response 当前网关响应对象。
     * @param traceId 本次请求追踪 ID。
     * @param message 面向调用方的合同错误说明。
     */
    public Mono<Void> writeBadRequest(ServerHttpResponse response, String traceId, String message) {
        response.setStatusCode(HttpStatus.BAD_REQUEST);
        return writeError(response, traceId, PlatformErrorCode.BAD_REQUEST, message);
    }

    /**
     * 写出内部服务端点保护拒绝响应。
     *
     * <p>内部服务端点保护发生在 permission-admin 远程判定之前。
     * 如果是角色或 token 不符合要求，返回 403；如果是本地限流，返回 429 并写入 Retry-After。
     * 这样调用方可以区分“身份不对”和“调用太频繁”，不会盲目重试 403。
     */
    public Mono<Void> writeGuardDenied(ServerHttpResponse response,
                                       String traceId,
                                       GatewayInternalServiceEndpointGuard.GuardDecision decision) {
        response.setStatusCode(decision.status());
        if (decision.status() == HttpStatus.TOO_MANY_REQUESTS && decision.retryAfterSeconds() > 0) {
            response.getHeaders().set(HttpHeaders.RETRY_AFTER, String.valueOf(decision.retryAfterSeconds()));
        }
        PlatformErrorCode errorCode = decision.status() == HttpStatus.TOO_MANY_REQUESTS
                ? PlatformErrorCode.RATE_LIMITED
                : PlatformErrorCode.FORBIDDEN;
        return writeError(response, traceId, errorCode, decision.reason());
    }

    /**
     * 写出网关层统一错误响应。
     *
     * <p>权限拒绝、内部端点保护和限流都应该使用平台统一响应体。
     * 这样前端、Python Runtime 或其他服务账号都可以按 `code/reason/traceId` 做统一处理。
     * 本轮额外把低敏结构化详情写入 data：
     * 1. `details` 解释本次拒绝的直接原因，例如项目上下文未授权；
     * 2. `suggestions` 给页面或实施人员一个可操作的检查清单；
     * 3. 顶层 `traceId` 仍然用于日志和审计串联，避免把堆栈、token 或下游内部信息暴露给浏览器。
     */
    private Mono<Void> writeError(ServerHttpResponse response,
                                  String traceId,
                                  PlatformErrorCode errorCode,
                                  String message) {
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        PlatformApiErrorDetail detail = buildDetail(errorCode, message);
        PlatformApiResponse<PlatformApiErrorDetail> body = PlatformApiResponse.error(errorCode, message, detail, traceId);
        byte[] bytes = serialize(body);
        DataBuffer buffer = response.bufferFactory().wrap(bytes);
        return response.writeWith(Mono.just(buffer));
    }

    /**
     * 为网关直接写出的错误补充结构化说明。
     *
     * <p>为什么这里不直接复用各业务服务的全局异常处理器？
     * 网关过滤器发生在请求转发之前，此时还没有进入下游 Spring MVC/WebFlux Controller，
     * 因此不会触发业务服务的 `@RestControllerAdvice`。如果这里仍然只返回 code/message，
     * 前端就会在“认证、授权、限流、内部端点保护”这些最常见入口错误上继续看到不友好的提示。</p>
     *
     * <p>这里的建议必须保持低敏：只能说明检查登录态、项目授权、角色策略、请求参数格式和重试窗口，
     * 不能回显 JWT、权限矩阵完整内容、下游服务地址、内部异常堆栈或用户提交的敏感 payload。</p>
     */
    private PlatformApiErrorDetail buildDetail(PlatformErrorCode errorCode, String message) {
        String safeMessage = message == null || message.isBlank()
                ? errorCode.getDefaultMessage()
                : message.trim();
        return switch (errorCode) {
            case BAD_REQUEST -> PlatformApiErrorDetail.of(
                    List.of(safeMessage),
                    List.of(),
                    List.of(
                            "请检查请求参数或请求头格式是否符合接口合同，例如项目 ID 必须是数字。",
                            "如果页面已经不再展示工作空间，请确认浏览器缓存、旧链接或旧请求状态没有继续携带 workspaceId。"
                    )
            );
            case RATE_LIMITED -> PlatformApiErrorDetail.of(
                    List.of(safeMessage),
                    List.of(),
                    List.of(
                            "请稍后重试，或降低自动刷新、批量操作、Agent 工具调用的频率。",
                            "如果这是后台任务或执行器回调，请检查是否存在重试风暴、循环调用或并发配置过高。"
                    )
            );
            case FORBIDDEN -> PlatformApiErrorDetail.of(
                    List.of(safeMessage),
                    List.of(),
                    List.of(
                            "请确认当前账号已经登录，并且属于页面当前选中的项目。",
                            "请确认角色具备该接口的路由权限，并且 permission-admin 中存在对应资源类型的数据范围策略。",
                            "如果刚调整过项目成员或权限策略，请刷新页面、重新登录，或等待网关权限缓存失效后重试。"
                    )
            );
            default -> PlatformApiErrorDetail.of(
                    List.of(safeMessage),
                    List.of(),
                    List.of("请记录 traceId 并联系管理员查看 gateway 与 permission-admin 日志。")
            );
        };
    }

    /**
     * 序列化响应体。
     */
    private byte[] serialize(PlatformApiResponse<?> body) {
        try {
            return objectMapper.writeValueAsBytes(body);
        } catch (JsonProcessingException exception) {
            return "{\"code\":20002,\"reason\":\"FORBIDDEN\",\"message\":\"forbidden\"}"
                    .getBytes(StandardCharsets.UTF_8);
        }
    }
}
