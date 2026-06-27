/**
 * @Author : Cui
 * @Date: 2026/05/05 19:06
 * @Description DataSmart Govern Backend - QualityExecutionReportSupport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.quality.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.czh.datasmart.govern.quality.controller.dto.QualityAnomalyAggregationItem;
import com.czh.datasmart.govern.quality.controller.dto.QualityAnomalyDetailRequest;
import com.czh.datasmart.govern.quality.controller.dto.QualityExecutionFailRequest;
import com.czh.datasmart.govern.quality.entity.QualityAnomalyDetail;
import com.czh.datasmart.govern.quality.entity.QualityCheckExecution;
import com.czh.datasmart.govern.quality.entity.QualityCheckReport;
import com.czh.datasmart.govern.quality.entity.QualityRule;
import com.czh.datasmart.govern.quality.mapper.QualityAnomalyDetailMapper;
import com.czh.datasmart.govern.quality.mapper.QualityCheckExecutionMapper;
import com.czh.datasmart.govern.quality.mapper.QualityCheckReportMapper;
import com.czh.datasmart.govern.quality.mapper.QualityRuleMapper;
import com.czh.datasmart.govern.quality.support.QualityAnomalyAggregationDimension;
import com.czh.datasmart.govern.quality.support.QualityCheckExecutionState;
import com.czh.datasmart.govern.quality.support.QualityCheckStatus;
import com.czh.datasmart.govern.quality.support.QualityComparisonOperator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * 质量检测执行、报告和异常明细支持组件。
 *
 * <p>这个组件从 `DataQualityServiceImpl` 中拆出，专门承载“检测执行过程”和“检测结果运营查询”。
 * 它不是简单的 Mapper 包装，而是 data-quality 模块中非常核心的一条产品能力线：
 * 1. execution 表示一次质量检测动作是否被成功执行；
 * 2. report 表示执行成功后得出的业务质量判断；
 * 3. anomaly detail 表示失败或异常报告中的样本级证据；
 * 4. aggregation 表示运营人员从大量异常中快速定位主要问题。
 *
 * <p>把这些逻辑独立出来后，`DataQualityServiceImpl` 可以继续聚焦规则生命周期、规则校验和跨模块调度。
 * 后续如果要支持高并发执行器、异步异常明细落盘、报告导出、质量大盘或 AI 根因分析，
 * 都可以优先扩展该组件，而不是把主服务继续做成上帝类。
 */
@Component
@RequiredArgsConstructor
public class QualityExecutionReportSupport {

    /**
     * 检测执行记录 Mapper。
     *
     * <p>执行记录用于追踪一次检测动作的技术生命周期，例如 RUNNING、SUCCESS、FAILED。
     */
    private final QualityCheckExecutionMapper qualityCheckExecutionMapper;

    /**
     * 质量检测报告 Mapper。
     *
     * <p>报告用于追踪一次检测动作产生的业务质量判断，例如 PASSED 或 FAILED。
     */
    private final QualityCheckReportMapper qualityCheckReportMapper;

    /**
     * 异常明细 Mapper。
     *
     * <p>异常明细是报告的证据层，用于下钻、清洗、整改和审计。
     */
    private final QualityAnomalyDetailMapper qualityAnomalyDetailMapper;

    /**
     * 质量规则 Mapper。
     *
     * <p>该组件需要在生成报告后回写规则最近一次有效检测快照，
     * 因此直接依赖 Mapper，避免主服务继续承载该持久化细节。
     */
    private final QualityRuleMapper qualityRuleMapper;

    /**
     * 创建运行中的质量检测执行记录。
     *
     * <p>人工执行和任务执行器触发都会先创建 execution。
     * 这样无论后续成功还是失败，平台都能追踪“这次检测动作”本身。
     */
    public QualityCheckExecution createRunningExecution(QualityRule rule, String triggerType) {
        return createRunningExecution(rule, triggerType, "system", null, null, null, null, null);
    }

