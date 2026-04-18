package com.czh.datasmart.govern.datasource.support;

import java.util.Arrays;

/**
 * @Author : Cui
 * @Date: 2026/4/18 23:12
 * @Description DataSmart Govern Backend - TriggerType.java
 * @Version:1.0.0
 *
 * 任务触发类型枚举。
 * 它回答的是“这次任务为什么会启动”，这个信息在审计、问题复盘和 SLA 统计里非常关键。
 */
public enum TriggerType {
    MANUAL,
    SCHEDULED,
    EVENT,
    DEPENDENCY,
    OPERATOR_RERUN,
    REPLAY,
    BACKFILL,
    SYSTEM_RECOVERY;

    public static TriggerType fromValue(String value) {
        return Arrays.stream(values())
                .filter(item -> item.name().equalsIgnoreCase(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("不支持的触发类型: " + value));
    }
}
