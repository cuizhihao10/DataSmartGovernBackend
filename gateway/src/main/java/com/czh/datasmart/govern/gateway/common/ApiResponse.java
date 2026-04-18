package com.czh.datasmart.govern.gateway.common;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @Author : Cui
 * @Date: 2026/4/18 22:20
 * @Description DataSmart Govern Backend - ApiResponse.java
 * @Version:1.0.0
 *
 * 网关模块统一响应体。
 * 即使网关当前只暴露少量管理型接口，也仍然保持与业务模块一致的响应结构，
 * 这样整个项目的 API 风格就不会在入口层出现割裂。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ApiResponse<T> {

    /**
     * 业务结果码。
     */
    private Integer code;

    /**
     * 响应说明。
     */
    private String message;

    /**
     * 真实业务数据。
     */
    private T data;

    /**
     * 默认成功响应。
     */
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(0, "success", data);
    }

    /**
     * 带提示信息的成功响应。
     */
    public static <T> ApiResponse<T> success(String message, T data) {
        return new ApiResponse<>(0, message, data);
    }

    /**
     * 失败响应。
     */
    public static <T> ApiResponse<T> error(Integer code, String message) {
        return new ApiResponse<>(code, message, null);
    }
}
