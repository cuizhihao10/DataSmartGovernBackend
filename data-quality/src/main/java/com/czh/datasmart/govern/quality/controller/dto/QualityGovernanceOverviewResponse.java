/**
 * @Author : Cui
 * @Date: 2026/06/27 20:48
 * @Description DataSmart Govern Backend - QualityGovernanceOverviewResponse.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.quality.controller.dto;

import com.czh.datasmart.govern.quality.support.QualityGovernanceRiskLevel;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 数据质量治理总览响应。
 *
 * <p>该 DTO 面向质量治理大盘、项目负责人首页、Agent 质量复盘和后续告警中心。它不是某条规则
 * 的详情，也不是某次执行的排障视图，而是把“规则覆盖、近期检测、异常积压、执行稳定性”
 * 聚合成一个低敏、可解释的治理态势快照。</p>
 *
 * <p>安全边界：本响应只返回计数、比例、ID 范围、枚举分布、风险等级和治理建议。它不返回
 * SQL、scanPlanSnapshot 正文、异常 samplePayload、observedValue、连接串、凭据、错误 message
 * 正文、内部 endpoint、prompt 或模型输出。这样运营人员能先判断方向，真正需要下钻样本时再走
 * 受控的报告详情、异常工作台、脱敏导出或审计流程。</p>
 */
@Data
public class QualityGovernanceOverviewResponse {

    /**
     * 响应契约版本。
     *
     * <p>治理总览后续可能继续增加趋势、SLA、告警、清洗任务和 Agent 复盘字段。显式版本可以让
     * 前端、网关和外部集成判断字段语义，避免把新增字段误认为旧版本固定行为。</p>
     */
    private String schemaVersion;

    /**
     * 本次总览生成时间。
     */
    private LocalDateTime generatedAt;

    /**
     * 查询条件中的租户 ID。
     *
     * <p>为空表示当前调用方没有显式按租户过滤，服务层仍会继续使用项目可见范围保护数据。</p>
     */
    private Long tenantId;

    /**
     * 查询条件中的项目 ID。
     *
     * <p>当请求处于 PROJECT 数据范围时，该字段会与授权项目集合共同生效，防止跨项目读取质量事实。</p>
     */
    private Long projectId;

    /**
     * 查询条件中的工作空间 ID。
     *
     * <p>用于区分研发、测试、生产或项目内不同协作空间的质量态势。</p>
     */
    private Long workspaceId;

    /**
     * 近期报告统计窗口开始时间。
     *
     * <p>规则库存是当前态，报告、异常和执行稳定性是时间窗口态。拆开这两类口径可以避免误解：
     * 没有近期报告不代表没有规则，只代表近期没有形成检测事实。</p>
     */
    private LocalDateTime windowStart;

    /**
     * 近期报告统计窗口结束时间。
     */
    private LocalDateTime windowEnd;

    /**
     * 调用方请求并经过服务端裁剪后的窗口天数。
     *
     * <p>服务端会把过小或过大的值压回安全范围，避免同步总览接口被误用成历史全量报表导出。</p>
     */
    private Integer windowDays;

    /**
     * TOP 聚合列表的返回上限。
     *
     * <p>异常 TOP 字段和 TOP 类型都使用该上限，服务端会裁剪到安全范围，保护数据库和接口响应体。</p>
     */
    private Integer topLimit;

    /**
     * 当前请求是否启用了 PROJECT 数据范围强制过滤。
     */
    private Boolean projectScopeEnforced;

    /**
     * PROJECT 数据范围下是否存在可见项目。
     *
     * <p>当该字段为 false 时，其他统计会安全收口为零或空列表，并且服务层不会继续访问数据库。</p>
     */
    private Boolean hasVisibleProjects;

    /**
     * 规则生命周期分布。
     *
     * <p>包含 DRAFT、ACTIVE、INACTIVE、ARCHIVED，默认不统计 DELETED。它用于回答“规则治理是否已经
     * 从草稿走向生产可执行”。</p>
     */
    private Map<String, Long> ruleStatusCounts;

    /**
     * 规则类型分布。
     *
     * <p>用于观察完整性、唯一性、有效性、一致性、准确性等质量维度是否均衡覆盖。</p>
     */
    private Map<String, Long> ruleTypeCounts;

    /**
     * 规则严重级别分布。
     *
     * <p>用于判断当前项目是否存在大量高严重级别规则，以及后续告警阈值和治理优先级如何设计。</p>
     */
    private Map<String, Long> ruleSeverityCounts;

    /**
     * 检测目标类型分布。
     *
     * <p>用于判断当前质量能力是否只覆盖关系型字段，还是已经扩展到表、Kafka Topic、文件对象和 API。</p>
     */
    private Map<String, Long> targetTypeCounts;

    /**
     * 近期报告结果分布。
     *
     * <p>包含 PASSED/FAILED，用于计算质量通过率和风险评分。</p>
     */
    private Map<String, Long> reportStatusCounts;

    /**
     * 执行动作状态分布。
     *
     * <p>包含 RUNNING/SUCCESS/FAILED。它让业务治理视图也能看到执行链路是否卡住或失败，但不会返回
     * 执行计划正文或错误正文。</p>
     */
    private Map<String, Long> executionStateCounts;

    /**
     * 近期报告总数。
     */
    private Long recentReportCount;

    /**
     * 近期失败报告数量。
     */
    private Long failedReportCount;

    /**
     * 近期报告通过率。
     *
     * <p>使用 PASSED / (PASSED + FAILED) 计算。没有近期报告时返回 0，避免前端出现除零或空值歧义。</p>
     */
    private BigDecimal passRate;

    /**
     * 当前过滤范围内的异常明细数量。
     *
     * <p>这里只返回数量，不返回 samplePayload、observedValue 或 recordIdentifier 正文。</p>
     */
    private Long anomalyCount;

    /**
     * TOP 异常字段。
     *
     * <p>用于快速定位“哪个字段问题最多”。聚合项只包含字段名、数量和最近发生时间，不包含样本值。</p>
     */
    private List<QualityAnomalyAggregationItem> topAnomalyFields;

    /**
     * TOP 异常类型。
     *
     * <p>用于快速定位“空值、重复、越界、格式错误”等问题类型的分布。</p>
     */
    private List<QualityAnomalyAggregationItem> topAnomalyTypes;

    /**
     * 质量治理评分，范围 0-100。
     *
     * <p>评分不是算法模型输出，而是规则化的运营启发式结果。它方便排序和告警，但不能替代详细报告。</p>
     */
    private Integer qualityScore;

    /**
     * 治理风险等级。
     */
    private QualityGovernanceRiskLevel riskLevel;

    /**
     * 下一步治理建议。
     *
     * <p>建议来自当前统计事实，例如补齐启用规则、处理失败报告、排查执行器失败、进入异常工作台等。
     * 它为后续 Agent 自动生成治理计划或创建清洗任务提供低敏上下文。</p>
     */
    private List<String> nextActions;

    /**
     * 数据可见范围说明。
     */
    private String dataVisibilityPolicy;

    /**
     * 敏感字段返回策略说明。
     */
    private String sensitiveDataPolicy;
}
