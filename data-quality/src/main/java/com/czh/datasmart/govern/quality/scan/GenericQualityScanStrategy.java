/**
 * @Author : Cui
 * @Date: 2026/04/27 21:50
 * @Description DataSmart Govern Backend - GenericQualityScanStrategy.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.quality.scan;

import com.czh.datasmart.govern.quality.controller.dto.QualityRuleTargetValidationResult;
import com.czh.datasmart.govern.quality.entity.QualityRule;
import com.czh.datasmart.govern.quality.support.QualityRuleTargetType;
import org.springframework.stereotype.Component;

/**
 * 通用质量扫描策略。
 *
 * <p>这个策略用于兼容历史规则和人工指标类规则。
 * 它不假设存在具体数据源连接器，只要求 targetObject 有值。
 *
 * <p>为什么需要保留 GENERIC？
 * 1. 项目早期已经有只填写 targetObject 的规则；
 * 2. 一些质量指标可能来自外部系统计算结果，而不是平台自己扫描；
 * 3. 保留通用策略可以避免在引入结构化目标时破坏已有 API。
 */
@Component
public class GenericQualityScanStrategy extends AbstractQualityScanStrategy {

    @Override
    public String strategyCode() {
        return "GENERIC_MANUAL_VALUE";
    }

    @Override
    public boolean supports(QualityRuleTargetType targetType) {
        return QualityRuleTargetType.GENERIC.equals(targetType);
    }

    @Override
    public QualityRuleTargetValidationResult validate(QualityRule rule) {
        if (!hasText(rule.getTargetObject())) {
            return invalidResult(rule, "通用目标缺少 targetObject，无法说明规则作用对象",
                    "请填写检测目标，例如 customer.email、订单成功率 或 人工指标编码。");
        }
        QualityRuleTargetValidationResult result = validResult(rule, "通用目标结构校验通过，可通过人工观测值或外部系统结果生成报告");
        result.getSuggestions().add("当前策略不直接扫描数据源，后续如果目标来自真实表、Topic、文件或 API，建议改用结构化目标类型。");
        return result;
    }
}
