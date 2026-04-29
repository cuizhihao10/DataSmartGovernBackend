/**
 * @Author : Cui
 * @Date: 2026/4/18 21:40
 * @Description DataSmart Govern Backend - DataQualityServiceImpl.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.quality.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.czh.datasmart.govern.quality.config.TaskManagementIntegrationProperties;
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
import com.czh.datasmart.govern.quality.integration.task.QualityTaskPayload;
import com.czh.datasmart.govern.quality.integration.task.QualityTaskPayloadParser;
import com.czh.datasmart.govern.quality.integration.task.TaskCreateRequest;
import com.czh.datasmart.govern.quality.integration.task.TaskCreateResponse;
import com.czh.datasmart.govern.quality.integration.task.TaskManagementClient;
import com.czh.datasmart.govern.quality.mapper.QualityAnomalyDetailMapper;
import com.czh.datasmart.govern.quality.mapper.QualityCheckExecutionMapper;
import com.czh.datasmart.govern.quality.mapper.QualityCheckReportMapper;
import com.czh.datasmart.govern.quality.mapper.QualityRuleMapper;
import com.czh.datasmart.govern.quality.scan.QualityScanStrategyRegistry;
import com.czh.datasmart.govern.quality.service.DataQualityService;
import com.czh.datasmart.govern.quality.support.QualityCheckExecutionState;
import com.czh.datasmart.govern.quality.support.QualityCheckStatus;
import com.czh.datasmart.govern.quality.support.QualityComparisonOperator;
import com.czh.datasmart.govern.quality.support.QualityAnomalyAggregationDimension;
import com.czh.datasmart.govern.quality.support.QualityRuleTargetType;
import com.czh.datasmart.govern.quality.support.QualityRuleStatus;
import com.czh.datasmart.govern.quality.support.QualityRuleType;
import com.czh.datasmart.govern.quality.support.QualitySeverity;
import com.czh.datasmart.govern.quality.support.QualityTargetValidationStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.NoSuchElementException;

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
     * 检测报告 Mapper。
     * 规则表负责保存“标准”，报告表负责保存“执行结果”。
     */
    private final QualityCheckReportMapper qualityCheckReportMapper;

    /**
     * 检测执行记录 Mapper。
     *
     * <p>执行记录用于追踪“这次检测动作是否成功跑完”，报告用于追踪“检测结果是否通过”。
     */
    private final QualityCheckExecutionMapper qualityCheckExecutionMapper;

    /**
     * 异常明细 Mapper。
     *
     * <p>异常明细与报告主记录拆表存储，是为了兼顾两个目标：
     * 1. 报告列表查询保持轻量，不会因为异常样本过多而拖慢首页和大盘；
     * 2. 进入报告详情后仍然可以看到可定位、可清洗、可审计的样本级证据。
     */
    private final QualityAnomalyDetailMapper qualityAnomalyDetailMapper;

    /**
     * 质量扫描策略注册表。
     *
     * <p>DataQualityServiceImpl 不直接知道每种目标怎么校验，而是把目标类型交给策略注册表。
     * 这样后续扩展 PostgreSQL、Kafka、文件、API 或对象存储扫描时，只需要增加策略实现。
     */
    private final QualityScanStrategyRegistry qualityScanStrategyRegistry;

    /**
     * 关系型 SQL 模板构建器。
     *
     * <p>它只负责生成可审查的只读 SQL，不负责拿数据源连接执行。
     * 这样可以继续保持 data-quality 与 datasource-management 的边界，不让质量模块绕过数据源治理直接访问客户数据库。
     */
    private final RelationalQualitySqlTemplateBuilder relationalQualitySqlTemplateBuilder;

    /**
     * task-management 远程客户端。
     */
    private final TaskManagementClient taskManagementClient;

    /**
     * task-management 集成配置。
     */
    private final TaskManagementIntegrationProperties taskManagementIntegrationProperties;

    /**
     * 质量任务 payload 解析与校验器。
     *
     * <p>data-quality 在提交任务前先校验自己生成的 JSON 合同，
     * 未来执行器认领任务后也应复用同一套校验逻辑，避免生产队列中混入不可执行任务。
     */
    private final QualityTaskPayloadParser qualityTaskPayloadParser;

    /**
     * JSON 序列化器。
     *
     * <p>任务中心的 params 是 JSON 字符串，因此质量模块需要把扫描计划和规则快照打包为 JSON payload。
     */
    private final ObjectMapper objectMapper;

    /**
     * 创建质量规则。
     * 当前会完成名称去重、输入归一化、初始状态设置等动作。
     *
     * <p>新规则默认进入 DRAFT，而不是直接 ACTIVE。
     * 这是为了贴近商业化治理流程：规则通常需要配置、预览、评审或至少人工确认后再启用。
     */
    @Override
    @Transactional
    public QualityRule createRule(String name, String ruleType, String targetObject, String targetType,
                                  Long dataSourceId, String databaseName, String schemaName, String tableName,
                                  String fieldName, String comparisonOperator,
                                  BigDecimal expectedValue, String severity, String description) {
        ensureRuleNameNotDuplicated(name, null);

        QualityRule rule = new QualityRule();
        rule.setName(name);
        rule.setRuleType(QualityRuleType.fromValue(ruleType).name());
        rule.setTargetObject(targetObject);
        applyTargetFields(rule, targetType, dataSourceId, databaseName, schemaName, tableName, fieldName);
        rule.setComparisonOperator(QualityComparisonOperator.fromValue(comparisonOperator).name());
        rule.setExpectedValue(expectedValue);
        rule.setSeverity(QualitySeverity.normalize(severity));
        rule.setDescription(description);
        rule.setStatus(QualityRuleStatus.DRAFT);
        rule.setRuleVersion(1);
        rule.setTargetValidationStatus(QualityTargetValidationStatus.UNVALIDATED);
        rule.setCreateTime(LocalDateTime.now());
        rule.setUpdateTime(LocalDateTime.now());
        save(rule);
        applyValidationResult(rule, qualityScanStrategyRegistry.validate(rule));

        log.info("创建质量规则成功，ruleId={}", rule.getId());
        return rule;
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
        QualityRule rule = getRequiredRule(id);
        ensureNotDeleted(rule);
        ensureRuleNameNotDuplicated(name, id);

        rule.setName(name);
        rule.setTargetObject(targetObject);
        applyTargetFields(rule, targetType, dataSourceId, databaseName, schemaName, tableName, fieldName);
        rule.setComparisonOperator(QualityComparisonOperator.fromValue(comparisonOperator).name());
        rule.setExpectedValue(expectedValue);
        rule.setSeverity(QualitySeverity.normalize(severity));
        rule.setDescription(description);
        rule.setRuleVersion(rule.getRuleVersion() == null ? 1 : rule.getRuleVersion() + 1);
        rule.setTargetValidationStatus(QualityTargetValidationStatus.UNVALIDATED);
        rule.setTargetValidationMessage("规则目标已更新，等待重新校验");
        rule.setTargetValidatedTime(null);
        rule.setUpdateTime(LocalDateTime.now());
        updateById(rule);
        applyValidationResult(rule, qualityScanStrategyRegistry.validate(rule));

        log.info("更新质量规则成功，ruleId={}", id);
        return rule;
    }

    /**
     * 启用规则。
     * 只有未删除规则才允许启用。
     */
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
        QualityRule rule = getRequiredRule(id);
        ensureNotDeleted(rule);
        QualityRuleTargetValidationResult validationResult = qualityScanStrategyRegistry.validate(rule);
        applyValidationResult(rule, validationResult);
        ensureTargetCanBeActivated(validationResult);
        rule.setStatus(QualityRuleStatus.ACTIVE);
        rule.setArchivedTime(null);
        rule.setUpdateTime(LocalDateTime.now());
        updateById(rule);

        log.info("启用质量规则成功，ruleId={}, reason={}", id, reason);
        return rule;
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
        QualityRule rule = getRequiredRule(id);
        ensureNotDeleted(rule);
        QualityRuleTargetValidationResult result = qualityScanStrategyRegistry.validate(rule);
        applyValidationResult(rule, result);
        log.info("校验质量规则目标完成，ruleId={}, status={}, strategy={}",
                id, result.getValidationStatus(), result.getScanStrategy());
        return result;
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
        QualityRule rule = getRequiredRule(id);
        ensureNotDeleted(rule);
        if (!QualityTargetValidationStatus.VALIDATED.equals(rule.getTargetValidationStatus())) {
            throw new IllegalStateException("质量规则目标尚未通过校验，不能生成扫描计划: " + rule.getTargetValidationMessage());
        }
        QualityScanPlan plan = qualityScanStrategyRegistry.buildScanPlan(rule, request);
        log.info("生成质量扫描计划完成，ruleId={}, strategy={}, mode={}, schedulable={}",
                id, plan.getScanStrategy(), plan.getExecutionMode(), plan.getSchedulable());
        return plan;
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
        QualityRule rule = getRequiredRule(id);
        ensureNotDeleted(rule);
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
        QualityRule rule = getRequiredRule(id);
        ensureNotDeleted(rule);
        QualityScanPlan plan = buildScanPlan(id, request == null ? null : request.getScanPlan());
        QualityTaskScheduleResult result = new QualityTaskScheduleResult();
        result.setRuleId(id);
        result.setScanPlan(plan);
        result.setDryRun(request != null && Boolean.TRUE.equals(request.getDryRun()));
        result.setScheduledTime(LocalDateTime.now());

        if (!Boolean.TRUE.equals(plan.getSchedulable())) {
            result.setSubmitted(false);
            result.setMessage("扫描计划不可调度，未提交到任务中心");
            result.getWarnings().addAll(plan.getWarnings());
            return result;
        }
        if (Boolean.TRUE.equals(result.getDryRun())) {
            result.setSubmitted(false);
            result.setMessage("当前为 dryRun，仅生成扫描计划，未提交任务");
            return result;
        }
        if (!taskManagementIntegrationProperties.isEnabled()) {
            result.setSubmitted(false);
            result.setMessage("task-management 集成未启用，未提交任务");
            result.getWarnings().add("请开启 datasmart.quality.task-management.enabled 后再提交真实任务。");
            return result;
        }

        TaskCreateRequest taskRequest = buildTaskCreateRequest(rule, plan, request);
        TaskCreateResponse task = taskManagementClient.createTask(taskRequest);
        if (task == null) {
            result.setSubmitted(false);
            result.setMessage("task-management 调用失败，但当前配置允许 fail-open，任务未真实入队");
            result.getWarnings().add("请检查 task-management 是否可访问，并确认任务是否需要人工补提。");
            return result;
        }
        result.setSubmitted(true);
        result.setTaskId(task.getId());
        result.setTaskStatus(task.getStatus());
        result.setTaskType(task.getType());
        result.setMessage("质量检测任务已提交到 task-management");
        return result;
    }

    /**
     * 构造 task-management 创建任务请求。
     *
     * <p>任务参数 params 使用 JSON 字符串承载。这里放入的是“执行质量扫描所需的最小闭环信息”：
     * 规则快照、扫描计划、提交原因。未来执行器不需要再回查过多上下文，也能知道本次任务要做什么。
     */
    private TaskCreateRequest buildTaskCreateRequest(QualityRule rule, QualityScanPlan plan,
                                                     QualityTaskScheduleRequest request) {
        TaskCreateRequest taskRequest = new TaskCreateRequest();
        taskRequest.setName("质量检测 - " + rule.getName());
        taskRequest.setDescription(buildQualityTaskDescription(rule, request));
        taskRequest.setType(taskManagementIntegrationProperties.getTaskType());
        taskRequest.setPriority(hasText(request == null ? null : request.getPriority())
                ? normalizeUpper(request.getPriority())
                : taskManagementIntegrationProperties.getDefaultPriority());
        taskRequest.setMaxRetryCount(request == null || request.getMaxRetryCount() == null
                ? taskManagementIntegrationProperties.getDefaultMaxRetryCount()
                : request.getMaxRetryCount());
        taskRequest.setParams(serializeQualityTaskPayload(rule, plan, request));
        return taskRequest;
    }

    /**
     * 构造质量检测任务描述。
     */
    private String buildQualityTaskDescription(QualityRule rule, QualityTaskScheduleRequest request) {
        String reason = request == null ? null : request.getReason();
        if (hasText(reason)) {
            return "质量规则[" + rule.getName() + "]检测任务，提交原因：" + reason;
        }
        return "质量规则[" + rule.getName() + "]检测任务，由 data-quality 根据扫描计划提交。";
    }

    /**
     * 序列化质量任务 payload。
     *
     * <p>这里不再使用临时 Map，而是构造 `QualityTaskPayload`。
     * 这是一次产品化升级：任务 payload 不是普通日志文本，而是跨微服务、跨执行器、跨版本的业务合同。
     * 在写入 task-management 之前先做 schema 校验，可以尽早发现字段缺失、规则快照不完整或扫描计划不可调度。
     */
    private String serializeQualityTaskPayload(QualityRule rule, QualityScanPlan plan,
                                               QualityTaskScheduleRequest request) {
        QualityTaskPayload payload = new QualityTaskPayload();
        payload.setSchemaVersion(QualityTaskPayloadParser.SUPPORTED_SCHEMA_VERSION);
        payload.setSourceModule(QualityTaskPayloadParser.SOURCE_MODULE);
        payload.setTaskKind(QualityTaskPayloadParser.TASK_KIND);
        payload.setTenantId(resolvePayloadTenantId(request));
        payload.setRuleId(rule.getId());
        payload.setRuleName(rule.getName());
        payload.setRuleVersion(rule.getRuleVersion());
        payload.setRuleType(rule.getRuleType());
        payload.setSeverity(rule.getSeverity());
        payload.setComparisonOperator(rule.getComparisonOperator());
        payload.setExpectedValue(rule.getExpectedValue());
        payload.setReason(request == null ? null : request.getReason());
        payload.setScanPlan(plan);
        qualityTaskPayloadParser.validate(payload);
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("序列化质量检测任务参数失败", ex);
        }
    }

    /**
     * 解析质量任务 payload 的租户 ID。
     *
     * <p>当前项目的 gateway 可信租户上下文还在逐步接入中，所以这里采用过渡策略：
     * - 如果提交任务请求显式传入 tenantId，则把它写入 payload，便于本地联调和测试租户级并发护栏；
     * - 如果未传入，则回退到 data-quality 服务账号配置中的 executorActorTenantId。
     *
     * <p>后续生产化时，这里应该优先读取 gateway 注入的 X-DataSmart-Tenant-Id，
     * 并由 permission-admin / gateway 保证调用方不能伪造其他租户。
     */
    private Long resolvePayloadTenantId(QualityTaskScheduleRequest request) {
        if (request != null && request.getTenantId() != null) {
            return request.getTenantId();
        }
        return taskManagementIntegrationProperties.getExecutorActorTenantId();
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
        QualityRule rule = getRequiredRule(request.getRuleId());
        ensureNotDeleted(rule);
        if (!QualityRuleStatus.ACTIVE.equals(rule.getStatus())) {
            throw new IllegalStateException("只有启用状态的质量规则才能由任务执行器执行: " + rule.getId());
        }

        QualityCheckExecution execution = createRunningExecution(
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
        QualityCheckExecution execution = getRequiredExecution(executionId);
        ensureExecutionRunning(execution);
        ensureResultMetrics(request.getSampleSize(), request.getExceptionCount());

        QualityRule rule = getRequiredRule(execution.getRuleId());
        ensureNotDeleted(rule);
        QualityCheckReport report = createReportFromCompletedExecution(
                execution,
                rule,
                request.getMeasuredValue(),
                request.getSampleSize(),
                request.getExceptionCount(),
                request.getNotes(),
                request.getAnomalies()
        );

        execution.setExecutionState(QualityCheckExecutionState.SUCCESS);
        execution.setFinishedAt(LocalDateTime.now());
        execution.setDurationMs(calculateDurationMs(execution));
        execution.setReportId(report.getId());
        execution.setMessage("任务触发质量检测执行成功，报告结果=" + report.getCheckStatus());
        execution.setUpdateTime(LocalDateTime.now());
        qualityCheckExecutionMapper.updateById(execution);

        updateRuleLastCheckSnapshot(rule, report);

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
        QualityCheckExecution execution = getRequiredExecution(executionId);
        ensureExecutionRunning(execution);

        execution.setExecutionState(QualityCheckExecutionState.FAILED);
        execution.setFinishedAt(LocalDateTime.now());
        execution.setDurationMs(calculateDurationMs(execution));
        execution.setMessage(buildExecutionFailureMessage(request));
        execution.setUpdateTime(LocalDateTime.now());
        qualityCheckExecutionMapper.updateById(execution);

        log.warn("质量任务执行失败回调完成，executionId={}, taskId={}, taskRunId={}, executorId={}, message={}",
                executionId, execution.getTaskId(), execution.getTaskRunId(),
                execution.getExecutorId(), execution.getMessage());
        return execution;
    }

    /**
     * 停用规则。
     * 停用后规则仍然存在，但不再允许执行检测。
     */
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
        QualityRule rule = getRequiredRule(id);
        ensureNotDeleted(rule);
        ensureNotArchived(rule);
        rule.setStatus(QualityRuleStatus.INACTIVE);
        rule.setUpdateTime(LocalDateTime.now());
        updateById(rule);

        log.info("停用质量规则成功，ruleId={}, reason={}", id, reason);
        return rule;
    }

    /**
     * 归档规则。
     *
     * <p>归档比停用更强，表达规则已经退出当前治理流程。
     * 归档规则不再执行，但历史报告仍可查询。
     */
    @Override
    @Transactional
    public QualityRule archiveRule(Long id, String reason) {
        QualityRule rule = getRequiredRule(id);
        ensureNotDeleted(rule);
        if (QualityRuleStatus.ARCHIVED.equals(rule.getStatus())) {
            return rule;
        }
        rule.setStatus(QualityRuleStatus.ARCHIVED);
        rule.setArchivedTime(LocalDateTime.now());
        rule.setUpdateTime(LocalDateTime.now());
        updateById(rule);

        log.info("归档质量规则成功，ruleId={}, reason={}", id, reason);
        return rule;
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
        QualityRule rule = getRequiredRule(id);
        ensureNotDeleted(rule);
        if (!QualityRuleStatus.ARCHIVED.equals(rule.getStatus())) {
            throw new IllegalStateException("只有已归档规则才能恢复");
        }
        rule.setStatus(QualityRuleStatus.INACTIVE);
        rule.setArchivedTime(null);
        rule.setUpdateTime(LocalDateTime.now());
        updateById(rule);

        log.info("恢复归档质量规则成功，ruleId={}, reason={}", id, reason);
        return rule;
    }

    /**
     * 逻辑删除规则。
     * 当前不做物理删除，目的是保留规则生命周期痕迹，便于未来审计与恢复设计。
     */
    @Override
    @Transactional
    public QualityRule deleteRule(Long id) {
        QualityRule rule = getRequiredRule(id);
        rule.setStatus(QualityRuleStatus.DELETED);
        rule.setUpdateTime(LocalDateTime.now());
        updateById(rule);

        log.info("逻辑删除质量规则成功，ruleId={}", id);
        return rule;
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
        QualityRule rule = getRequiredRule(ruleId);
        ensureNotDeleted(rule);
        if (!QualityRuleStatus.ACTIVE.equals(rule.getStatus())) {
            throw new IllegalStateException("只有启用状态的规则才能执行质量检测");
        }
        ensureResultMetrics(sampleSize, exceptionCount);

        QualityCheckExecution execution = createRunningExecution(rule, "MANUAL");
        QualityCheckReport report = createReportFromCompletedExecution(
                execution,
                rule,
                measuredValue,
                sampleSize,
                exceptionCount,
                notes,
                anomalies
        );

        execution.setExecutionState(QualityCheckExecutionState.SUCCESS);
        execution.setFinishedAt(LocalDateTime.now());
        execution.setDurationMs(calculateDurationMs(execution));
        execution.setReportId(report.getId());
        execution.setMessage("质量检测执行成功，报告结果=" + report.getCheckStatus());
        execution.setUpdateTime(LocalDateTime.now());
        qualityCheckExecutionMapper.updateById(execution);

        updateRuleLastCheckSnapshot(rule, report);

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
        getRequiredRule(ruleId);
        LambdaQueryWrapper<QualityCheckReport> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(QualityCheckReport::getRuleId, ruleId)
                .orderByDesc(QualityCheckReport::getCreateTime)
                .orderByDesc(QualityCheckReport::getId);
        return qualityCheckReportMapper.selectList(wrapper);
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
                                                 LocalDateTime endTime, Boolean failedOnly) {
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
        return qualityCheckReportMapper.selectPage(new Page<>(safeCurrent(current), safeSize(size)), wrapper);
    }

    /**
     * 查询某份报告下的异常明细。
     *
     * <p>先校验报告存在，再查询明细。这个额外校验的意义是让调用方区分两种情况：
     * 1. 报告存在但没有异常明细，返回空列表；
     * 2. 报告 ID 本身不存在，抛出明确异常。
     */
    @Override
    public List<QualityAnomalyDetail> listAnomaliesByReportId(Long reportId) {
        QualityCheckReport report = qualityCheckReportMapper.selectById(reportId);
        if (report == null) {
            throw new NoSuchElementException("质量检测报告不存在: " + reportId);
        }
        return qualityAnomalyDetailMapper.selectList(new LambdaQueryWrapper<QualityAnomalyDetail>()
                .eq(QualityAnomalyDetail::getReportId, reportId)
                .orderByDesc(QualityAnomalyDetail::getId));
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
                                                     LocalDateTime endTime) {
        LambdaQueryWrapper<QualityAnomalyDetail> wrapper = buildAnomalyQueryWrapper(
                reportId,
                ruleId,
                anomalyType,
                fieldName,
                severity,
                targetObject,
                startTime,
                endTime
        );
        wrapper.orderByDesc(QualityAnomalyDetail::getCreateTime)
                .orderByDesc(QualityAnomalyDetail::getId);
        return qualityAnomalyDetailMapper.selectPage(new Page<>(safeCurrent(current), safeSize(size)), wrapper);
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
                                                                  Integer limit) {
        QualityAnomalyAggregationDimension dimension = QualityAnomalyAggregationDimension.fromValue(groupBy);
        return qualityAnomalyDetailMapper.aggregateAnomalies(
                dimension.getColumnName(),
                reportId,
                ruleId,
                normalizeUpper(anomalyType),
                fieldName,
                normalizeUpper(severity),
                targetObject,
                startTime,
                endTime,
                safeAggregationLimit(limit)
        );
    }

    /**
     * 查询某条规则下的执行记录。
     */
    @Override
    public List<QualityCheckExecution> listExecutionsByRuleId(Long ruleId) {
        getRequiredRule(ruleId);
        return qualityCheckExecutionMapper.selectList(new LambdaQueryWrapper<QualityCheckExecution>()
                .eq(QualityCheckExecution::getRuleId, ruleId)
                .orderByDesc(QualityCheckExecution::getExecutionNo)
                .orderByDesc(QualityCheckExecution::getId));
    }

    /**
     * 应用规则目标字段。
     *
     * <p>这个方法把控制器传入的结构化目标信息统一写入实体，并完成 targetType 归一化。
     * 之所以集中处理，是为了保证创建和更新接口使用完全一致的目标建模规则。
     */
    private void applyTargetFields(QualityRule rule, String targetType, Long dataSourceId,
                                   String databaseName, String schemaName, String tableName, String fieldName) {
        QualityRuleTargetType normalizedTargetType = QualityRuleTargetType.fromValue(targetType);
        rule.setTargetType(normalizedTargetType.name());
        rule.setDataSourceId(dataSourceId);
        rule.setDatabaseName(trimToNull(databaseName));
        rule.setSchemaName(trimToNull(schemaName));
        rule.setTableName(trimToNull(tableName));
        rule.setFieldName(trimToNull(fieldName));
    }

    /**
     * 把目标校验结果写回规则实体和数据库。
     *
     * <p>目标校验不是一次性返回给调用方就结束，它会影响后续启用、调度、扫描和后台展示。
     * 因此需要把 scanStrategy、validationStatus、message、validatedTime 落库。
     */
    private void applyValidationResult(QualityRule rule, QualityRuleTargetValidationResult result) {
        rule.setScanStrategy(result.getScanStrategy());
        rule.setTargetValidationStatus(result.getValidationStatus());
        rule.setTargetValidationMessage(result.getMessage());
        rule.setTargetValidatedTime(result.getValidatedTime());
        rule.setUpdateTime(LocalDateTime.now());
        updateById(rule);
    }

    /**
     * 启用前校验目标是否可执行。
     *
     * <p>这是产品安全线：允许用户保存草稿规则，但不允许明显不可扫描的规则进入 ACTIVE。
     * 这样后续调度器不会不断领取无法执行的质量任务，运营人员也能在启用前看到原因。
     */
    private void ensureTargetCanBeActivated(QualityRuleTargetValidationResult result) {
        if (!Boolean.TRUE.equals(result.getValid())) {
            throw new IllegalStateException("质量规则目标校验未通过，不能启用: " + result.getMessage());
        }
    }

    /**
     * 查询必须存在的规则。
     * 这是服务层里非常常见的收口方法，用于消除重复的“查询 + 判空”模板代码。
     */
    private QualityRule getRequiredRule(Long id) {
        QualityRule rule = getById(id);
        if (rule == null) {
            throw new NoSuchElementException("质量规则不存在: " + id);
        }
        return rule;
    }

    /**
     * 查询必须存在的质量检测执行记录。
     *
     * <p>执行器成功/失败回调都必须基于已经创建的 executionId。
     * 如果 execution 不存在，说明执行器回调顺序错误、任务 payload 过期，或者调用方传错了环境。
     */
    private QualityCheckExecution getRequiredExecution(Long executionId) {
        QualityCheckExecution execution = qualityCheckExecutionMapper.selectById(executionId);
        if (execution == null) {
            throw new NoSuchElementException("质量检测执行记录不存在: " + executionId);
        }
        return execution;
    }

    /**
     * 校验规则是否已被逻辑删除。
     * 被删除规则不应该再参与修改、启停和执行。
     */
    private void ensureNotDeleted(QualityRule rule) {
        if (QualityRuleStatus.DELETED.equals(rule.getStatus())) {
            throw new IllegalStateException("质量规则已删除: " + rule.getId());
        }
    }

    /**
     * 校验规则未归档。
     */
    private void ensureNotArchived(QualityRule rule) {
        if (QualityRuleStatus.ARCHIVED.equals(rule.getStatus())) {
            throw new IllegalStateException("质量规则已归档，请先恢复后再操作: " + rule.getId());
        }
    }

    /**
     * 校验执行记录仍处于运行中。
     *
     * <p>成功回调和失败回调都只能处理 RUNNING 状态。
     * 这是一条基础幂等保护线：如果执行器因为网络超时重试同一个回调，
     * 已经 SUCCESS 或 FAILED 的 execution 不会被再次覆盖，避免报告重复生成或失败原因覆盖成功结果。
     */
    private void ensureExecutionRunning(QualityCheckExecution execution) {
        if (!QualityCheckExecutionState.RUNNING.equals(execution.getExecutionState())) {
            throw new IllegalStateException("质量检测执行记录已经结束，不能重复回调: " + execution.getId()
                    + ", state=" + execution.getExecutionState());
        }
    }

    /**
     * 校验检测结果指标之间的基本一致性。
     *
     * <p>DTO 上已经通过 Bean Validation 限制了非空和非负数，
     * 但“异常数不能大于样本量”属于跨字段业务规则，必须放在服务层统一校验。
     */
    private void ensureResultMetrics(Integer sampleSize, Integer exceptionCount) {
        if (sampleSize != null && exceptionCount != null && exceptionCount > sampleSize) {
            throw new IllegalArgumentException("异常数量不能大于样本量");
        }
    }

    /**
     * 计算执行耗时。
     *
     * <p>startedAt 理论上一定存在，但这里仍做保护，是为了兼容未来手工修复数据或历史迁移数据。
     */
    private Long calculateDurationMs(QualityCheckExecution execution) {
        if (execution.getStartedAt() == null || execution.getFinishedAt() == null) {
            return null;
        }
        return ChronoUnit.MILLIS.between(execution.getStartedAt(), execution.getFinishedAt());
    }

    /**
     * 构造执行失败说明。
     *
     * <p>message 字段当前是给运营后台和日志快速阅读的摘要，因此把错误类型、是否建议重试和错误描述拼在一起。
     * 后续如果要做精细化错误统计，应继续把 errorType、retryable 拆成独立列或结构化 JSON 字段。
     */
    private String buildExecutionFailureMessage(QualityExecutionFailRequest request) {
        String errorType = hasText(request.getErrorType()) ? normalizeUpper(request.getErrorType()) : "UNKNOWN";
        String retryable = request.getRetryable() == null ? "UNKNOWN" : String.valueOf(request.getRetryable());
        return trimToMaxLength("errorType=" + errorType
                + ", retryable=" + retryable
                + ", message=" + request.getErrorMessage(), 1000);
    }

    /**
     * 校验规则名称是否重复。
     * 规则名称在管理界面通常是主要识别字段，因此尽量保持唯一更利于治理和排障。
     */
    private void ensureRuleNameNotDuplicated(String name, Long currentId) {
        LambdaQueryWrapper<QualityRule> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(QualityRule::getName, name)
                .ne(currentId != null, QualityRule::getId, currentId)
                .ne(QualityRule::getStatus, QualityRuleStatus.DELETED);
        if (count(wrapper) > 0) {
            throw new IllegalArgumentException("质量规则名称已存在: " + name);
        }
    }

    /**
     * 构造质量检测摘要。
     * 摘要字段的存在意义，是让报告在列表视图里就能被快速阅读，
     * 而不需要每次都展开全部明细。
     */
    private String buildSummary(QualityRule rule, BigDecimal measuredValue, boolean passed,
                                 Integer sampleSize, Integer exceptionCount) {
        return String.format(
                "规则[%s]针对对象[%s]的检测结果为[%s]，实际值=%s，期望值=%s，样本量=%d，异常数=%d",
                rule.getName(),
                rule.getTargetObject(),
                passed ? "PASSED" : "FAILED",
                measuredValue,
                rule.getExpectedValue(),
                sampleSize,
                exceptionCount
        );
    }

    /**
     * 根据已完成的执行动作生成质量报告。
     *
     * <p>这个方法同时服务于两条执行路径：
     * 1. 人工同步执行：Controller 直接传入观测值，服务层立即生成 execution 和 report；
     * 2. 任务异步执行：执行器先 start 创建 execution，扫描完成后通过 success 回调生成 report。
     *
     * <p>把报告生成逻辑收口到这里，可以保证不同入口使用同一套比较运算、通过率计算、异常明细写入和报告快照字段。
     * 这对商业化产品很重要，因为用户不会接受“手动跑是一个结果，定时任务跑又是另一套判定标准”。
     */
    private QualityCheckReport createReportFromCompletedExecution(QualityCheckExecution execution, QualityRule rule,
                                                                  BigDecimal measuredValue, Integer sampleSize,
                                                                  Integer exceptionCount, String notes,
                                                                  List<QualityAnomalyDetailRequest> anomalies) {
        QualityComparisonOperator operator = QualityComparisonOperator.fromValue(rule.getComparisonOperator());
        boolean passed = operator.matches(measuredValue, rule.getExpectedValue());

        QualityCheckReport report = new QualityCheckReport();
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
     * 回写规则最近一次有效检测快照。
     *
     * <p>这里的“有效检测”指已经生成质量报告的检测。
     * 如果执行器连接数据源失败或扫描超时，只会更新 execution 失败状态，不会更新规则最近检测结果。
     * 这样规则列表展示的 lastCheckStatus 始终代表最近一次业务质量判定，而不是平台技术失败。
     */
    private void updateRuleLastCheckSnapshot(QualityRule rule, QualityCheckReport report) {
        rule.setLastCheckTime(report.getCreateTime());
        rule.setLastCheckStatus(report.getCheckStatus());
        rule.setLastReportId(report.getId());
        rule.setUpdateTime(LocalDateTime.now());
        updateById(rule);
    }

    /**
     * 创建运行中的质量检测执行记录。
     */
    private QualityCheckExecution createRunningExecution(QualityRule rule, String triggerType) {
        return createRunningExecution(rule, triggerType, "system", null, null, null, null, null);
    }

    /**
     * 创建运行中的质量检测执行记录。
     *
     * <p>该重载方法专门为任务执行器回调预留了跨模块字段。
     * 人工执行时这些字段为空；任务触发时会写入 taskId、taskRunId、executorId 和 scanPlanSnapshot。
     * 这样同一张 execution 表既能支撑当前同步检测，也能平滑承接异步调度。
     */
    private QualityCheckExecution createRunningExecution(QualityRule rule, String triggerType, String operator,
                                                         Long taskId, Long taskRunId, String executorId,
                                                         String scanPlanSnapshot, String message) {
        Long maxExecutionNo = qualityCheckExecutionMapper.selectMaxExecutionNo(rule.getId());
        QualityCheckExecution execution = new QualityCheckExecution();
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
     * 持久化异常明细。
     *
     * <p>这个方法刻意放在报告写入之后，因为异常明细需要绑定 reportId。
     * 它与报告创建处于同一个事务中：如果明细写入失败，整个检测报告也会回滚，
     * 避免出现“报告显示失败但没有任何可追溯异常证据”的不一致状态。
     *
     * <p>当前逐条 insert 更容易学习和调试；未来如果单次检测可能产生上万条异常，
     * 应扩展为批量写入、分页写入、Kafka 异步写入或 MinIO 明细文件归档，防止单事务过大。
     */
    private void persistAnomalyDetails(QualityCheckReport report, QualityRule rule,
                                       List<QualityAnomalyDetailRequest> anomalies) {
        if (anomalies == null || anomalies.isEmpty()) {
            return;
        }
        for (QualityAnomalyDetailRequest request : anomalies) {
            QualityAnomalyDetail detail = new QualityAnomalyDetail();
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

    /**
     * 构建异常明细查询条件。
     *
     * <p>分页查询和后续可能出现的导出查询会共享同一组筛选条件。
     * 把条件构造收口到一个私有方法，可以避免两个接口因为某个条件漏加而出现“列表和导出结果不一致”的问题。
     */
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
     * 计算通过率。
     */
    private BigDecimal calculatePassRate(Integer sampleSize, Integer exceptionCount) {
        if (sampleSize == null || sampleSize <= 0) {
            return BigDecimal.ZERO;
        }
        int safeExceptionCount = exceptionCount == null ? 0 : exceptionCount;
        int passedCount = Math.max(sampleSize - safeExceptionCount, 0);
        return BigDecimal.valueOf(passedCount)
                .divide(BigDecimal.valueOf(sampleSize), 4, RoundingMode.HALF_UP);
    }

    /**
     * 判断字符串是否有真实内容。
     *
     * <p>后台查询接口经常会收到 null、空字符串或全空格字符串。
     * 统一收口判断可以让条件拼接更稳定，也避免在多个方法里重复写判空逻辑。
     */
    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * 把编码类字段归一化为大写。
     *
     * <p>规则类型、严重级别、触发类型、状态等字段本质上是“编码值”，统一大写可以降低前端、
     * 外部系统和人工调用时大小写不一致导致查不到数据的概率。
     */
    private String normalizeUpper(String value) {
        return hasText(value) ? value.trim().toUpperCase() : value;
    }

    /**
     * 去除字符串两侧空格，如果结果为空则返回 null。
     *
     * <p>数据库里同时存在 null 和空字符串会让筛选、索引和校验逻辑变复杂。
     * 对可选结构化字段使用 null 表达“未填写”，语义更清晰。
     */
    private String trimToNull(String value) {
        if (!hasText(value)) {
            return null;
        }
        return value.trim();
    }

    /**
     * 按数据库字段长度安全截断字符串。
     *
     * <p>回调接口通常来自外部执行器，错误信息可能携带很长的堆栈、SQL 或连接器日志。
     * 当前 message 字段长度是 1000，因此服务层在入库前做一次保护，
     * 避免因为错误描述过长导致“记录失败原因”这件事本身又失败。
     */
    private String trimToMaxLength(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    /**
     * 保护分页页码。
     *
     * <p>如果调用方传入 null 或小于 1 的页码，统一回落到第一页，避免构造非法分页对象。
     */
    private long safeCurrent(Integer current) {
        return current == null || current < 1 ? 1L : current;
    }

    /**
     * 保护分页大小。
     *
     * <p>这里设置最大 200 条，是为了避免后台列表接口被误用成大批量导出接口。
     * 真正的报表导出后续应单独设计异步导出任务，避免长查询阻塞普通 API。
     */
    private long safeSize(Integer size) {
        if (size == null || size < 1) {
            return 10L;
        }
        return Math.min(size, 200);
    }

    /**
     * 保护聚合返回数量。
     *
     * <p>聚合接口通常服务于看板 TopN，例如 Top10 问题字段、Top20 异常类型。
     * 这里设置最大 100，既能覆盖大多数分析场景，也避免一次 group by 返回过多分组拖慢接口。
     */
    private int safeAggregationLimit(Integer limit) {
        if (limit == null || limit < 1) {
            return 10;
        }
        return Math.min(limit, 100);
    }
}
