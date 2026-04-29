/**
 * @Author : Cui
 * @Date: 2026/04/27 21:50
 * @Description DataSmart Govern Backend - AbstractQualityScanStrategy.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.quality.scan;

import com.czh.datasmart.govern.quality.controller.dto.QualityRuleTargetValidationResult;
import com.czh.datasmart.govern.quality.entity.QualityRule;
import com.czh.datasmart.govern.quality.support.QualityTargetValidationStatus;

import java.time.LocalDateTime;

/**
 * 质量扫描策略基类。
 *
 * <p>各类扫描策略都会构造类似的校验结果对象。
 * 抽出这个基类不是为了炫技，而是为了让每个策略类只关注自己的业务差异，
 * 例如关系型策略关心 datasourceId/tableName/fieldName，Kafka 策略关心 topic。
 */
public abstract class AbstractQualityScanStrategy implements QualityScanStrategy {

    /**
     * 构造一个校验通过的结果。
     */
    protected QualityRuleTargetValidationResult validResult(QualityRule rule, String message) {
        QualityRuleTargetValidationResult result = baseResult(rule);
        result.setValid(true);
        result.setValidationStatus(QualityTargetValidationStatus.VALIDATED);
        result.setMessage(message);
        return result;
    }

    /**
     * 构造一个校验失败的结果。
     */
    protected QualityRuleTargetValidationResult invalidResult(QualityRule rule, String message, String suggestion) {
        QualityRuleTargetValidationResult result = baseResult(rule);
        result.setValid(false);
        result.setValidationStatus(QualityTargetValidationStatus.INVALID);
        result.setMessage(message);
        result.getSuggestions().add(suggestion);
        return result;
    }

    /**
     * 构造基础结果字段。
     */
    private QualityRuleTargetValidationResult baseResult(QualityRule rule) {
        QualityRuleTargetValidationResult result = new QualityRuleTargetValidationResult();
        result.setRuleId(rule.getId());
        result.setTargetType(rule.getTargetType());
        result.setScanStrategy(strategyCode());
        result.setValidatedTime(LocalDateTime.now());
        return result;
    }

    /**
     * 判断字符串是否有真实内容。
     */
    protected boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
