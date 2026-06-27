/**
 * @Author : Cui
 * @Date: 2026/06/27 20:48
 * @Description DataSmart Govern Backend - QualityGovernanceOverviewService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.quality.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.czh.datasmart.govern.quality.controller.dto.QualityAnomalyAggregationItem;
import com.czh.datasmart.govern.quality.controller.dto.QualityGovernanceOverviewResponse;
import com.czh.datasmart.govern.quality.entity.QualityAnomalyDetail;
import com.czh.datasmart.govern.quality.entity.QualityCheckExecution;
import com.czh.datasmart.govern.quality.entity.QualityCheckReport;
import com.czh.datasmart.govern.quality.entity.QualityRule;
import com.czh.datasmart.govern.quality.mapper.QualityAnomalyDetailMapper;
import com.czh.datasmart.govern.quality.mapper.QualityCheckExecutionMapper;
import com.czh.datasmart.govern.quality.mapper.QualityCheckReportMapper;
import com.czh.datasmart.govern.quality.mapper.QualityRuleMapper;
import com.czh.datasmart.govern.quality.service.support.QualityGovernanceOverviewCalculator;
import com.czh.datasmart.govern.quality.service.support.QualityProjectVisibility;
import com.czh.datasmart.govern.quality.support.QualityAnomalyAggregationDimension;
import com.czh.datasmart.govern.quality.support.QualityCheckExecutionState;
import com.czh.datasmart.govern.quality.support.QualityCheckStatus;
import com.czh.datasmart.govern.quality.support.QualityGovernanceRiskLevel;
import com.czh.datasmart.govern.quality.support.QualityRuleStatus;
import com.czh.datasmart.govern.quality.support.QualityRuleTargetType;
import com.czh.datasmart.govern.quality.support.QualityRuleType;
import com.czh.datasmart.govern.quality.support.QualitySeverity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据质量治理总览服务。
 *
 * <p>这个服务补齐的是 data-quality 的“治理大盘入口”。已有的规则管理接口回答“定义了哪些规则”，
 * 报告接口回答“某次检测结果如何”，执行器诊断接口回答“worker/任务链路是否健康”。但真实商业产品
 * 还需要一个更上层的问题：当前项目或工作空间的数据质量治理态势是否健康、风险在哪里、下一步应该做什么。</p>
 *
 * <p>设计上本服务只读取已有事实表，不创建新状态、不改变执行流程、不触碰源库数据。这样既能快速形成
 * 产品闭环，也不会把只读运营视图和执行状态机耦合在一起。后续如果要做趋势图、SLA、告警规则或 Agent
 * 复盘，本服务可以继续作为低敏聚合事实来源。</p>
 */
@Service
@RequiredArgsConstructor
public class QualityGovernanceOverviewService {

    /**
     * 总览响应契约版本。
     */
    private static final String SCHEMA_VERSION = "quality-governance-overview.v1";

    /**
     * 默认统计最近 30 天的报告、执行和异常事实。
     */
    private static final int DEFAULT_WINDOW_DAYS = 30;

    /**
     * 同步总览接口允许的最大时间窗口。
     *
     * <p>质量总览是在线查询，不是历史报表导出。限制窗口可以保护数据库，也能推动大批量历史分析
     * 走异步报表或离线数仓流程。</p>
     */
    private static final int MAX_WINDOW_DAYS = 365;

    /**
     * TOP 异常聚合默认返回条数。
     */
    private static final int DEFAULT_TOP_LIMIT = 10;

    /**
     * TOP 异常聚合最大返回条数。
     */
    private static final int MAX_TOP_LIMIT = 50;

    private final QualityRuleMapper ruleMapper;
    private final QualityCheckReportMapper reportMapper;
    private final QualityCheckExecutionMapper executionMapper;
    private final QualityAnomalyDetailMapper anomalyDetailMapper;
    private final QualityGovernanceOverviewCalculator overviewCalculator;

