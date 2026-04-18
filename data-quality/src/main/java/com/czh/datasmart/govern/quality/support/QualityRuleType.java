package com.czh.datasmart.govern.quality.support;

import java.util.Arrays;

/**
 * 质量规则类型。
 * <p>
 * 当前先聚焦最常见、最容易理解的规则类别，让模块先把“规则定义”和“执行结果”跑通。
 */
public enum QualityRuleType {
    COMPLETENESS,
    UNIQUENESS,
    VALIDITY,
    CONSISTENCY,
    ACCURACY;

    public static QualityRuleType fromValue(String value) {
        return Arrays.stream(values())
                .filter(item -> item.name().equalsIgnoreCase(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unsupported quality rule type: " + value));
    }
}
