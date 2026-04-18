/**
 * @Author : Cui
 * @Date: 2026/4/18 21:35
 * @Description DataSmart Govern Backend - QualityRuleType.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.quality.support;

import java.util.Arrays;

/**
 * 质量规则类型。
 * 当前先抽取最常见且最容易形成治理闭环的质量维度，
 * 让模块先把“规则定义”和“规则执行”这两部分跑通。
 */
public enum QualityRuleType {

    /**
     * 完整性：数据是否缺失。
     */
    COMPLETENESS,

    /**
     * 唯一性：是否存在重复值。
     */
    UNIQUENESS,

    /**
     * 有效性：值是否符合预期格式或范围。
     */
    VALIDITY,

    /**
     * 一致性：不同来源或不同字段之间是否一致。
     */
    CONSISTENCY,

    /**
     * 准确性：数据是否接近真实世界或基准值。
     */
    ACCURACY;

    /**
     * 将外部输入解析为规则类型。
     */
    public static QualityRuleType fromValue(String value) {
        return Arrays.stream(values())
                .filter(item -> item.name().equalsIgnoreCase(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("不支持的质量规则类型: " + value));
    }
}
