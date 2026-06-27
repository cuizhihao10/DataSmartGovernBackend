/**
 * @Author : Cui
 * @Date: 2026/06/28 15:12
 * @Description DataSmart Govern Backend - QualityRemediationTaskService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.quality.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.czh.datasmart.govern.quality.config.TaskManagementIntegrationProperties;
import com.czh.datasmart.govern.quality.controller.dto.QualityAnomalyAggregationItem;
import com.czh.datasmart.govern.quality.controller.dto.QualityRemediationTaskRequest;
import com.czh.datasmart.govern.quality.controller.dto.QualityRemediationTaskResponse;
import com.czh.datasmart.govern.quality.entity.QualityAnomalyDetail;
import com.czh.datasmart.govern.quality.entity.QualityCheckReport;
import com.czh.datasmart.govern.quality.integration.task.QualityRemediationTaskPayload;
import com.czh.datasmart.govern.quality.integration.task.TaskCreateRequest;
import com.czh.datasmart.govern.quality.integration.task.TaskCreateResponse;
import com.czh.datasmart.govern.quality.integration.task.TaskManagementClient;
import com.czh.datasmart.govern.quality.mapper.QualityAnomalyDetailMapper;
import com.czh.datasmart.govern.quality.mapper.QualityCheckReportMapper;
import com.czh.datasmart.govern.quality.service.support.QualityProjectScopeSupport;
import com.czh.datasmart.govern.quality.service.support.QualityProjectVisibility;
import com.czh.datasmart.govern.quality.support.QualityAnomalyAggregationDimension;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

import static com.czh.datasmart.govern.quality.service.support.QualityRemediationTaskTextSanitizer.PAYLOAD_POLICY;
import static com.czh.datasmart.govern.quality.service.support.QualityRemediationTaskTextSanitizer.firstText;
import static com.czh.datasmart.govern.quality.service.support.QualityRemediationTaskTextSanitizer.normalizeCode;
import static com.czh.datasmart.govern.quality.service.support.QualityRemediationTaskTextSanitizer.safeText;
import static com.czh.datasmart.govern.quality.service.support.QualityRemediationTaskTextSanitizer.truncate;

/**
 * 质量异常治理任务服务。
 *
 * <p>该服务把 data-quality 已经沉淀的“报告/异常事实”转成 task-management 可调度的治理任务。
 * 它刻意独立于 {@code DataQualityServiceImpl} 和 {@code QualityExecutionReportSupport}，原因有三点：</p>
 *
 * <p>1. 职责不同：报告查询回答“看见什么异常”，治理任务回答“下一步派给谁处理”；</p>
 * <p>2. 风险不同：创建任务是写操作，需要更明确的权限、低敏 payload 和失败策略；</p>
 * <p>3. 演进不同：后续可能加入审批、批量派单、Agent 草案、清洗执行器和 SLA，而不应继续把报告查询类撑大。</p>
 *
 * <p>安全边界是本类最重要的设计点：任务 payload 只允许包含筛选条件、数量、TOP 聚合和治理建议。
 * 它不会读取或输出 {@code recordIdentifier}、{@code observedValue}、{@code samplePayload}，
 * 也不会把 SQL、prompt、模型输出、工具参数、凭据或内部 endpoint 写入 task-management。</p>
 */
@Service
@RequiredArgsConstructor
public class QualityRemediationTaskService {

    /**
     * 当前支持的治理类型。
     *
     * <p>不使用任意字符串直接透传，是为了防止任务中心出现大量语义不一致的类型值。
     * 后续如果新增“自动清洗预案”“源系统缺陷单”“规则调优评审”等类型，应在这里显式扩展。</p>
     */
    private static final Set<String> SUPPORTED_REMEDIATION_TYPES = Set.of(
            "MANUAL_REVIEW",
            "CLEANING_PLAN",
            "SOURCE_SYSTEM_FIX",
            "RULE_TUNING"
    );

    /**
     * 当前支持的优先级。
     */
    private static final Set<String> SUPPORTED_PRIORITIES = Set.of("HIGH", "MEDIUM", "LOW");

    private final QualityCheckReportMapper qualityCheckReportMapper;
    private final QualityAnomalyDetailMapper qualityAnomalyDetailMapper;
    private final QualityProjectScopeSupport qualityProjectScopeSupport;
    private final TaskManagementIntegrationProperties taskProperties;
    private final TaskManagementClient taskManagementClient;
    private final ObjectMapper objectMapper;

