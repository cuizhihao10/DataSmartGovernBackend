/**
 * @Author : Cui
 * @Date: 2026/4/18 21:35
 * @Description DataSmart Govern Backend - QualitySeverity.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.quality.support;

import java.util.Arrays;

/**
 * 质量规则严重级别。
 * 严重级别不是决定规则能否执行，而是表达规则失败后对业务影响有多大。
 * 这个字段后续在告警、报表分层和治理优先级排序时会很有价值。
 */
public enum QualitySeverity {
    HIGH,
    MEDIUM,
    LOW;

    /**
     * 将外部输入归一化为系统内部标准值。
     * 如果调用方不传，则默认使用 MEDIUM。
     */
    public static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return MEDIUM.name();
        }
        return Arrays.stream(values())
                .map(Enum::name)
                .filter(item -> item.equalsIgnoreCase(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("不支持的质量严重级别: " + value));
    }
}
