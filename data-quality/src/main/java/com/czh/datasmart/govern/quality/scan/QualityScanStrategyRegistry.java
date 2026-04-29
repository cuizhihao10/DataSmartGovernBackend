/**
 * @Author : Cui
 * @Date: 2026/04/27 21:50
 * @Description DataSmart Govern Backend - QualityScanStrategyRegistry.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.quality.scan;

import com.czh.datasmart.govern.quality.controller.dto.QualityScanPlan;
import com.czh.datasmart.govern.quality.controller.dto.QualityScanPlanRequest;
import com.czh.datasmart.govern.quality.controller.dto.QualityRuleTargetValidationResult;
import com.czh.datasmart.govern.quality.entity.QualityRule;
import com.czh.datasmart.govern.quality.support.QualityRuleTargetType;
import com.czh.datasmart.govern.quality.support.QualityTargetValidationStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 质量扫描策略注册表。
 *
 * <p>Spring 会把所有实现了 QualityScanStrategy 的 Bean 注入进来。
 * 这样新增一种扫描能力时，只需要新增一个策略类并声明 @Component，
 * 不需要修改 DataQualityServiceImpl 的 if/else 分支。
 *
 * <p>这也是商业化产品里常见的可扩展设计：
 * 先稳定业务主流程，再通过策略、插件、连接器逐步扩展数据源和执行方式。
 */
@Component
@RequiredArgsConstructor
public class QualityScanStrategyRegistry {

    /**
     * 当前模块内所有扫描策略。
     */
    private final List<QualityScanStrategy> strategies;

    /**
     * 根据规则目标类型选择策略并执行校验。
     *
     * <p>如果暂时没有策略支持该目标类型，会返回 UNSUPPORTED，而不是抛出系统异常。
     * 这样前端可以把“不支持”作为产品状态展示给用户，而不是把它当成服务故障。
     */
    public QualityRuleTargetValidationResult validate(QualityRule rule) {
        QualityRuleTargetType targetType = QualityRuleTargetType.fromValue(rule.getTargetType());
        return strategies.stream()
                .filter(strategy -> strategy.supports(targetType))
                .findFirst()
                .map(strategy -> strategy.validate(rule))
                .orElseGet(() -> unsupportedResult(rule, targetType));
    }

    /**
     * 根据规则目标类型生成扫描计划。
     *
     * <p>生成计划前不直接执行数据扫描。它只是让调度器、执行器和前端预览“未来会怎么扫”。
     */
    public QualityScanPlan buildScanPlan(QualityRule rule, QualityScanPlanRequest request) {
        QualityRuleTargetType targetType = QualityRuleTargetType.fromValue(rule.getTargetType());
        return strategies.stream()
                .filter(strategy -> strategy.supports(targetType))
                .findFirst()
                .map(strategy -> strategy.buildScanPlan(rule, request))
                .orElseGet(() -> unsupportedPlan(rule, targetType));
    }

    /**
     * 构造不支持结果。
     */
    private QualityRuleTargetValidationResult unsupportedResult(QualityRule rule, QualityRuleTargetType targetType) {
        QualityRuleTargetValidationResult result = new QualityRuleTargetValidationResult();
        result.setRuleId(rule.getId());
        result.setValid(false);
        result.setTargetType(targetType.name());
        result.setScanStrategy("UNSUPPORTED");
        result.setValidationStatus(QualityTargetValidationStatus.UNSUPPORTED);
        result.setMessage("当前平台暂不支持该目标类型的质量扫描: " + targetType.name());
        result.getSuggestions().add("请先选择 GENERIC、RELATIONAL_TABLE、RELATIONAL_FIELD、KAFKA_TOPIC、FILE_OBJECT 或 API_ENDPOINT 中已支持的目标类型。");
        result.setValidatedTime(LocalDateTime.now());
        return result;
    }

    /**
     * 构造不支持目标类型的扫描计划。
     */
    private QualityScanPlan unsupportedPlan(QualityRule rule, QualityRuleTargetType targetType) {
        QualityScanPlan plan = new QualityScanPlan();
        plan.setRuleId(rule.getId());
        plan.setRuleName(rule.getName());
        plan.setRuleVersion(rule.getRuleVersion());
        plan.setTargetType(targetType.name());
        plan.setScanStrategy("UNSUPPORTED");
        plan.setSchedulable(false);
        plan.setRiskLevel("HIGH");
        plan.setMessage("当前平台暂不支持该目标类型的扫描计划: " + targetType.name());
        plan.getSuggestions().add("请先选择已支持的目标类型，或为该目标类型新增扫描策略实现。");
        plan.setPlannedTime(LocalDateTime.now());
        return plan;
    }
}
