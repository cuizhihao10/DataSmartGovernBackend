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
     *
     * <p>该方法保留旧接口的简单失败形态，适合只需要顶层 message 的场景。
     * 对规则配置、扫描计划、SQL 生成、质量执行等需要多条错误明细的场景，
     * 应使用带 data 的重载返回结构化详情。</p>
     */
    public static <T> ApiResponse<T> error(Integer code, String message) {
        return new ApiResponse<>(code, message, null);
    }

    /**
     * 带结构化错误明细的失败响应。
     *
     * <p>数据质量模块的规则与执行错误往往不是一句话能解释清楚：
     * 例如字段不存在、规则参数缺失、数据源只读 SQL 被拒绝、异常样本采集失败等。
     * data 中放置低敏错误详情后，前端可以展示完整修复清单，同时不泄露 SQL 正文或堆栈。</p>
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
