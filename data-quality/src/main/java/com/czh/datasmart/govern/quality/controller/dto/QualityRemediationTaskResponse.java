/**
 * @Author : Cui
 * @Date: 2026/06/28 15:10
 * @Description DataSmart Govern Backend - QualityRemediationTaskResponse.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.quality.controller.dto;

import com.czh.datasmart.govern.quality.integration.task.QualityRemediationTaskPayload;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 质量异常治理任务创建响应。
 *
 * <p>响应被设计成“可解释但低敏”的结构：调用方能知道是否创建了任务、任务 ID 是多少、
 * 匹配了多少异常、使用了哪个 taskType、为什么没有提交，但不能从响应中拿到异常样本值、
 * recordIdentifier、observedValue、samplePayload、SQL、prompt、模型输出或凭据。</p>
 */
@Data
public class QualityRemediationTaskResponse {

    /**
     * 是否已经向 task-management 成功提交任务。
     */
    private boolean submitted;

    /**
     * 是否为预演模式。
     *
     * <p>dryRun=true 时 submitted 一定为 false，因为没有产生真实任务副作用。</p>
     */
    private boolean dryRun;

    /**
     * 人类可读的处理结果说明。
     */
    private String message;

    /**
     * task-management 返回的任务 ID。
     */
    private Long taskId;

    /**
     * task-management 任务类型，例如 DATA_QUALITY_REMEDIATION。
     */
    private String taskType;

    /**
     * task-management 返回的任务状态，例如 PENDING。
     */
    private String taskStatus;

    /**
     * 实际使用的任务优先级。
     */
    private String priority;

    /**
     * 本次筛选条件命中的异常数量。
     *
     * <p>这是聚合数量，不是明细列表。即使调用方后续要查看样本，也应该走受权限保护的异常工作台，
     * 而不是从任务创建响应里获得明细。</p>
     */
    private Long anomalyCount;

    /**
     * 任务所属租户。
     */
    private Long tenantId;

    /**
     * 任务所属项目。
     */
    private Long projectId;

    /**
     * 任务所属工作空间。
     */
    private Long workspaceId;

    /**
     * 关联质量报告。
     */
    private Long reportId;

    /**
     * 关联质量规则。
     */
    private Long ruleId;

    /**
     * payload 低敏策略说明。
     */
    private String payloadPolicy;

    /**
     * 低敏 payload 预览。
     *
     * <p>预览用于前端确认、Agent dry-run 和学习调试。它故意不包含任何敏感样本正文，
     * 只包含筛选条件、数量、TOP 聚合和治理意图。</p>
     */
    private QualityRemediationTaskPayload payloadPreview;

    /**
     * 非阻断警告。
     *
     * <p>例如 task-management 集成关闭、fail-open 返回未提交、或可见项目为空。
     * 警告用于帮助调用方解释“为什么这次没有真实任务 ID”。</p>
     */
    private List<String> warnings = new ArrayList<>();
}
