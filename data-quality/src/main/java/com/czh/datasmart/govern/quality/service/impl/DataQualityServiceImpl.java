/**
 * @Author : Cui
 * @Date: 2026/4/18 21:40
 * @Description DataSmart Govern Backend - DataQualityServiceImpl.java
 * @Version:1.0.0
 */
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
 * 这个类的核心职责，不只是把规则和报告存到数据库，更重要的是把
 * “业务上定义的质量标准”转换成一次确定性的通过/失败判断。
 *
 * 可以把当前实现理解为一个最小可用的数据质量引擎：
 * 1. 管理规则定义。
 * 2. 校验规则是否可执行。
 * 3. 用比较运算符判断实际观测值和期望值。
 * 4. 把本次判断沉淀成可追溯报告。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataQualityServiceImpl extends ServiceImpl<QualityRuleMapper, QualityRule> implements DataQualityService {

    /**
     * 检测报告 Mapper。
     * 规则表负责保存“标准”，报告表负责保存“执行结果”。
     */
    private final QualityCheckReportMapper qualityCheckReportMapper;

    /**
     * 创建质量规则。
     * 当前会完成名称去重、输入归一化、初始状态设置等动作。
     */
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

        log.info("创建质量规则成功，ruleId={}", rule.getId());
        return rule;
    }

    /**
     * 更新质量规则。
     * 当前允许调整名称、目标、运算符、阈值、严重级别和说明，
     * 但不允许在这里直接改 ruleType，以保持规则基础分类稳定。
     */
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

        log.info("更新质量规则成功，ruleId={}", id);
        return rule;
    }

    /**
     * 启用规则。
     * 只有未删除规则才允许启用。
     */
    @Override
    @Transactional
    public QualityRule enableRule(Long id) {
        QualityRule rule = getRequiredRule(id);
        ensureNotDeleted(rule);
        rule.setStatus(QualityRuleStatus.ACTIVE);
        rule.setUpdateTime(LocalDateTime.now());
        updateById(rule);

        log.info("启用质量规则成功，ruleId={}", id);
        return rule;
    }

    /**
     * 停用规则。
     * 停用后规则仍然存在，但不再允许执行检测。
     */
    @Override
    @Transactional
    public QualityRule disableRule(Long id) {
        QualityRule rule = getRequiredRule(id);
        ensureNotDeleted(rule);
        rule.setStatus(QualityRuleStatus.INACTIVE);
        rule.setUpdateTime(LocalDateTime.now());
        updateById(rule);

        log.info("停用质量规则成功，ruleId={}", id);
        return rule;
    }

    /**
     * 逻辑删除规则。
     * 当前不做物理删除，目的是保留规则生命周期痕迹，便于未来审计与恢复设计。
     */
    @Override
    @Transactional
    public QualityRule deleteRule(Long id) {
        QualityRule rule = getRequiredRule(id);
        rule.setStatus(QualityRuleStatus.DELETED);
        rule.setUpdateTime(LocalDateTime.now());
        updateById(rule);

        log.info("逻辑删除质量规则成功，ruleId={}", id);
        return rule;
    }

    /**
     * 执行质量检测。
     * 当前的核心原理是“规则 + 观测值”：
     * 1. 读取规则并校验规则处于可执行状态。
     * 2. 根据规则中的比较运算符，判断实际观测值与期望值的关系。
     * 3. 生成一份报告快照，把本次判断的上下文和结果持久化。
     *
     * 这种设计的价值在于，不管未来观测值来自 SQL、任务执行器还是 AI 分析，
     * 最终都能落成统一的判断逻辑和统一的报告模型。
     */
    @Override
    @Transactional
    public QualityCheckReport runQualityCheck(Long ruleId, BigDecimal measuredValue, Integer sampleSize,
                                              Integer exceptionCount, String notes) {
        QualityRule rule = getRequiredRule(ruleId);
        ensureNotDeleted(rule);
        if (!QualityRuleStatus.ACTIVE.equals(rule.getStatus())) {
            throw new IllegalStateException("只有启用状态的规则才能执行质量检测");
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

        log.info("执行质量检测完成，ruleId={}, reportId={}, status={}",
                ruleId, report.getId(), report.getCheckStatus());
        return report;
    }

    /**
     * 查询某条规则下的历史报告。
     * 结果按创建时间倒序返回，便于优先看到最近一次检测结果。
     */
    @Override
    public List<QualityCheckReport> listReportsByRuleId(Long ruleId) {
        getRequiredRule(ruleId);
        LambdaQueryWrapper<QualityCheckReport> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(QualityCheckReport::getRuleId, ruleId)
                .orderByDesc(QualityCheckReport::getCreateTime)
                .orderByDesc(QualityCheckReport::getId);
        return qualityCheckReportMapper.selectList(wrapper);
    }

    /**
     * 查询必须存在的规则。
     * 这是服务层里非常常见的收口方法，用于消除重复的“查询 + 判空”模板代码。
     */
    private QualityRule getRequiredRule(Long id) {
        QualityRule rule = getById(id);
        if (rule == null) {
            throw new NoSuchElementException("质量规则不存在: " + id);
        }
        return rule;
    }

    /**
     * 校验规则是否已被逻辑删除。
     * 被删除规则不应该再参与修改、启停和执行。
     */
    private void ensureNotDeleted(QualityRule rule) {
        if (QualityRuleStatus.DELETED.equals(rule.getStatus())) {
            throw new IllegalStateException("质量规则已删除: " + rule.getId());
        }
    }

    /**
     * 校验规则名称是否重复。
     * 规则名称在管理界面通常是主要识别字段，因此尽量保持唯一更利于治理和排障。
     */
    private void ensureRuleNameNotDuplicated(String name, Long currentId) {
        LambdaQueryWrapper<QualityRule> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(QualityRule::getName, name)
                .ne(currentId != null, QualityRule::getId, currentId)
                .ne(QualityRule::getStatus, QualityRuleStatus.DELETED);
        if (count(wrapper) > 0) {
            throw new IllegalArgumentException("质量规则名称已存在: " + name);
        }
    }

    /**
     * 构造质量检测摘要。
     * 摘要字段的存在意义，是让报告在列表视图里就能被快速阅读，
     * 而不需要每次都展开全部明细。
     */
    private String buildSummary(QualityRule rule, BigDecimal measuredValue, boolean passed,
                                Integer sampleSize, Integer exceptionCount) {
        return String.format(
                "规则[%s]针对对象[%s]的检测结果为[%s]，实际值=%s，期望值=%s，样本量=%d，异常数=%d",
                rule.getName(),
                rule.getTargetObject(),
                passed ? "PASSED" : "FAILED",
                measuredValue,
                rule.getExpectedValue(),
                sampleSize,
                exceptionCount
        );
    }
}
