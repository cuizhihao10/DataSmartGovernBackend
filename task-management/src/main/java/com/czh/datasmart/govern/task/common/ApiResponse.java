package com.czh.datasmart.govern.task.common;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 统一 API 响应体。
 * <p>
 * 当前项目不是一个单体页面的小工具，而是会继续扩展成多模块协同的后端平台。
 * 一旦进入这种场景，接口返回格式是否统一，会直接影响：
 * 1. 网关如何透传或改写响应。
 * 2. 前端如何编写通用请求封装。
 * 3. 后续别的服务如何稳定消费这个模块的接口。
 * <p>
 * 因此这里统一采用 {@code code + message + data} 的结构：
 * - code：业务结果编码。
 * - message：人类可读提示。
 * - data：真正业务数据。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ApiResponse<T> {

    /**
     * 业务状态码。
     * 当前约定 0 表示成功，失败时由统一异常处理器映射为对应状态码。
     */
    private Integer code;

    /**
     * 提示信息。
     */
    private String message;

    /**
     * 业务载荷。
     */
    private T data;

    /**
     * 返回成功响应，使用默认成功消息。
     */
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(0, "success", data);
    }

    /**
     * 返回成功响应，允许附带更具体的业务说明。
     */
    public static <T> ApiResponse<T> success(String message, T data) {
        return new ApiResponse<>(0, message, data);
    }

    /**
     * 返回失败响应。
     */
    public static <T> ApiResponse<T> error(Integer code, String message) {
        return new ApiResponse<>(code, message, null);
    }
}
