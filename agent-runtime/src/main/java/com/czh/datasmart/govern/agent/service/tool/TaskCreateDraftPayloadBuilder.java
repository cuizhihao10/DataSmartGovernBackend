/**
 * @Author : Cui
 * @Date: 2026/05/24 23:11
 * @Description DataSmart Govern Backend - TaskCreateDraftPayloadBuilder.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.tool;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * `task.create.draft` 输出载荷构建器。
 *
 * <p>该类只负责把内部请求转换成前端、审批页和后续工具链都能理解的结构化草稿。
 * 之所以单独拆出来，是为了让 `TaskCreateDraftToolAdapter` 保持很薄：
 * 适配器只表达“执行某个工具”，载荷构建器表达“草稿长什么样”。</p>
 *
 * <p>当前草稿采用 Map 结构，是为了和已有 Agent 工具输出保持一致。
 * 后续如果前端确认页、审批单和 task-management draft 表稳定下来，可以再升级成强类型 DTO。</p>
 */
@Component
public class TaskCreateDraftPayloadBuilder {

    private static final int MAX_INLINE_SUGGESTIONS = 20;

    /**
     * 构建工具执行输出。
     *
     * <p>输出分为四块：</p>
     * <p>1. summary：给审计列表和前端卡片快速展示；</p>
     * <p>2. taskDraft：对齐 task-management 创建任务所需的核心字段，但不会真正落库；</p>
     * <p>3. approval：告诉调用方为什么仍然需要人工确认；</p>
     * <p>4. riskControls：明确本轮工具没有副作用，以及后续真实创建时需要再检查什么。</p>
     */
    public Map<String, Object> build(TaskCreateDraftRequest request) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("summary", summary(request));
        output.put("taskDraft", taskDraft(request));
        output.put("approval", approval(request));
        output.put("riskControls", riskControls());
        output.put("recommendedActions", recommendedActions(request));
        return output;
    }

    private Map<String, Object> summary(TaskCreateDraftRequest request) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("draftOnly", true);
        summary.put("sideEffect", "NONE");
        summary.put("approvalRequired", true);
        summary.put("taskType", request.taskType());
        summary.put("priority", request.priority());
        summary.put("tenantId", request.tenantId());
        summary.put("projectId", request.projectId());
        summary.put("sourceSuggestionCount", suggestionCount(request.sourceSuggestion()));
        summary.put("message", "已生成任务草稿，但尚未写入 task-management，也不会进入调度队列。");
        return summary;
    }

    private Map<String, Object> taskDraft(TaskCreateDraftRequest request) {
        Map<String, Object> draft = new LinkedHashMap<>();
        draft.put("name", taskName(request));
        draft.put("description", taskDescription(request));
        draft.put("type", request.taskType());
        draft.put("tenantId", request.tenantId());
        draft.put("projectId", request.projectId());
        if (request.workspaceId() != null) {
            draft.put("workspaceId", request.workspaceId());
        }
        draft.put("priority", request.priority());
        draft.put("maxRetryCount", request.maxRetryCount());
        draft.put("maxDeferCount", request.maxDeferCount());
        draft.put("statusPolicy", "DRAFT_ONLY_NOT_PERSISTED");
        draft.put("params", params(request));
        return draft;
    }

    private Map<String, Object> params(TaskCreateDraftRequest request) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("payloadVersion", "agent-task-draft-v1");
        params.put("objective", request.objective());
        params.put("sourceToolCode", request.sourceSuggestion().isEmpty() ? null : "quality.rule.suggest");
        params.put("qualitySuggestionSummary", sourceSummary(request.sourceSuggestion()));
        params.put("qualityRuleSuggestions", inlineSuggestions(request.sourceSuggestion()));
        params.put("approvalRequiredBeforeCreate", true);
        params.put("executionGuard", "真实创建任务前必须再次校验项目范围、任务类型、执行器配额、规则状态和审批单有效期。");
        return withoutNullValues(params);
    }

    private Map<String, Object> approval(TaskCreateDraftRequest request) {
        Map<String, Object> approval = new LinkedHashMap<>();
        approval.put("required", true);
        approval.put("reason", "任务创建会影响队列调度、执行资源、数据访问和告警结果，因此 Agent 只能生成草稿，不能直接提交可执行任务。");
        approval.put("suggestedApproverRole", "PROJECT_OWNER_OR_PLATFORM_OPERATOR");
        approval.put("approvalChecklist", List.of(
                "确认任务目标与当前项目治理目标一致",
                "确认规则草案已经人工复核或处于可接受置信度",
                "确认执行窗口、并发配额、数据源压力和失败告警策略",
                "确认 task-management 创建后不会绕过 permission-admin 的项目范围边界"
        ));
        approval.put("taskType", request.taskType());
        return approval;
    }

    private Map<String, Object> riskControls() {
        Map<String, Object> controls = new LinkedHashMap<>();
        controls.put("downstreamHttpCalled", false);
        controls.put("taskPersisted", false);
        controls.put("queueVisible", false);
        controls.put("idempotencyRequiredOnRealCreate", true);
        controls.put("futureProductionRecommendation",
                "后续建议在 task-management 增加 DRAFT/PENDING_APPROVAL 状态或独立 task_draft 表，再由审批流转为 PENDING。");
        return controls;
    }

    private List<String> recommendedActions(TaskCreateDraftRequest request) {
        if ("DATA_QUALITY_SCAN".equals(request.taskType())) {
            return List.of(
                    "先在 data-quality 中保存并确认规则草案",
                    "为质量扫描任务选择执行窗口和并发配额",
                    "审批通过后再调用 task-management 创建 PENDING 任务",
                    "执行后将报告、异常明细和规则效果回写到质量模块"
            );
        }
        return List.of(
                "人工复核任务草稿内容",
                "补齐执行参数和负责人",
                "审批通过后再提交到 task-management",
                "必要时拆分为更小的子任务或模板任务"
        );
    }

    private String taskName(TaskCreateDraftRequest request) {
        Object tableName = request.sourceSuggestion().get("tableName");
        if (tableName != null && !String.valueOf(tableName).isBlank()) {
            return request.taskType() + " 草稿 - " + tableName;
        }
        Object datasourceId = request.sourceSuggestion().get("datasourceId");
        if (datasourceId != null) {
            return request.taskType() + " 草稿 - datasource-" + datasourceId;
        }
        return request.taskType() + " 草稿";
    }

    private String taskDescription(TaskCreateDraftRequest request) {
        return "Agent 根据用户目标生成的受控任务草稿。目标：" + request.objective()
                + "。该草稿尚未落库，审批通过后才允许创建真实任务。";
    }

    private Map<String, Object> sourceSummary(Map<String, Object> sourceSuggestion) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("datasourceId", sourceSuggestion.get("datasourceId"));
        summary.put("tableName", sourceSuggestion.get("tableName"));
        summary.put("businessGoal", sourceSuggestion.get("businessGoal"));
        summary.put("suggestionCount", suggestionCount(sourceSuggestion));
        summary.put("generationStrategy", sourceSuggestion.get("generationStrategy"));
        return withoutNullValues(summary);
    }

    private Object inlineSuggestions(Map<String, Object> sourceSuggestion) {
        Object suggestions = sourceSuggestion.get("suggestions");
        if (suggestions instanceof List<?> list && list.size() > MAX_INLINE_SUGGESTIONS) {
            return list.subList(0, MAX_INLINE_SUGGESTIONS);
        }
        return suggestions;
    }

    private int suggestionCount(Map<String, Object> sourceSuggestion) {
        Object count = sourceSuggestion.get("suggestionCount");
        if (count instanceof Number number) {
            return number.intValue();
        }
        Object suggestions = sourceSuggestion.get("suggestions");
        if (suggestions instanceof List<?> list) {
            return list.size();
        }
        return 0;
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
}
