/**
 * @Author : Cui
 * @Date: 2026/06/28 16:10
 * @Description DataSmart Govern Backend - QualityRemediationTaskTextSanitizer.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.quality.service.support;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 质量异常治理任务的低敏文本处理工具。
 *
 * <p>该类从 {@code QualityRemediationTaskService} 中拆分出来，是为了降低服务编排类的代码行数和职责耦合。
 * 主服务只负责“如何从质量异常创建治理任务”，本类只负责“哪些文本可以进入 task-management payload”。
 * 这样后续如果要接入真正的 DLP、字段分级分类、敏感词策略中心或租户级脱敏策略，也可以优先替换本类，
 * 而不需要重写质量异常派单的业务主流程。</p>
 *
 * <p>需要特别注意：这里的脱敏不是完整安全产品能力，而是质量模块进入任务中心之前的最后一道兜底护栏。
 * 真正商业化部署时，还应结合数据分类分级、连接串识别、凭据管理、SQL 审计和模型输出安全策略共同治理。</p>
 */
public final class QualityRemediationTaskTextSanitizer {

    /**
     * 质量异常治理任务 payload 的低敏策略说明。
     *
     * <p>该字符串会同时出现在 data-quality 响应和 task-management 参数中，目的是让后续任务列表、审计日志、
     * Agent ToolPlan 或前端详情页都能清楚知道：这个任务携带的是聚合摘要，不是样本明细。</p>
     */
    public static final String PAYLOAD_POLICY = "LOW_SENSITIVE_AGGREGATION_ONLY: "
            + "仅包含筛选条件、异常数量、TOP 聚合和治理建议；不包含样本正文、明细标识、查询脚本、模型或工具正文、凭据、连接串或内部地址。";

    /**
     * 明显不应该进入低敏 payload 的关键词。
     *
     * <p>这些关键词覆盖几类常见泄露风险：凭据、连接串、SQL 正文、prompt/样本字段名、内部 URL。
     * 由于治理任务会进入 task-management 并可能被多人查看，所以自由文本一旦命中这些标记，就不再原样透传。</p>
     */
    private static final List<String> SENSITIVE_MARKERS = List.of(
            "password",
            "secret",
            "token",
            "credential",
            "jdbc:",
            "select ",
            "insert ",
            "update ",
            "delete ",
            "prompt",
            "samplepayload",
            "observedvalue",
            "recordidentifier",
            "http://",
            "https://"
    );

    private QualityRemediationTaskTextSanitizer() {
    }

    /**
     * 对自由文本做长度限制与敏感片段兜底隐藏。
     *
     * <p>适用字段包括治理原因、治理建议、字段名、目标对象、报告规则名称等。
     * 它们都属于“用户或上游系统可能输入的文本”，不能因为只是任务标题/描述就默认安全。</p>
     *
     * @param value 原始文本，可为空
     * @param maxLength 最长保留字符数，避免任务 payload 被异常长文本撑大
     * @return 可安全进入低敏 payload 的文本；如果命中敏感标记，则返回统一隐藏提示
     */
    public static String safeText(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.replace('\r', ' ').replace('\n', ' ').trim();
        if (containsSensitiveMarker(normalized)) {
            return "内容包含可能敏感片段，已按低敏策略隐藏。";
        }
        return truncate(normalized, maxLength);
    }

    /**
     * 将外部传入的枚举式编码统一规范为大写下划线格式。
     *
     * <p>业务接口里经常会出现 {@code high}、{@code HIGH}、{@code source-system-fix}
     * 这类不同写法。服务层不应该把这些写法直接透传到任务中心，否则后续筛选和统计会变得混乱。
     * 因此这里统一转换为 {@code HIGH}、{@code SOURCE_SYSTEM_FIX} 这样的稳定编码。</p>
     */
    public static String normalizeCode(String value, String defaultValue) {
        return normalizeCode(value, defaultValue, null);
    }

    /**
     * 将外部传入的枚举式编码统一规范化，并可选地使用白名单限制取值。
     *
     * @param value 原始编码
     * @param defaultValue 原始编码为空或不在白名单时使用的默认值
     * @param allowedValues 允许的稳定编码集合；为空表示只做格式规范化，不做枚举限制
     * @return 规范后的编码
     */
    public static String normalizeCode(String value, String defaultValue, Set<String> allowedValues) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        String normalized = value.trim()
                .replace('-', '_')
                .replace(' ', '_')
                .toUpperCase(Locale.ROOT);
        normalized = truncate(normalized, 64);
        if (allowedValues != null && !allowedValues.contains(normalized)) {
            return defaultValue;
        }
        return normalized;
    }

    /**
     * 从多个候选文本中选择第一个非空值。
     *
     * <p>质量异常治理任务经常需要在“请求体字段”和“报告快照字段”之间做优先级选择。
     * 用这个方法集中处理，可以避免服务层散落大量 {@code value != null && !value.isBlank()} 判断。</p>
     */
    public static String firstText(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    /**
     * 截断过长字符串。
     *
     * <p>这里按 Java 字符数量截断，足以满足当前任务 payload 的保护需求。
     * 如果后续要严格按模型 token、数据库字节长度或多语言字符宽度控制，可以在本方法内替换实现。</p>
     */
    public static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private static boolean containsSensitiveMarker(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return SENSITIVE_MARKERS.stream().anyMatch(lower::contains);
    }
}
