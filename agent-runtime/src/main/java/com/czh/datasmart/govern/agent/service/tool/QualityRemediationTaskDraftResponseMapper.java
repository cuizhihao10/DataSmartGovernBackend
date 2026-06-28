/**
 * @Author : Cui
 * @Date: 2026/06/28 13:26
 * @Description DataSmart Govern Backend - QualityRemediationTaskDraftResponseMapper.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.tool;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * data-quality 治理任务 dry-run 响应映射器。
 *
 * <p>工具适配器不应把下游 HTTP 响应原样塞进 Agent 输出。原因是 Agent 输出后续会进入工具审计、
 * 二轮模型反馈、前端确认页和 runtime event 投影。即使 data-quality 当前响应已经按低敏设计，
 * Java 控制面仍应再做一层结构收口：只保留执行状态、数量、任务类型、低敏 payload preview 和警告。</p>
 */
@Component
public class QualityRemediationTaskDraftResponseMapper {

    public AgentToolExecutionOutcome toOutcome(QualityRemediationTaskDraftRequest request,
                                               Map<String, Object> response) {
        if (response == null) {
            return AgentToolExecutionOutcome.failed("QUALITY_REMEDIATION_DRAFT_EMPTY_RESPONSE",
                    "data-quality 返回空响应，无法确认质量治理任务草案是否生成");
        }
        int code = integerValue(response.get("code"), 0);
        if (code != 0) {
            return AgentToolExecutionOutcome.failed("QUALITY_REMEDIATION_DRAFT_FAILED",
                    "data-quality 治理任务草案生成失败: " + safeMessage(response.get("message")));
        }
        Object data = response.get("data");
        if (!(data instanceof Map<?, ?> rawData)) {
            return AgentToolExecutionOutcome.failed("QUALITY_REMEDIATION_DRAFT_MISSING_DATA",
                    "data-quality 响应缺少 data，工具无法生成治理任务草案摘要");
        }

        Map<String, Object> dataMap = copyStringKeyMap(rawData);
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("summary", summary(dataMap, response.get("message")));
        output.put("remediationTaskDraft", remediationTaskDraft(request, dataMap));
        output.put("recommendedActions", recommendedActions(dataMap));
        return AgentToolExecutionOutcome.succeeded("质量异常治理任务 dry-run 预览已生成，等待人工确认后再提交。", output);
    }

    /**
     * 构建审计列表和前端卡片最需要的低敏摘要。
     */
    private Map<String, Object> summary(Map<String, Object> data, Object remoteMessage) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("draftOnly", true);
        summary.put("sideEffect", "NONE");
        summary.put("submitted", booleanValue(data.get("submitted")));
        summary.put("dryRun", booleanValue(data.get("dryRun")));
        summary.put("taskId", data.get("taskId"));
        summary.put("taskType", data.get("taskType"));
        summary.put("taskStatus", data.get("taskStatus"));
        summary.put("priority", data.get("priority"));
        summary.put("anomalyCount", data.get("anomalyCount"));
        summary.put("payloadPolicy", data.get("payloadPolicy"));
        summary.put("warnings", data.get("warnings"));
        summary.put("remoteMessage", remoteMessage);
        summary.put("message", "已生成低敏治理任务草案预览；当前没有创建真实 task-management 任务。");
        return withoutNullValues(summary);
    }

    /**
     * 构建给审批页和后续 execution graph 使用的草案对象。
     *
     * <p>这里保留 `lowSensitivePayloadPreview`，是为了让人工审批知道任务会带哪些聚合信息。
     * 该 preview 来自 data-quality 的低敏 contract，不包含样本、SQL、prompt、模型输出或凭据。
     * 如果未来 data-quality payload 扩展出更细字段，仍建议在这里继续白名单化，而不是无限透传。</p>
     */
    private Map<String, Object> remediationTaskDraft(QualityRemediationTaskDraftRequest request,
                                                     Map<String, Object> data) {
        Map<String, Object> draft = new LinkedHashMap<>();
        draft.put("draftOnly", true);
        draft.put("sideEffect", "NONE");
        draft.put("approvalRequiredBeforeSubmit", true);
        draft.put("submitted", booleanValue(data.get("submitted")));
        draft.put("dryRun", booleanValue(data.get("dryRun")));
        draft.put("taskType", data.get("taskType"));
        draft.put("taskStatus", data.get("taskStatus"));
        draft.put("priority", data.get("priority"));
        draft.put("anomalyCount", data.get("anomalyCount"));
        draft.put("payloadPolicy", data.get("payloadPolicy"));
        draft.put("scope", scope(request, data));
        if (data.get("payloadPreview") instanceof Map<?, ?> preview) {
            draft.put("lowSensitivePayloadPreview", copyStringKeyMap(preview));
        }
        return withoutNullValues(draft);
    }

    private Map<String, Object> scope(QualityRemediationTaskDraftRequest request, Map<String, Object> data) {
        Map<String, Object> scope = new LinkedHashMap<>();
        scope.put("tenantId", firstNonNull(data.get("tenantId"), request.tenantId()));
        scope.put("projectId", firstNonNull(data.get("projectId"), request.projectId()));
        scope.put("workspaceId", firstNonNull(data.get("workspaceId"), request.workspaceId()));
        scope.put("reportId", firstNonNull(data.get("reportId"), request.reportId()));
        scope.put("ruleId", firstNonNull(data.get("ruleId"), request.ruleId()));
        scope.put("severity", request.severity());
        scope.put("anomalyType", request.anomalyType());
        scope.put("fieldName", request.fieldName());
        scope.put("targetObject", request.targetObject());
        return withoutNullValues(scope);
    }

    private List<String> recommendedActions(Map<String, Object> data) {
        if (Long.valueOf(0L).equals(longValue(data.get("anomalyCount")))) {
            return List.of(
                    "确认筛选条件是否过窄或报告是否仍在可见项目范围内",
                    "必要时先回到质量异常工作台重新选择异常范围"
            );
        }
        return List.of(
                "由项目负责人或运营人员复核治理任务草案",
                "确认异常范围、优先级、负责人和 SLA 后再提交真实任务",
                "真实提交前再次经过 permission-admin 动作权限和 data-quality 项目范围校验"
        );
    }

    private Map<String, Object> copyStringKeyMap(Map<?, ?> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            copy.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return copy;
    }

    private Map<String, Object> withoutNullValues(Map<String, Object> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            if (entry.getValue() != null) {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        return result;
    }

    private boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private Long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private int integerValue(Object value, int defaultValue) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private Object firstNonNull(Object first, Object second) {
        return first != null ? first : second;
    }

    private String safeMessage(Object value) {
        if (value == null) {
            return "下游未返回错误说明";
        }
        String message = String.valueOf(value);
        return message.isBlank() ? "下游未返回错误说明" : message;
    }
}
