/**
 * @Author : Cui
 * @Date: 2026/04/27 22:05
 * @Description DataSmart Govern Backend - QualityScanExecutionMode.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.quality.support;

import java.util.Arrays;

/**
 * 质量扫描执行模式。
 *
 * <p>质量检测真正进入生产执行时，不应该只有“全表扫”这一种方式。
 * 不同数据规模、不同 SLA、不同源库承载能力下，应该选择不同扫描策略：
 * 1. SAMPLE_SCAN：抽样扫描，适合大表低风险快速巡检；
 * 2. FULL_SCAN：全量扫描，适合小表或高风险规则；
 * 3. PARTITION_SCAN：按分区字段扫描，适合大表日分区、月分区或租户分区；
 * 4. INCREMENTAL_WINDOW：按时间窗口扫描，适合周期性质量任务。
 */
public enum QualityScanExecutionMode {

    /**
     * 抽样扫描。
     */
    SAMPLE_SCAN,

    /**
     * 全量扫描。
     */
    FULL_SCAN,

    /**
     * 分区扫描。
     */
    PARTITION_SCAN,

    /**
     * 增量窗口扫描。
     */
    INCREMENTAL_WINDOW;

    /**
     * 外部入参归一化。
     *
     * <p>默认使用 SAMPLE_SCAN，避免在没有明确性能配置时直接全表扫描。
     */
    public static QualityScanExecutionMode fromValue(String value) {
        if (value == null || value.isBlank()) {
            return SAMPLE_SCAN;
        }
        return Arrays.stream(values())
                .filter(item -> item.name().equalsIgnoreCase(value.trim()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("不支持的质量扫描执行模式: " + value));
    }
}
