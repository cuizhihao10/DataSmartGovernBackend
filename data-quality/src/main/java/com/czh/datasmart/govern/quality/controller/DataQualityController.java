/**
 * @Author : Cui
 * @Date: 2026/4/18 21:40
 * @Description DataSmart Govern Backend - DataQualityController.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.quality.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.czh.datasmart.govern.quality.common.ApiResponse;
import com.czh.datasmart.govern.quality.controller.dto.QualityAnomalyAggregationItem;
import com.czh.datasmart.govern.quality.controller.dto.CreateQualityRuleRequest;
import com.czh.datasmart.govern.quality.controller.dto.QualityExecutionFailRequest;
import com.czh.datasmart.govern.quality.controller.dto.QualityExecutionStartRequest;
import com.czh.datasmart.govern.quality.controller.dto.QualityExecutionSuccessRequest;
import com.czh.datasmart.govern.quality.controller.dto.QualityExecutorRunResult;
import com.czh.datasmart.govern.quality.controller.dto.QualityRuleLifecycleRequest;
import com.czh.datasmart.govern.quality.controller.dto.QualityRuleTargetValidationResult;
import com.czh.datasmart.govern.quality.controller.dto.QualityScanPlan;
import com.czh.datasmart.govern.quality.controller.dto.QualityScanPlanRequest;
import com.czh.datasmart.govern.quality.controller.dto.QualityTaskScheduleRequest;
import com.czh.datasmart.govern.quality.controller.dto.QualityTaskScheduleResult;
import com.czh.datasmart.govern.quality.controller.dto.RelationalQualityScanSqlPlan;
import com.czh.datasmart.govern.quality.controller.dto.RunQualityCheckRequest;
import com.czh.datasmart.govern.quality.controller.dto.UpdateQualityRuleRequest;
import com.czh.datasmart.govern.quality.entity.QualityAnomalyDetail;
import com.czh.datasmart.govern.quality.entity.QualityCheckExecution;
import com.czh.datasmart.govern.quality.entity.QualityCheckReport;
import com.czh.datasmart.govern.quality.entity.QualityRule;
import com.czh.datasmart.govern.quality.executor.QualityTaskExecutorCoordinator;
import com.czh.datasmart.govern.quality.service.DataQualityService;
import com.czh.datasmart.govern.quality.support.QualityRuleStatus;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * 数据质量控制器。
 * 当前模块对外暴露两组紧密关联的能力：
 * 1. 质量规则管理。
 * 2. 规则执行与检测报告查询。
 *
 * 这两组能力合在一起，形成了最小但完整的治理闭环：
 * 先定义什么叫合格，再执行判断，最后把判断过程沉淀成报告。
 */
@RestController
@RequestMapping("/quality-rules")
@RequiredArgsConstructor
public class DataQualityController {

    /**
     * 控制器只依赖服务接口，保持接口层与业务层边界清晰。
     */
    private final DataQualityService dataQualityService;

    /**
     * 质量任务执行器 coordinator。
     *
     * <p>它不是普通用户功能，而是面向本地联调、运维验证和未来后台执行器的受控入口。
     * 当前默认关闭，需要显式开启配置后才能认领 task-management 中的质量任务。
     */
    private final QualityTaskExecutorCoordinator qualityTaskExecutorCoordinator;

    /**
     * 创建质量规则。
     */
    @PostMapping
    public ResponseEntity<ApiResponse<QualityRule>> createRule(@Valid @RequestBody CreateQualityRuleRequest request) {
        QualityRule rule = dataQualityService.createRule(
                request.getName(),
                request.getRuleType(),
                request.getTargetObject(),
                request.getTargetType(),
                request.getDataSourceId(),
                request.getDatabaseName(),
                request.getSchemaName(),
                request.getTableName(),
                request.getFieldName(),
                request.getComparisonOperator(),
                request.getExpectedValue(),
                request.getSeverity(),
                request.getDescription()
        );
        return ResponseEntity.ok(ApiResponse.success("质量规则创建成功", rule));
    }