    /**
     * 创建质量异常治理任务。
     *
     * @param request 用户、运营人员或 Agent 提交的治理任务创建请求
     * @param visibility gateway/permission-admin 解析后的项目可见范围
     * @param trustedTenantId gateway 透传的可信租户 ID，可为空
     * @param actorId gateway 透传的当前操作者 ID，可为空
     * @return 创建结果；dry-run、无异常、未启用 task-management 或 fail-open 都会以 submitted=false 返回
     */
    public QualityRemediationTaskResponse createRemediationTask(QualityRemediationTaskRequest request,
                                                                QualityProjectVisibility visibility,
                                                                Long trustedTenantId,
                                                                Long actorId) {
        QualityRemediationTaskRequest safeRequest = request == null
                ? new QualityRemediationTaskRequest()
                : request;
        QualityProjectVisibility safeVisibility = visibility == null
                ? new QualityProjectVisibility(null, null, List.of(), false)
                : visibility;

        /*
         * PROJECT 范围为空时必须短路。
         * 这不是“查询结果为空”的普通情况，而是“当前身份没有任何可见项目”。
         * 如果继续按 reportId 或筛选条件查询数据库，就可能通过响应差异泄露某个 reportId 是否存在。
         */
        if (safeVisibility.projectScopeEnforced() && safeVisibility.authorizedProjectIds().isEmpty()) {
            return notSubmitted("当前 PROJECT 数据范围没有可见项目，未创建质量异常治理任务。", safeRequest,
                    "PROJECT_SCOPE_EMPTY");
        }

        QualityCheckReport report = loadAndValidateReportIfPresent(safeRequest, safeVisibility);
        RemediationTaskScope scope = resolveScope(safeRequest, report, safeVisibility, trustedTenantId);
        Long anomalyCount = countMatchingAnomalies(safeRequest, scope, safeVisibility);
        Long effectiveAnomalyCount = effectiveAnomalyCount(anomalyCount, report);
        if (effectiveAnomalyCount <= 0) {
            return notSubmitted("当前筛选条件下没有匹配质量异常，未创建治理任务。", safeRequest,
                    "NO_MATCHED_ANOMALY");
        }

        QualityRemediationTaskPayload payload = buildPayload(safeRequest, report, scope, safeVisibility,
                effectiveAnomalyCount);
        QualityRemediationTaskResponse response = baseResponse(safeRequest, payload, false);

        if (Boolean.TRUE.equals(safeRequest.getDryRun())) {
            response.setDryRun(true);
            response.setMessage("dry-run 已生成低敏治理任务 payload 预览，但未提交 task-management。");
            return response;
        }
        if (!taskProperties.isEnabled()) {
            response.getWarnings().add("task-management 集成当前关闭，未创建真实治理任务。");
            response.setMessage("已生成低敏治理任务 payload，但 task-management 集成关闭。");
            return response;
        }

        TaskCreateResponse task = taskManagementClient.createTask(buildTaskCreateRequest(safeRequest, payload,
                actorId));
        if (task == null) {
            response.getWarnings().add("task-management 调用失败且 fail-open=true，未创建真实治理任务。");
            response.setMessage("已生成低敏治理任务 payload，但 task-management fail-open 返回未提交。");
            return response;
        }
        response.setSubmitted(true);
        response.setTaskId(task.getId());
        response.setTaskType(task.getType());
        response.setTaskStatus(task.getStatus());
        response.setPriority(task.getPriority());
        response.setMessage("质量异常治理任务已提交 task-management。");
        return response;
    }

    /**
     * 如果请求指定 reportId，则加载报告并校验项目可见性。
     */
    private QualityCheckReport loadAndValidateReportIfPresent(QualityRemediationTaskRequest request,
                                                              QualityProjectVisibility visibility) {
        if (request.getReportId() == null) {
            return null;
        }
        QualityCheckReport report = qualityCheckReportMapper.selectById(request.getReportId());
        if (report == null) {
            throw new NoSuchElementException("质量报告不存在: " + request.getReportId());
        }
        qualityProjectScopeSupport.validateProjectReadable(report.getProjectId(), visibility, "质量报告");
        return report;
    }