    /**
     * 生成数据质量治理总览。
     *
     * @param tenantId 可选租户过滤条件
     * @param windowDays 近期统计窗口天数，服务端会裁剪到安全范围
     * @param topLimit TOP 异常字段和类型的返回上限，服务端会裁剪到安全范围
     * @param visibility gateway/permission-admin 透传并解析后的 PROJECT 可见范围
     * @return 低敏治理态势总览
     */
    public QualityGovernanceOverviewResponse overview(Long tenantId,
                                                      Integer windowDays,
                                                      Integer topLimit,
                                                      QualityProjectVisibility visibility) {
        int safeWindowDays = safeWindowDays(windowDays);
        int safeTopLimit = safeTopLimit(topLimit);
        LocalDateTime windowEnd = LocalDateTime.now();
        LocalDateTime windowStart = windowEnd.minusDays(safeWindowDays);

        QualityGovernanceOverviewResponse response = baseResponse(
                tenantId, safeWindowDays, safeTopLimit, windowStart, windowEnd, visibility);
        if (hasNoVisibleProject(visibility)) {
            fillEmptyOverview(response);
            return response;
        }

        Map<String, Long> ruleStatusCounts = ruleStatusCounts(tenantId, visibility);
        Map<String, Long> ruleTypeCounts = ruleTypeCounts(tenantId, visibility);
        Map<String, Long> ruleSeverityCounts = ruleSeverityCounts(tenantId, visibility);
        Map<String, Long> targetTypeCounts = targetTypeCounts(tenantId, visibility);
        Map<String, Long> reportStatusCounts = reportStatusCounts(tenantId, windowStart, windowEnd, visibility);
        Map<String, Long> executionStateCounts = executionStateCounts(tenantId, windowStart, windowEnd, visibility);
        long anomalyCount = anomalyCount(tenantId, windowStart, windowEnd, visibility);
        List<QualityAnomalyAggregationItem> topFields =
                aggregateAnomalies(tenantId, QualityAnomalyAggregationDimension.FIELD, windowStart, windowEnd,
                        safeTopLimit, visibility);
        List<QualityAnomalyAggregationItem> topTypes =
                aggregateAnomalies(tenantId, QualityAnomalyAggregationDimension.TYPE, windowStart, windowEnd,
                        safeTopLimit, visibility);

        long recentReportCount = overviewCalculator.sum(reportStatusCounts);
        long failedReportCount = reportStatusCounts.getOrDefault(QualityCheckStatus.FAILED, 0L);
        var passRate = overviewCalculator.calculatePassRate(reportStatusCounts);
        int score = overviewCalculator.calculateQualityScore(
                ruleStatusCounts, reportStatusCounts, executionStateCounts, anomalyCount);
        QualityGovernanceRiskLevel riskLevel =
                overviewCalculator.calculateRiskLevel(ruleStatusCounts, recentReportCount, score);

        response.setRuleStatusCounts(ruleStatusCounts);
        response.setRuleTypeCounts(ruleTypeCounts);
        response.setRuleSeverityCounts(ruleSeverityCounts);
        response.setTargetTypeCounts(targetTypeCounts);
        response.setReportStatusCounts(reportStatusCounts);
        response.setExecutionStateCounts(executionStateCounts);
        response.setRecentReportCount(recentReportCount);
        response.setFailedReportCount(failedReportCount);
        response.setPassRate(passRate);
        response.setAnomalyCount(anomalyCount);
        response.setTopAnomalyFields(topFields);
        response.setTopAnomalyTypes(topTypes);
        response.setQualityScore(score);
        response.setRiskLevel(riskLevel);
        response.setNextActions(overviewCalculator.buildNextActions(ruleStatusCounts, reportStatusCounts, executionStateCounts,
                anomalyCount, topFields, topTypes));
        return response;
    }

