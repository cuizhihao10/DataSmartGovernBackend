/**
 * @Author : Cui
 * @Date: 2026/4/18 21:30
 * @Description DataSmart Govern Backend - ApiResponse.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.quality.common;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 统一 API 响应体。
 * 数据质量模块虽然业务内容不同于任务模块，但在接口层仍应保持一致的响应风格，
 * 这样网关、前端和其他服务在消费多个模块时，不需要为每个模块写不同的解析逻辑。
 *
 * 当前继续采用三段式结构：
 * - code：业务结果码。
 * - message：给调用方看的结果说明。
 * - data：实际业务载荷。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ApiResponse<T> {

    /**
     * 业务结果码。
     * 当前约定 0 表示成功，非 0 表示失败。
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
     * 带自定义成功说明的响应。
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
