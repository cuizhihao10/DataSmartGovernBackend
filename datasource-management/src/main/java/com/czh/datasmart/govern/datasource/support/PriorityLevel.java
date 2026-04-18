package com.czh.datasmart.govern.datasource.support;

import java.util.Arrays;

/**
 * @Author : Cui
 * @Date: 2026/4/18 23:12
 * @Description DataSmart Govern Backend - PriorityLevel.java
 * @Version:1.0.0
 *
 * 任务优先级枚举。
 * 优先级不是装饰性字段，它会直接影响：
 * - 后续调度器的出队顺序；
 * - 运维人员处置告警时的判断；
 * - 紧急补数、生产修复、故障回放等场景的资源倾斜策略。
 */
public enum PriorityLevel {
    LOW,
    MEDIUM,
    HIGH,
    URGENT;

    public static PriorityLevel fromValue(String value) {
        return Arrays.stream(values())
                .filter(item -> item.name().equalsIgnoreCase(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("不支持的优先级: " + value));
    }
}