    /**
     * 构造基础响应字段。
     *
     * <p>这些字段不依赖数据库，先填好可以确保即使后续因为 PROJECT 空授权提前返回，调用方也能知道
     * 本次查询使用了什么窗口、是否启用项目范围、为什么结果为空。</p>
     */
    private QualityGovernanceOverviewResponse baseResponse(Long tenantId,
                                                           int safeWindowDays,
                                                           int safeTopLimit,
                                                           LocalDateTime windowStart,
                                                           LocalDateTime windowEnd,
                                                           QualityProjectVisibility visibility) {
        QualityGovernanceOverviewResponse response = new QualityGovernanceOverviewResponse();
        response.setSchemaVersion(SCHEMA_VERSION);
        response.setGeneratedAt(LocalDateTime.now());
        response.setTenantId(tenantId);
        response.setProjectId(visibility == null ? null : visibility.requestedProjectId());
        response.setWorkspaceId(visibility == null ? null : visibility.requestedWorkspaceId());
        response.setWindowStart(windowStart);
        response.setWindowEnd(windowEnd);
        response.setWindowDays(safeWindowDays);
        response.setTopLimit(safeTopLimit);
        response.setProjectScopeEnforced(visibility != null && visibility.projectScopeEnforced());
        response.setHasVisibleProjects(!hasNoVisibleProject(visibility));
        response.setDataVisibilityPolicy("治理总览已按 tenantId、projectId、workspaceId 与 PROJECT 授权范围过滤；PROJECT 空授权时不会访问业务事实表。");
        response.setSensitiveDataPolicy("仅返回计数、比例、风险等级、枚举分布和低敏建议；不返回 SQL、执行计划正文、样本载荷、观测值、连接串、凭据、错误正文、prompt、模型输出或内部 endpoint。");
        return response;
    }

    /**
     * PROJECT 空授权场景的安全空响应。
     */
    private void fillEmptyOverview(QualityGovernanceOverviewResponse response) {
        response.setRuleStatusCounts(overviewCalculator.zeroRuleStatusCounts());
        response.setRuleTypeCounts(overviewCalculator.zeroRuleTypeCounts());
        response.setRuleSeverityCounts(overviewCalculator.zeroSeverityCounts());
        response.setTargetTypeCounts(overviewCalculator.zeroTargetTypeCounts());
        response.setReportStatusCounts(overviewCalculator.zeroReportStatusCounts());
        response.setExecutionStateCounts(overviewCalculator.zeroExecutionStateCounts());
        response.setRecentReportCount(0L);
        response.setFailedReportCount(0L);
        response.setPassRate(overviewCalculator.zeroPassRate());
        response.setAnomalyCount(0L);
        response.setTopAnomalyFields(List.of());
        response.setTopAnomalyTypes(List.of());
        response.setQualityScore(0);
        response.setRiskLevel(QualityGovernanceRiskLevel.NO_VISIBLE_PROJECT);
        response.setNextActions(List.of("当前 PROJECT 数据范围没有任何授权项目，治理总览已安全返回空结果；请切换项目或申请质量治理查看权限。"));
    }

    /**
     * 统计规则生命周期分布。
     */
    private Map<String, Long> ruleStatusCounts(Long tenantId, QualityProjectVisibility visibility) {
        Map<String, Long> result = new LinkedHashMap<>();
        for (String status : List.of(QualityRuleStatus.DRAFT, QualityRuleStatus.ACTIVE,
                QualityRuleStatus.INACTIVE, QualityRuleStatus.ARCHIVED)) {
            result.put(status, ruleMapper.selectCount(ruleWrapper(tenantId, visibility)
                    .eq(QualityRule::getStatus, status)));
        }
        return result;
    }

    /**
     * 统计质量维度覆盖情况。
     */
    private Map<String, Long> ruleTypeCounts(Long tenantId, QualityProjectVisibility visibility) {
        Map<String, Long> result = new LinkedHashMap<>();
        for (QualityRuleType type : QualityRuleType.values()) {
            result.put(type.name(), ruleMapper.selectCount(ruleWrapper(tenantId, visibility)
                    .eq(QualityRule::getRuleType, type.name())));
        }
        return result;
    }

    /**
     * 统计规则严重级别覆盖情况。
     */
    private Map<String, Long> ruleSeverityCounts(Long tenantId, QualityProjectVisibility visibility) {
        Map<String, Long> result = new LinkedHashMap<>();
        for (QualitySeverity severity : QualitySeverity.values()) {
            result.put(severity.name(), ruleMapper.selectCount(ruleWrapper(tenantId, visibility)
                    .eq(QualityRule::getSeverity, severity.name())));
        }
        return result;
    }

    /**
     * 统计检测目标类型覆盖情况。
     */
    private Map<String, Long> targetTypeCounts(Long tenantId, QualityProjectVisibility visibility) {
        Map<String, Long> result = new LinkedHashMap<>();
        for (QualityRuleTargetType targetType : QualityRuleTargetType.values()) {
            result.put(targetType.name(), ruleMapper.selectCount(ruleWrapper(tenantId, visibility)
                    .eq(QualityRule::getTargetType, targetType.name())));
        }
        return result;
    }

