/**
 * @Author : Cui
 * @Date: 2026/4/18 21:35
 * @Description DataSmart Govern Backend - DataQualityService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.quality.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.czh.datasmart.govern.quality.entity.QualityCheckReport;
import com.czh.datasmart.govern.quality.entity.QualityRule;

import java.math.BigDecimal;
import java.util.List;

/**
 * 数据质量服务接口。
 * 这里定义的是模块对外暴露的核心业务能力，而不是单纯围绕数据库操作命名。
 * 它覆盖两个方向：
 * 1. 规则管理。
 * 2. 规则执行与报告查询。
 */
public interface DataQualityService extends IService<QualityRule> {

    /**
     * 创建质量规则。
     */
    QualityRule createRule(String name, String ruleType, String targetObject, String comparisonOperator,
                           BigDecimal expectedValue, String severity, String description);

    /**
     * 更新质量规则。
     */
    QualityRule updateRule(Long id, String name, String targetObject, String comparisonOperator,
                           BigDecimal expectedValue, String severity, String description);

    /**
     * 启用规则。
     */
    QualityRule enableRule(Long id);

    /**
     * 停用规则。
     */
    QualityRule disableRule(Long id);

    /**
     * 逻辑删除规则。
     */
    QualityRule deleteRule(Long id);

    /**
     * 执行一次质量检测并生成报告。
     */
    QualityCheckReport runQualityCheck(Long ruleId, BigDecimal measuredValue, Integer sampleSize,
                                       Integer exceptionCount, String notes);

    /**
     * 查询某个规则下的历史报告。
     */
    List<QualityCheckReport> listReportsByRuleId(Long ruleId);
}
