/**
 * @Author : Cui
 * @Date: 2026/05/05 23:08
 * @Description DataSmart Govern Backend - QualityTaskSchedulingSupport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.quality.service.support;

import com.czh.datasmart.govern.quality.config.TaskManagementIntegrationProperties;
import com.czh.datasmart.govern.quality.controller.dto.QualityScanPlan;
import com.czh.datasmart.govern.quality.controller.dto.QualityTaskScheduleRequest;
import com.czh.datasmart.govern.quality.controller.dto.QualityTaskScheduleResult;
import com.czh.datasmart.govern.quality.entity.QualityRule;
import com.czh.datasmart.govern.quality.integration.task.QualityTaskPayload;
import com.czh.datasmart.govern.quality.integration.task.QualityTaskPayloadParser;
import com.czh.datasmart.govern.quality.integration.task.TaskCreateRequest;
import com.czh.datasmart.govern.quality.integration.task.TaskCreateResponse;
import com.czh.datasmart.govern.quality.integration.task.TaskManagementClient;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 质量任务提交支撑组件。
 *
 * <p>该组件负责把 data-quality 生成的扫描计划提交给 task-management。
 * 它的核心价值是把“质量规则自身生命周期”和“跨服务任务调度集成”分开：
 * 1. 规则生命周期关注规则能不能存在、能不能启用、能不能生成扫描计划；
 * 2. 调度提交关注任务 payload 合同、任务优先级、重试次数、dryRun、fail-open 和远程调用；
 * 3. 后续如果 task-management 改为消息队列、服务发现、Feign、gRPC 或 outbox 投递，只需要优先修改该组件。
 *
 * <p>商业化演进方向：
 * - 支持批量提交质量任务，并做租户级并发配额；
 * - 提交前检查队列容量、执行窗口、任务模板、审批状态；
 * - 提交失败时写入 outbox，避免调用 task-management 短暂失败导致任务丢失；
 * - 把提交结果、耗时、失败原因接入 observability 指标。
 */
@Component
@RequiredArgsConstructor
public class QualityTaskSchedulingSupport {

    /**
     * task-management 远程客户端。
     * 当前使用 HTTP 调用，后续可以演进为服务发现、声明式客户端或消息投递。
     */
    private final TaskManagementClient taskManagementClient;

    /**
     * task-management 集成配置。
     * 这里控制开关、任务类型、默认优先级、默认重试次数和服务账号租户等跨服务参数。
     */
    private final TaskManagementIntegrationProperties taskManagementIntegrationProperties;

    /**
     * 质量任务 payload 解析与校验器。
     * 提交前校验 payload，是为了尽早发现跨服务合同不完整，避免不可执行任务进入队列。
     */
    private final QualityTaskPayloadParser qualityTaskPayloadParser;

    /**
     * JSON 序列化器。
     * task-management 当前的 params 字段是 JSON 字符串，因此这里负责把结构化 payload 转成可持久化文本。
     */
    private final ObjectMapper objectMapper;

    public QualityTaskScheduleResult schedule(QualityRule rule, QualityScanPlan plan, QualityTaskScheduleRequest request) {
        QualityTaskScheduleResult result = new QualityTaskScheduleResult();
        result.setRuleId(rule.getId());
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

    private TaskCreateRequest buildTaskCreateRequest(QualityRule rule, QualityScanPlan plan,
                                                     QualityTaskScheduleRequest request) {
        TaskCreateRequest taskRequest = new TaskCreateRequest();
        taskRequest.setName("质量检测 - " + rule.getName());
        taskRequest.setDescription(buildQualityTaskDescription(rule, request));
        taskRequest.setType(taskManagementIntegrationProperties.getTaskType());
        taskRequest.setTenantId(resolvePayloadTenantId(request));
        taskRequest.setOwnerId(taskManagementIntegrationProperties.getExecutorActorId());
        taskRequest.setPriority(hasText(request == null ? null : request.getPriority())
                ? normalizeUpper(request.getPriority())
                : taskManagementIntegrationProperties.getDefaultPriority());
        taskRequest.setMaxRetryCount(request == null || request.getMaxRetryCount() == null
                ? taskManagementIntegrationProperties.getDefaultMaxRetryCount()
                : request.getMaxRetryCount());
        taskRequest.setParams(serializeQualityTaskPayload(rule, plan, request));
        return taskRequest;
    }

    private String buildQualityTaskDescription(QualityRule rule, QualityTaskScheduleRequest request) {
        String reason = request == null ? null : request.getReason();
        if (hasText(reason)) {
            return "质量规则[" + rule.getName() + "]检测任务，提交原因：" + reason;
        }
        return "质量规则[" + rule.getName() + "]检测任务，由 data-quality 根据扫描计划提交。";
    }

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

    private Long resolvePayloadTenantId(QualityTaskScheduleRequest request) {
        if (request != null && request.getTenantId() != null) {
            return request.getTenantId();
        }
        return taskManagementIntegrationProperties.getExecutorActorTenantId();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String normalizeUpper(String value) {
        return hasText(value) ? value.trim().toUpperCase() : value;
    }
}
