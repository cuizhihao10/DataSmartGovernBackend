/**
 * @Author : Cui
 * @Date: 2026/06/22 20:19
 * @Description DataSmart Govern Backend - QualityExecutionDiagnosticsService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.quality.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.czh.datasmart.govern.quality.config.TaskManagementIntegrationProperties;
import com.czh.datasmart.govern.quality.controller.dto.QualityExecutionDiagnosticsExecutionView;
import com.czh.datasmart.govern.quality.controller.dto.QualityExecutionDiagnosticsResponse;
import com.czh.datasmart.govern.quality.controller.dto.QualityExecutionDiagnosticsRuntimeView;
import com.czh.datasmart.govern.quality.entity.QualityAnomalyDetail;
import com.czh.datasmart.govern.quality.entity.QualityCheckExecution;
import com.czh.datasmart.govern.quality.entity.QualityCheckReport;
import com.czh.datasmart.govern.quality.mapper.QualityAnomalyDetailMapper;
import com.czh.datasmart.govern.quality.mapper.QualityCheckExecutionMapper;
import com.czh.datasmart.govern.quality.mapper.QualityCheckReportMapper;
import com.czh.datasmart.govern.quality.service.support.QualityProjectVisibility;
import com.czh.datasmart.govern.quality.support.QualityCheckExecutionState;
import com.czh.datasmart.govern.quality.support.QualityCheckStatus;
import com.czh.datasmart.govern.quality.support.QualitySeverity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 质量执行诊断查询服务。
 *
 * <p>这个服务是 data-quality 从“能执行质量检测”走向“可运营、可排障、可闭环”的一层查询能力。
 * 它不新增业务状态，也不改变执行器主流程，而是聚合已有事实表：</p>
 *
 * <p>1. {@code quality_check_execution}：判断执行动作是否 RUNNING、SUCCESS 或 FAILED；</p>
 * <p>2. {@code quality_check_report}：判断业务质量结果是否 PASSED 或 FAILED；</p>
 * <p>3. {@code quality_anomaly_detail}：判断是否已经产生异常样本，需要进入异常工作台；</p>
 * <p>4. {@link TaskManagementIntegrationProperties}：返回当前执行器和调度器的低敏运行配置。</p>
 *
 * <p>重要的安全设计：诊断接口只返回“计数、状态、ID、时间、配置开关”这类低敏元数据。
 * 它不会返回执行计划正文、失败 message 正文、SQL、样本载荷、异常观测值、连接串、内部 endpoint 或密钥。
 * 这样运营人员能快速判断问题方向，而敏感内容仍留在受权限控制的详情、审计或脱敏导出流程中。</p>
 */
@Service
@RequiredArgsConstructor
public class QualityExecutionDiagnosticsService {

    /**
     * 诊断响应版本。
     */
    private static final String SCHEMA_VERSION = "quality-execution-diagnostics.v1";

    /**
     * 最近执行默认条数。
     */
    private static final int DEFAULT_RECENT_LIMIT = 20;

    /**
     * 最近执行最大条数。
     *
     * <p>诊断接口不是全量导出接口。限制最大条数可以保护数据库和接口响应体，
     * 也能避免一次运维查询误拉大量历史执行记录。</p>
     */
    private static final int MAX_RECENT_LIMIT = 100;

    /**
     * 执行记录 Mapper，用于读取执行状态和最近执行快照。
     */
    private final QualityCheckExecutionMapper executionMapper;

    /**
     * 报告 Mapper，用于读取 PASSED/FAILED 与严重级别分布。
     */
    private final QualityCheckReportMapper reportMapper;

    /**
     * 异常明细 Mapper，用于读取异常样本数量。
     */
    private final QualityAnomalyDetailMapper anomalyDetailMapper;

    /**
     * 质量执行器与 task-management 集成配置。
     */
    private final TaskManagementIntegrationProperties properties;

