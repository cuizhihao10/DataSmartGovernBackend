/**
 * @Author : Cui
 * @Date: 2026/06/27 20:55
 * @Description DataSmart Govern Backend - QualityGovernanceOverviewCalculator.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.quality.service.support;

import com.czh.datasmart.govern.quality.controller.dto.QualityAnomalyAggregationItem;
import com.czh.datasmart.govern.quality.support.QualityCheckExecutionState;
import com.czh.datasmart.govern.quality.support.QualityCheckStatus;
import com.czh.datasmart.govern.quality.support.QualityGovernanceRiskLevel;
import com.czh.datasmart.govern.quality.support.QualityRuleStatus;
import com.czh.datasmart.govern.quality.support.QualityRuleTargetType;
import com.czh.datasmart.govern.quality.support.QualityRuleType;
import com.czh.datasmart.govern.quality.support.QualitySeverity;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据质量治理总览计算器。
 *
 * <p>该组件只负责纯计算：空计数模板、通过率、治理评分、风险等级和下一步建议。把这些逻辑从
 * {@code QualityGovernanceOverviewService} 中拆出来有两个目的：</p>
 *
 * <p>1. 降低服务类行数和职责重量，让服务类专注于查询事实表；</p>
 * <p>2. 让评分规则集中在一个可测试、可替换的位置。未来如果引入租户级评分权重、行业模板、
 * SLA 策略或告警阈值，不需要改动聚合查询代码。</p>
 */
@Component
public class QualityGovernanceOverviewCalculator {

    /**
     * 空通过率。
     *
     * <p>统一返回 0.0000，避免前端或 Agent 在没有报告时出现 null/除零歧义。</p>
     */
    public BigDecimal zeroPassRate() {
        return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
    }

