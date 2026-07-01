/**
 * @Author : Cui
 * @Date: 2026/07/01 16:41
 * @Description DataSmartGovernBackend - PlatformAlertRuleView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.observability.controller.dto;

/**
 * 平台告警规则展示视图。
 *
 * <p>observability 模块最终会和 Prometheus、Alertmanager、Grafana、日志平台以及企业通知渠道协作。
 * 但在项目闭环收敛阶段，我们先需要一个“产品级规则目录”：明确每个运行时至少应该被哪些基础告警覆盖，
 * 告警触发后由哪个角色处理，以及排障时应看什么。</p>
 *
 * <p>该 DTO 只返回规则元数据，不返回真实 PromQL 查询结果、日志正文、HTTP 响应正文、token、密码、
 * 内部 endpoint、业务样本、SQL、prompt 或模型输出，避免可观测性接口成为高敏数据出口。</p>
 *
 * @param ruleCode 告警规则编码，稳定用于前端、测试和后续 Prometheus rule 文件对齐。
 * @param moduleCode 规则覆盖的模块编码。
 * @param displayName 面向运维人员展示的规则名称。
 * @param category 规则类别，例如 AVAILABILITY、METRICS_EXPORT、BUSINESS_RUNTIME。
 * @param severity 规则严重级别，例如 CRITICAL、WARNING、INFO。
 * @param signalType 该规则依赖的信号类型，例如 HEALTH_PROBE 或 METRICS_PROBE。
 * @param conditionSummary 触发条件摘要，使用中文说明规则语义，不暴露底层敏感查询正文。
 * @param recommendedOwnerRole 建议处理角色，例如 OPERATOR、PLATFORM_ADMINISTRATOR。
 * @param runbookHint 低敏排障提示，指向运维动作而不是泄露内部响应。
 * @param enabledByDefault 是否属于默认必须启用的基础闭环规则。
 */
public record PlatformAlertRuleView(
        String ruleCode,
        String moduleCode,
        String displayName,
        String category,
        String severity,
        String signalType,
        String conditionSummary,
        String recommendedOwnerRole,
        String runbookHint,
        boolean enabledByDefault) {
}
