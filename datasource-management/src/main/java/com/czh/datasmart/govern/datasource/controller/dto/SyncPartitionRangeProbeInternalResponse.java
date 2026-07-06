/**
 * @Author : Cui
 * @Date: 2026/07/07 23:22
 * @Description DataSmart Govern Backend - SyncPartitionRangeProbeInternalResponse.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.controller.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * 分片范围探测 internal 响应。
 *
 * <p>该响应只返回自动范围切分所需的最小事实：splitPk 的最小值、最大值、估算行数和低敏提示。
 * 它不返回 SQL、连接串、账号、密码、样本行、where 条件原文或数据库异常堆栈。</p>
 *
 * <p>注意：min/max 本身仍然可能透露业务数据范围，因此该响应只能在 data-sync 与 datasource-management
 * 的 internal 服务账号链路中流转，不应进入普通 API、日志、receipt 或审计摘要。</p>
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class SyncPartitionRangeProbeInternalResponse {

    public static final String PAYLOAD_POLICY =
            "INTERNAL_PARTITION_RANGE_PROBE_NO_SQL_NO_ROWS_NO_CREDENTIALS_MIN_MAX_FOR_CONTROL_PLANE_ONLY";

    /**
     * 探测状态。
     *
     * <p>典型值为 RANGE_PROBED、RANGE_EMPTY、RANGE_NOT_NUMERIC、RANGE_PROBE_FAILED。</p>
     */
    private String probeStatus;

    /**
     * splitPk 最小值。
     *
     * <p>当前仅支持数值型，因此使用 Long 承载第一阶段能力。后续如果支持 DECIMAL、时间窗口或字符串 hash，
     * 应新增策略字段，而不是复用本字段表达多种语义。</p>
     */
    private Long minValue;

    /**
     * splitPk 最大值。
     */
    private Long maxValue;

    /**
     * 当前对象的精确行数。
     *
     * <p>第一阶段使用 COUNT(*)，便于教学和闭环验证。生产大表可改为统计表估算值，并在 warnings 中标记
     * APPROXIMATE_ROW_COUNT，避免探测阶段拖慢源库。</p>
     */
    private Long rowCount;

    /**
     * 探测字段是否为数值范围。
     */
    private Boolean numericRange;

    /**
     * 低敏提示。
     */
    private List<String> warnings;

    /**
     * 载荷安全策略说明。
     */
    private String payloadPolicy;
}
