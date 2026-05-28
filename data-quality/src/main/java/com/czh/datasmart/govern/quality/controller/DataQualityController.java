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
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import com.czh.datasmart.govern.quality.common.ApiResponse;
import com.czh.datasmart.govern.quality.controller.dto.CreateQualityRuleRequest;
import com.czh.datasmart.govern.quality.controller.dto.QualityRuleLifecycleRequest;
import com.czh.datasmart.govern.quality.controller.dto.QualityRuleSuggestionRequest;
import com.czh.datasmart.govern.quality.controller.dto.QualityRuleSuggestionResponse;
import com.czh.datasmart.govern.quality.controller.dto.QualityRuleTargetValidationResult;
import com.czh.datasmart.govern.quality.controller.dto.QualityScanPlan;
import com.czh.datasmart.govern.quality.controller.dto.QualityScanPlanRequest;
import com.czh.datasmart.govern.quality.controller.dto.QualityTaskScheduleRequest;
import com.czh.datasmart.govern.quality.controller.dto.QualityTaskScheduleResult;
import com.czh.datasmart.govern.quality.controller.dto.RelationalQualityScanSqlPlan;
import com.czh.datasmart.govern.quality.controller.dto.RunQualityCheckRequest;
import com.czh.datasmart.govern.quality.controller.dto.UpdateQualityRuleRequest;
import com.czh.datasmart.govern.quality.entity.QualityCheckExecution;
import com.czh.datasmart.govern.quality.entity.QualityCheckReport;
import com.czh.datasmart.govern.quality.entity.QualityRule;
import com.czh.datasmart.govern.quality.service.DataQualityService;
import com.czh.datasmart.govern.quality.service.support.QualityProjectScopeSupport;
import com.czh.datasmart.govern.quality.service.support.QualityProjectVisibility;
import com.czh.datasmart.govern.quality.service.support.QualityRuleSuggestionSupport;
import com.czh.datasmart.govern.quality.support.QualityRuleStatus;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.NoSuchElementException;

/**
 * 数据质量规则控制器。
 *
 * <p>该控制器现在只保留“质量规则本身”的资源入口：创建、列表、详情、更新、目标校验、
 * 扫描计划、任务提交、生命周期动作、手动检测以及规则级历史查询。
 * 报告与异常横向检索已经拆到 QualityReportController，执行器运维入口已经拆到
 * QualityExecutorOperationsController。</p>
 *
 * <p>这样拆分不是为了机械减少行数，而是让产品边界更清楚：
 * 规则管理关注“什么是合格”，报告查询关注“检测结果如何”，执行器入口关注“如何异步执行”。
 * 三条线后续都可以独立增强，不必持续堆回一个 Controller。</p>
 */
@RestController
@RequestMapping("/quality-rules")
@RequiredArgsConstructor
public class DataQualityController {

    private final DataQualityService dataQualityService;
    private final QualityProjectScopeSupport qualityProjectScopeSupport;
    private final QualityRuleSuggestionSupport qualityRuleSuggestionSupport;

