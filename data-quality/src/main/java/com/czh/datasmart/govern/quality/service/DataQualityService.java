/**
 * @Author : Cui
 * @Date: 2026/4/18 21:35
 * @Description DataSmart Govern Backend - DataQualityService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.quality.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;
import com.czh.datasmart.govern.quality.controller.dto.QualityAnomalyAggregationItem;
import com.czh.datasmart.govern.quality.controller.dto.QualityAnomalyDetailRequest;
import com.czh.datasmart.govern.quality.controller.dto.QualityExecutionFailRequest;
import com.czh.datasmart.govern.quality.controller.dto.QualityExecutionStartRequest;
import com.czh.datasmart.govern.quality.controller.dto.QualityExecutionSuccessRequest;
import com.czh.datasmart.govern.quality.controller.dto.QualityRuleTargetValidationResult;
import com.czh.datasmart.govern.quality.controller.dto.QualityScanPlan;
import com.czh.datasmart.govern.quality.controller.dto.QualityScanPlanRequest;
import com.czh.datasmart.govern.quality.controller.dto.QualityTaskScheduleRequest;
import com.czh.datasmart.govern.quality.controller.dto.QualityTaskScheduleResult;
import com.czh.datasmart.govern.quality.controller.dto.RelationalQualityScanSqlPlan;
import com.czh.datasmart.govern.quality.entity.QualityAnomalyDetail;
import com.czh.datasmart.govern.quality.entity.QualityCheckExecution;
import com.czh.datasmart.govern.quality.entity.QualityCheckReport;
import com.czh.datasmart.govern.quality.entity.QualityRule;
import com.czh.datasmart.govern.quality.service.support.QualityProjectVisibility;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 数据质量服务接口。
 * 这里定义的是模块对外暴露的核心业务能力，而不是单纯围绕数据库操作命名。
 * 它覆盖两个方向：
 * 1. 规则管理。
 * 2. 规则执行与报告查询。
 */
public interface DataQualityService extends IService<QualityRule> {

    /**
     * 创建质量规则。
     */
    QualityRule createRule(Long tenantId, Long projectId, Long workspaceId,
                           String name, String ruleType, String targetObject, String targetType,
                           Long dataSourceId, String databaseName, String schemaName, String tableName,
                           String fieldName, String comparisonOperator,
                           BigDecimal expectedValue, String severity, String description);

    /**
     * 更新质量规则。
     */
    QualityRule updateRule(Long id, String name, String targetObject, String targetType,
                           Long dataSourceId, String databaseName, String schemaName, String tableName,
                           String fieldName, String comparisonOperator,
                           BigDecimal expectedValue, String severity, String description);

    /**
     * 启用规则。
     */
    QualityRule enableRule(Long id);

    /**
     * 带原因启用规则。
     */
    QualityRule enableRule(Long id, String reason);

    /**
     * 停用规则。
     */
    QualityRule disableRule(Long id);

    /**
     * 带原因停用规则。
     */
    QualityRule disableRule(Long id, String reason);

    /**
     * 归档规则。
     */
    QualityRule archiveRule(Long id, String reason);

    /**
     * 从归档恢复规则。
     */
    QualityRule restoreRule(Long id, String reason);

    /**
     * 校验规则检测目标。
     *
     * <p>该方法会根据规则 targetType 选择扫描策略，校验规则是否具备被真实扫描的最低字段条件，
     * 并把校验状态写回规则表，方便管理后台展示。
     */
    QualityRuleTargetValidationResult validateRuleTarget(Long id);

    /**
     * 生成质量扫描计划。
     *
     * <p>扫描计划不会直接访问源数据，而是把规则和执行参数转换成可调度、可审查的执行说明。
     */
    QualityScanPlan buildScanPlan(Long id, QualityScanPlanRequest request);

    /**
     * 生成关系型质量扫描 SQL 计划。
     *
     * <p>该方法不会直接访问源数据库，只把质量规则和扫描计划转换成可审查的只读 SQL 模板。
     * 真正执行前仍需要 datasource-management 提供受控连接、权限校验、超时和审计。
     */
    RelationalQualityScanSqlPlan buildRelationalSqlPlan(Long id, QualityScanPlanRequest request);

