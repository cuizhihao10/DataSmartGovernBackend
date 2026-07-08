/**
 * @Author : Cui
 * @Date: 2026/07/08 18:50
 * @Description DataSmart Govern Backend - PlatformApiErrorDetail.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.common.api;

import java.util.List;

/**
 * 平台统一错误明细。
 *
 * <p>{@link PlatformApiResponse} 已经提供 code、reason、message、traceId 等基础错误字段。
 * 但真实产品里的失败往往不是一句话能解释清楚的，例如表单里多个字段同时缺失、创建同步任务预检查返回多条阻断项、
 * 或下游服务返回了可恢复的配置错误。如果后端只返回一个 HTTP 状态码，前端只能弹出“HTTP 500/400”，用户不知道该改哪里。</p>
 *
 * <p>本对象专门放在 response.data 中承载“可展示、低敏、结构化”的错误细节：</p>
 * <p>1. {@code details} 面向普通用户和运营人员，适合直接在弹窗中逐条展示；</p>
 * <p>2. {@code fieldErrors} 面向表单页，前端可以把字段名和错误信息映射到对应输入项；</p>
 * <p>3. {@code suggestions} 面向可恢复错误，告诉用户下一步该补什么配置、切换什么模式或联系哪个系统能力。</p>
 *
 * <p>安全边界：这里不能放堆栈、SQL 正文、连接串、密码、token、样本行、完整请求体或远端内部 endpoint。
 * 详细堆栈应进入服务端日志，并通过 traceId 串联排障。</p>
 *
 * @param details 可直接展示的错误明细
 * @param fieldErrors 字段级错误明细
 * @param suggestions 可选修复建议
 */
public record PlatformApiErrorDetail(
        List<String> details,
        List<FieldErrorDetail> fieldErrors,
        List<String> suggestions
) {

    /**
     * 字段级错误。
     *
     * <p>{@code field} 使用 Java DTO 字段名或请求参数名，前端可以按自己的表单字段做映射；
     * {@code message} 是可展示提示，不应包含内部异常类名或原始堆栈。</p>
     *
     * @param field 字段名
     * @param message 字段错误说明
     */
    public record FieldErrorDetail(String field, String message) {
    }

    /**
     * 构建只有明细列表的错误详情。
     *
     * @param details 可展示错误明细
     * @return 标准错误详情对象
     */
    public static PlatformApiErrorDetail ofDetails(List<String> details) {
        return new PlatformApiErrorDetail(safeList(details), List.of(), List.of());
    }

    /**
     * 构建包含字段错误的详情。
     *
     * @param details 总体错误说明
     * @param fieldErrors 字段级错误
     * @param suggestions 修复建议
     * @return 标准错误详情对象
     */
    public static PlatformApiErrorDetail of(List<String> details,
                                            List<FieldErrorDetail> fieldErrors,
                                            List<String> suggestions) {
        return new PlatformApiErrorDetail(safeList(details), safeList(fieldErrors), safeList(suggestions));
    }

    private static <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
