package com.czh.datasmart.govern.datasource.controller.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * @Author : Cui
 * @Date: 2026/04/28 20:12
 * @Description DataSmart Govern Backend - ReadOnlySqlExecutionResult.java
 * @Version:1.0.0
 *
 * 受控只读 SQL 执行结果。
 *
 * 结果对象刻意保留了“执行信息 + 结果集 + 警告”三类字段：
 * - 执行信息用于排查：查的是哪个数据源、耗时多久、应用了多少行上限；
 * - 结果集用于消费：质量模块可以读取统计值或异常样本；
 * - 警告用于产品提示：告诉调用方本接口不是大规模导出通道，值已被字符串化，SQL 已被二次包裹限流。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReadOnlySqlExecutionResult {

    /**
     * 数据源 ID。
     */
    private Long datasourceId;

    /**
     * 数据源名称。
     */
    private String datasourceName;

    /**
     * 数据源类型，例如 MYSQL、POSTGRESQL、SQLSERVER。
     */
    private String datasourceType;

    /**
     * 本次执行目的。
     *
     * 当前来自请求体，后续应由上游模块按标准枚举传入，便于审计、统计和权限策略差异化。
     */
    private String purpose;

    /**
     * 是否真实执行了 SQL。
     *
     * 当前如果开关关闭或权限失败会直接抛错，不返回 false；
     * 这里预留字段是为了后续支持 dry-run、审核预检、策略模拟等场景。
     */
    private Boolean executed;

    /**
     * 实际返回行数。
     */
    private Integer returnedRowCount;

    /**
     * 结果列数量。
     */
    private Integer columnCount;

    /**
     * 服务端最终应用的最大返回行数。
     */
    private Integer appliedMaxRows;

    /**
     * 服务端最终应用的查询超时秒数。
     */
    private Integer appliedQueryTimeoutSeconds;

    /**
     * SQL 执行和结果读取总耗时，单位毫秒。
     */
    private Long durationMs;

    /**
     * 列名列表。
     *
     * 使用 ResultSetMetaData.getColumnLabel 获取，优先保留 SQL alias，便于质量 SQL 输出 metric_value 等业务列名。
     */
    private List<String> columns;

    /**
     * 行数据。
     *
     * 当前把 JDBC 值统一转为字符串或 null，避免不同数据库驱动返回 BigDecimal、Timestamp、Blob 等对象导致 JSON 序列化差异。
     * 后续如果质量扫描需要强类型数值，可以在 data-quality 侧对指定列做显式解析。
     */
    private List<Map<String, Object>> rows;

    /**
     * 执行警告。
     *
     * 这里会返回限流、脱敏、字符串化、非导出用途等提示，帮助调用方理解接口边界。
     */
    private List<String> warnings;

    /**
     * 执行时间。
     */
    private LocalDateTime executedAt;

    /**
     * 面向调用方的摘要消息。
     */
    private String message;
}