    /**
     * 收敛任务所属租户、项目和工作空间。
     *
     * <p>优先级为：报告快照 &gt; 可信 Header &gt; 请求体。
     * 报告快照代表已经落库的质量事实；可信 Header 代表 gateway/permission-admin 的上下文；
     * 请求体只在没有更可信来源时作为补充。</p>
     */
    private RemediationTaskScope resolveScope(QualityRemediationTaskRequest request,
                                              QualityCheckReport report,
                                              QualityProjectVisibility visibility,
                                              Long trustedTenantId) {
        Long tenantId = firstNonNull(report == null ? null : report.getTenantId(), trustedTenantId, request.getTenantId());
        Long projectId = firstNonNull(report == null ? null : report.getProjectId(),
                request.getProjectId(),
                visibility.requestedProjectId(),
                singleAuthorizedProjectOrNull(visibility));
        Long workspaceId = firstNonNull(report == null ? null : report.getWorkspaceId(),
                request.getWorkspaceId(),
                visibility.requestedWorkspaceId());
        if (tenantId == null) {
            throw new IllegalArgumentException("创建质量异常治理任务必须具备 tenantId，请通过网关 Header、reportId 或请求体提供。");
        }
        if (projectId == null) {
            throw new IllegalArgumentException("创建质量异常治理任务必须指定 projectId 或 reportId，避免生成跨项目含义不清的治理任务。");
        }
        qualityProjectScopeSupport.validateProjectReadable(projectId, visibility, "质量异常治理任务");
        Long ruleId = firstNonNull(report == null ? null : report.getRuleId(), request.getRuleId());
        return new RemediationTaskScope(tenantId, projectId, workspaceId, ruleId);
    }

    /**
     * 统计筛选条件命中的异常数量。
     */
    private Long countMatchingAnomalies(QualityRemediationTaskRequest request,
                                        RemediationTaskScope scope,
                                        QualityProjectVisibility visibility) {
        return qualityAnomalyDetailMapper.selectCount(baseAnomalyWrapper(request, scope, visibility));
    }

    /**
     * 报告可能已经记录异常总数，但早期数据未必沉淀异常明细。
     * 因此 reportId 入口在明细计数为 0 且 report.exceptionCount 为正时，允许使用报告异常数创建治理任务。
     */
    private Long effectiveAnomalyCount(Long detailCount, QualityCheckReport report) {
        long normalizedDetailCount = detailCount == null ? 0L : detailCount;
        if (normalizedDetailCount > 0 || report == null || report.getExceptionCount() == null) {
            return normalizedDetailCount;
        }
        return Math.max(0L, report.getExceptionCount().longValue());
    }

    /**
     * 构建异常查询条件。
     *
     * <p>该方法只使用 MyBatis-Plus 参数绑定，不拼接用户输入。动态 GROUP BY 另由枚举白名单控制。</p>
     */
    private LambdaQueryWrapper<QualityAnomalyDetail> baseAnomalyWrapper(QualityRemediationTaskRequest request,
                                                                        RemediationTaskScope scope,
                                                                        QualityProjectVisibility visibility) {
        LambdaQueryWrapper<QualityAnomalyDetail> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(QualityAnomalyDetail::getTenantId, scope.tenantId());
        wrapper.eq(QualityAnomalyDetail::getProjectId, scope.projectId());
        if (scope.workspaceId() != null) {
            wrapper.eq(QualityAnomalyDetail::getWorkspaceId, scope.workspaceId());
        }
        if (request.getReportId() != null) {
            wrapper.eq(QualityAnomalyDetail::getReportId, request.getReportId());
        }
        if (scope.ruleId() != null) {
            wrapper.eq(QualityAnomalyDetail::getRuleId, scope.ruleId());
        }
        eqIfPresent(wrapper, QualityAnomalyDetail::getAnomalyType, normalizeCode(request.getAnomalyType(), null));
        eqIfPresent(wrapper, QualityAnomalyDetail::getSeverity, normalizeCode(request.getSeverity(), null));
        likeIfPresent(wrapper, QualityAnomalyDetail::getFieldName, safeText(request.getFieldName(), 128));
        likeIfPresent(wrapper, QualityAnomalyDetail::getTargetObject, safeText(request.getTargetObject(), 256));
        if (request.getStartTime() != null) {
            wrapper.ge(QualityAnomalyDetail::getCreateTime, request.getStartTime());
        }
        if (request.getEndTime() != null) {
            wrapper.le(QualityAnomalyDetail::getCreateTime, request.getEndTime());
        }
        if (visibility.projectScopeEnforced()) {
            wrapper.in(QualityAnomalyDetail::getProjectId, visibility.authorizedProjectIds());
        }
        return wrapper;
    }

