/**
 * @Author : Cui
 * @Date: 2026/06/22 20:19
 * @Description DataSmart Govern Backend - QualityExecutionDiagnosticsResponse.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.quality.controller.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 质量执行诊断响应。
 *
 * <p>这是面向管理员、运营人员和后续告警系统的低敏诊断视图，目标不是替代报告详情页，
 * 而是回答几个生产排障的第一层问题：</p>
 *
 * <p>1. 当前执行链路是否配置为可运行；</p>
 * <p>2. 最近是否存在 RUNNING 卡住、FAILED 增多或报告 FAILED 增多；</p>
 * <p>3. 异常样本数量是否已经产生，需要进入异常工作台；</p>
 * <p>4. 查询是否被 PROJECT 范围过滤，避免管理员误把“无权限”看成“无数据”。</p>
 *
 * <p>安全边界：本响应不返回 scanPlanSnapshot、message 正文、异常样本、SQL、连接串、
 * 内部 endpoint、认证信息或 datasource 查询结果。需要看正文时，应走更高权限的详情接口、
 * 审计流程或脱敏导出流程。</p>
 */
@Data
public class QualityExecutionDiagnosticsResponse {

    /**
     * 响应契约版本。
     *
     * <p>诊断视图后续很可能继续增加指标和卡片。显式版本能帮助前端或外部集成判断字段语义，
     * 避免把新增字段误解成旧版本固定行为。</p>
     */
    private String schemaVersion;

    /**
     * 本次诊断生成时间。
     */
    private LocalDateTime inspectedAt;

    /**
     * 查询条件中的租户 ID。
     */
    private Long tenantId;

    /**
     * 查询条件中的项目 ID。
     */
    private Long projectId;

    /**
     * 查询条件中的工作空间 ID。
     */
    private Long workspaceId;

    /**
     * 查询条件中的质量规则 ID。
     */
    private Long ruleId;

    /**
     * 最近执行返回条数上限，已经过安全裁剪。
     */
    private Integer recentExecutionLimit;

    /**
     * 当前请求是否启用了 PROJECT 级数据范围过滤。
     */
    private Boolean projectScopeEnforced;

    /**
     * PROJECT 范围下是否存在可见项目。
     */
    private Boolean hasVisibleProjects;

    /**
     * 执行状态计数，例如 RUNNING、SUCCESS、FAILED。
     */
    private Map<String, Long> executionStateCounts;

    /**
     * 报告检测结果计数，例如 PASSED、FAILED。
     */
    private Map<String, Long> reportStatusCounts;

    /**
     * 报告严重级别计数，例如 HIGH、MEDIUM、LOW。
     */
    private Map<String, Long> severityCounts;

    /**
     * 当前过滤范围内的异常明细总数。
     */
    private Long anomalyCount;

    /**
     * 最近执行低敏快照。
     */
    private List<QualityExecutionDiagnosticsExecutionView> recentExecutions;

    /**
     * 当前执行器运行配置快照。
     */
    private QualityExecutionDiagnosticsRuntimeView runtime;

    /**
     * 诊断过程产生的运营提醒。
     */
    private List<String> warnings;

    /**
     * 数据范围策略说明。
     */
    private String dataVisibilityPolicy;

    /**
     * 敏感字段策略说明。
     */
    private String sensitiveDataPolicy;
}
