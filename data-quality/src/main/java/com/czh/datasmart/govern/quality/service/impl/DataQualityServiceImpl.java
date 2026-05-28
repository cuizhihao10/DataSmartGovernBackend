/**
 * @Author : Cui
 * @Date: 2026/4/18 21:40
 * @Description DataSmart Govern Backend - DataQualityServiceImpl.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.quality.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
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
import com.czh.datasmart.govern.quality.executor.relational.RelationalQualitySqlTemplateBuilder;
import com.czh.datasmart.govern.quality.mapper.QualityRuleMapper;
import com.czh.datasmart.govern.quality.service.DataQualityService;
import com.czh.datasmart.govern.quality.service.support.QualityExecutionReportSupport;
import com.czh.datasmart.govern.quality.service.support.QualityProjectVisibility;
import com.czh.datasmart.govern.quality.service.support.QualityRuleLifecycleSupport;
import com.czh.datasmart.govern.quality.service.support.QualityTaskSchedulingSupport;
import com.czh.datasmart.govern.quality.support.QualityRuleStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 数据质量服务实现。
 * 这个类的核心职责，不只是把规则和报告存到数据库，更重要的是把
 * “业务上定义的质量标准”转换成一次确定性的通过/失败判断。
 *
 * 可以把当前实现理解为一个最小可用的数据质量引擎：
 * 1. 管理规则定义。
 * 2. 校验规则是否可执行。
 * 3. 用比较运算符判断实际观测值和期望值。
 * 4. 把本次判断沉淀成可追溯报告。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataQualityServiceImpl extends ServiceImpl<QualityRuleMapper, QualityRule> implements DataQualityService {

    /**
     * 关系型 SQL 模板构建器。
     *
     * <p>它只负责生成可审查的只读 SQL，不负责拿数据源连接执行。
     * 这样可以继续保持 data-quality 与 datasource-management 的边界，不让质量模块绕过数据源治理直接访问客户数据库。
     */
    private final RelationalQualitySqlTemplateBuilder relationalQualitySqlTemplateBuilder;

    /**
     * 质量执行、报告与异常明细支撑组件。
     *
     * <p>它承载 execution/report/anomaly 这条执行结果能力线，
     * 让本服务继续聚焦规则生命周期和跨模块调度编排。
     */
    private final QualityExecutionReportSupport qualityExecutionReportSupport;

    /**
     * 质量规则生命周期支撑组件。
     *
     * <p>它负责规则创建、更新、校验、启用、停用、归档、恢复和删除，让主服务不再直接承担规则状态机细节。
     */
    private final QualityRuleLifecycleSupport qualityRuleLifecycleSupport;

    /**
     * 质量任务提交支撑组件。
     *
     * <p>它负责把扫描计划转换为 task-management 创建任务请求，并处理 dryRun、集成开关和 payload 合同校验。
     */
    private final QualityTaskSchedulingSupport qualityTaskSchedulingSupport;

    /**
     * 创建质量规则。
     * 当前会完成名称去重、输入归一化、初始状态设置等动作。
     *
     * <p>新规则默认进入 DRAFT，而不是直接 ACTIVE。
     * 这是为了贴近商业化治理流程：规则通常需要配置、预览、评审或至少人工确认后再启用。
     */
    @Override
    @Transactional
    public QualityRule createRule(Long tenantId, Long projectId, Long workspaceId,
                                  String name, String ruleType, String targetObject, String targetType,
                                  Long dataSourceId, String databaseName, String schemaName, String tableName,
                                  String fieldName, String comparisonOperator,
                                  BigDecimal expectedValue, String severity, String description) {
        return qualityRuleLifecycleSupport.createRule(
                tenantId, projectId, workspaceId,
                name, ruleType, targetObject, targetType,
                dataSourceId, databaseName, schemaName, tableName,
                fieldName, comparisonOperator, expectedValue, severity, description
        );
    }

    /**
     * 更新质量规则。
     * 当前允许调整名称、目标、运算符、阈值、严重级别和说明，
     * 但不允许在这里直接改 ruleType，以保持规则基础分类稳定。
     */
    @Override
    @Transactional
    public QualityRule updateRule(Long id, String name, String targetObject, String targetType,
                                  Long dataSourceId, String databaseName, String schemaName, String tableName,
                                  String fieldName, String comparisonOperator,
                                  BigDecimal expectedValue, String severity, String description) {
        return qualityRuleLifecycleSupport.updateRule(
                id, name, targetObject, targetType,
                dataSourceId, databaseName, schemaName, tableName,
                fieldName, comparisonOperator, expectedValue, severity, description
        );
    }

    @Override
    @Transactional
    public QualityRule enableRule(Long id) {
        return enableRule(id, null);
    }

    /**
     * 启用规则。
     *
     * <p>DRAFT、INACTIVE、ARCHIVED 都允许启用。
     * 对 ARCHIVED 规则启用时会清空 archivedTime，表示它重新回到当前治理流程。
     */
    @Override
    @Transactional
    public QualityRule enableRule(Long id, String reason) {
        return qualityRuleLifecycleSupport.enableRule(id, reason);
    }

    /**
     * 校验质量规则目标。
     *
     * <p>该方法既返回校验结果，也把校验状态写回规则表。
     * 这让“点击校验按钮”和“启用规则前自动校验”共享同一套逻辑，避免出现前端校验通过、
     * 后端启用时又使用另一套标准的情况。
     */
    @Override
    @Transactional
    public QualityRuleTargetValidationResult validateRuleTarget(Long id) {
        return qualityRuleLifecycleSupport.validateRuleTarget(id);
    }

    /**
     * 生成质量扫描计划。
     *
     * <p>生成计划前会先检查规则存在、未删除，并且目标已经通过校验。
     * 这里暂时不强制规则必须 ACTIVE，是为了支持“草稿规则预览扫描计划”的产品体验：
     * 用户可以在启用前先看到扫描范围和风险，再决定是否调整参数。
     */
    @Override
    public QualityScanPlan buildScanPlan(Long id, QualityScanPlanRequest request) {
        return qualityRuleLifecycleSupport.buildScanPlan(id, request);
    }

    /**
     * 生成关系型质量扫描 SQL 计划。
     *
     * <p>这是从“扫描计划”到“可执行模板”的下一层转换，但它仍然不触碰真实源库。
     * 这样设计的原因是：SQL 模板必须先经过治理系统审查，确认只读、有限制、有超时、有样本上限，
     * 然后才能交给受控执行器在 datasource-management 授权的连接上执行。
     *
     * <p>当前第一版只支持关系型 COMPLETENESS 和 UNIQUENESS 字段规则，其他规则会返回 supported=false。
     * 这比强行生成不准确 SQL 更可靠，也更符合商业产品对结果可信度的要求。
     */
    @Override
    public RelationalQualityScanSqlPlan buildRelationalSqlPlan(Long id, QualityScanPlanRequest request) {
        QualityRule rule = qualityRuleLifecycleSupport.getRequiredRule(id);
        qualityRuleLifecycleSupport.ensureNotDeleted(rule);
        QualityScanPlan scanPlan = buildScanPlan(id, request);
        RelationalQualityScanSqlPlan sqlPlan = relationalQualitySqlTemplateBuilder.build(rule, scanPlan);
        log.info("生成关系型质量扫描 SQL 计划完成，ruleId={}, supported={}, targetType={}, ruleType={}",
                id, sqlPlan.getSupported(), sqlPlan.getTargetType(), sqlPlan.getRuleType());
        return sqlPlan;
    }

    /**
     * 提交质量检测任务。
     *
     * <p>该流程是 data-quality 和 task-management 之间的第一版业务闭环：
     * 1. data-quality 负责校验规则和生成扫描计划；
     * 2. task-management 负责保存任务、排队、认领、心跳、超时恢复和重试；
     * 3. 未来质量执行器认领 DATA_QUALITY_SCAN 任务后，再按 params 中的 scanPlan 执行扫描并回写报告。
     */
    @Override
    public QualityTaskScheduleResult scheduleQualityCheckTask(Long id, QualityTaskScheduleRequest request) {
        QualityRule rule = qualityRuleLifecycleSupport.getRequiredRule(id);
        qualityRuleLifecycleSupport.ensureNotDeleted(rule);
        QualityScanPlan plan = buildScanPlan(id, request == null ? null : request.getScanPlan());
        return qualityTaskSchedulingSupport.schedule(rule, plan, request);
    }

    /**
     * 任务执行器开始执行质量检测。
     *
     * <p>这是 data-quality 与未来质量执行器之间的第一段回调合同。
     * 任务中心只知道“有一个 DATA_QUALITY_SCAN 任务正在被执行”，但它不理解质量规则、报告和异常证据。
     * data-quality 需要在执行开始时创建自己的 execution 记录，后续才能把扫描成功/失败、报告 ID、异常明细
     * 都挂到同一个质量域执行链路下。
     *
     * <p>这里要求规则必须仍然是 ACTIVE，这是生产安全线：
     * 1. 防止执行器消费到很久以前提交、但规则已经停用或归档的任务；
     * 2. 防止草稿规则绕过启用审批直接进入生产扫描；
     * 3. 让“是否允许执行”的最终判断仍然由 data-quality 规则生命周期控制。
     */
    @Override
    @Transactional
    public QualityCheckExecution startTaskExecution(QualityExecutionStartRequest request) {
        QualityRule rule = qualityRuleLifecycleSupport.getRequiredRule(request.getRuleId());
        qualityRuleLifecycleSupport.ensureNotDeleted(rule);
        if (!QualityRuleStatus.ACTIVE.equals(rule.getStatus())) {
            throw new IllegalStateException("只有启用状态的质量规则才能由任务执行器执行: " + rule.getId());
        }

        QualityCheckExecution execution = qualityExecutionReportSupport.createRunningExecution(
                rule,
                "TASK_TRIGGERED",
                request.getExecutorId(),
                request.getTaskId(),
                request.getTaskRunId(),
                request.getExecutorId(),
                request.getScanPlanSnapshot(),
                request.getMessage()
        );
        log.info("质量任务执行开始，ruleId={}, executionId={}, taskId={}, taskRunId={}, executorId={}",
                rule.getId(), execution.getId(), request.getTaskId(), request.getTaskRunId(), request.getExecutorId());
        return execution;
    }

    /**
     * 任务执行器成功完成质量检测并回写报告。
     *
     * <p>该方法会把一次 RUNNING execution 收口为 SUCCESS，并生成质量报告。
     * 这里刻意不重新创建 execution，因为 execution 已经在 startTaskExecution 中代表了“这次任务运行”。
     * 如果成功回调重新创建执行记录，运营后台就会看到一次任务产生两条执行记录，链路会变得混乱。
     *
     * <p>并发和幂等边界：
     * 当前先用状态校验阻止已完成 execution 被重复成功/失败回调覆盖。
     * 未来如果执行器或网络重试非常频繁，应进一步引入回调幂等键、乐观锁版本号或数据库条件更新。
     */
    @Override
    @Transactional
    public QualityCheckReport completeTaskExecution(Long executionId, QualityExecutionSuccessRequest request) {
        QualityCheckExecution execution = qualityExecutionReportSupport.getRequiredExecution(executionId);
        qualityExecutionReportSupport.ensureExecutionRunning(execution);
        qualityExecutionReportSupport.ensureResultMetrics(request.getSampleSize(), request.getExceptionCount());

        QualityRule rule = qualityRuleLifecycleSupport.getRequiredRule(execution.getRuleId());
        qualityRuleLifecycleSupport.ensureNotDeleted(rule);
        QualityCheckReport report = qualityExecutionReportSupport.createReportFromCompletedExecution(
                execution,
                rule,
                request.getMeasuredValue(),
                request.getSampleSize(),
                request.getExceptionCount(),
                request.getNotes(),
                request.getAnomalies()
        );

        qualityExecutionReportSupport.markExecutionSucceeded(
                execution,
                report,
                "任务触发质量检测执行成功，报告结果=" + report.getCheckStatus()
        );
        qualityExecutionReportSupport.updateRuleLastCheckSnapshot(rule, report);

        log.info("质量任务执行成功回调完成，executionId={}, ruleId={}, reportId={}, status={}",
                executionId, rule.getId(), report.getId(), report.getCheckStatus());
        return report;
    }

    /**
     * 任务执行器失败回调。
     *
     * <p>失败回调只更新 execution，不生成 report。
     * 这是为了保持两个概念的边界：
     * 1. execution FAILED 表示技术执行失败，例如连接失败、扫描超时、执行器崩溃；
     * 2. report FAILED 表示检测动作完成，但业务数据没有达到质量规则要求。
     *
     * <p>这里也不直接改写规则最近检测结果，因为没有形成有效质量判定。
     * 后续可以在质量运营大盘里单独统计“执行失败率”，避免把平台稳定性问题混入数据质量评分。
     */
    @Override
    @Transactional
    public QualityCheckExecution failTaskExecution(Long executionId, QualityExecutionFailRequest request) {
        QualityCheckExecution execution = qualityExecutionReportSupport.getRequiredExecution(executionId);
        qualityExecutionReportSupport.ensureExecutionRunning(execution);
        qualityExecutionReportSupport.markExecutionFailed(execution, request);

        log.warn("质量任务执行失败回调完成，executionId={}, taskId={}, taskRunId={}, executorId={}, message={}",
                executionId, execution.getTaskId(), execution.getTaskRunId(),
                execution.getExecutorId(), execution.getMessage());
        return execution;
    }

    @Override
    @Transactional
    public QualityRule disableRule(Long id) {
        return disableRule(id, null);
    }

    /**
     * 停用规则。
     *
     * <p>停用保留规则定义和历史报告，但不允许继续执行检测。
     */
    @Override
    @Transactional
    public QualityRule disableRule(Long id, String reason) {
        return qualityRuleLifecycleSupport.disableRule(id, reason);
    }

    @Override
    @Transactional
    public QualityRule archiveRule(Long id, String reason) {
        return qualityRuleLifecycleSupport.archiveRule(id, reason);
    }

    /**
     * 恢复归档规则。
     *
     * <p>恢复后进入 INACTIVE，而不是直接 ACTIVE。
     * 这是一个保守设计：归档规则可能已经长期未执行，恢复后应先由管理员确认配置，再显式启用。
     */
    @Override
    @Transactional
    public QualityRule restoreRule(Long id, String reason) {
        return qualityRuleLifecycleSupport.restoreRule(id, reason);
    }

    @Override
    @Transactional
    public QualityRule deleteRule(Long id) {
        return qualityRuleLifecycleSupport.deleteRule(id);
    }

    /**
     * 执行质量检测。
     * 当前的核心原理是“规则 + 观测值”：
     * 1. 读取规则并校验规则处于可执行状态。
     * 2. 根据规则中的比较运算符，判断实际观测值与期望值的关系。
     * 3. 生成一份报告快照，把本次判断的上下文和结果持久化。
     *
     * 这种设计的价值在于，不管未来观测值来自 SQL、任务执行器还是 AI 分析，
     * 最终都能落成统一的判断逻辑和统一的报告模型。
     */
    @Override
    @Transactional
    public QualityCheckReport runQualityCheck(Long ruleId, BigDecimal measuredValue, Integer sampleSize,
                                              Integer exceptionCount, String notes,
                                              List<QualityAnomalyDetailRequest> anomalies) {
        QualityRule rule = qualityRuleLifecycleSupport.getRequiredRule(ruleId);
        qualityRuleLifecycleSupport.ensureNotDeleted(rule);
        if (!QualityRuleStatus.ACTIVE.equals(rule.getStatus())) {
            throw new IllegalStateException("只有启用状态的规则才能执行质量检测");
        }
        qualityExecutionReportSupport.ensureResultMetrics(sampleSize, exceptionCount);

        QualityCheckExecution execution = qualityExecutionReportSupport.createRunningExecution(rule, "MANUAL");
        QualityCheckReport report = qualityExecutionReportSupport.createReportFromCompletedExecution(
                execution,
                rule,
                measuredValue,
                sampleSize,
                exceptionCount,
                notes,
                anomalies
        );

        qualityExecutionReportSupport.markExecutionSucceeded(
                execution,
                report,
                "质量检测执行成功，报告结果=" + report.getCheckStatus()
        );
        qualityExecutionReportSupport.updateRuleLastCheckSnapshot(rule, report);

        log.info("执行质量检测完成，ruleId={}, reportId={}, status={}",
                ruleId, report.getId(), report.getCheckStatus());
        return report;
    }

    /**
     * 查询某条规则下的历史报告。
     * 结果按创建时间倒序返回，便于优先看到最近一次检测结果。
     */
    @Override
    public List<QualityCheckReport> listReportsByRuleId(Long ruleId) {
        qualityRuleLifecycleSupport.getRequiredRule(ruleId);
        return qualityExecutionReportSupport.listReportsByRuleId(ruleId);
    }

    /**
     * 分页查询质量报告。
     *
     * <p>这里没有直接把条件拼成 SQL 字符串，而是使用 MyBatis-Plus 的 LambdaQueryWrapper：
     * 1. 字段引用来自实体 getter，减少列名写错的风险；
     * 2. 每个条件都只在参数有值时追加，适合后台筛选面板的“可选条件组合查询”；
     * 3. 查询统一按创建时间和主键倒序，让最新报告始终排在前面。
     *
     * <p>failedOnly 是一个产品化便捷条件，常用于质量大盘的“只看异常”视图。
     * 如果 failedOnly=true，会优先把 checkStatus 固定为 FAILED，避免前端还要同时理解两个参数的组合规则。
     */
    @Override
    public IPage<QualityCheckReport> pageReports(Integer current, Integer size, Long ruleId, String ruleType,
                                                 String severity, String checkStatus, String targetObject,
                                                 String triggerType, LocalDateTime startTime,
                                                 LocalDateTime endTime, Boolean failedOnly,
                                                 QualityProjectVisibility visibility) {
        return qualityExecutionReportSupport.pageReports(
                current, size, ruleId, ruleType, severity, checkStatus,
                targetObject, triggerType, startTime, endTime, failedOnly, visibility
        );
    }

    /**
     * 查询某份报告下的异常明细。
     *
     * <p>先校验报告存在，再查询明细。这个额外校验的意义是让调用方区分两种情况：
     * 1. 报告存在但没有异常明细，返回空列表；
     * 2. 报告 ID 本身不存在，抛出明确异常。
     */
    @Override
    public List<QualityAnomalyDetail> listAnomaliesByReportId(Long reportId, QualityProjectVisibility visibility) {
        return qualityExecutionReportSupport.listAnomaliesByReportId(reportId, visibility);
    }

    /**
     * 分页查询异常明细。
     *
     * <p>这个接口的产品定位是“异常运营台”的下钻列表：
     * 1. 从质量大盘点击某个失败规则，可以按 ruleId 查询；
     * 2. 从某份报告详情进入，可以按 reportId 查询；
     * 3. 从字段画像页进入，可以按 fieldName 查询；
     * 4. 从告警中心进入，可以按 severity、anomalyType 和时间窗口查询。
     *
     * <p>分页大小沿用 safeSize 的上限控制，避免异常明细接口被误用成全量导出接口。
     * 真正的大批量导出应该走异步导出任务，而不是同步接口一次性返回大量样本。
     */
    @Override
    public IPage<QualityAnomalyDetail> pageAnomalies(Integer current, Integer size, Long reportId, Long ruleId,
                                                     String anomalyType, String fieldName, String severity,
                                                     String targetObject, LocalDateTime startTime,
                                                     LocalDateTime endTime, QualityProjectVisibility visibility) {
        return qualityExecutionReportSupport.pageAnomalies(
                current, size, reportId, ruleId, anomalyType, fieldName,
                severity, targetObject, startTime, endTime, visibility
        );
    }

    /**
     * 聚合统计异常明细。
     *
     * <p>分页明细适合排查单条样本，聚合统计适合发现“主要矛盾”。
     * 例如同一个报告里有 10 万条异常，如果逐条看会非常低效；
     * 先按 FIELD 聚合，就能快速知道是 email 字段、phone 字段还是 amount 字段贡献了最多异常。
     *
     * <p>groupBy 会先转换为 QualityAnomalyAggregationDimension，再由枚举提供数据库列名。
     * 这一步是安全边界：调用方只能使用平台允许的聚合维度，不能构造任意 SQL 字段。
     */
    @Override
    public List<QualityAnomalyAggregationItem> aggregateAnomalies(Long reportId, Long ruleId, String anomalyType,
                                                                  String fieldName, String severity,
                                                                  String targetObject, LocalDateTime startTime,
                                                                  LocalDateTime endTime, String groupBy,
                                                                  Integer limit,
                                                                  QualityProjectVisibility visibility) {
        return qualityExecutionReportSupport.aggregateAnomalies(
                reportId, ruleId, anomalyType, fieldName, severity,
                targetObject, startTime, endTime, groupBy, limit, visibility
        );
    }

    @Override
    public List<QualityCheckExecution> listExecutionsByRuleId(Long ruleId) {
        qualityRuleLifecycleSupport.getRequiredRule(ruleId);
        return qualityExecutionReportSupport.listExecutionsByRuleId(ruleId);
    }

}