    /**
     * 提交质量检测任务到任务中心。
     *
     * <p>该方法会先生成扫描计划，再把计划作为任务参数提交给 task-management。
     */
    QualityTaskScheduleResult scheduleQualityCheckTask(Long id, QualityTaskScheduleRequest request);

    /**
     * 记录任务触发的质量检测开始执行。
     *
     * <p>该能力主要面向未来的质量执行器，而不是普通前端用户。
     * 执行器从 task-management 认领 `DATA_QUALITY_SCAN` 任务后，先调用该方法创建 RUNNING 执行记录，
     * 再执行真实扫描，最后调用成功或失败回调收口。
     */
    QualityCheckExecution startTaskExecution(QualityExecutionStartRequest request);

    /**
     * 记录任务触发的质量检测成功完成，并生成质量报告。
     *
     * <p>这里的成功表示“扫描动作完成”，质量结果仍可能是 PASSED 或 FAILED。
     */
    QualityCheckReport completeTaskExecution(Long executionId, QualityExecutionSuccessRequest request);

    /**
     * 记录任务触发的质量检测执行失败。
     *
     * <p>失败执行通常不会生成质量报告，避免把连接失败、超时、执行器异常误判成业务数据质量不通过。
     */
    QualityCheckExecution failTaskExecution(Long executionId, QualityExecutionFailRequest request);

    /**
     * 逻辑删除规则。
     */
    QualityRule deleteRule(Long id);

    /**
     * 执行一次质量检测并生成报告。
     *
     * <p>anomalies 用于保存本次检测发现的异常样本。接口层允许为空，代表调用方当前只提供了汇总指标；
     * 如果传入明细，服务层会把它们与生成的报告绑定，形成“报告摘要 + 样本证据”的闭环。
     */
    QualityCheckReport runQualityCheck(Long ruleId, BigDecimal measuredValue, Integer sampleSize,
                                       Integer exceptionCount, String notes,
                                       List<QualityAnomalyDetailRequest> anomalies);

    /**
     * 查询某个规则下的历史报告。
     */
    List<QualityCheckReport> listReportsByRuleId(Long ruleId);

    /**
     * 分页查询质量报告。
     *
     * <p>这是面向运营后台、质量大盘和审计检索的横向查询能力，不再局限于“先进入某条规则详情再看报告”。
     * 真实产品里，管理员往往需要从失败报告、严重级别、目标对象、触发来源或时间窗口反向定位问题。
     */
    IPage<QualityCheckReport> pageReports(Integer current, Integer size, Long ruleId, String ruleType,
                                          String severity, String checkStatus, String targetObject,
                                          String triggerType, LocalDateTime startTime,
                                          LocalDateTime endTime, Boolean failedOnly,
                                          QualityProjectVisibility visibility);

    /**
     * 查询某份报告下的异常明细。
     *
     * <p>当前返回列表；后续如果异常量很大，应继续扩展为分页、导出和冷热存储策略。
     */
    List<QualityAnomalyDetail> listAnomaliesByReportId(Long reportId, QualityProjectVisibility visibility);

    /**
     * 分页查询异常明细。
     *
     * <p>这是面向异常运营台的全局明细检索能力。它不要求必须先进入某份报告，
     * 可以直接按规则、报告、异常类型、字段、严重级别、目标对象和时间范围定位问题。
     */
    IPage<QualityAnomalyDetail> pageAnomalies(Integer current, Integer size, Long reportId, Long ruleId,
                                              String anomalyType, String fieldName, String severity,
                                              String targetObject, LocalDateTime startTime,
                                              LocalDateTime endTime, QualityProjectVisibility visibility);

    /**
     * 聚合统计异常明细。
     *
     * <p>用于质量运营大盘回答“哪里问题最多”和“问题是否还在持续发生”。
     */
    List<QualityAnomalyAggregationItem> aggregateAnomalies(Long reportId, Long ruleId, String anomalyType,
                                                           String fieldName, String severity, String targetObject,
                                                           LocalDateTime startTime, LocalDateTime endTime,
                                                           String groupBy, Integer limit,
                                                           QualityProjectVisibility visibility);

    /**
     * 查询某个规则下的检测执行记录。
     */
    List<QualityCheckExecution> listExecutionsByRuleId(Long ruleId);
}
