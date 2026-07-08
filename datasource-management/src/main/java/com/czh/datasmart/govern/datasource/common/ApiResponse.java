package com.czh.datasmart.govern.datasource.common;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @Author : Cui
 * @Date: 2026/4/18 21:55
 * @Description DataSmart Govern Backend - ApiResponse.java
 * @Version:1.0.0
 *
 * 统一 API 响应体。
 * 当前各业务模块都逐步对齐到统一的返回结构，
 * 这样有利于网关透传、前端统一解析，以及跨模块学习时快速识别接口风格。
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
     * 提示信息。
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
     * 带业务提示语的成功响应。
     */
    public static <T> ApiResponse<T> success(String message, T data) {
        return new ApiResponse<>(0, message, data);
    }

    /**
     * 失败响应。
     *
     * <p>该方法保留历史行为，失败时 data 为 null。
     * 当错误需要展示字段级明细、修复建议或下游依赖摘要时，请使用带 data 的重载。</p>
     */
    public static <T> ApiResponse<T> error(Integer code, String message) {
        return new ApiResponse<>(code, message, null);
    }

    /**
     * 带结构化错误明细的失败响应。
     *
     * <p>数据源创建、连接测试、元数据发现和只读 SQL 检查经常会出现多个可修复错误。
     * 把低敏明细放入 data 后，前端可以一次性展示“哪个字段错了、哪个数据源不可访问、
     * 下一步应该怎么处理”，而不是只弹出一个 400/500 错误码。</p>
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