    /**
     * 构建低敏治理任务 payload。
     */
    private QualityRemediationTaskPayload buildPayload(QualityRemediationTaskRequest request,
                                                       QualityCheckReport report,
                                                       RemediationTaskScope scope,
                                                       QualityProjectVisibility visibility,
                                                       Long anomalyCount) {
        int topLimit = safeAggregationLimit(request.getAggregationLimit());
        QualityRemediationTaskPayload payload = new QualityRemediationTaskPayload();
        payload.setPayloadPolicy(PAYLOAD_POLICY);
        payload.setTenantId(scope.tenantId());
        payload.setProjectId(scope.projectId());
        payload.setWorkspaceId(scope.workspaceId());
        payload.setReportId(request.getReportId());
        payload.setRuleId(scope.ruleId());
        payload.setRuleName(safeText(report == null ? null : report.getRuleName(), 128));
        payload.setRuleType(safeText(report == null ? null : report.getRuleType(), 64));
        payload.setSeverity(firstText(normalizeCode(request.getSeverity(), null),
                safeText(report == null ? null : report.getSeverity(), 64)));
        payload.setTargetObject(firstText(safeText(request.getTargetObject(), 256),
                safeText(report == null ? null : report.getTargetObject(), 256)));
        payload.setRemediationType(normalizeRemediationType(request.getRemediationType()));
        payload.setReason(safeText(request.getReason(), 500));
        payload.setRecommendation(safeText(request.getRecommendation(), 500));
        payload.setFilters(filters(request, scope));
        payload.setAnomalyCount(anomalyCount);
        payload.setTopFields(aggregate(request, scope, visibility, QualityAnomalyAggregationDimension.FIELD, topLimit));
        payload.setTopTypes(aggregate(request, scope, visibility, QualityAnomalyAggregationDimension.TYPE, topLimit));
        payload.setTopSeverities(aggregate(request, scope, visibility, QualityAnomalyAggregationDimension.SEVERITY, topLimit));
        payload.setCreatedAt(LocalDateTime.now());
        return payload;
    }

    /**
     * 构建低敏筛选条件摘要。
     */
    private Map<String, Object> filters(QualityRemediationTaskRequest request, RemediationTaskScope scope) {
        Map<String, Object> filters = new LinkedHashMap<>();
        putIfPresent(filters, "tenantId", scope.tenantId());
        putIfPresent(filters, "projectId", scope.projectId());
        putIfPresent(filters, "workspaceId", scope.workspaceId());
        putIfPresent(filters, "reportId", request.getReportId());
        putIfPresent(filters, "ruleId", scope.ruleId());
        putIfPresent(filters, "anomalyType", normalizeCode(request.getAnomalyType(), null));
        putIfPresent(filters, "fieldName", safeText(request.getFieldName(), 128));
        putIfPresent(filters, "severity", normalizeCode(request.getSeverity(), null));
        putIfPresent(filters, "targetObject", safeText(request.getTargetObject(), 256));
        putIfPresent(filters, "startTime", request.getStartTime());
        putIfPresent(filters, "endTime", request.getEndTime());
        return filters;
    }

    /**
     * 调用 Mapper 的安全聚合查询。
     */
    private List<QualityAnomalyAggregationItem> aggregate(QualityRemediationTaskRequest request,
                                                          RemediationTaskScope scope,
                                                          QualityProjectVisibility visibility,
                                                          QualityAnomalyAggregationDimension dimension,
                                                          int topLimit) {
        return qualityAnomalyDetailMapper.aggregateAnomalies(
                dimension.getColumnName(),
                scope.tenantId(),
                request.getReportId(),
                scope.ruleId(),
                normalizeCode(request.getAnomalyType(), null),
                safeText(request.getFieldName(), 128),
                normalizeCode(request.getSeverity(), null),
                safeText(request.getTargetObject(), 256),
                request.getStartTime(),
                request.getEndTime(),
                topLimit,
                scope.projectId(),
                scope.workspaceId(),
                visibility.authorizedProjectIds(),
                visibility.projectScopeEnforced()
        );
    }

    /**
     * 构建 task-management 创建任务请求。
     */
    private TaskCreateRequest buildTaskCreateRequest(QualityRemediationTaskRequest request,
                                                     QualityRemediationTaskPayload payload,
                                                     Long actorId) {
        TaskCreateRequest taskRequest = new TaskCreateRequest();
        taskRequest.setName(taskName(payload));
        taskRequest.setDescription(taskDescription(payload));
        taskRequest.setType(taskProperties.getRemediationTaskType());
        taskRequest.setTenantId(payload.getTenantId());
        taskRequest.setProjectId(payload.getProjectId());
        taskRequest.setOwnerId(firstNonNull(request.getAssigneeActorId(), actorId, taskProperties.getExecutorActorId()));
        taskRequest.setPriority(normalizePriority(request.getPriority()));
        taskRequest.setMaxRetryCount(firstNonNull(request.getMaxRetryCount(), taskProperties.getDefaultMaxRetryCount()));
        taskRequest.setParams(toJson(payload));
        return taskRequest;
    }

