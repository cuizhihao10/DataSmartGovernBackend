/**
 * @Author : Cui
 * @Date: 2026/06/28 15:10
 * @Description DataSmart Govern Backend - QualityRemediationTaskPayload.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.quality.integration.task;

import com.czh.datasmart.govern.quality.controller.dto.QualityAnomalyAggregationItem;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DATA_QUALITY_REMEDIATION 任务载荷。
 *
 * <p>这个对象会被序列化到 task-management 的 `params` 字段，因此它是跨微服务协作合同，
 * 不是普通内部临时 Map。把它建模成强类型类有三个重要原因：</p>
 *
 * <p>1. 版本可控：schemaVersion 可以帮助后续清洗执行器或 Agent 判断如何兼容旧任务；</p>
 * <p>2. 低敏可审查：字段列表清楚声明哪些信息允许进入任务中心，避免样本值和 SQL 悄悄扩散；</p>
 * <p>3. 职责清晰：data-quality 只提交“治理意图和异常聚合”，task-management 只调度任务，不理解质量明细表结构。</p>
 */
@Data
public class QualityRemediationTaskPayload {

    /**
     * payload schema 版本。
     */
    private String schemaVersion = "DATA_QUALITY_REMEDIATION_TASK_V1";

    /**
     * payload 来源模块。
     */
    private String sourceModule = "data-quality";

    /**
     * 业务任务种类。
     */
    private String taskKind = "QUALITY_REMEDIATION";

    /**
     * 低敏策略说明。
     *
     * <p>下游、人审页面和审计工具看到该字段后，应明确知道此 payload 只包含聚合与筛选摘要，
     * 不包含异常样本正文、SQL、prompt、模型输出、工具参数、凭据或内部 endpoint。</p>
     */
    private String payloadPolicy;

    /**
     * 租户 ID。
     */
    private Long tenantId;

    /**
     * 项目 ID。
     */
    private Long projectId;

    /**
     * 工作空间 ID。
     */
    private Long workspaceId;

    /**
     * 关联报告 ID。
     */
    private Long reportId;

    /**
     * 关联规则 ID。
     */
    private Long ruleId;

    /**
     * 报告快照中的规则名称。
     *
     * <p>规则名称用于任务标题和人工识别。它不是规则详情，也不包含 SQL 或阈值正文。</p>
     */
    private String ruleName;

    /**
     * 规则类型快照。
     */
    private String ruleType;

    /**
     * 严重级别摘要。
     */
    private String severity;

    /**
     * 检测目标低敏摘要。
     */
    private String targetObject;

    /**
     * 治理类型，例如 MANUAL_REVIEW、CLEANING_PLAN、SOURCE_SYSTEM_FIX、RULE_TUNING。
     */
    private String remediationType;

    /**
     * 创建任务原因，已经过长度限制和敏感片段兜底隐藏。
     */
    private String reason;

    /**
     * 建议处理方式，已经过长度限制和敏感片段兜底隐藏。
     */
    private String recommendation;

    /**
     * 本次治理任务使用的筛选条件摘要。
     *
     * <p>这里使用 LinkedHashMap 是为了让 JSON 输出顺序更稳定，便于排障、测试断言和人工阅读。
     * Map 中只允许放低敏筛选条件，不允许放原始样本、观测值、SQL 或工具参数。</p>
     */
    private Map<String, Object> filters = new LinkedHashMap<>();

    /**
     * 命中异常数量。
     */
    private Long anomalyCount = 0L;

    /**
     * TOP 异常字段聚合。
     */
    private List<QualityAnomalyAggregationItem> topFields = new ArrayList<>();

    /**
     * TOP 异常类型聚合。
     */
    private List<QualityAnomalyAggregationItem> topTypes = new ArrayList<>();

    /**
     * TOP 严重级别聚合。
     */
    private List<QualityAnomalyAggregationItem> topSeverities = new ArrayList<>();

    /**
     * payload 创建时间。
     */
    private LocalDateTime createdAt;
}
