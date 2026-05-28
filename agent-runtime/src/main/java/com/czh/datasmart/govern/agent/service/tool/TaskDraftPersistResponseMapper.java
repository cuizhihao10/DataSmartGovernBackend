/**
 * @Author : Cui
 * @Date: 2026/05/24 23:59
 * @Description DataSmart Govern Backend - TaskDraftPersistResponseMapper.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.tool;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * task-management 草稿创建响应映射器。
 *
 * <p>Agent 工具输出不能简单透传下游响应。原因是工具执行结果还会进入审计、前端确认页、
 * 后续工具链引用和 Agent 记忆摘要。这里把下游统一响应转换为稳定的 Agent 工具输出结构，
 * 并额外补充审批与后续动作建议。</p>
 */
@Component
public class TaskDraftPersistResponseMapper {

    /**
     * 将 task-management 返回的统一 envelope 转换为工具执行结果。
     *
     * <p>成功输出中保留完整 taskDraft，便于后续工具用 `outputRef.path=taskDraft.id` 精确引用草稿 ID。
     * summary 则只保留审批页和审计列表最需要的字段，避免调用方为了展示状态去解析完整实体。</p>
     */
    public AgentToolExecutionOutcome toOutcome(TaskDraftPersistRequest request, Map<String, Object> response) {
        if (response == null) {
            return AgentToolExecutionOutcome.failed("TASK_DRAFT_PERSIST_EMPTY_RESPONSE",
                    "task-management 返回空响应，无法确认任务草稿是否保存成功");
        }
        int code = integerValue(response.get("code"), 0);
        if (code != 0) {
            return AgentToolExecutionOutcome.failed("TASK_DRAFT_PERSIST_FAILED",
                    "task-management 保存任务草稿失败: " + safeMessage(response.get("message")));
        }
        Object data = response.get("data");
        if (!(data instanceof Map<?, ?> rawData)) {
            return AgentToolExecutionOutcome.failed("TASK_DRAFT_PERSIST_MISSING_DATA",
                    "task-management 响应缺少 data，无法读取已保存的草稿信息");
        }

        Map<String, Object> taskDraft = copyStringKeyMap(rawData);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("persisted", true);
        summary.put("draftId", taskDraft.get("id"));
        summary.put("status", taskDraft.get("status"));
        summary.put("taskType", taskDraft.getOrDefault("type", request.type()));
        summary.put("tenantId", taskDraft.getOrDefault("tenantId", request.tenantId()));
        summary.put("projectId", taskDraft.getOrDefault("projectId", request.projectId()));
        summary.put("approvalRequired", true);
        summary.put("queueVisible", false);
        summary.put("message", "任务草稿已保存到 task-management，但尚未提交审批，也不会进入真实执行队列。");

        Map<String, Object> approval = new LinkedHashMap<>();
        approval.put("currentState", taskDraft.getOrDefault("status", "DRAFT"));
        approval.put("requiredBeforeExecution", true);
        approval.put("nextLifecycle", "DRAFT -> PENDING_APPROVAL -> APPROVED -> CONVERTED");
        approval.put("guard", "只有 APPROVED 草稿才能转换为真实 PENDING 任务，当前工具不会自动提交、审批或转换。");

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("summary", summary);
        output.put("taskDraft", taskDraft);
        output.put("approval", approval);
        output.put("recommendedActions", List.of(
                "在任务草稿详情页复核名称、类型、参数、项目范围和执行窗口",
                "确认无误后调用 task-management 的 /task-drafts/{id}/submit 提交审批",
                "审批通过后再调用 /task-drafts/{id}/convert 转换为真实任务",
                "后续建议补充幂等键，避免重复 convert 产生多条真实任务"
        ));
        output.put("remoteMessage", response.get("message"));
        return AgentToolExecutionOutcome.succeeded("任务草稿已持久化，等待提交审批。", output);
    }

    private Map<String, Object> copyStringKeyMap(Map<?, ?> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            copy.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return copy;
    }

    private int integerValue(Object value, int defaultValue) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    private String safeMessage(Object value) {
        if (value == null) {
            return "下游未返回错误说明";
        }
        String message = String.valueOf(value);
        return message.isBlank() ? "下游未返回错误说明" : message;
    }
}