    private QualityRemediationTaskResponse baseResponse(QualityRemediationTaskRequest request,
                                                        QualityRemediationTaskPayload payload,
                                                        boolean submitted) {
        QualityRemediationTaskResponse response = new QualityRemediationTaskResponse();
        response.setSubmitted(submitted);
        response.setDryRun(Boolean.TRUE.equals(request.getDryRun()));
        response.setTaskType(taskProperties.getRemediationTaskType());
        response.setPriority(normalizePriority(request.getPriority()));
        response.setAnomalyCount(payload.getAnomalyCount());
        response.setTenantId(payload.getTenantId());
        response.setProjectId(payload.getProjectId());
        response.setWorkspaceId(payload.getWorkspaceId());
        response.setReportId(payload.getReportId());
        response.setRuleId(payload.getRuleId());
        response.setPayloadPolicy(PAYLOAD_POLICY);
        response.setPayloadPreview(payload);
        return response;
    }

    private QualityRemediationTaskResponse notSubmitted(String message,
                                                        QualityRemediationTaskRequest request,
                                                        String warningCode) {
        QualityRemediationTaskResponse response = new QualityRemediationTaskResponse();
        response.setSubmitted(false);
        response.setDryRun(request != null && Boolean.TRUE.equals(request.getDryRun()));
        response.setTaskType(taskProperties.getRemediationTaskType());
        response.setPriority(normalizePriority(request == null ? null : request.getPriority()));
        response.setPayloadPolicy(PAYLOAD_POLICY);
        response.setMessage(message);
        response.getWarnings().add(warningCode);
        return response;
    }

    private String taskName(QualityRemediationTaskPayload payload) {
        String target = firstText(payload.getTargetObject(), payload.getRuleName(), "未命名质量目标");
        return "质量异常治理 - " + truncate(target, 80);
    }

    private String taskDescription(QualityRemediationTaskPayload payload) {
        return "基于 data-quality 低敏异常聚合创建的治理任务；命中异常数="
                + payload.getAnomalyCount()
                + "，治理类型=" + payload.getRemediationType()
                + "，payload 只包含聚合摘要与筛选条件。";
    }

    private String toJson(QualityRemediationTaskPayload payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("序列化质量异常治理任务 payload 失败", ex);
        }
    }

    private String normalizeRemediationType(String value) {
        return normalizeCode(value, "MANUAL_REVIEW", SUPPORTED_REMEDIATION_TYPES);
    }

    private String normalizePriority(String value) {
        return normalizeCode(value, taskProperties.getDefaultPriority(), SUPPORTED_PRIORITIES);
    }

    private int safeAggregationLimit(Integer limit) {
        return Math.max(1, Math.min(limit == null ? 10 : limit, 50));
    }

    private Long singleAuthorizedProjectOrNull(QualityProjectVisibility visibility) {
        if (!visibility.projectScopeEnforced() || visibility.authorizedProjectIds().size() != 1) {
            return null;
        }
        return visibility.authorizedProjectIds().get(0);
    }

    private void putIfPresent(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    @SafeVarargs
    private <T> T firstNonNull(T... values) {
        for (T value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private <T> void eqIfPresent(LambdaQueryWrapper<QualityAnomalyDetail> wrapper,
                                 com.baomidou.mybatisplus.core.toolkit.support.SFunction<QualityAnomalyDetail, T> column,
                                 T value) {
        if (value != null) {
            wrapper.eq(column, value);
        }
    }

    private void likeIfPresent(LambdaQueryWrapper<QualityAnomalyDetail> wrapper,
                               com.baomidou.mybatisplus.core.toolkit.support.SFunction<QualityAnomalyDetail, String> column,
                               String value) {
        if (value != null && !value.isBlank()) {
            wrapper.like(column, value);
        }
    }

    /**
     * 服务内部使用的任务范围快照。
     *
     * <p>record 让 tenant/project/workspace/rule 的派生结果作为一个整体在方法间传递，
     * 避免多个 Long 参数在调用链中顺序错位。</p>
     */
    private record RemediationTaskScope(Long tenantId, Long projectId, Long workspaceId, Long ruleId) {
    }
}