    /**
     * 统计近期质量报告 PASSED/FAILED 分布。
     */
    private Map<String, Long> reportStatusCounts(Long tenantId,
                                                 LocalDateTime windowStart,
                                                 LocalDateTime windowEnd,
                                                 QualityProjectVisibility visibility) {
        Map<String, Long> result = new LinkedHashMap<>();
        for (String status : List.of(QualityCheckStatus.PASSED, QualityCheckStatus.FAILED)) {
            result.put(status, reportMapper.selectCount(reportWrapper(tenantId, windowStart, windowEnd, visibility)
                    .eq(QualityCheckReport::getCheckStatus, status)));
        }
        return result;
    }

    /**
     * 统计近期执行动作 RUNNING/SUCCESS/FAILED 分布。
     */
    private Map<String, Long> executionStateCounts(Long tenantId,
                                                   LocalDateTime windowStart,
                                                   LocalDateTime windowEnd,
                                                   QualityProjectVisibility visibility) {
        Map<String, Long> result = new LinkedHashMap<>();
        for (String state : List.of(QualityCheckExecutionState.RUNNING,
                QualityCheckExecutionState.SUCCESS, QualityCheckExecutionState.FAILED)) {
            result.put(state, executionMapper.selectCount(executionWrapper(tenantId, windowStart, windowEnd, visibility)
                    .eq(QualityCheckExecution::getExecutionState, state)));
        }
        return result;
    }

    /**
     * 统计近期异常明细数量。
     */
    private long anomalyCount(Long tenantId,
                              LocalDateTime windowStart,
                              LocalDateTime windowEnd,
                              QualityProjectVisibility visibility) {
        return anomalyDetailMapper.selectCount(anomalyWrapper(tenantId, windowStart, windowEnd, visibility));
    }

    /**
     * 按白名单维度聚合 TOP 异常。
     *
     * <p>group by 列名只来自 {@link QualityAnomalyAggregationDimension}，不会接收前端任意字符串。
     * 这是动态聚合查询必须保留的 SQL 注入防线。</p>
     */
    private List<QualityAnomalyAggregationItem> aggregateAnomalies(Long tenantId,
                                                                   QualityAnomalyAggregationDimension dimension,
                                                                   LocalDateTime windowStart,
                                                                   LocalDateTime windowEnd,
                                                                   int safeTopLimit,
                                                                   QualityProjectVisibility visibility) {
        return anomalyDetailMapper.aggregateAnomalies(
                dimension.getColumnName(),
                tenantId,
                null,
                null,
                null,
                null,
                null,
                null,
                windowStart,
                windowEnd,
                safeTopLimit,
                visibility == null ? null : visibility.requestedProjectId(),
                visibility == null ? null : visibility.requestedWorkspaceId(),
                visibility == null ? List.of() : visibility.authorizedProjectIds(),
                visibility != null && visibility.projectScopeEnforced()
        );
    }

    private LambdaQueryWrapper<QualityRule> ruleWrapper(Long tenantId, QualityProjectVisibility visibility) {
        LambdaQueryWrapper<QualityRule> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(tenantId != null, QualityRule::getTenantId, tenantId)
                .ne(QualityRule::getStatus, QualityRuleStatus.DELETED);
        applyRuleVisibility(wrapper, visibility);
        return wrapper;
    }

    private LambdaQueryWrapper<QualityCheckReport> reportWrapper(Long tenantId,
                                                                 LocalDateTime windowStart,
                                                                 LocalDateTime windowEnd,
                                                                 QualityProjectVisibility visibility) {
        LambdaQueryWrapper<QualityCheckReport> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(tenantId != null, QualityCheckReport::getTenantId, tenantId)
                .ge(QualityCheckReport::getCreateTime, windowStart)
                .le(QualityCheckReport::getCreateTime, windowEnd);
        applyReportVisibility(wrapper, visibility);
        return wrapper;
    }

