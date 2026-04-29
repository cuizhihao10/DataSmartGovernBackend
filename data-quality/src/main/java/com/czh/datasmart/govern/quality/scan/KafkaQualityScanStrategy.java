/**
 * @Author : Cui
 * @Date: 2026/04/27 21:50
 * @Description DataSmart Govern Backend - KafkaQualityScanStrategy.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.quality.scan;

import com.czh.datasmart.govern.quality.controller.dto.QualityRuleTargetValidationResult;
import com.czh.datasmart.govern.quality.entity.QualityRule;
import com.czh.datasmart.govern.quality.support.QualityRuleTargetType;
import org.springframework.stereotype.Component;

/**
 * Kafka Topic 质量扫描策略。
 *
 * <p>流式数据质量检测与离线表检测差异很大。
 * 它通常要考虑消费位点、消息积压、窗口时间、反压、重复消费和采样成本。
 * 当前策略先校验 topic 名是否存在于 targetObject 中，为后续接入 Kafka 采样执行器预留路由。
 */
@Component
public class KafkaQualityScanStrategy extends AbstractQualityScanStrategy {

    @Override
    public String strategyCode() {
        return "KAFKA_TOPIC_SAMPLE_SCAN";
    }

    @Override
    public boolean supports(QualityRuleTargetType targetType) {
        return QualityRuleTargetType.KAFKA_TOPIC.equals(targetType);
    }

    @Override
    public QualityRuleTargetValidationResult validate(QualityRule rule) {
        if (!hasText(rule.getTargetObject())) {
            return invalidResult(rule, "Kafka 质量规则缺少 topic 名称",
                    "请在 targetObject 中填写 topic，例如 ods.customer.changed。");
        }
        QualityRuleTargetValidationResult result = validResult(rule, "Kafka Topic 目标结构校验通过，可作为后续流式质量采样目标");
        result.getSuggestions().add("后续需要补充消费组、窗口大小、最大采样条数、超时时间和重复消费幂等策略。");
        return result;
    }
}
