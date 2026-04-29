/**
 * @Author : Cui
 * @Date: 2026/04/28 21:06
 * @Description DataSmart Govern Backend - DatasourceReadOnlySqlExecutionResult.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.quality.integration.datasource;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * datasource-management 受控只读 SQL 执行结果的本地合同模型。
 *
 * <p>这里不追求映射远程结果的所有字段，而是保留质量执行器需要消费和记录的关键内容：
 * - rows/columns 用于解析质量指标和异常样本；
 * - durationMs/appliedMaxRows 用于写入任务结果摘要；
 * - warnings 用于后续审计和执行说明。
 */
@Data
public class DatasourceReadOnlySqlExecutionResult {

    /**
     * 数据源 ID。
     */
    private Long datasourceId;

    /**
     * 数据源名称。
     */
    private String datasourceName;

    /**
     * 数据源类型。
     */
    private String datasourceType;

    /**
     * 执行目的。
     */
    private String purpose;

    /**
     * 是否真实执行。
     */
    private Boolean executed;

    /**
     * 实际返回行数。
     */
    private Integer returnedRowCount;

    /**
     * 列数量。
     */
    private Integer columnCount;

    /**
     * 服务端应用的最大行数。
     */
    private Integer appliedMaxRows;

    /**
     * 服务端应用的查询超时秒数。
     */
    private Integer appliedQueryTimeoutSeconds;

    /**
     * 执行耗时毫秒。
     */
    private Long durationMs;

    /**
     * 结果列名。
     */
    private List<String> columns;

    /**
     * 行数据。
     *
     * <p>datasource-management 当前会把非空 JDBC 值统一转成字符串，
     * data-quality 在解析 metric 行时再显式转换为 BigDecimal 或 Integer。
     */
    private List<Map<String, Object>> rows;

    /**
     * 远程执行警告。
     */
    private List<String> warnings;

    /**
     * 执行时间。
     */
    private LocalDateTime executedAt;

    /**
     * 远程执行摘要消息。
     */
    private String message;
}
