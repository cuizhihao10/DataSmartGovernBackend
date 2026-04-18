package com.czh.datasmart.govern.quality.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.czh.datasmart.govern.quality.entity.QualityCheckReport;
import com.czh.datasmart.govern.quality.entity.QualityRule;
import com.czh.datasmart.govern.quality.mapper.QualityCheckReportMapper;
import com.czh.datasmart.govern.quality.mapper.QualityRuleMapper;
import com.czh.datasmart.govern.quality.service.DataQualityService;
import com.czh.datasmart.govern.quality.support.QualityCheckStatus;
import com.czh.datasmart.govern.quality.support.QualityComparisonOperator;
import com.czh.datasmart.govern.quality.support.QualityRuleStatus;
import com.czh.datasmart.govern.quality.support.QualityRuleType;
import com.czh.datasmart.govern.quality.support.QualitySeverity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * 数据质量服务实现。
 * <p>
 * 当前阶段的 data-quality 模块先聚焦于“规则定义 + 规则执行结果”的最小闭环。
 * 这样做有两个明显好处：
 * 1. 不依赖真实数据源也能先把领域模型和接口契约打稳。
 * 2. 后面当 datasource-management 更成熟后，可以很自然地把真实采样值接进来。
 * <p>
 * 这里的质量检测不是空跑，而是围绕一个很真实的业务问题展开：
 * “当我们拿到某个质量指标的实际观测值时，如何根据预定义规则判断它是否合格，并留下报告？”
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataQualityServiceImpl extends ServiceImpl<QualityRuleMapper, QualityRule> implements DataQualityService {

    private final QualityCheckReportMapper qualityCheckReportMapper;

    @Override
    @Transactional
    public QualityRule createRule(String name, String ruleType, String targetObject, String comparisonOperator,
                                  BigDecimal expectedValue, String severity, String description) {
        ensureRuleNameNotDuplicated(name, null);
        QualityRule rule = new QualityRule();
        rule.setName(name);
        rule.setRuleType(QualityRuleType.fromValue(ruleType).name());
        rule.setTargetObject(targetObject);
        rule.setComparisonOperator(QualityComparisonOperator.fromValue(comparisonOperator).name());
        rule.setExpectedValue(expectedValue);
        rule.setSeverity(QualitySeverity.normalize(severity));
        rule.setDescription(description);
        rule.setStatus(QualityRuleStatus.ACTIVE);
        rule.setCreateTime(LocalDateTime.now());
        rule.setUpdateTime(LocalDateTime.now());
        save(rule);
        log.info("Created quality rule: {}", rule.getId());
        return rule;
    }

    @Override
    @Transactional
    public QualityRule updateRule(Long id, String name, String targetObject, String comparisonOperator,
                                  BigDecimal expectedValue, String severity, String description) {
        QualityRule rule = getRequiredRule(id);
        ensureNotDeleted(rule);
        ensureRuleNameNotDuplicated(name, id);

        rule.setName(name);
        rule.setTargetObject(targetObject);
        rule.setComparisonOperator(QualityComparisonOperator.fromValue(comparisonOperator).name());
        rule.setExpectedValue(expectedValue);
        rule.setSeverity(QualitySeverity.normalize(severity));
        rule.setDescription(description);
        rule.setUpdateTime(LocalDateTime.now());
        updateById(rule);
        log.info("Updated quality rule: {}", id);
        return rule;
    }

    @Override
    @Transactional
    public QualityRule enableRule(Long id) {
        QualityRule rule = getRequiredRule(id);
        ensureNotDeleted(rule);
        rule.setStatus(QualityRuleStatus.ACTIVE);
        rule.setUpdateTime(LocalDateTime.now());
        updateById(rule);
        log.info("Enabled quality rule: {}", id);
        return rule;
    }

    @Override
    @Transactional
    public QualityRule disableRule(Long id) {
        QualityRule rule = getRequiredRule(id);
        ensureNotDeleted(rule);
        rule.setStatus(QualityRuleStatus.INACTIVE);
        rule.setUpdateTime(LocalDateTime.now());
        updateById(rule);
        log.info("Disabled quality rule: {}", id);
        return rule;
    }

    @Override
    @Transactional
    public QualityRule deleteRule(Long id) {
        QualityRule rule = getRequiredRule(id);
        rule.setStatus(QualityRuleStatus.DELETED);
        rule.setUpdateTime(LocalDateTime.now());
        updateById(rule);
        log.info("Deleted quality rule logically: {}", id);
        return rule;
    }

    /**
     * 执行质量检测。
     * <p>
     * 当前这一步采用“规则 + 观测值”的方式生成报告，核心原理非常直接：
     * 1. 从规则中拿到 expectedValue 和 comparisonOperator。
     * 2. 用实际观测值 measuredValue 与期望阈值比较。
     * 3. 生成 PASSED / FAILED 的检测结论。
     * 4. 把结果写入质量报告表。
     * <p>
     * 这个模型非常适合作为未来真实检测任务的基础骨架，
     * 因为无论实际观测值来自 SQL、流式计算还是 AI 推断，最终都还是要归结为这一步比较。
     */
    @Override
    @Transactional
    public QualityCheckReport runQualityCheck(Long ruleId, BigDecimal measuredValue, Integer sampleSize,
                                              Integer exceptionCount, String notes) {
        QualityRule rule = getRequiredRule(ruleId);
        ensureNotDeleted(rule);
        if (!QualityRuleStatus.ACTIVE.equals(rule.getStatus())) {
            throw new IllegalStateException("Only active rules can run quality checks");
        }

        QualityComparisonOperator operator = QualityComparisonOperator.fromValue(rule.getComparisonOperator());
        boolean passed = operator.matches(measuredValue, rule.getExpectedValue());

        QualityCheckReport report = new QualityCheckReport();
        report.setRuleId(rule.getId());
        report.setRuleName(rule.getName());
        report.setTargetObject(rule.getTargetObject());
        report.setMeasuredValue(measuredValue);
        report.setExpectedValue(rule.getExpectedValue());
        report.setComparisonOperator(rule.getComparisonOperator());
        report.setCheckStatus(passed ? QualityCheckStatus.PASSED : QualityCheckStatus.FAILED);
        report.setSampleSize(sampleSize);
        report.setExceptionCount(exceptionCount);
        report.setNotes(notes);
        report.setSummary(buildSummary(rule, measuredValue, passed, sampleSize, exceptionCount));
        report.setCreateTime(LocalDateTime.now());
        qualityCheckReportMapper.insert(report);

        log.info("Ran quality check, ruleId={}, reportId={}, status={}",
                ruleId, report.getId(), report.getCheckStatus());
        return report;
    }

    @Override
    public List<QualityCheckReport> listReportsByRuleId(Long ruleId) {
        getRequiredRule(ruleId);
        return qualityCheckReportMapper.selectList(new LambdaQueryWrapper<QualityCheckReport>()
                .eq(QualityCheckReport::getRuleId, ruleId)
                .orderByDesc(QualityCheckReport::getCreateTime)
                .orderByDesc(QualityCheckReport::getId));
    }

    private QualityRule getRequiredRule(Long id) {
        QualityRule rule = getById(id);
        if (rule == null) {
            throw new NoSuchElementException("Quality rule not found: " + id);
        }
        return rule;
    }

    private void ensureNotDeleted(QualityRule rule) {
        if (QualityRuleStatus.DELETED.equals(rule.getStatus())) {
            throw new IllegalStateException("Quality rule has been deleted: " + rule.getId());
        }
    }

    private void ensureRuleNameNotDuplicated(String name, Long currentId) {
        LambdaQueryWrapper<QualityRule> wrapper = new LambdaQueryWrapper<QualityRule>()
                .eq(QualityRule::getName, name)
                .ne(currentId != null, QualityRule::getId, currentId)
                .ne(QualityRule::getStatus, QualityRuleStatus.DELETED);
        if (count(wrapper) > 0) {
            throw new IllegalArgumentException("quality rule name already exists: " + name);
        }
    }

    private String buildSummary(QualityRule rule, BigDecimal measuredValue, boolean passed,
                                Integer sampleSize, Integer exceptionCount) {
        return "Rule [" + rule.getName() + "] on target [" + rule.getTargetObject() + "] expected "
                + rule.getComparisonOperator() + " " + rule.getExpectedValue()
                + ", actual=" + measuredValue
                + ", sampleSize=" + sampleSize
                + ", exceptionCount=" + exceptionCount
                + ", result=" + (passed ? QualityCheckStatus.PASSED : QualityCheckStatus.FAILED);
    }
}
