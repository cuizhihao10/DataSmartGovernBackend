/**
 * @Author : Cui
 * @Date: 2026/07/08 19:10
 * @Description DataSmart Govern Backend - PlatformApiErrorDescriptor.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.common.api;

import com.czh.datasmart.govern.common.error.PlatformErrorCode;

/**
 * 平台错误响应描述。
 *
 * <p>{@link PlatformApiErrorDetail} 只描述“要给前端展示哪些详细信息”，
 * 但一个完整的异常响应还需要同时确定三件事：</p>
 * <p>1. {@code errorCode}：平台统一错误码，便于前端、网关、日志和告警使用同一种错误语言；</p>
 * <p>2. {@code httpStatus}：HTTP 协议层状态，便于浏览器、网关、重试器、负载均衡器理解失败类型；</p>
 * <p>3. {@code message}：顶层摘要，适合在弹窗标题或通知栏中直接展示。</p>
 *
 * <p>为什么单独抽出这个 record：</p>
 * <p>如果每个微服务的 {@code GlobalExceptionHandler} 都自己判断异常类型、拼 message、
 * 决定 HTTP 状态和构造 details，就会很快出现“同一种错误在不同模块提示风格不一样”的问题。
 * 这个对象把公共判断结果一次性封装起来，让各模块只负责把它塞进自己当前的响应 envelope，
 * 从而兼顾统一体验与渐进式改造。</p>
 *
 * @param errorCode 平台统一错误码
 * @param httpStatus 建议返回的 HTTP 状态码
 * @param message 顶层人类可读错误摘要
 * @param detail 可展开展示的结构化错误详情
 */
public record PlatformApiErrorDescriptor(
        PlatformErrorCode errorCode,
        int httpStatus,
        String message,
        PlatformApiErrorDetail detail
) {

    /**
     * 使用错误码默认 HTTP 状态构建描述。
     *
     * @param errorCode 平台统一错误码
     * @param message 顶层错误摘要
     * @param detail 结构化错误详情
     * @return 平台错误响应描述
     */
    public static PlatformApiErrorDescriptor of(PlatformErrorCode errorCode,
                                                String message,
                                                PlatformApiErrorDetail detail) {
        return new PlatformApiErrorDescriptor(errorCode, errorCode.getHttpStatus(), message, detail);
    }
}
