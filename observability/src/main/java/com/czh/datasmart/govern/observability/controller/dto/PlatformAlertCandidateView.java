/**
 * @Author : Cui
 * @Date: 2026/07/01 16:42
 * @Description DataSmartGovernBackend - PlatformAlertCandidateView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.observability.controller.dto;

/**
 * 平台告警候选视图。
 *
 * <p>“候选”表示 observability 根据当前健康快照推导出“如果接入正式告警渠道，此刻应该产生哪类告警”。
 * 它不是已经投递到企业微信、钉钉或邮件的正式告警事件，也不会持久化事故单。
 * 这样设计可以先把闭环验收做起来：服务 DOWN、metrics 缺失、探针超时等问题能被统一分类，
 * 后续再接 Alertmanager 或事件表时不会重写业务语义。</p>
 *
 * @param ruleCode 命中的告警规则编码。
 * @param moduleCode 出现问题的模块编码。
 * @param displayName 模块展示名。
 * @param severity 告警级别。
 * @param status 当前探针状态。
 * @param issueCode 低敏问题分类码，例如 CONNECTION_REFUSED、HTTP_404、TIMEOUT。
 * @param description 告警候选说明。
 * @param nextAction 建议下一步动作。
 */
public record PlatformAlertCandidateView(
        String ruleCode,
        String moduleCode,
        String displayName,
        String severity,
        String status,
        String issueCode,
        String description,
        String nextAction) {
}