    /**
     * 创建带任务上下文的运行中执行记录。
     *
     * <p>该重载专门服务 task-management 触发的异步质量检测。
     * taskId、taskRunId、executorId 和 scanPlanSnapshot 会把质量检测和任务中心执行链路串起来。
     */
    public QualityCheckExecution createRunningExecution(QualityRule rule, String triggerType, String operator,
                                                        Long taskId, Long taskRunId, String executorId,
                                                        String scanPlanSnapshot, String message) {
        Long maxExecutionNo = qualityCheckExecutionMapper.selectMaxExecutionNo(rule.getId());
        QualityCheckExecution execution = new QualityCheckExecution();
        execution.setTenantId(rule.getTenantId());
        execution.setProjectId(rule.getProjectId());
        execution.setWorkspaceId(rule.getWorkspaceId());
        execution.setRuleId(rule.getId());
        execution.setExecutionNo(maxExecutionNo + 1);
        execution.setTriggerType(triggerType);
        execution.setExecutionState(QualityCheckExecutionState.RUNNING);
        execution.setOperator(hasText(operator) ? operator : "system");
        execution.setTaskId(taskId);
        execution.setTaskRunId(taskRunId);
        execution.setExecutorId(executorId);
        execution.setScanPlanSnapshot(scanPlanSnapshot);
        execution.setMessage(message);
        execution.setStartedAt(LocalDateTime.now());
        execution.setCreateTime(LocalDateTime.now());
        execution.setUpdateTime(LocalDateTime.now());
        qualityCheckExecutionMapper.insert(execution);
        return execution;
    }

    /**
     * 根据 executionId 获取执行记录。
     */
    public QualityCheckExecution getRequiredExecution(Long executionId) {
        QualityCheckExecution execution = qualityCheckExecutionMapper.selectById(executionId);
        if (execution == null) {
            throw new NoSuchElementException("质量检测执行记录不存在: " + executionId);
        }
        return execution;
    }

    /**
     * 确认执行记录仍处于 RUNNING。
     *
     * <p>这是成功/失败回调的基础幂等保护线：
     * 已经 SUCCESS 或 FAILED 的 execution 不能再次被覆盖，避免重复报告或错误状态反转。
     */
    public void ensureExecutionRunning(QualityCheckExecution execution) {
        if (!QualityCheckExecutionState.RUNNING.equals(execution.getExecutionState())) {
            throw new IllegalStateException("质量检测执行记录已经结束，不能重复回调: " + execution.getId()
                    + ", state=" + execution.getExecutionState());
        }
    }

    /**
     * 校验检测结果指标之间的基本一致性。
     */
    public void ensureResultMetrics(Integer sampleSize, Integer exceptionCount) {
        if (sampleSize != null && exceptionCount != null && exceptionCount > sampleSize) {
            throw new IllegalArgumentException("异常数量不能大于样本量");
        }
    }

    /**
     * 根据已完成执行生成质量报告。
     *
     * <p>人工执行和异步任务执行共用该方法，确保通过/失败判断、通过率、摘要和异常明细写入完全一致。
     */
    public QualityCheckReport createReportFromCompletedExecution(QualityCheckExecution execution, QualityRule rule,
                                                                 BigDecimal measuredValue, Integer sampleSize,
                                                                 Integer exceptionCount, String notes,
                                                                 List<QualityAnomalyDetailRequest> anomalies) {
        QualityComparisonOperator operator = QualityComparisonOperator.fromValue(rule.getComparisonOperator());
        boolean passed = operator.matches(measuredValue, rule.getExpectedValue());

        QualityCheckReport report = new QualityCheckReport();
        report.setTenantId(rule.getTenantId());
        report.setProjectId(rule.getProjectId());
        report.setWorkspaceId(rule.getWorkspaceId());
        report.setRuleId(rule.getId());
        report.setExecutionId(execution.getId());
        report.setRuleVersion(rule.getRuleVersion());
        report.setRuleName(rule.getName());
        report.setRuleType(rule.getRuleType());
        report.setTargetObject(rule.getTargetObject());
        report.setSeverity(rule.getSeverity());
        report.setMeasuredValue(measuredValue);
        report.setExpectedValue(rule.getExpectedValue());
        report.setComparisonOperator(rule.getComparisonOperator());
        report.setCheckStatus(passed ? QualityCheckStatus.PASSED : QualityCheckStatus.FAILED);
        report.setSampleSize(sampleSize);
        report.setExceptionCount(exceptionCount);
        report.setPassRate(calculatePassRate(sampleSize, exceptionCount));
        report.setTriggerType(execution.getTriggerType());
        report.setNotes(notes);
        report.setSummary(buildSummary(rule, measuredValue, passed, sampleSize, exceptionCount));
        report.setCreateTime(LocalDateTime.now());
        qualityCheckReportMapper.insert(report);

        persistAnomalyDetails(report, rule, anomalies);
        return report;
    }

    /**
     * 把 execution 标记为成功。
     */
    public void markExecutionSucceeded(QualityCheckExecution execution, QualityCheckReport report, String message) {
        execution.setExecutionState(QualityCheckExecutionState.SUCCESS);
        execution.setFinishedAt(LocalDateTime.now());
        execution.setDurationMs(calculateDurationMs(execution));
        execution.setReportId(report.getId());
        execution.setMessage(message);
        execution.setUpdateTime(LocalDateTime.now());
        qualityCheckExecutionMapper.updateById(execution);
    }