    private LambdaQueryWrapper<QualityCheckExecution> executionWrapper(Long tenantId,
                                                                       LocalDateTime windowStart,
                                                                       LocalDateTime windowEnd,
                                                                       QualityProjectVisibility visibility) {
        LambdaQueryWrapper<QualityCheckExecution> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(tenantId != null, QualityCheckExecution::getTenantId, tenantId)
                .ge(QualityCheckExecution::getStartedAt, windowStart)
                .le(QualityCheckExecution::getStartedAt, windowEnd);
        applyExecutionVisibility(wrapper, visibility);
        return wrapper;
    }

    private LambdaQueryWrapper<QualityAnomalyDetail> anomalyWrapper(Long tenantId,
                                                                    LocalDateTime windowStart,
                                                                    LocalDateTime windowEnd,
                                                                    QualityProjectVisibility visibility) {
        LambdaQueryWrapper<QualityAnomalyDetail> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(tenantId != null, QualityAnomalyDetail::getTenantId, tenantId)
                .ge(QualityAnomalyDetail::getCreateTime, windowStart)
                .le(QualityAnomalyDetail::getCreateTime, windowEnd);
        applyAnomalyVisibility(wrapper, visibility);
        return wrapper;
    }

    private void applyRuleVisibility(LambdaQueryWrapper<QualityRule> wrapper, QualityProjectVisibility visibility) {
        if (visibility == null) {
            return;
        }
        wrapper.eq(visibility.requestedProjectId() != null, QualityRule::getProjectId,
                        visibility.requestedProjectId())
                .eq(visibility.requestedWorkspaceId() != null, QualityRule::getWorkspaceId,
                        visibility.requestedWorkspaceId());
        if (visibility.projectScopeEnforced()) {
            wrapper.in(QualityRule::getProjectId, visibility.authorizedProjectIds());
        }
    }

    private void applyReportVisibility(LambdaQueryWrapper<QualityCheckReport> wrapper,
                                       QualityProjectVisibility visibility) {
        if (visibility == null) {
            return;
        }
        wrapper.eq(visibility.requestedProjectId() != null, QualityCheckReport::getProjectId,
                        visibility.requestedProjectId())
                .eq(visibility.requestedWorkspaceId() != null, QualityCheckReport::getWorkspaceId,
                        visibility.requestedWorkspaceId());
        if (visibility.projectScopeEnforced()) {
            wrapper.in(QualityCheckReport::getProjectId, visibility.authorizedProjectIds());
        }
    }

    private void applyExecutionVisibility(LambdaQueryWrapper<QualityCheckExecution> wrapper,
                                          QualityProjectVisibility visibility) {
        if (visibility == null) {
            return;
        }
        wrapper.eq(visibility.requestedProjectId() != null, QualityCheckExecution::getProjectId,
                        visibility.requestedProjectId())
                .eq(visibility.requestedWorkspaceId() != null, QualityCheckExecution::getWorkspaceId,
                        visibility.requestedWorkspaceId());
        if (visibility.projectScopeEnforced()) {
            wrapper.in(QualityCheckExecution::getProjectId, visibility.authorizedProjectIds());
        }
    }

    private void applyAnomalyVisibility(LambdaQueryWrapper<QualityAnomalyDetail> wrapper,
                                        QualityProjectVisibility visibility) {
        if (visibility == null) {
            return;
        }
        wrapper.eq(visibility.requestedProjectId() != null, QualityAnomalyDetail::getProjectId,
                        visibility.requestedProjectId())
                .eq(visibility.requestedWorkspaceId() != null, QualityAnomalyDetail::getWorkspaceId,
                        visibility.requestedWorkspaceId());
        if (visibility.projectScopeEnforced()) {
            wrapper.in(QualityAnomalyDetail::getProjectId, visibility.authorizedProjectIds());
        }
    }

    private boolean hasNoVisibleProject(QualityProjectVisibility visibility) {
        return visibility != null
                && visibility.projectScopeEnforced()
                && visibility.authorizedProjectIds().isEmpty();
    }

    private int safeWindowDays(Integer windowDays) {
        if (windowDays == null) {
            return DEFAULT_WINDOW_DAYS;
        }
        return Math.max(1, Math.min(windowDays, MAX_WINDOW_DAYS));
    }

    private int safeTopLimit(Integer topLimit) {
        if (topLimit == null) {
            return DEFAULT_TOP_LIMIT;
        }
        return Math.max(1, Math.min(topLimit, MAX_TOP_LIMIT));
    }

}
