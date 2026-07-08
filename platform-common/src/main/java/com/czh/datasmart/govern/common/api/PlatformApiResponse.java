/**
 * @Author : Cui
 * @Date: 2026/04/25 22:30
 * @Description DataSmart Govern Backend - PlatformApiResponse.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.common.api;

import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 平台级统一 API 响应体。
 *
 * 这个类是“全平台契约 v1”的核心对象之一，目标是让 gateway、task-management、datasource-management、
 * data-quality、observability 以及未来的 permission-admin、data-asset、compliance-masking 等模块使用同一种
 * 响应语义，而不是每个微服务各自复制一份 ApiResponse。
 *
 * 为什么不只依赖 HTTP 状态码：
 * 1. HTTP 状态码表达协议层结果，例如 200、400、401、500；
 * 2. code 表达业务层或平台层错误语义，例如参数错误、权限不足、租户越权、任务状态冲突；
 * 3. traceId 让前端、网关、后端日志、审计事件、可观测平台可以串起同一次请求；
 * 4. timestamp 让调用方即使没有访问服务端日志，也能知道响应生成的大致时间。
 *
 * 后续迁移策略：
 * 当前各模块已有自己的 ApiResponse。不要一次性大重构。
 * 后续每改一个模块的 Controller 或 GlobalExceptionHandler 时，再逐步切换到 PlatformApiResponse。
 *
 * @param <T> 真实业务载荷类型。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PlatformApiResponse<T> {

    /**
     * 平台统一业务码。
     * 约定 0 表示成功，非 0 表示失败；失败码建议来自 PlatformErrorCode，避免每个模块随意定义。
     */
    private Integer code;

    /**
     * 机器可读的错误标识或成功标识。
     * 例如 SUCCESS、VALIDATION_ERROR、FORBIDDEN、TASK_STATE_CONFLICT。
     */
    private String reason;

    /**
     * 人类可读说明。
     * 面向前端、调用方和排障人员，建议使用清晰的中文或标准英文，不要只返回异常类名。
     */
    private String message;

    /**
     * 真实业务数据。
     * 失败时通常为空，成功时承载列表、详情、操作结果等对象。
     */
    private T data;

    /**
     * 链路追踪 ID。
     * gateway 应负责生成或透传；领域服务只负责继续向日志、审计、下游调用传递。
     */
    private String traceId;

    /**
     * 响应生成时间。
     * 使用 LocalDateTime 是为了贴合当前项目已有 Java 时间类型；如后续跨时区要求更强，可升级为 OffsetDateTime。
     */
    private LocalDateTime timestamp;

    /**
     * 成功响应工厂方法。
     *
     * @param data 业务数据。
     * @param traceId 当前请求链路 ID。
     * @return 标准成功响应。
     */
    public static <T> PlatformApiResponse<T> success(T data, String traceId) {
        return new PlatformApiResponse<>(0, "SUCCESS", "success", data, traceId, LocalDateTime.now());
    }

    /**
     * 带自定义成功消息的工厂方法。
     * 动作类接口，例如创建、审批、重试、恢复，通常比查询接口更需要明确的成功说明。
     */
    public static <T> PlatformApiResponse<T> success(String message, T data, String traceId) {
        return new PlatformApiResponse<>(0, "SUCCESS", message, data, traceId, LocalDateTime.now());
    }

    /**
     * 失败响应工厂方法。
     *
     * @param errorCode 平台统一错误码。
     * @param message 面向调用方的错误说明；为空时使用错误码默认说明。
     * @param traceId 当前请求链路 ID。
     * @return 标准失败响应。
     */
    public static <T> PlatformApiResponse<T> error(PlatformErrorCode errorCode, String message, String traceId) {
        String resolvedMessage = message == null || message.trim().isEmpty() ? errorCode.getDefaultMessage() : message;
        return new PlatformApiResponse<>(errorCode.getCode(), errorCode.name(), resolvedMessage, null, traceId, LocalDateTime.now());
    }

    /**
     * 带结构化错误详情的失败响应工厂方法。
     *
     * <p>历史 error(...) 方法会把 data 置空，适合只有一句提示的场景。
     * 但创建任务、数据源连接、元数据发现、字段映射、SQL 检查等交互式接口，经常需要把多条字段错误、
     * 阻断项和修复建议返回给前端弹窗或表单定位。如果仍然只返回 message，用户会看到“HTTP 500/400”或一段很长的拼接文本。</p>
     *
     * <p>该方法保持响应 envelope 不变，只允许失败时把低敏详情放入 data。
     * 这样前端可以优先展示 message，再展开 data.details / data.fieldErrors / data.suggestions；
     * 同时旧前端如果只读取 message，也不会因为多了 data 字段而解析失败。</p>
     *
     * @param errorCode 平台统一错误码
     * @param message 面向用户的摘要说明
     * @param data 结构化错误详情，通常使用 {@link PlatformApiErrorDetail}
     * @param traceId 当前请求链路 ID
     * @return 标准失败响应
     */
    public static <T> PlatformApiResponse<T> error(PlatformErrorCode errorCode,
                                                   String message,
                                                   T data,
                                                   String traceId) {
        String resolvedMessage = message == null || message.trim().isEmpty() ? errorCode.getDefaultMessage() : message;
        return new PlatformApiResponse<>(errorCode.getCode(), errorCode.name(), resolvedMessage, data, traceId, LocalDateTime.now());
    }
}
