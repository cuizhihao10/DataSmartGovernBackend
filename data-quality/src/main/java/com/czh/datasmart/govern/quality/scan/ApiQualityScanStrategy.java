/**
 * @Author : Cui
 * @Date: 2026/04/27 21:50
 * @Description DataSmart Govern Backend - ApiQualityScanStrategy.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.quality.scan;

import com.czh.datasmart.govern.quality.controller.dto.QualityRuleTargetValidationResult;
import com.czh.datasmart.govern.quality.entity.QualityRule;
import com.czh.datasmart.govern.quality.support.QualityRuleTargetType;
import org.springframework.stereotype.Component;

/**
 * API 响应质量扫描策略。
 *
 * <p>API 质量检测常见于第三方接口、内部服务接口、主数据服务和指标服务。
 * 它关注的不是表字段，而是接口是否可访问、响应结构是否稳定、关键字段是否缺失、
 * 状态码是否符合预期、延迟是否超过阈值。
 *
 * <p>当前阶段只做 endpoint 字符串校验，后续可接入 HTTP 客户端、鉴权配置、超时重试和响应 JSONPath 校验。
 */
@Component
public class ApiQualityScanStrategy extends AbstractQualityScanStrategy {

    @Override
    public String strategyCode() {
        return "API_RESPONSE_CONTRACT_SCAN";
    }

    @Override
    public boolean supports(QualityRuleTargetType targetType) {
        return QualityRuleTargetType.API_ENDPOINT.equals(targetType);
    }

    @Override
    public QualityRuleTargetValidationResult validate(QualityRule rule) {
        if (!hasText(rule.getTargetObject())) {
            return invalidResult(rule, "API 质量规则缺少接口地址或接口标识",
                    "请在 targetObject 中填写接口地址或稳定接口编码，例如 https://api.example.com/customers。");
        }
        QualityRuleTargetValidationResult result = validResult(rule, "API 目标结构校验通过，可作为后续接口响应质量检测目标");
        result.getSuggestions().add("后续需要补充鉴权方式、请求方法、请求模板、超时时间、重试策略和响应字段路径。");
        return result;
    }
}
