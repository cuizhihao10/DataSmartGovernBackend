/**
 * @Author : Cui
 * @Date: 2026/04/27 22:05
 * @Description DataSmart Govern Backend - QualityScanPlan.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.quality.controller.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 质量扫描计划。
 *
 * <p>扫描计划是“规则”和“执行任务”之间的中间产物。
 * 规则描述业务标准，扫描计划描述执行器应该如何访问数据、扫描多大范围、如何保护源系统。
 *
 * <p>当前计划只生成并返回，不落库、不执行。
 * 后续接入 task-management 后，可以把这个对象序列化为任务 payload，
 * 让质量执行器按计划领取、心跳、超时恢复和生成报告。
 */
@Data
public class QualityScanPlan {

    /**
     * 规则 ID。
     */
    private Long ruleId;

    /**
     * 规则名称。
     */
    private String ruleName;

    /**
     * 规则版本。
     *
     * <p>执行任务应该记录规则版本，避免规则更新后历史执行计划解释不清。
     */
    private Integer ruleVersion;

    /**
     * 目标类型。
     */
    private String targetType;

    /**
     * 扫描策略编码。
     */
    private String scanStrategy;

    /**
     * 执行模式。
     */
    private String executionMode;

    /**
     * 数据源 ID。
     */
    private Long dataSourceId;

    /**
     * 数据库名称。
     */
    private String databaseName;

    /**
     * Schema 名称。
     */
    private String schemaName;

    /**
     * 表名。
     */
    private String tableName;

    /**
     * 字段名。
     */
    private String fieldName;

    /**
     * 抽样行数上限。
     */
    private Integer sampleLimit;

    /**
     * 最大扫描行数。
     */
    private Long maxScannedRows;

    /**
     * 分区字段。
     */
    private String partitionField;

    /**
     * 过滤条件。
     */
    private String whereClause;

    /**
     * 超时时间，单位秒。
     */
    private Integer timeoutSeconds;

    /**
     * 是否采集异常样本。
     */
    private Boolean collectAnomalySamples;

    /**
     * 异常样本数量上限。
     */
    private Integer anomalySampleLimit;

    /**
     * 计划风险级别。
     *
     * <p>当前使用 LOW、MEDIUM、HIGH 三档，方便前端用醒目的方式提示全表扫描或高行数扫描风险。
     */
    private String riskLevel;

    /**
     * 计划是否具备进入调度任务的最低条件。
     */
    private Boolean schedulable;

    /**
     * 计划说明。
     */
    private String message;

    /**
     * 风险提示。
     */
    private List<String> warnings = new ArrayList<>();

    /**
     * 执行建议。
     */
    private List<String> suggestions = new ArrayList<>();

    /**
     * 计划生成时间。
     */
    private LocalDateTime plannedTime;
}