    /**
     * 生成质量执行低敏诊断视图。
     *
     * @param tenantId 可选租户过滤条件
     * @param ruleId 可选规则过滤条件
     * @param limit 最近执行返回上限
     * @param visibility 当前请求经过 gateway/permission-admin 透传后的项目可见范围
     * @return 可直接返回给运营台的低敏诊断响应
     */
    public QualityExecutionDiagnosticsResponse diagnose(Long tenantId,
                                                        Long ruleId,
                                                        Integer limit,
                                                        QualityProjectVisibility visibility) {
        int safeLimit = safeLimit(limit);
        QualityExecutionDiagnosticsResponse response = baseResponse(tenantId, ruleId, safeLimit, visibility);
        response.setRuntime(runtimeView());

        if (hasNoVisibleProject(visibility)) {
            response.setExecutionStateCounts(zeroExecutionStateCounts());
            response.setReportStatusCounts(zeroReportStatusCounts());
            response.setSeverityCounts(zeroSeverityCounts());
            response.setAnomalyCount(0L);
            response.setRecentExecutions(List.of());
            response.setWarnings(emptyProjectWarnings());
            return response;
        }

        Map<String, Long> executionStateCounts = executionStateCounts(tenantId, ruleId, visibility);
        Map<String, Long> reportStatusCounts = reportStatusCounts(tenantId, ruleId, visibility);
        Map<String, Long> severityCounts = severityCounts(tenantId, ruleId, visibility);
        long anomalyCount = countAnomalies(tenantId, ruleId, visibility);
        List<QualityExecutionDiagnosticsExecutionView> recentExecutions =
                recentExecutions(tenantId, ruleId, visibility, safeLimit);

        response.setExecutionStateCounts(executionStateCounts);
        response.setReportStatusCounts(reportStatusCounts);
        response.setSeverityCounts(severityCounts);
        response.setAnomalyCount(anomalyCount);
        response.setRecentExecutions(recentExecutions);
        response.setWarnings(buildWarnings(executionStateCounts, reportStatusCounts, anomalyCount,
                recentExecutions.size(), safeLimit));
        return response;
    }

    /**
     * 构造响应基础字段。
     *
     * <p>基础字段不依赖数据库，因此即使 PROJECT 范围没有任何授权项目，也能返回明确的空诊断视图，
     * 让调用方知道“这是权限过滤后的空结果”，而不是服务异常或数据库无数据。</p>
     */
    private QualityExecutionDiagnosticsResponse baseResponse(Long tenantId,
                                                             Long ruleId,
                                                             int safeLimit,
                                                             QualityProjectVisibility visibility) {
        QualityExecutionDiagnosticsResponse response = new QualityExecutionDiagnosticsResponse();
        response.setSchemaVersion(SCHEMA_VERSION);
        response.setInspectedAt(LocalDateTime.now());
        response.setTenantId(tenantId);
        response.setProjectId(visibility == null ? null : visibility.requestedProjectId());
        response.setWorkspaceId(visibility == null ? null : visibility.requestedWorkspaceId());
        response.setRuleId(ruleId);
        response.setRecentExecutionLimit(safeLimit);
        response.setProjectScopeEnforced(visibility != null && visibility.projectScopeEnforced());
        response.setHasVisibleProjects(!hasNoVisibleProject(visibility));
        response.setDataVisibilityPolicy("诊断结果已按 tenantId、projectId、workspaceId、ruleId 与 PROJECT 授权范围过滤");
        response.setSensitiveDataPolicy("仅返回低敏元数据；不返回扫描计划正文、SQL、样本载荷、异常值、错误正文、连接串、凭据或内部 endpoint");
        return response;
    }

    /**
     * 统计执行状态分布。
     */
    private Map<String, Long> executionStateCounts(Long tenantId, Long ruleId, QualityProjectVisibility visibility) {
        Map<String, Long> result = new LinkedHashMap<>();
        for (String state : List.of(
                QualityCheckExecutionState.RUNNING,
                QualityCheckExecutionState.SUCCESS,
                QualityCheckExecutionState.FAILED)) {
            LambdaQueryWrapper<QualityCheckExecution> wrapper = executionWrapper(tenantId, ruleId, visibility);
            wrapper.eq(QualityCheckExecution::getExecutionState, state);
            result.put(state, executionMapper.selectCount(wrapper));
        }
        return result;
    }