    /**
     * 创建质量规则。
     *
     * <p>规则默认进入 DRAFT，不会立即参与生产检测。真实产品中通常还需要审批、版本发布、
     * 灰度启用和影响面评估；当前先保留清晰的规则草稿入口。</p>
     */
    @PostMapping
    public ResponseEntity<ApiResponse<QualityRule>> createRule(
            @Valid @RequestBody CreateQualityRuleRequest request,
            @RequestHeader(value = PlatformContextHeaders.DATA_SCOPE_LEVEL, required = false) String dataScopeLevel,
            @RequestHeader(value = PlatformContextHeaders.AUTHORIZED_PROJECT_IDS, required = false) String authorizedProjectIds) {
        /*
         * 创建入口也要消费 PROJECT 范围：
         * 如果当前身份只被授权 project=101，却在请求体里提交 project=999，
         * 后续列表虽然看不到，但数据已经被写入了未授权项目，这同样属于越权写入。
         */
        qualityProjectScopeSupport.resolveVisibility(
                request.getProjectId(), request.getWorkspaceId(), dataScopeLevel, authorizedProjectIds);
        QualityRule rule = dataQualityService.createRule(
                request.getTenantId(),
                request.getProjectId(),
                request.getWorkspaceId(),
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
     * 生成质量规则草案建议。
     *
     * <p>这是 data-quality 面向 Agent Runtime 的第一条“只生成草案、不直接落库”的智能化入口。
     * 它和创建规则接口有非常明确的边界：</p>
     * <p>1. 本接口不会写入 `quality_rule` 表；</p>
     * <p>2. 本接口不会启用规则，也不会提交检测任务；</p>
     * <p>3. 本接口只根据 datasource metadata、业务目标和规则引擎生成可审查的建议；</p>
     * <p>4. 如果用户或审批流程确认草案，后续才应调用创建规则接口保存为 DRAFT。</p>
     *
     * <p>为什么要校验 PROJECT 数据范围：
     * 即使只是生成草案，也可能暴露表名、字段名和治理意图。如果 Agent 为未授权项目生成建议，
     * 用户仍然可能通过建议内容推断出其他项目的数据结构。因此草案生成也必须遵守项目可见性。</p>
     */
    @PostMapping("/suggestions")
    public ResponseEntity<ApiResponse<QualityRuleSuggestionResponse>> suggestRules(
            @Valid @RequestBody QualityRuleSuggestionRequest request,
            @RequestHeader(value = PlatformContextHeaders.DATA_SCOPE_LEVEL, required = false) String dataScopeLevel,
            @RequestHeader(value = PlatformContextHeaders.AUTHORIZED_PROJECT_IDS, required = false) String authorizedProjectIds) {
        qualityProjectScopeSupport.resolveVisibility(
                request.getProjectId(), request.getWorkspaceId(), dataScopeLevel, authorizedProjectIds);
        return ResponseEntity.ok(ApiResponse.success("质量规则草案建议生成完成",
                qualityRuleSuggestionSupport.suggest(request)));
    }

    /**
     * 分页查询质量规则。
     *
     * <p>当前支持按规则类型和状态过滤，并默认排除 DELETED 规则。
     * 这里仍使用 MyBatis-Plus 分页能力；后续如果接入租户、目录、标签、负责人和权限范围，
     * 建议继续下沉到 service/query support，避免 Controller 拼复杂查询条件。</p>
     */
    @GetMapping
    public ResponseEntity<ApiResponse<IPage<QualityRule>>> listRules(
            @RequestParam(defaultValue = "1") Integer current,
            @RequestParam(defaultValue = "10") Integer size,
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) Long workspaceId,
            @RequestParam(required = false) String ruleType,
            @RequestParam(required = false) String status,
            @RequestHeader(value = PlatformContextHeaders.DATA_SCOPE_LEVEL, required = false) String dataScopeLevel,
            @RequestHeader(value = PlatformContextHeaders.AUTHORIZED_PROJECT_IDS, required = false) String authorizedProjectIds) {
        QualityProjectVisibility visibility = qualityProjectScopeSupport.resolveVisibility(
                projectId, workspaceId, dataScopeLevel, authorizedProjectIds);
        if (visibility.projectScopeEnforced() && visibility.authorizedProjectIds().isEmpty()) {
            return ResponseEntity.ok(ApiResponse.success(new Page<>(current, size)));
        }
        LambdaQueryWrapper<QualityRule> wrapper = new LambdaQueryWrapper<>();
        wrapper.ne(QualityRule::getStatus, QualityRuleStatus.DELETED);
        applyProjectVisibility(wrapper, visibility);
        if (ruleType != null && !ruleType.isBlank()) {
            wrapper.eq(QualityRule::getRuleType, ruleType.toUpperCase());
        }
        if (status != null && !status.isBlank()) {
            wrapper.eq(QualityRule::getStatus, status.toUpperCase());
        }
        wrapper.orderByDesc(QualityRule::getCreateTime);
        return ResponseEntity.ok(ApiResponse.success(dataQualityService.page(new Page<>(current, size), wrapper)));
    }

    /**
     * 查询规则详情。
     */
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<QualityRule>> getRule(
            @PathVariable Long id,
            @RequestHeader(value = PlatformContextHeaders.DATA_SCOPE_LEVEL, required = false) String dataScopeLevel,
            @RequestHeader(value = PlatformContextHeaders.AUTHORIZED_PROJECT_IDS, required = false) String authorizedProjectIds) {
        QualityRule rule = dataQualityService.getById(id);
        if (rule == null || QualityRuleStatus.DELETED.equals(rule.getStatus())) {
            throw new NoSuchElementException("质量规则不存在: " + id);
        }
        validateRuleReadable(rule, dataScopeLevel, authorizedProjectIds);
        return ResponseEntity.ok(ApiResponse.success(rule));
    }

    /**
     * 更新质量规则。
     *
     * <p>更新规则会改变后续检测语义。当前直接更新当前版本；未来商业化版本建议引入规则版本、
     * 变更审批、影响面分析和发布记录，避免生产规则被无审计修改。</p>
     */
    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<QualityRule>> updateRule(
            @PathVariable Long id,
            @Valid @RequestBody UpdateQualityRuleRequest request,
            @RequestHeader(value = PlatformContextHeaders.DATA_SCOPE_LEVEL, required = false) String dataScopeLevel,
            @RequestHeader(value = PlatformContextHeaders.AUTHORIZED_PROJECT_IDS, required = false) String authorizedProjectIds) {
        getRequiredVisibleRule(id, dataScopeLevel, authorizedProjectIds);
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
     * <p>该接口不会真正扫描业务数据，只检查规则目标是否具备最低扫描前提。
     * 例如关系型规则需要 dataSourceId、tableName、fieldName；Kafka、文件、API 等目标未来也应有专属校验策略。</p>
     */
    @PostMapping("/{id}/validate-target")
    public ResponseEntity<ApiResponse<QualityRuleTargetValidationResult>> validateTarget(
            @PathVariable Long id,
            @RequestHeader(value = PlatformContextHeaders.DATA_SCOPE_LEVEL, required = false) String dataScopeLevel,
            @RequestHeader(value = PlatformContextHeaders.AUTHORIZED_PROJECT_IDS, required = false) String authorizedProjectIds) {
        getRequiredVisibleRule(id, dataScopeLevel, authorizedProjectIds);
        return ResponseEntity.ok(ApiResponse.success("质量规则目标校验完成", dataQualityService.validateRuleTarget(id)));
    }

    /**
     * 生成质量扫描计划。
     *
     * <p>扫描计划不访问源库、不启动检测，只把规则和执行参数转换成“执行器应该怎么扫”的说明，
     * 便于用户在提交任务前审查采样、行数、超时、风险和调度可行性。</p>
     */
    @PostMapping("/{id}/scan-plan")
    public ResponseEntity<ApiResponse<QualityScanPlan>> buildScanPlan(
            @PathVariable Long id,
            @Valid @RequestBody(required = false) QualityScanPlanRequest request,
            @RequestHeader(value = PlatformContextHeaders.DATA_SCOPE_LEVEL, required = false) String dataScopeLevel,
            @RequestHeader(value = PlatformContextHeaders.AUTHORIZED_PROJECT_IDS, required = false) String authorizedProjectIds) {
        getRequiredVisibleRule(id, dataScopeLevel, authorizedProjectIds);
        return ResponseEntity.ok(ApiResponse.success("质量扫描计划生成完成", dataQualityService.buildScanPlan(id, request)));
    }

    /**
     * 生成关系型质量扫描 SQL 计划。
     *
     * <p>该接口把质量规则转换成可审查的只读 SQL 模板，但不直接执行 SQL。
     * 先暴露计划再受控执行，有利于处理源库权限、SQL 审计、超时、最大扫描行数、脱敏和租户配额。</p>
     */
    @PostMapping("/{id}/relational-sql-plan")
    public ResponseEntity<ApiResponse<RelationalQualityScanSqlPlan>> buildRelationalSqlPlan(
            @PathVariable Long id,
            @Valid @RequestBody(required = false) QualityScanPlanRequest request,
            @RequestHeader(value = PlatformContextHeaders.DATA_SCOPE_LEVEL, required = false) String dataScopeLevel,
            @RequestHeader(value = PlatformContextHeaders.AUTHORIZED_PROJECT_IDS, required = false) String authorizedProjectIds) {
        getRequiredVisibleRule(id, dataScopeLevel, authorizedProjectIds);
        return ResponseEntity.ok(ApiResponse.success("关系型质量扫描 SQL 计划生成完成",
                dataQualityService.buildRelationalSqlPlan(id, request)));
    }

    /**
     * 提交质量检测任务。
     *
     * <p>该接口把“已校验规则 + 扫描计划”提交到 task-management，让质量检测进入统一任务队列，
     * 避免在 HTTP 请求线程里同步扫描真实数据源。</p>
     */
    @PostMapping("/{id}/schedule-task")
    public ResponseEntity<ApiResponse<QualityTaskScheduleResult>> scheduleQualityTask(
            @PathVariable Long id,
            @Valid @RequestBody(required = false) QualityTaskScheduleRequest request,
            @RequestHeader(value = PlatformContextHeaders.DATA_SCOPE_LEVEL, required = false) String dataScopeLevel,
            @RequestHeader(value = PlatformContextHeaders.AUTHORIZED_PROJECT_IDS, required = false) String authorizedProjectIds) {
        getRequiredVisibleRule(id, dataScopeLevel, authorizedProjectIds);
        return ResponseEntity.ok(ApiResponse.success("质量检测任务提交处理完成",
                dataQualityService.scheduleQualityCheckTask(id, request)));
    }

    @PostMapping("/{id}/enable")
    public ResponseEntity<ApiResponse<QualityRule>> enableRule(
            @PathVariable Long id,
            @Valid @RequestBody(required = false) QualityRuleLifecycleRequest request,
            @RequestHeader(value = PlatformContextHeaders.DATA_SCOPE_LEVEL, required = false) String dataScopeLevel,
            @RequestHeader(value = PlatformContextHeaders.AUTHORIZED_PROJECT_IDS, required = false) String authorizedProjectIds) {
        getRequiredVisibleRule(id, dataScopeLevel, authorizedProjectIds);
        return ResponseEntity.ok(ApiResponse.success("质量规则已启用",
                dataQualityService.enableRule(id, request == null ? null : request.getReason())));
    }

    @PostMapping("/{id}/disable")
    public ResponseEntity<ApiResponse<QualityRule>> disableRule(
            @PathVariable Long id,
            @Valid @RequestBody(required = false) QualityRuleLifecycleRequest request,
            @RequestHeader(value = PlatformContextHeaders.DATA_SCOPE_LEVEL, required = false) String dataScopeLevel,
            @RequestHeader(value = PlatformContextHeaders.AUTHORIZED_PROJECT_IDS, required = false) String authorizedProjectIds) {
        getRequiredVisibleRule(id, dataScopeLevel, authorizedProjectIds);
        return ResponseEntity.ok(ApiResponse.success("质量规则已停用",
                dataQualityService.disableRule(id, request == null ? null : request.getReason())));
    }

    @PostMapping("/{id}/archive")
    public ResponseEntity<ApiResponse<QualityRule>> archiveRule(
            @PathVariable Long id,
            @Valid @RequestBody(required = false) QualityRuleLifecycleRequest request,
            @RequestHeader(value = PlatformContextHeaders.DATA_SCOPE_LEVEL, required = false) String dataScopeLevel,
            @RequestHeader(value = PlatformContextHeaders.AUTHORIZED_PROJECT_IDS, required = false) String authorizedProjectIds) {
        getRequiredVisibleRule(id, dataScopeLevel, authorizedProjectIds);
        return ResponseEntity.ok(ApiResponse.success("质量规则已归档",
                dataQualityService.archiveRule(id, request == null ? null : request.getReason())));
    }

    @PostMapping("/{id}/restore")
    public ResponseEntity<ApiResponse<QualityRule>> restoreRule(
            @PathVariable Long id,
            @Valid @RequestBody(required = false) QualityRuleLifecycleRequest request,
            @RequestHeader(value = PlatformContextHeaders.DATA_SCOPE_LEVEL, required = false) String dataScopeLevel,
            @RequestHeader(value = PlatformContextHeaders.AUTHORIZED_PROJECT_IDS, required = false) String authorizedProjectIds) {
        getRequiredVisibleRule(id, dataScopeLevel, authorizedProjectIds);
        return ResponseEntity.ok(ApiResponse.success("质量规则已恢复为停用状态",
                dataQualityService.restoreRule(id, request == null ? null : request.getReason())));
    }

    /**
     * 逻辑删除规则。
     *
     * <p>当前删除不会物理移除数据，而是把状态改为 DELETED，保留审计和历史报告关联的可能性。</p>
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<QualityRule>> deleteRule(
            @PathVariable Long id,
            @RequestHeader(value = PlatformContextHeaders.DATA_SCOPE_LEVEL, required = false) String dataScopeLevel,
            @RequestHeader(value = PlatformContextHeaders.AUTHORIZED_PROJECT_IDS, required = false) String authorizedProjectIds) {
        getRequiredVisibleRule(id, dataScopeLevel, authorizedProjectIds);
        return ResponseEntity.ok(ApiResponse.success("质量规则已删除", dataQualityService.deleteRule(id)));
    }

    /**
     * 执行一次质量检测。
     *
     * <p>这是同步检测入口，适合学习、联调和小规模人工检测。
     * 商业化大数据量扫描应优先走 schedule-task + 执行器异步闭环，避免阻塞接口线程。</p>
     */
    @PostMapping("/{id}/run-check")
    public ResponseEntity<ApiResponse<QualityCheckReport>> runQualityCheck(
            @PathVariable Long id,
            @Valid @RequestBody RunQualityCheckRequest request,
            @RequestHeader(value = PlatformContextHeaders.DATA_SCOPE_LEVEL, required = false) String dataScopeLevel,
            @RequestHeader(value = PlatformContextHeaders.AUTHORIZED_PROJECT_IDS, required = false) String authorizedProjectIds) {
        getRequiredVisibleRule(id, dataScopeLevel, authorizedProjectIds);
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
     *
     * <p>这是规则详情页的纵向查询；横向报告检索已经拆到 QualityReportController。</p>
     */
    @GetMapping("/{id}/reports")
    public ResponseEntity<ApiResponse<List<QualityCheckReport>>> listReports(
            @PathVariable Long id,
            @RequestHeader(value = PlatformContextHeaders.DATA_SCOPE_LEVEL, required = false) String dataScopeLevel,
            @RequestHeader(value = PlatformContextHeaders.AUTHORIZED_PROJECT_IDS, required = false) String authorizedProjectIds) {
        getRequiredVisibleRule(id, dataScopeLevel, authorizedProjectIds);
        return ResponseEntity.ok(ApiResponse.success(dataQualityService.listReportsByRuleId(id)));
    }

    /**
     * 查询规则检测执行记录。
     *
     * <p>报告回答“质量是否通过”，execution 回答“检测动作是否跑完、跑了多久、生成哪个报告”。</p>
     */
    @GetMapping("/{id}/executions")
    public ResponseEntity<ApiResponse<List<QualityCheckExecution>>> listExecutions(
            @PathVariable Long id,
            @RequestHeader(value = PlatformContextHeaders.DATA_SCOPE_LEVEL, required = false) String dataScopeLevel,
            @RequestHeader(value = PlatformContextHeaders.AUTHORIZED_PROJECT_IDS, required = false) String authorizedProjectIds) {
        getRequiredVisibleRule(id, dataScopeLevel, authorizedProjectIds);
        return ResponseEntity.ok(ApiResponse.success(dataQualityService.listExecutionsByRuleId(id)));
    }

    /**
     * 将项目/工作空间可见范围追加到规则列表查询。
     *
     * <p>列表查询是最容易被忽视的权限入口。这里同时处理用户显式筛选的 projectId/workspaceId，
     * 以及 gateway 计算出的 PROJECT 授权集合，确保“主动筛选”和“被动权限边界”都能落到 SQL 条件。</p>
     */
    private void applyProjectVisibility(LambdaQueryWrapper<QualityRule> wrapper,
                                        QualityProjectVisibility visibility) {
        wrapper.eq(visibility.requestedProjectId() != null, QualityRule::getProjectId,
                        visibility.requestedProjectId())
                .eq(visibility.requestedWorkspaceId() != null, QualityRule::getWorkspaceId,
                        visibility.requestedWorkspaceId());
        if (visibility.projectScopeEnforced()) {
            wrapper.in(QualityRule::getProjectId, visibility.authorizedProjectIds());
        }
    }

    /**
     * 读取规则并校验 PROJECT 范围。
     *
     * <p>详情、更新、启停、删除、运行检查等接口都只接收规则 ID。
     * 如果不在读取规则后校验 projectId，用户就可能通过猜测 ID 越权操作其他项目的质量规则。</p>
     */
    private QualityRule getRequiredVisibleRule(Long id, String dataScopeLevel, String authorizedProjectIds) {
        QualityRule rule = dataQualityService.getById(id);
        if (rule == null || QualityRuleStatus.DELETED.equals(rule.getStatus())) {
            throw new NoSuchElementException("质量规则不存在: " + id);
        }
        validateRuleReadable(rule, dataScopeLevel, authorizedProjectIds);
        return rule;
    }

    /**
     * 基于规则自身 projectId 执行详情类访问校验。
     */
    private void validateRuleReadable(QualityRule rule, String dataScopeLevel, String authorizedProjectIds) {
        QualityProjectVisibility visibility = qualityProjectScopeSupport.resolveVisibility(
                null, null, dataScopeLevel, authorizedProjectIds);
        qualityProjectScopeSupport.validateProjectReadable(rule.getProjectId(), visibility, "质量规则");
    }
}
