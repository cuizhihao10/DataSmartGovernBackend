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
     *
     * <p>该方法保留历史简单错误响应。对于 gateway 入口层来说，
     * 只返回一个状态码会让用户误以为“系统完全不可用”，因此全局异常处理器会优先使用
     * 带 data 的重载，把低敏原因和排查建议返回给前端。</p>
     */
    public static <T> ApiResponse<T> error(Integer code, String message) {
        return new ApiResponse<>(code, message, null);
    }

    /**
     * 带结构化错误明细的失败响应。
     *
     * <p>gateway 是统一入口，既要告诉前端请求为什么失败，又不能泄露下游服务名、实例地址、
     * 路由表达式或认证内部细节。因此 data 只应放置低敏、可展示的错误详情，
     * 完整异常仍然写入服务端日志。</p>
     *
     * @param code 失败码；本模块历史上使用 HTTP 状态码
     * @param message 顶层错误摘要
     * @param data 结构化错误详情，通常为 PlatformApiErrorDetail
     * @return 统一失败响应
     */
    public static <T> ApiResponse<T> error(Integer code, String message, T data) {
        return new ApiResponse<>(code, message, data);
    }
}
