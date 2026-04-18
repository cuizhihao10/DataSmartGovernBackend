package com.czh.datasmart.govern.task.support;

import java.util.Arrays;

/**
 * @Author : Cui
 * @Date: 2026/4/18 22:12
 * @Description DataSmart Govern Backend - TaskPriority.java
 * @Version:1.0.0
 *
 * 任务优先级枚举。
 * 当前它的核心职责不是做复杂调度算法，而是做输入归一化和语义约束。
 * 在项目早期如果不尽早把优先级值收敛成统一常量，
 * 数据库里很容易出现 HIGH、high、High 这类混杂数据，后续统计和排序都会变得麻烦。
 */
public enum TaskPriority {
    HIGH,
    MEDIUM,
    LOW;

    /**
     * 将外部传入的优先级字符串归一化为标准枚举名。
     * 设计规则：
     * 1. 不传或空白时，默认 MEDIUM，保证任务创建简单可用。
     * 2. 传入非法值时立即报错，阻止脏数据进入数据库。
     */
    public static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return MEDIUM.name();
        }

        return Arrays.stream(values())
                .map(Enum::name)
                .filter(item -> item.equalsIgnoreCase(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("不支持的任务优先级: " + value));
    }
}