    /**
     * 统计质量报告结果分布。
     */
    private Map<String, Long> reportStatusCounts(Long tenantId, Long ruleId, QualityProjectVisibility visibility) {
        Map<String, Long> result = new LinkedHashMap<>();
        for (String status : List.of(QualityCheckStatus.PASSED, QualityCheckStatus.FAILED)) {
            LambdaQueryWrapper<QualityCheckReport> wrapper = reportWrapper(tenantId, ruleId, visibility);
            wrapper.eq(QualityCheckReport::getCheckStatus, status);
            result.put(status, reportMapper.selectCount(wrapper));
        }
        return result;
    }

    /**
     * 统计报告严重级别分布。
     */
    private Map<String, Long> severityCounts(Long tenantId, Long ruleId, QualityProjectVisibility visibility) {
        Map<String, Long> result = new LinkedHashMap<>();
        for (QualitySeverity severity : QualitySeverity.values()) {
            LambdaQueryWrapper<QualityCheckReport> wrapper = reportWrapper(tenantId, ruleId, visibility);
            wrapper.eq(QualityCheckReport::getSeverity, severity.name());
            result.put(severity.name(), reportMapper.selectCount(wrapper));
        }
        return result;
    }

    /**
     * 统计异常明细数量。
     */
    private long countAnomalies(Long tenantId, Long ruleId, QualityProjectVisibility visibility) {
        return anomalyDetailMapper.selectCount(anomalyWrapper(tenantId, ruleId, visibility));
    }

    /**
     * 查询最近执行快照。
     *
     * <p>这里使用 {@code last("LIMIT ...")}，但 limit 只来自 {@link #safeLimit(Integer)}
     * 裁剪后的整数，不接收任何字符串拼接输入，因此不会形成 SQL 注入入口。</p>
     */
    private List<QualityExecutionDiagnosticsExecutionView> recentExecutions(Long tenantId,
                                                                            Long ruleId,
                                                                            QualityProjectVisibility visibility,
                                                                            int safeLimit) {
        LambdaQueryWrapper<QualityCheckExecution> wrapper = executionWrapper(tenantId, ruleId, visibility);
        wrapper.orderByDesc(QualityCheckExecution::getStartedAt)
                .orderByDesc(QualityCheckExecution::getId)
                .last("LIMIT " + safeLimit);
        return executionMapper.selectList(wrapper)
                .stream()
                .map(this::toExecutionView)
                .toList();
    }

    /**
     * 把执行实体转换为低敏视图。
     */
    private QualityExecutionDiagnosticsExecutionView toExecutionView(QualityCheckExecution execution) {
        QualityExecutionDiagnosticsExecutionView view = new QualityExecutionDiagnosticsExecutionView();
        view.setExecutionId(execution.getId());
        view.setTenantId(execution.getTenantId());
        view.setProjectId(execution.getProjectId());
        view.setWorkspaceId(execution.getWorkspaceId());
        view.setRuleId(execution.getRuleId());
        view.setExecutionNo(execution.getExecutionNo());
        view.setTriggerType(execution.getTriggerType());
        view.setExecutionState(execution.getExecutionState());
        view.setOperator(execution.getOperator());
        view.setTaskId(execution.getTaskId());
        view.setTaskRunId(execution.getTaskRunId());
        view.setExecutorId(execution.getExecutorId());
        view.setStartedAt(execution.getStartedAt());
        view.setFinishedAt(execution.getFinishedAt());
        view.setDurationMs(execution.getDurationMs());
        view.setReportId(execution.getReportId());
        view.setScanPlanSnapshotAvailable(hasText(execution.getScanPlanSnapshot()));
        view.setMessageAvailable(hasText(execution.getMessage()));
        view.setDetailVisibilityPolicy("诊断视图只返回存在性；正文需通过受控详情、审计或脱敏导出流程查看");
        return view;
    }