    /**
     * 分页查询质量规则。
     * 当前支持按规则类型和状态做过滤，并默认排除已逻辑删除规则。
     */
    @GetMapping
    public ResponseEntity<ApiResponse<IPage<QualityRule>>> listRules(
            @RequestParam(defaultValue = "1") Integer current,
            @RequestParam(defaultValue = "10") Integer size,
            @RequestParam(required = false) String ruleType,
            @RequestParam(required = false) String status) {
        LambdaQueryWrapper<QualityRule> wrapper = new LambdaQueryWrapper<>();
        wrapper.ne(QualityRule::getStatus, QualityRuleStatus.DELETED);
        if (ruleType != null && !ruleType.isBlank()) {
            wrapper.eq(QualityRule::getRuleType, ruleType.toUpperCase());
        }
        if (status != null && !status.isBlank()) {
            wrapper.eq(QualityRule::getStatus, status.toUpperCase());
        }
        wrapper.orderByDesc(QualityRule::getCreateTime);
        return ResponseEntity.ok(ApiResponse.success(
                dataQualityService.page(new Page<>(current, size), wrapper)));
    }

    /**
     * 分页查询质量检测报告。
     *
     * <p>这个接口是面向“质量运营后台”的横向检索入口，不要求用户先进入某条规则详情。
     * 在真实项目里，质量管理员通常会从以下问题出发：
     * 1. 最近一小时有哪些失败报告；
     * 2. 哪些 CRITICAL 级别规则正在频繁失败；
     * 3. 某个表、字段或业务对象近期质量趋势如何；
     * 4. 人工触发、定时触发、任务触发的检测结果是否存在明显差异。
     *
     * <p>日期参数使用 ISO 格式，例如 2026-04-27T00:00:00。
     * failedOnly=true 时会优先筛选 FAILED 报告，适合告警台和异常列表快捷入口。
     */
    @GetMapping("/reports")
    public ResponseEntity<ApiResponse<IPage<QualityCheckReport>>> pageReports(
            @RequestParam(defaultValue = "1") Integer current,
            @RequestParam(defaultValue = "10") Integer size,
            @RequestParam(required = false) Long ruleId,
            @RequestParam(required = false) String ruleType,
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) String checkStatus,
            @RequestParam(required = false) String targetObject,
            @RequestParam(required = false) String triggerType,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime,
            @RequestParam(required = false) Boolean failedOnly) {
        return ResponseEntity.ok(ApiResponse.success(dataQualityService.pageReports(
                current,
                size,
                ruleId,
                ruleType,
                severity,
                checkStatus,
                targetObject,
                triggerType,
                startTime,
                endTime,
                failedOnly
        )));
    }

    /**
     * 查询某份质量报告下的异常明细。
     *
     * <p>报告摘要告诉我们“失败了多少”，异常明细告诉我们“哪些样本失败、为什么失败、建议如何处理”。
     * 这个接口未来可以直接服务于前端报告详情页、清洗任务创建页、人工复核页和 AI 复盘上下文。
     */
    @GetMapping("/reports/{reportId}/anomalies")
    public ResponseEntity<ApiResponse<List<QualityAnomalyDetail>>> listAnomalies(@PathVariable Long reportId) {
        return ResponseEntity.ok(ApiResponse.success(dataQualityService.listAnomaliesByReportId(reportId)));
    }

    /**
     * 分页查询质量异常明细。
     *
     * <p>这个接口是报告详情异常列表的升级版，不再局限于单份报告。
     * 它可以支撑质量运营台、问题排查页、人工复核页和清洗任务创建页按多维条件定位异常样本。
     *
     * <p>典型用法：
     * 1. reportId：查看某份报告下的异常样本；
     * 2. ruleId：查看某条规则近期产生的全部异常；
     * 3. anomalyType：筛选空值、重复值、越界值等问题类型；
     * 4. fieldName：定位某个字段的问题样本；
     * 5. severity + 时间范围：筛选需要优先处理的高风险异常。
     */
    @GetMapping("/anomalies")
    public ResponseEntity<ApiResponse<IPage<QualityAnomalyDetail>>> pageAnomalies(
            @RequestParam(defaultValue = "1") Integer current,
            @RequestParam(defaultValue = "10") Integer size,
            @RequestParam(required = false) Long reportId,
            @RequestParam(required = false) Long ruleId,
            @RequestParam(required = false) String anomalyType,
            @RequestParam(required = false) String fieldName,
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) String targetObject,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime) {
        return ResponseEntity.ok(ApiResponse.success(dataQualityService.pageAnomalies(
                current,
                size,
                reportId,
                ruleId,
                anomalyType,
                fieldName,
                severity,
                targetObject,
                startTime,
                endTime
        )));
    }

    /**
     * 聚合统计质量异常。
     *
     * <p>分页明细解决“看具体样本”，聚合接口解决“先看主要问题在哪里”。
     * groupBy 支持 FIELD、TYPE、SEVERITY、TARGET_OBJECT，默认 FIELD。
     * 例如质量负责人可以先按 FIELD 找到异常最多的字段，再用 /anomalies 接口按字段下钻。
     *
     * <p>limit 默认 10，最大 100。这个限制是为了避免聚合接口被误用成全量分析任务。
     * 如果后续要做复杂质量趋势分析，应设计专门的离线统计表或异步分析任务。
     */
    @GetMapping("/anomalies/aggregation")
    public ResponseEntity<ApiResponse<List<QualityAnomalyAggregationItem>>> aggregateAnomalies(
            @RequestParam(required = false) Long reportId,
            @RequestParam(required = false) Long ruleId,
            @RequestParam(required = false) String anomalyType,
            @RequestParam(required = false) String fieldName,
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) String targetObject,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime,
            @RequestParam(required = false) String groupBy,
            @RequestParam(required = false) Integer limit) {
        return ResponseEntity.ok(ApiResponse.success(dataQualityService.aggregateAnomalies(
                reportId,
                ruleId,
                anomalyType,
                fieldName,
                severity,
                targetObject,
                startTime,
                endTime,
                groupBy,
                limit
        )));
    }

    /**
     * 质量执行器开始执行回调。
     *
     * <p>该接口不是普通用户在前端手动点击的“执行检测”，而是面向未来质量执行器的系统级回调入口。
     * 推荐调用顺序如下：
     * 1. task-management 中的执行器先认领 `DATA_QUALITY_SCAN` 任务；
     * 2. 执行器读取任务 params 中的 ruleId 和 scanPlan；
     * 3. 执行器调用本接口创建 data-quality 自己的 RUNNING execution；
     * 4. 执行器扫描真实数据源；
     * 5. 执行器根据扫描结果调用 succeed 或 fail 回调。
     *
     * <p>这样设计可以避免 data-quality 与 task-management 互相直接修改对方表结构，
     * 两个微服务只通过明确 API 合同交换状态，后续替换执行器实现、增加 Python AI 扫描器或多语言 worker 都更安全。
     */
    @PostMapping("/executor/executions/start")
    public ResponseEntity<ApiResponse<QualityCheckExecution>> startTaskExecution(
            @Valid @RequestBody QualityExecutionStartRequest request) {
        return ResponseEntity.ok(ApiResponse.success("质量检测任务执行记录已开始",
                dataQualityService.startTaskExecution(request)));
    }

    /**
     * 质量执行器成功完成回调。
     *
     * <p>该接口会把一个 RUNNING execution 收口为 SUCCESS，并生成 quality_check_report 和异常明细。
     * 注意这里的 SUCCESS 表示扫描动作成功完成，而不是质量结果一定通过；
     * 真实质量结果由 report.checkStatus 表示，可能是 PASSED，也可能是 FAILED。
     *
     * <p>产品上这能支撑两个不同视角：
     * 1. 运维视角：执行器有没有稳定跑完、耗时多少、有没有失败；
     * 2. 业务视角：数据质量是否达标、异常样本在哪里、是否需要清洗或告警。
     */
    @PostMapping("/executor/executions/{executionId}/succeed")
    public ResponseEntity<ApiResponse<QualityCheckReport>> completeTaskExecution(
            @PathVariable Long executionId,
            @Valid @RequestBody QualityExecutionSuccessRequest request) {
        return ResponseEntity.ok(ApiResponse.success("质量检测任务执行成功并生成报告",
                dataQualityService.completeTaskExecution(executionId, request)));
    }

    /**
     * 质量执行器失败回调。
     *
     * <p>该接口用于记录扫描动作没有成功完成的场景，例如数据源连接失败、执行超时、SQL 被安全策略拒绝、
     * 执行器进程异常、任务被取消等。它只更新 execution 为 FAILED，不生成 report。
     *
     * <p>不生成 report 是一个刻意设计：
     * 如果技术执行失败也生成“质量失败报告”，质量大盘就会把平台故障误认为业务数据质量差，
     * 进而影响规则评分、治理优先级和告警判断。
     */
    @PostMapping("/executor/executions/{executionId}/fail")
    public ResponseEntity<ApiResponse<QualityCheckExecution>> failTaskExecution(
            @PathVariable Long executionId,
            @Valid @RequestBody QualityExecutionFailRequest request) {
        return ResponseEntity.ok(ApiResponse.success("质量检测任务执行失败已记录",
                dataQualityService.failTaskExecution(executionId, request)));
    }

    /**
     * 手动触发一次质量执行器 coordinator。
     *
     * <p>这是一个受控的执行器联调入口，不是面向普通业务用户的质量检测按钮。
     * 它每次最多认领一条 `DATA_QUALITY_SCAN` 任务，并执行如下流程：
     * 1. 调用 task-management claim 接口认领任务；
     * 2. 解析并校验 `QUALITY_SCAN_TASK_V1` payload；
     * 3. 调用 data-quality start 创建质量 execution；
     * 4. 向 task-management 上报心跳；
     * 5. 对当前已支持的关系型规则执行受控只读扫描，并回写质量报告与任务终态。
     *
     * <p>为什么仍然需要受控入口：
     * 当前只支持一部分关系型质量规则，未支持的规则会明确失败，避免污染质量大盘和审计证据。
     * 手动入口适合本地联调、故障复盘和验证后台 scheduler 的单条执行行为。
     */
    @PostMapping("/executor/coordinator/run-once")
    public ResponseEntity<ApiResponse<QualityExecutorRunResult>> runExecutorOnce() {
        return ResponseEntity.ok(ApiResponse.success("质量执行器 coordinator 单次运行完成",
                qualityTaskExecutorCoordinator.runOnce()));
    }

    /**
     * 手动触发一小批质量执行器 coordinator。
     *
     * <p>这个接口用于模拟后台 scheduler 的单轮行为：最多认领并处理 maxRuns 条任务。
     * 它仍然不是普通业务用户入口，而是面向运维、联调和压测前验证的受控入口。
     *
     * <p>为什么需要 run-batch：
     * - run-once 便于观察单条任务；
     * - scheduler 会按固定间隔自动触发一小批任务；
     * - run-batch 让我们可以在不开启后台自动调度的情况下，手动验证“小批量消费”是否符合预期。
     *
     * <p>maxRuns 会在 coordinator 内部压到安全范围，当前最大 20。
     */
    @PostMapping("/executor/coordinator/run-batch")
    public ResponseEntity<ApiResponse<List<QualityExecutorRunResult>>> runExecutorBatch(
            @RequestParam(defaultValue = "1") Integer maxRuns) {
        return ResponseEntity.ok(ApiResponse.success("质量执行器 coordinator 批量运行完成",
                qualityTaskExecutorCoordinator.runBatch(maxRuns)));
    }

    /**
     * 查询规则详情。
     */
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<QualityRule>> getRule(@PathVariable Long id) {
        QualityRule rule = dataQualityService.getById(id);
        if (rule == null || QualityRuleStatus.DELETED.equals(rule.getStatus())) {
            throw new NoSuchElementException("质量规则不存在: " + id);
        }
        return ResponseEntity.ok(ApiResponse.success(rule));
    }

    /**
     * 更新质量规则。
     */
    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<QualityRule>> updateRule(
            @PathVariable Long id,
            @Valid @RequestBody UpdateQualityRuleRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                "质量规则更新成功",
                dataQualityService.updateRule(
                        id,
                        request.getName(),
                        request.getTargetObject(),
                        request.getTargetType(),
                        request.getDataSourceId(),
                        request.getDatabaseName(),
                        request.getSchemaName(),
                        request.getTableName(),
                        request.getFieldName(),
                        request.getComparisonOperator(),
                        request.getExpectedValue(),
                        request.getSeverity(),
                        request.getDescription()
                )));
    }

    /**
     * 校验规则检测目标。
     *
     * <p>该接口用于在规则启用前检查目标是否具备最低扫描前提。
     * 它不会真正扫描业务数据，而是根据 targetType 调用对应策略做结构性校验：
     * 1. 关系型表/字段规则检查 dataSourceId、tableName、fieldName；
     * 2. Kafka 规则检查 topic；
     * 3. 文件规则检查文件或对象路径；
     * 4. API 规则检查接口地址或接口编码。
     *
     * <p>校验结果会写回规则表，前端可以直接在规则列表展示最近校验状态和失败原因。
     */
    @PostMapping("/{id}/validate-target")
    public ResponseEntity<ApiResponse<QualityRuleTargetValidationResult>> validateTarget(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success("质量规则目标校验完成", dataQualityService.validateRuleTarget(id)));
    }

    /**
     * 生成质量扫描计划。
     *
     * <p>这个接口不会真正访问源库，也不会启动检测任务。
     * 它用于把规则和执行参数转换成“执行器未来应该怎么扫”的计划说明，
     * 让用户在启用调度前就能看到扫描模式、采样限制、最大扫描行数、超时时间、风险提示和调度可行性。
     *
     * <p>后续接入 task-management 后，可以把返回的计划作为任务 payload，
     * 由质量执行器领取任务、按计划扫描、写入执行记录、报告和异常明细。
     */
    @PostMapping("/{id}/scan-plan")
    public ResponseEntity<ApiResponse<QualityScanPlan>> buildScanPlan(
            @PathVariable Long id,
            @Valid @RequestBody(required = false) QualityScanPlanRequest request) {
        return ResponseEntity.ok(ApiResponse.success("质量扫描计划生成完成", dataQualityService.buildScanPlan(id, request)));
    }

    /**
     * 生成关系型质量扫描 SQL 计划。
     *
     * <p>这个接口用于把关系型质量规则转换成可审查的只读 SQL 模板。
     * 它不会直接访问 MySQL/PostgreSQL，也不会执行 SQL，因此不会对源库产生压力。
     *
     * <p>当前主要支持两类字段级规则：
     * 1. COMPLETENESS：生成空值率/非空率检测 SQL；
     * 2. UNIQUENESS：生成重复值/唯一率检测 SQL。
     *
     * <p>为什么要先暴露 SQL 计划而不是直接执行：
     * 真实商业产品里，源库扫描必须考虑只读账号、权限范围、SQL 审计、超时时间、最大扫描行数、
     * 异常样本脱敏、源库负载保护和租户配额。先让 SQL 可见、可审查、可解释，后续再接受控执行器更稳。
     */
    @PostMapping("/{id}/relational-sql-plan")
    public ResponseEntity<ApiResponse<RelationalQualityScanSqlPlan>> buildRelationalSqlPlan(
            @PathVariable Long id,
            @Valid @RequestBody(required = false) QualityScanPlanRequest request) {
        return ResponseEntity.ok(ApiResponse.success("关系型质量扫描 SQL 计划生成完成",
                dataQualityService.buildRelationalSqlPlan(id, request)));
    }

    /**
     * 提交质量检测任务。
     *
     * <p>该接口把“已校验规则 + 扫描计划”提交到 task-management，
     * 让质量检测进入统一任务队列，而不是在 data-quality 接口线程里同步执行。
     *
     * <p>这一步对商业化产品很重要：
     * 1. 长时间扫描不会阻塞用户请求；
     * 2. 任务可以被执行器认领和心跳续租；
     * 3. 超时、失败、重试、取消可以由任务中心统一管理；
     * 4. 后续质量执行器可以把结果回写为 execution、report 和 anomaly detail。
     */
    @PostMapping("/{id}/schedule-task")
    public ResponseEntity<ApiResponse<QualityTaskScheduleResult>> scheduleQualityTask(
            @PathVariable Long id,
            @Valid @RequestBody(required = false) QualityTaskScheduleRequest request) {
        return ResponseEntity.ok(ApiResponse.success("质量检测任务提交处理完成",
                dataQualityService.scheduleQualityCheckTask(id, request)));
    }

    /**
     * 启用规则。
     *
     * <p>新建规则默认是 DRAFT，不会立即参与生产检测。
     * 启用动作代表管理员确认规则已经准备好进入执行流程。
     */
    @PostMapping("/{id}/enable")
    public ResponseEntity<ApiResponse<QualityRule>> enableRule(
            @PathVariable Long id,
            @Valid @RequestBody(required = false) QualityRuleLifecycleRequest request) {
        return ResponseEntity.ok(ApiResponse.success("质量规则已启用",
                dataQualityService.enableRule(id, request == null ? null : request.getReason())));
    }

    /**
     * 停用规则。
     *
     * <p>停用用于临时下线规则，例如规则误报、数据源维护、阈值需要调整。
     * 停用后历史报告仍保留，后续可再次启用。
     */
    @PostMapping("/{id}/disable")
    public ResponseEntity<ApiResponse<QualityRule>> disableRule(
            @PathVariable Long id,
            @Valid @RequestBody(required = false) QualityRuleLifecycleRequest request) {
        return ResponseEntity.ok(ApiResponse.success("质量规则已停用",
                dataQualityService.disableRule(id, request == null ? null : request.getReason())));
    }

    /**
     * 归档规则。
     *
     * <p>归档表示规则退出当前治理流程，但不是删除。
     * 归档后的规则不能执行检测，历史报告仍可作为审计和复盘证据。
     */
    @PostMapping("/{id}/archive")
    public ResponseEntity<ApiResponse<QualityRule>> archiveRule(
            @PathVariable Long id,
            @Valid @RequestBody(required = false) QualityRuleLifecycleRequest request) {
        return ResponseEntity.ok(ApiResponse.success("质量规则已归档",
                dataQualityService.archiveRule(id, request == null ? null : request.getReason())));
    }

    /**
     * 恢复归档规则。
     *
     * <p>恢复后进入 INACTIVE，而不是直接 ACTIVE。
     * 这样能避免长期下线的规则在未复核情况下直接重新影响质量检测结果。
     */
    @PostMapping("/{id}/restore")
    public ResponseEntity<ApiResponse<QualityRule>> restoreRule(
            @PathVariable Long id,
            @Valid @RequestBody(required = false) QualityRuleLifecycleRequest request) {
        return ResponseEntity.ok(ApiResponse.success("质量规则已恢复为停用状态",
                dataQualityService.restoreRule(id, request == null ? null : request.getReason())));
    }

    /**
     * 逻辑删除规则。
     * 当前删除并不物理移除数据，而是把状态改为 DELETED，便于后续保留审计痕迹。
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<QualityRule>> deleteRule(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success("质量规则已删除", dataQualityService.deleteRule(id)));
    }

    /**
     * 执行一次质量检测。
     * 该接口会依据规则定义和外部传入观测值生成一次报告。
     */
    @PostMapping("/{id}/run-check")
    public ResponseEntity<ApiResponse<QualityCheckReport>> runQualityCheck(
            @PathVariable Long id,
            @Valid @RequestBody RunQualityCheckRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                "质量检测执行完成",
                dataQualityService.runQualityCheck(
                        id,
                        request.getMeasuredValue(),
                        request.getSampleSize(),
                        request.getExceptionCount(),
                        request.getNotes(),
                        request.getAnomalies()
                )));
    }

    /**
     * 查询某条规则下的历史报告。
     * 详情接口看规则定义，报告接口看历史执行结果，二者结合才能完整理解一条规则。
     */
    @GetMapping("/{id}/reports")
    public ResponseEntity<ApiResponse<List<QualityCheckReport>>> listReports(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(dataQualityService.listReportsByRuleId(id)));
    }

    /**
     * 查询规则检测执行记录。
     *
     * <p>报告回答“本次检测通过还是失败”，执行记录回答“本次检测动作是否成功跑完、跑了多久、生成哪个报告”。
     * 后续质量任务接入调度器后，这个接口会成为质量执行历史页的重要数据来源。
     */
    @GetMapping("/{id}/executions")
    public ResponseEntity<ApiResponse<List<QualityCheckExecution>>> listExecutions(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(dataQualityService.listExecutionsByRuleId(id)));
    }
}
