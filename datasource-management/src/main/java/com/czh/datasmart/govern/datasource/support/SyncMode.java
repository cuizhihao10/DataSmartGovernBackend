package com.czh.datasmart.govern.datasource.support;

import java.util.Arrays;

/**
 * @Author : Cui
 * @Date: 2026/4/18 23:12
 * @Description DataSmart Govern Backend - SyncMode.java
 * @Version:1.0.0
 *
 * 同步模式枚举。
 * 同步模式决定的不是“任务名称”，而是数据移动的业务语义：
 * - 任务如何判定完成；
 * - 检查点保存什么；
 * - 可以怎样重试、回放、补数；
 * - 对一致性、延迟和吞吐有什么预期。
 *
 * 之所以把模式抽成独立枚举，而不是把相关配置都塞进 JSON，
 * 是因为同步模式会直接影响状态机、调度策略、前端交互和运维手册，
 * 必须成为可搜索、可过滤、可审计的一级字段。
 */
public enum SyncMode {
    FULL,
    INCREMENTAL_TIME,
    INCREMENTAL_ID,
    STREAMING,
    CDC,
    SCHEDULED_BATCH,
    OFFLINE_IMPORT,
    OFFLINE_EXPORT,
    REPLAY,
    BACKFILL;

    /**
     * 把外部输入转换为平台内部标准同步模式。
     */
    public static SyncMode fromValue(String value) {
        return Arrays.stream(values())
                .filter(item -> item.name().equalsIgnoreCase(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("不支持的同步模式: " + value));
    }
}