    /**
     * 构造运行配置低敏快照。
     */
    private QualityExecutionDiagnosticsRuntimeView runtimeView() {
        QualityExecutionDiagnosticsRuntimeView view = new QualityExecutionDiagnosticsRuntimeView();
        view.setTaskManagementIntegrationEnabled(properties.isEnabled());
        view.setExecutorCoordinatorEnabled(properties.isExecutorCoordinatorEnabled());
        view.setExecutorSchedulerEnabled(properties.isExecutorSchedulerEnabled());
        view.setExecutorConcurrencyGuardEnabled(properties.isExecutorConcurrencyGuardEnabled());
        view.setExecutorId(properties.getExecutorId());
        view.setTaskType(properties.getTaskType());
        view.setSchedulerInitialDelaySeconds(properties.getExecutorSchedulerInitialDelaySeconds());
        view.setSchedulerFixedDelaySeconds(properties.getExecutorSchedulerFixedDelaySeconds());
        view.setSchedulerMaxRunsPerTick(properties.getSafeExecutorSchedulerMaxRunsPerTick());
        view.setMaxConcurrentRunsGlobal(properties.getSafeExecutorMaxConcurrentRunsGlobal());
        view.setMaxConcurrentRunsPerTenant(properties.getSafeExecutorMaxConcurrentRunsPerTenant());
        view.setMaxConcurrentRunsPerDatasource(properties.getSafeExecutorMaxConcurrentRunsPerDatasource());
        view.setThrottleDeferSeconds(properties.getSafeExecutorThrottleDeferSeconds());
        view.setLeaseSeconds(properties.getExecutorLeaseSeconds());
        view.setFailOpen(properties.isFailOpen());
        view.setSourceService(properties.getSourceService());
        return view;
    }

    /**
     * 构造执行表基础查询条件。
     */
    private LambdaQueryWrapper<QualityCheckExecution> executionWrapper(Long tenantId,
                                                                       Long ruleId,
                                                                       QualityProjectVisibility visibility) {
        LambdaQueryWrapper<QualityCheckExecution> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(tenantId != null, QualityCheckExecution::getTenantId, tenantId)
                .eq(ruleId != null, QualityCheckExecution::getRuleId, ruleId);
        applyExecutionVisibility(wrapper, visibility);
        return wrapper;
    }

    /**
     * 构造报告表基础查询条件。
     */
    private LambdaQueryWrapper<QualityCheckReport> reportWrapper(Long tenantId,
                                                                 Long ruleId,
                                                                 QualityProjectVisibility visibility) {
        LambdaQueryWrapper<QualityCheckReport> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(tenantId != null, QualityCheckReport::getTenantId, tenantId)
                .eq(ruleId != null, QualityCheckReport::getRuleId, ruleId);
        applyReportVisibility(wrapper, visibility);
        return wrapper;
    }

    /**
     * 构造异常明细表基础查询条件。
     */
    private LambdaQueryWrapper<QualityAnomalyDetail> anomalyWrapper(Long tenantId,
                                                                    Long ruleId,
                                                                    QualityProjectVisibility visibility) {
        LambdaQueryWrapper<QualityAnomalyDetail> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(tenantId != null, QualityAnomalyDetail::getTenantId, tenantId)
                .eq(ruleId != null, QualityAnomalyDetail::getRuleId, ruleId);
        applyAnomalyVisibility(wrapper, visibility);
        return wrapper;
    }

    /**
     * 对执行表追加项目/工作空间可见范围。
     */
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

    /**
     * 对报告表追加项目/工作空间可见范围。
     */
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

    /**
     * 对异常明细表追加项目/工作空间可见范围。
     */
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