    /**
     * 计算质量通过率。
     *
     * <p>通过率只基于近期报告窗口内的 PASSED/FAILED 数量，不混入规则库存，也不混入执行失败。
     * 执行失败代表平台链路未完成，报告失败代表数据质量未达标，两者在产品语义上必须分开。</p>
     */
    public BigDecimal calculatePassRate(Map<String, Long> reportStatusCounts) {
        long passed = reportStatusCounts.getOrDefault(QualityCheckStatus.PASSED, 0L);
        long total = passed + reportStatusCounts.getOrDefault(QualityCheckStatus.FAILED, 0L);
        if (total == 0) {
            return zeroPassRate();
        }
        return BigDecimal.valueOf(passed)
                .divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP);
    }

    /**
     * 计算质量治理评分。
     *
     * <p>当前评分是可解释的规则化启发式，不是机器学习模型。这样做的好处是每次扣分都能追溯到
     * 明确事实：无规则、无启用规则、无近期报告、失败报告、异常积压、执行失败或运行悬挂。</p>
     */
    public int calculateQualityScore(Map<String, Long> ruleStatusCounts,
                                     Map<String, Long> reportStatusCounts,
                                     Map<String, Long> executionStateCounts,
                                     long anomalyCount) {
        long totalRules = sum(ruleStatusCounts);
        if (totalRules == 0) {
            return 0;
        }
        int score = 100;
        long activeRules = ruleStatusCounts.getOrDefault(QualityRuleStatus.ACTIVE, 0L);
        long draftRules = ruleStatusCounts.getOrDefault(QualityRuleStatus.DRAFT, 0L);
        long recentReports = sum(reportStatusCounts);
        long failedReports = reportStatusCounts.getOrDefault(QualityCheckStatus.FAILED, 0L);

        if (activeRules == 0) {
            score -= 30;
        }
        if (recentReports == 0) {
            score -= 15;
        } else {
            score -= Math.min(50, (int) Math.round((failedReports * 50.0d) / recentReports));
        }
        if (draftRules > activeRules) {
            score -= 5;
        }
        if (anomalyCount > 0) {
            score -= anomalyPenalty(anomalyCount);
        }
        if (executionStateCounts.getOrDefault(QualityCheckExecutionState.FAILED, 0L) > 0) {
            score -= 10;
        }
        if (executionStateCounts.getOrDefault(QualityCheckExecutionState.RUNNING, 0L) > 0) {
            score -= 5;
        }
        return Math.max(0, Math.min(100, score));
    }

    /**
     * 把评分和关键事实转换成运营风险等级。
     *
     * <p>没有规则、没有启用规则或没有近期报告时，优先返回 NOT_STARTED。这样可以避免一个“没有失败”
     * 的空项目被误判成 HEALTHY。</p>
     */
    public QualityGovernanceRiskLevel calculateRiskLevel(Map<String, Long> ruleStatusCounts,
                                                         long recentReportCount,
                                                         int score) {
        long totalRules = sum(ruleStatusCounts);
        long activeRules = ruleStatusCounts.getOrDefault(QualityRuleStatus.ACTIVE, 0L);
        if (totalRules == 0 || activeRules == 0 || recentReportCount == 0) {
            return QualityGovernanceRiskLevel.NOT_STARTED;
        }
        if (score >= 90) {
            return QualityGovernanceRiskLevel.HEALTHY;
        }
        if (score >= 75) {
            return QualityGovernanceRiskLevel.WATCH;
        }
        if (score >= 50) {
            return QualityGovernanceRiskLevel.RISK;
        }
        return QualityGovernanceRiskLevel.CRITICAL;
    }

    /**
     * 根据总览事实生成下一步建议。
     *
     * <p>这些建议刻意保持低敏，只描述治理动作方向，不包含具体 SQL、样本值、错误正文或数据源连接信息。
     * 后续 Agent 可以把这些建议作为规划输入，再结合权限审批创建清洗任务、规则复核任务或运维排障任务。</p>
     */
    public List<String> buildNextActions(Map<String, Long> ruleStatusCounts,
                                         Map<String, Long> reportStatusCounts,
                                         Map<String, Long> executionStateCounts,
                                         long anomalyCount,
                                         List<QualityAnomalyAggregationItem> topFields,
                                         List<QualityAnomalyAggregationItem> topTypes) {
        List<String> actions = new ArrayList<>();
        long totalRules = sum(ruleStatusCounts);
        long activeRules = ruleStatusCounts.getOrDefault(QualityRuleStatus.ACTIVE, 0L);
        long draftRules = ruleStatusCounts.getOrDefault(QualityRuleStatus.DRAFT, 0L);
        long failedReports = reportStatusCounts.getOrDefault(QualityCheckStatus.FAILED, 0L);
        long recentReports = sum(reportStatusCounts);

        if (totalRules == 0) {
            actions.add("当前范围尚未建立质量规则，建议先为核心表、核心字段和关键业务指标创建完整性、唯一性、有效性等基础规则。");
        }
        if (activeRules == 0 && totalRules > 0) {
            actions.add("当前没有启用规则，建议完成规则目标校验、审批或人工复核后启用至少一组核心规则。");
        }
        if (draftRules > 0) {
            actions.add("存在草稿规则，建议进入规则评审流程，确认阈值、目标对象和严重级别后再发布。");
        }
        if (recentReports == 0 && activeRules > 0) {
            actions.add("当前窗口内没有检测报告，建议为已启用规则提交质量检测任务或检查 task-management 调度链路。");
        }
        if (failedReports > 0) {
            actions.add("近期存在失败质量报告，建议优先从失败报告列表进入异常明细，区分规则过严、源数据问题和执行参数问题。");
        }
        if (anomalyCount > 0) {
            actions.add("当前范围存在异常样本积压，建议进入异常工作台查看脱敏详情，并评估是否创建清洗任务或源系统整改任务。");
        }
        if (!topFields.isEmpty()) {
            actions.add("TOP 异常字段已经形成聚合结果，建议优先治理排名靠前字段，避免逐条查看样本导致排障效率低。");
        }
        if (!topTypes.isEmpty()) {
            actions.add("TOP 异常类型已经形成聚合结果，建议按问题类型批量制定修复策略，例如空值补齐、重复合并、格式标准化或范围修正。");
        }
        if (executionStateCounts.getOrDefault(QualityCheckExecutionState.FAILED, 0L) > 0) {
            actions.add("近期存在执行失败，建议结合执行器诊断接口排查连接、权限、超时、SQL 审计或 worker 稳定性问题。");
        }
        if (executionStateCounts.getOrDefault(QualityCheckExecutionState.RUNNING, 0L) > 0) {
            actions.add("近期仍有 RUNNING 执行记录，建议确认是否为长耗时扫描，或是否需要 worker 心跳、超时恢复和人工介入。");
        }
        if (actions.isEmpty()) {
            actions.add("当前质量治理态势较稳定，建议继续扩大规则覆盖到更多数据源类型，并为关键对象配置趋势告警和周期性复盘。");
        }
        return actions;
    }

    /**
     * 计算 map 中所有计数之和。
     */
    public long sum(Map<String, Long> values) {
        return values.values().stream().mapToLong(Long::longValue).sum();
    }

    /**
     * 生成空规则生命周期计数。
     */
    public Map<String, Long> zeroRuleStatusCounts() {
        Map<String, Long> result = new LinkedHashMap<>();
        for (String status : List.of(QualityRuleStatus.DRAFT, QualityRuleStatus.ACTIVE,
                QualityRuleStatus.INACTIVE, QualityRuleStatus.ARCHIVED)) {
            result.put(status, 0L);
        }
        return result;
    }

    /**
     * 生成空规则类型计数。
     */
    public Map<String, Long> zeroRuleTypeCounts() {
        Map<String, Long> result = new LinkedHashMap<>();
        for (QualityRuleType type : QualityRuleType.values()) {
            result.put(type.name(), 0L);
        }
        return result;
    }

    /**
     * 生成空严重级别计数。
     */
    public Map<String, Long> zeroSeverityCounts() {
        Map<String, Long> result = new LinkedHashMap<>();
        for (QualitySeverity severity : QualitySeverity.values()) {
            result.put(severity.name(), 0L);
        }
        return result;
    }

    /**
     * 生成空检测目标类型计数。
     */
    public Map<String, Long> zeroTargetTypeCounts() {
        Map<String, Long> result = new LinkedHashMap<>();
        for (QualityRuleTargetType targetType : QualityRuleTargetType.values()) {
            result.put(targetType.name(), 0L);
        }
        return result;
    }

    /**
     * 生成空报告状态计数。
     */
    public Map<String, Long> zeroReportStatusCounts() {
        Map<String, Long> result = new LinkedHashMap<>();
        result.put(QualityCheckStatus.PASSED, 0L);
        result.put(QualityCheckStatus.FAILED, 0L);
        return result;
    }

    /**
     * 生成空执行状态计数。
     */
    public Map<String, Long> zeroExecutionStateCounts() {
        Map<String, Long> result = new LinkedHashMap<>();
        result.put(QualityCheckExecutionState.RUNNING, 0L);
        result.put(QualityCheckExecutionState.SUCCESS, 0L);
        result.put(QualityCheckExecutionState.FAILED, 0L);
        return result;
    }

    private int anomalyPenalty(long anomalyCount) {
        if (anomalyCount >= 1000) {
            return 20;
        }
        if (anomalyCount >= 100) {
            return 15;
        }
        if (anomalyCount >= 10) {
            return 10;
        }
        return 5;
    }
}