    /**
     * 把 execution 标记为失败。
     *
     * <p>技术失败不会生成 report，因为它并不代表业务质量不达标，而是平台执行链路未完成。
     */
    public void markExecutionFailed(QualityCheckExecution execution, QualityExecutionFailRequest request) {
        execution.setExecutionState(QualityCheckExecutionState.FAILED);
        execution.setFinishedAt(LocalDateTime.now());
        execution.setDurationMs(calculateDurationMs(execution));
        execution.setMessage(buildExecutionFailureMessage(request));
        execution.setUpdateTime(LocalDateTime.now());
        qualityCheckExecutionMapper.updateById(execution);
    }

    /**
     * 回写规则最近一次有效检测快照。
     */
    public void updateRuleLastCheckSnapshot(QualityRule rule, QualityCheckReport report) {
        rule.setLastCheckTime(report.getCreateTime());
        rule.setLastCheckStatus(report.getCheckStatus());
        rule.setLastReportId(report.getId());
        rule.setUpdateTime(LocalDateTime.now());
        qualityRuleMapper.updateById(rule);
    }

    public List<QualityCheckReport> listReportsByRuleId(Long ruleId) {
        LambdaQueryWrapper<QualityCheckReport> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(QualityCheckReport::getRuleId, ruleId)
                .orderByDesc(QualityCheckReport::getCreateTime)
                .orderByDesc(QualityCheckReport::getId);
        return qualityCheckReportMapper.selectList(wrapper);
    }

    public IPage<QualityCheckReport> pageReports(Integer current, Integer size, Long ruleId, String ruleType,
                                                 String severity, String checkStatus, String targetObject,
                                                 String triggerType, LocalDateTime startTime,
                                                 LocalDateTime endTime, Boolean failedOnly,
                                                 QualityProjectVisibility visibility) {
        if (hasNoProjectVisibility(visibility)) {
            return new Page<>(safeCurrent(current), safeSize(size));
        }
        LambdaQueryWrapper<QualityCheckReport> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ruleId != null, QualityCheckReport::getRuleId, ruleId)
                .eq(hasText(ruleType), QualityCheckReport::getRuleType, normalizeUpper(ruleType))
                .eq(hasText(severity), QualityCheckReport::getSeverity, normalizeUpper(severity))
                .eq(hasText(triggerType), QualityCheckReport::getTriggerType, normalizeUpper(triggerType))
                .like(hasText(targetObject), QualityCheckReport::getTargetObject, targetObject);

        if (Boolean.TRUE.equals(failedOnly)) {
            wrapper.eq(QualityCheckReport::getCheckStatus, QualityCheckStatus.FAILED);
        } else if (hasText(checkStatus)) {
            wrapper.eq(QualityCheckReport::getCheckStatus, normalizeUpper(checkStatus));
        }