    /**
     * 根据诊断结果生成运营提醒。
     */
    private List<String> buildWarnings(Map<String, Long> executionStateCounts,
                                       Map<String, Long> reportStatusCounts,
                                       long anomalyCount,
                                       int recentExecutionCount,
                                       int safeLimit) {
        List<String> warnings = new ArrayList<>();
        if (!properties.isEnabled()) {
            warnings.add("task-management 集成未启用，质量检测任务不会进入统一任务中心闭环");
        }
        if (!properties.isExecutorCoordinatorEnabled()) {
            warnings.add("质量执行器 coordinator 未启用，手动 run-once 或后台 scheduler 都不会真实认领任务");
        }
        if (!properties.isExecutorSchedulerEnabled()) {
            warnings.add("质量执行器 scheduler 未启用，后台不会自动消费 DATA_QUALITY_SCAN 队列");
        }
        if (executionStateCounts.getOrDefault(QualityCheckExecutionState.RUNNING, 0L) > 0) {
            warnings.add("存在 RUNNING 执行记录，请关注是否为长耗时扫描或执行器异常退出导致的悬挂执行");
        }
        if (executionStateCounts.getOrDefault(QualityCheckExecutionState.FAILED, 0L) > 0) {
            warnings.add("存在 FAILED 执行记录，请优先排查连接、权限、超时、扫描计划或执行器稳定性问题");
        }
        if (reportStatusCounts.getOrDefault(QualityCheckStatus.FAILED, 0L) > 0) {
            warnings.add("存在 FAILED 质量报告，说明扫描动作完成但业务数据未满足质量规则");
        }
        if (anomalyCount > 0) {
            warnings.add("当前范围已产生异常样本，建议进入异常工作台查看脱敏详情、根因和清洗建议");
        }
        if (recentExecutionCount == safeLimit) {
            warnings.add("最近执行列表已按 limit 截断，如需全量历史应使用分页历史接口或审计导出");
        }
        if (warnings.isEmpty()) {
            warnings.add("当前过滤范围未发现明显执行异常；仍建议结合 Actuator 指标和 task-management 队列状态交叉验证");
        }
        return warnings;
    }

    /**
     * PROJECT 范围没有任何授权项目时的提示。
     */
    private List<String> emptyProjectWarnings() {
        return List.of("当前请求处于 PROJECT 数据范围，但没有任何授权项目，诊断结果已安全收口为空");
    }

    /**
     * 空执行状态计数。
     */
    private Map<String, Long> zeroExecutionStateCounts() {
        Map<String, Long> result = new LinkedHashMap<>();
        result.put(QualityCheckExecutionState.RUNNING, 0L);
        result.put(QualityCheckExecutionState.SUCCESS, 0L);
        result.put(QualityCheckExecutionState.FAILED, 0L);
        return result;
    }

    /**
     * 空报告状态计数。
     */
    private Map<String, Long> zeroReportStatusCounts() {
        Map<String, Long> result = new LinkedHashMap<>();
        result.put(QualityCheckStatus.PASSED, 0L);
        result.put(QualityCheckStatus.FAILED, 0L);
        return result;
    }

    /**
     * 空严重级别计数。
     */
    private Map<String, Long> zeroSeverityCounts() {
        Map<String, Long> result = new LinkedHashMap<>();
        for (QualitySeverity severity : QualitySeverity.values()) {
            result.put(severity.name(), 0L);
        }
        return result;
    }

    /**
     * 最近执行条数安全裁剪。
     */
    private int safeLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_RECENT_LIMIT;
        }
        return Math.max(1, Math.min(limit, MAX_RECENT_LIMIT));
    }

    /**
     * 判断 PROJECT 范围是否没有任何可见项目。
     */
    private boolean hasNoVisibleProject(QualityProjectVisibility visibility) {
        return visibility != null
                && visibility.projectScopeEnforced()
                && visibility.authorizedProjectIds().isEmpty();
    }

    /**
     * 判断字符串是否有实际内容。
     */
    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
