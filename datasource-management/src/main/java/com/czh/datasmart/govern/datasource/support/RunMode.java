package com.czh.datasmart.govern.datasource.support;

import java.util.Arrays;

/**
 * @Author : Cui
 * @Date: 2026/4/18 23:12
 * @Description DataSmart Govern Backend - RunMode.java
 * @Version:1.0.0
 *
 * 执行模式枚举。
 * 这个字段与同步模式不同：
 * - 同步模式描述“搬什么、按什么业务语义搬”；
 * - 执行模式描述“这次运行采用什么控制方式”。
 */
public enum RunMode {
    MANUAL,
    SCHEDULED,
    DRY_RUN,
    REPLAY,
    BACKFILL,
    RECOVERY;

    public static RunMode fromValue(String value) {
        return Arrays.stream(values())
                .filter(item -> item.name().equalsIgnoreCase(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("不支持的执行模式: " + value));
    }
}
