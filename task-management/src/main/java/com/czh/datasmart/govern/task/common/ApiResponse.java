package com.czh.datasmart.govern.task.common;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @Author : Cui
 * @Date: 2026/4/18 22:10
 * @Description DataSmart Govern Backend - ApiResponse.java
 * @Version:1.0.0
 *
 * 统一 API 响应体。
 * 在微服务项目中，接口是否统一返回固定结构，直接影响三个层面：
 * 1. 网关是否容易做统一包装、透传和异常翻译。
 * 2. 前端或其他服务是否容易写通用调用封装。
 * 3. 后续做日志采集、错误统计、链路排查时，是否容易提取稳定字段。
 *
 * 当前采用最常见也最容易学习的三段式结构：
 * - code：业务结果码，0 表示成功，非 0 表示失败。
 * - message：给调用方看的文字说明。
 * - data：真正的业务载荷。
 *
 * 这里没有直接复用 HTTP 状态码作为唯一返回字段，
 * 是因为很多业务系统会同时保留“HTTP 语义 + 业务码语义”两层表达：
 * - HTTP 状态码解决协议层成功/失败。
 * - code/message 解决业务层是否满足预期。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ApiResponse<T> {

    /**
     * 业务结果码。
     * 当前约定：
     * - 0：成功。
     * - 其他值：失败，通常与异常处理器映射出的语义保持一致。
     */
    private Integer code;

    /**
     * 结果说明。
     * 成功时通常是简短确认语句，失败时用于向调用方解释失败原因。
     */
    private String message;

    /**
     * 真实业务数据。
     * 当接口失败时通常为 null。
     */
    private T data;

    /**
     * 成功响应工厂方法。
     * 默认使用统一成功消息，适合列表查询、详情查询等“数据即结果”的接口。
     */
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(0, "success", data);
    }

    /**
     * 带自定义消息的成功响应。
     * 适合 create、start、pause、retry 这类动作型接口，
     * 让调用方在拿到数据的同时，也能看到本次动作的业务含义。
     */
    public static <T> ApiResponse<T> success(String message, T data) {
        return new ApiResponse<>(0, message, data);
    }

    /**
     * 失败响应工厂方法。
     * 主要由全局异常处理器统一调用，避免 Controller 到处手写错误返回。
     */
    public static <T> ApiResponse<T> error(Integer code, String message) {
        return new ApiResponse<>(code, message, null);
    }
}
