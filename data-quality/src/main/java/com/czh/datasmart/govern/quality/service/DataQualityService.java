package com.czh.datasmart.govern.quality.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.czh.datasmart.govern.quality.entity.QualityCheckReport;
import com.czh.datasmart.govern.quality.entity.QualityRule;

import java.math.BigDecimal;
import java.util.List;

/**
 * 数据质量服务接口。
 */
public interface DataQualityService extends IService<QualityRule> {

    QualityRule createRule(String name, String ruleType, String targetObject, String comparisonOperator,
                           BigDecimal expectedValue, String severity, String description);

    QualityRule updateRule(Long id, String name, String targetObject, String comparisonOperator,
                           BigDecimal expectedValue, String severity, String description);

    QualityRule enableRule(Long id);

    QualityRule disableRule(Long id);

    QualityRule deleteRule(Long id);

    QualityCheckReport runQualityCheck(Long ruleId, BigDecimal measuredValue, Integer sampleSize,
                                       Integer exceptionCount, String notes);

    List<QualityCheckReport> listReportsByRuleId(Long ruleId);
}