        wrapper.ge(startTime != null, QualityCheckReport::getCreateTime, startTime)
                .le(endTime != null, QualityCheckReport::getCreateTime, endTime)
                .orderByDesc(QualityCheckReport::getCreateTime)
                .orderByDesc(QualityCheckReport::getId);
        applyReportProjectVisibility(wrapper, visibility);
        return qualityCheckReportMapper.selectPage(new Page<>(safeCurrent(current), safeSize(size)), wrapper);
    }

    public List<QualityAnomalyDetail> listAnomaliesByReportId(Long reportId, QualityProjectVisibility visibility) {
        QualityCheckReport report = qualityCheckReportMapper.selectById(reportId);
        if (report == null) {
            throw new NoSuchElementException("质量检测报告不存在: " + reportId);
        }
        validateReportReadable(report, visibility);
        return qualityAnomalyDetailMapper.selectList(new LambdaQueryWrapper<QualityAnomalyDetail>()
                .eq(QualityAnomalyDetail::getReportId, reportId)
                .orderByDesc(QualityAnomalyDetail::getId));
    }

    public IPage<QualityAnomalyDetail> pageAnomalies(Integer current, Integer size, Long reportId, Long ruleId,
                                                     String anomalyType, String fieldName, String severity,
                                                     String targetObject, LocalDateTime startTime,
                                                     LocalDateTime endTime,
                                                     QualityProjectVisibility visibility) {
        if (hasNoProjectVisibility(visibility)) {
            return new Page<>(safeCurrent(current), safeSize(size));
        }
        LambdaQueryWrapper<QualityAnomalyDetail> wrapper = buildAnomalyQueryWrapper(
                reportId, ruleId, anomalyType, fieldName, severity, targetObject, startTime, endTime
        );
        wrapper.orderByDesc(QualityAnomalyDetail::getCreateTime)
                .orderByDesc(QualityAnomalyDetail::getId);
        applyAnomalyProjectVisibility(wrapper, visibility);
        return qualityAnomalyDetailMapper.selectPage(new Page<>(safeCurrent(current), safeSize(size)), wrapper);
    }

    public List<QualityAnomalyAggregationItem> aggregateAnomalies(Long reportId, Long ruleId, String anomalyType,
                                                                  String fieldName, String severity,
                                                                  String targetObject, LocalDateTime startTime,
                                                                  LocalDateTime endTime, String groupBy,
                                                                  Integer limit,
                                                                  QualityProjectVisibility visibility) {
        if (hasNoProjectVisibility(visibility)) {
            return List.of();
        }
        QualityAnomalyAggregationDimension dimension = QualityAnomalyAggregationDimension.fromValue(groupBy);
        return qualityAnomalyDetailMapper.aggregateAnomalies(
                dimension.getColumnName(),
                null,
                reportId,
                ruleId,
                normalizeUpper(anomalyType),
                fieldName,
                normalizeUpper(severity),
                targetObject,
                startTime,
                endTime,
                safeAggregationLimit(limit),
                visibility == null ? null : visibility.requestedProjectId(),
                visibility == null ? null : visibility.requestedWorkspaceId(),
                visibility == null ? List.of() : visibility.authorizedProjectIds(),
                visibility != null && visibility.projectScopeEnforced()
        );
    }

    public List<QualityCheckExecution> listExecutionsByRuleId(Long ruleId) {
        return qualityCheckExecutionMapper.selectList(new LambdaQueryWrapper<QualityCheckExecution>()
                .eq(QualityCheckExecution::getRuleId, ruleId)
                .orderByDesc(QualityCheckExecution::getExecutionNo)
                .orderByDesc(QualityCheckExecution::getId));
    }

    private void persistAnomalyDetails(QualityCheckReport report, QualityRule rule,
                                       List<QualityAnomalyDetailRequest> anomalies) {
        if (anomalies == null || anomalies.isEmpty()) {
            return;
        }
        for (QualityAnomalyDetailRequest request : anomalies) {
            QualityAnomalyDetail detail = new QualityAnomalyDetail();
            detail.setTenantId(rule.getTenantId());
            detail.setProjectId(rule.getProjectId());
            detail.setWorkspaceId(rule.getWorkspaceId());
            detail.setReportId(report.getId());
            detail.setRuleId(rule.getId());
            detail.setTargetObject(rule.getTargetObject());
            detail.setAnomalyType(normalizeUpper(request.getAnomalyType()));
            detail.setFieldName(request.getFieldName());
            detail.setRecordIdentifier(request.getRecordIdentifier());
            detail.setObservedValue(request.getObservedValue());
            detail.setExpectedValue(request.getExpectedValue());
            detail.setSeverity(hasText(request.getSeverity()) ? normalizeUpper(request.getSeverity()) : rule.getSeverity());
            detail.setRecommendation(request.getRecommendation());
            detail.setSamplePayload(request.getSamplePayload());
            detail.setCreateTime(LocalDateTime.now());
            qualityAnomalyDetailMapper.insert(detail);
        }
    }

    private LambdaQueryWrapper<QualityAnomalyDetail> buildAnomalyQueryWrapper(Long reportId, Long ruleId,
                                                                              String anomalyType, String fieldName,
                                                                              String severity, String targetObject,
                                                                              LocalDateTime startTime,
                                                                              LocalDateTime endTime) {
        LambdaQueryWrapper<QualityAnomalyDetail> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(reportId != null, QualityAnomalyDetail::getReportId, reportId)
                .eq(ruleId != null, QualityAnomalyDetail::getRuleId, ruleId)
                .eq(hasText(anomalyType), QualityAnomalyDetail::getAnomalyType, normalizeUpper(anomalyType))
                .like(hasText(fieldName), QualityAnomalyDetail::getFieldName, fieldName)
                .eq(hasText(severity), QualityAnomalyDetail::getSeverity, normalizeUpper(severity))
                .like(hasText(targetObject), QualityAnomalyDetail::getTargetObject, targetObject)
                .ge(startTime != null, QualityAnomalyDetail::getCreateTime, startTime)
                .le(endTime != null, QualityAnomalyDetail::getCreateTime, endTime);
        return wrapper;
    }

    /**
     * 判断 PROJECT 范围下是否没有任何可见项目。
     *
     * <p>权限系统里“空授权集合”表示没有项目可见，而不是不过滤。
     * 因此列表和聚合查询必须返回空结果，不能因为集合为空就退化为全租户查询。</p>
     */
    private boolean hasNoProjectVisibility(QualityProjectVisibility visibility) {
        return visibility != null
                && visibility.projectScopeEnforced()
                && visibility.authorizedProjectIds().isEmpty();
    }

    /**
     * 将项目/工作空间可见范围追加到质量报告查询。
     *
     * <p>该方法只处理报告表本身的 project_id/workspace_id，不回表 join 规则表。
     * 这是因为报告是高频运营数据，项目维度应作为事实表冗余字段直接索引。</p>
     */
    private void applyReportProjectVisibility(LambdaQueryWrapper<QualityCheckReport> wrapper,
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
     * 将项目/工作空间可见范围追加到异常明细查询。
     *
     * <p>异常明细可能包含样本载荷和修复建议，敏感度高于报告摘要。
     * 所以异常检索必须与报告检索使用同样严格的 PROJECT 过滤语义。</p>
     */
    private void applyAnomalyProjectVisibility(LambdaQueryWrapper<QualityAnomalyDetail> wrapper,
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
     * 校验单份报告是否对当前请求可读。
     *
     * <p>详情接口只接收 reportId，无法在 SQL 层天然带上用户选择的 projectId。
     * 因此读取报告后必须再用报告自身的 projectId 做一次权限判断，防止通过猜测 ID 越权查看异常样本。</p>
     */
    private void validateReportReadable(QualityCheckReport report, QualityProjectVisibility visibility) {
        if (visibility == null || !visibility.projectScopeEnforced()) {
            return;
        }
        if (report.getProjectId() == null || !visibility.authorizedProjectIds().contains(report.getProjectId())) {
            throw new IllegalArgumentException("当前身份不能访问未授权项目的质量报告，reportId="
                    + report.getId() + ", projectId=" + report.getProjectId());
        }
    }

    private Long calculateDurationMs(QualityCheckExecution execution) {
        return execution.getStartedAt() == null || execution.getFinishedAt() == null
                ? null
                : ChronoUnit.MILLIS.between(execution.getStartedAt(), execution.getFinishedAt());
    }

    private String buildExecutionFailureMessage(QualityExecutionFailRequest request) {
        String errorType = hasText(request.getErrorType()) ? normalizeUpper(request.getErrorType()) : "UNKNOWN";
        String retryable = request.getRetryable() == null ? "UNKNOWN" : String.valueOf(request.getRetryable());
        return trimToMaxLength("errorType=" + errorType
                + ", retryable=" + retryable
                + ", message=" + request.getErrorMessage(), 1000);
    }
    private BigDecimal calculatePassRate(Integer sampleSize, Integer exceptionCount) {
        if (sampleSize == null || sampleSize <= 0) {
            return BigDecimal.ZERO;
        }
        int safeExceptionCount = exceptionCount == null ? 0 : exceptionCount;
        int passedCount = Math.max(sampleSize - safeExceptionCount, 0);
        return BigDecimal.valueOf(passedCount)
                .divide(BigDecimal.valueOf(sampleSize), 4, RoundingMode.HALF_UP);
    }
    private String buildSummary(QualityRule rule, BigDecimal measuredValue, boolean passed,
                                Integer sampleSize, Integer exceptionCount) {
        return String.format("规则[%s]针对对象[%s]的检测结果为[%s]，实际值=%s，期望值=%s，样本量=%d，异常数=%d",
                rule.getName(), rule.getTargetObject(), passed ? "PASSED" : "FAILED",
                measuredValue, rule.getExpectedValue(), sampleSize, exceptionCount);
    }
    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
    private String normalizeUpper(String value) {
        return hasText(value) ? value.trim().toUpperCase() : value;
    }
    private String trimToMaxLength(String value, int maxLength) {
        if (value == null) { return null; }
        String trimmed = value.trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }
    private long safeCurrent(Integer current) {
        return current == null || current < 1 ? 1L : current;
    }
    private long safeSize(Integer size) {
        if (size == null || size < 1) { return 10L; }
        return Math.min(size, 100);
    }
    private int safeAggregationLimit(Integer limit) {
        if (limit == null || limit < 1) { return 20; }
        return Math.min(limit, 200);
    }
}
