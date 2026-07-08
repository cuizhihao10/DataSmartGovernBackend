/**
 * @Author : Cui
 * @Date: 2026/07/09 22:36
 * @Description DataSmart Govern Backend - SyncTableRowCountProbeInternalResponse.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.controller.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * 同步目标表行数探测 internal 响应。
 *
 * <p>响应只保留预检查需要的低敏事实：探测是否成功、行数、是否为空、耗时和 warning。
 * 它不返回生成的 SQL，也不返回目标表任何业务数据。这样 data-sync 可以据此判断全量 INSERT 是否安全，
 * 但不会扩大目标库数据暴露面。</p>
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class SyncTableRowCountProbeInternalResponse {

    public static final String PAYLOAD_POLICY =
            "INTERNAL_TABLE_ROW_COUNT_PROBE_NO_SQL_NO_ROWS_NO_CREDENTIALS_COUNT_ONLY_FOR_PRECHECK";

    /**
     * 探测状态。
     *
     * <p>典型值：</p>
     * <p>1. {@code ROW_COUNT_PROBED}：成功获得精确行数；</p>
     * <p>2. {@code ROW_COUNT_PROBE_FAILED}：受控查询失败，预检查应 fail-closed；</p>
     * <p>3. {@code ROW_COUNT_PROBE_REJECTED}：请求对象定位不安全或参数不完整。</p>
     */
    private String probeStatus;

    /**
     * 表行数。
     *
     * <p>当前为了本地 E2E 和产品闭环使用精确 {@code COUNT(*)}。生产大表后续可引入统计信息估算，
     * 但必须在 warnings 中标注“估算值”，不能把估算伪装成精确事实。</p>
     */
    private Long rowCount;

    /**
     * 目标表是否为空。
     *
     * <p>全量 INSERT 场景只有在该值为 true 时才可直接放行；false 表示目标表已有数据，可能发生主键冲突或重复写入。</p>
     */
    private Boolean empty;

    /**
     * 探测耗时，单位毫秒。
     *
     * <p>该字段可用于后续观测“预检查是否因为大表 COUNT 变慢”，并为升级统计信息估算或异步预检提供依据。</p>
     */
    private Long durationMs;

    /**
     * 低敏提示。
     *
     * <p>例如“当前使用精确 COUNT(*)，大表生产环境建议切换为估算或抽样”。</p>
     */
    private List<String> warnings;

    /**
     * 载荷安全策略说明。
     */
    private String payloadPolicy;
}
