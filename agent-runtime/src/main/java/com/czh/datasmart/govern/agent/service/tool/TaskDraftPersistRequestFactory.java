/**
 * @Author : Cui
 * @Date: 2026/05/24 23:59
 * @Description DataSmart Govern Backend - TaskDraftPersistRequestFactory.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.tool;

import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * `task.draft.persist` 请求工厂。
 *
 * <p>该工厂负责把 Agent ToolPlan 中的草稿对象转换为 task-management 可接收的创建草稿请求。
 * 它刻意和 `TaskCreateDraftRequestFactory` 分开，原因是两个工具的业务含义不同：</p>
 *
 * <p>1. `task.create.draft`：只在 agent-runtime 内生成结构化草稿，绝不产生下游写入；</p>
 * <p>2. `task.draft.persist`：把已经生成并确认要保留的草稿写入 task-management 的 `task_draft` 表。</p>
 *
 * <p>为什么不直接让 `task.create.draft` 顺手保存：</p>
 * <p>商业化 Agent 平台需要区分“模型生成建议”和“系统写入业务对象”。
 * 生成建议可以自动发生，写入业务对象则必须经过工具风险等级、审批凭证、项目范围和审计链路校验。
 * 拆成两个工具后，前端和 Python Runtime 可以明确展示“下一步将保存草稿”，用户也更容易理解风险边界。</p>
 */
@Component
public class TaskDraftPersistRequestFactory {

    private static final String TASK_CREATE_DRAFT_TOOL_CODE = "task.create.draft";
    private static final String DEFAULT_PRIORITY = "MEDIUM";
    private static final int DEFAULT_MAX_RETRY_COUNT = 3;
    private static final int DEFAULT_MAX_DEFER_COUNT = 20;

    private final AgentToolOutputReferenceResolver outputReferenceResolver;
    private final ObjectMapper objectMapper;

    public TaskDraftPersistRequestFactory(AgentToolOutputReferenceResolver outputReferenceResolver,
                                          ObjectMapper objectMapper) {
        this.outputReferenceResolver = outputReferenceResolver;
        this.objectMapper = objectMapper;
    }

    /**
     * 构建持久化请求。
     *
     * <p>输入来源支持三种优先级：</p>
     * <p>1. ToolPlan 直接携带 `taskDraft` 对象，适合前端确认页把编辑后的草稿回传；</p>
     * <p>2. ToolPlan 携带 `taskDraftRef/draftRef/outputRef`，通过显式工具输出引用读取前序草稿；</p>
     * <p>3. 如果没有显式引用，回退读取同一 Run 内最近一次 `task.create.draft` 的 `taskDraft` 输出。</p>
     */
    public TaskDraftPersistRequest build(AgentToolExecutionContext context) {
        Map<String, Object> arguments = context.audit().getPlanArguments();
        Map<String, Object> taskDraft = resolveTaskDraft(context, arguments);
        Long tenantId = longValue(firstNonNull(taskDraft.get("tenantId"), context.session().getTenantId()));
        Long projectId = longValue(firstNonNull(taskDraft.get("projectId"), context.session().getProjectId()));

        if (tenantId == null || projectId == null) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "持久化任务草稿必须具备 tenantId 和 projectId，避免 Agent 写入无租户或无项目边界的草稿");
        }

        return new TaskDraftPersistRequest(
                requiredText(taskDraft.get("name"), "任务草稿名称不能为空"),
                stringValue(taskDraft.get("description")),
                requiredText(taskDraft.get("type"), "任务类型不能为空").toUpperCase(Locale.ROOT),
                tenantId,
                longValue(firstNonNull(taskDraft.get("ownerId"), context.session().getActorId())),
                projectId,
                serializeParams(taskDraft.get("params")),
                normalizePriority(taskDraft.get("priority")),
                boundedInteger(taskDraft.get("maxRetryCount"), DEFAULT_MAX_RETRY_COUNT, 0, 20),
                boundedInteger(taskDraft.get("maxDeferCount"), DEFAULT_MAX_DEFER_COUNT, 0, 10_000),
                "AGENT",
                sourceRef(context, arguments)
        );
    }

    /**
     * 解析待持久化的任务草稿对象。
     *
     * <p>显式引用是生产推荐方式，因为一个 Run 内可能生成多个草稿。
     * 如果只依赖“最近一次输出”，当未来支持批量表、并行工具、多个任务草稿时，容易保存错对象。
     * 当前仍保留回退能力，是为了兼容 Python Runtime 尚未完全生成 `taskDraftRef` 的阶段。</p>
     */
    private Map<String, Object> resolveTaskDraft(AgentToolExecutionContext context, Map<String, Object> arguments) {
        Object value = arguments.get("taskDraft");
        if (!(value instanceof Map<?, ?>)) {
            Object reference = firstNonNull(arguments.get("taskDraftRef"),
                    firstNonNull(arguments.get("draftRef"), arguments.get("outputRef")));
            value = outputReferenceResolver
                    .resolve(context, reference, TASK_CREATE_DRAFT_TOOL_CODE, "taskDraft")
                    .orElse(null);
        }
        if (!(value instanceof Map<?, ?> rawMap)) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "执行 task.draft.persist 必须提供 taskDraft 或 taskDraftRef，不能保存空草稿");
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
            copy.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return copy;
    }

    /**
     * 序列化任务参数。
     *
     * <p>task-management 当前用字符串字段保存 params，原因是不同任务类型的参数差异很大。
     * 在 agent-runtime 侧如果收到 Map/List，就统一序列化为 JSON 字符串；如果已经是字符串，则原样传递。
     * 后续等 DATA_QUALITY_SCAN、DATA_SYNC、MANUAL_REVIEW 的 schema 稳定后，可以在这里增加类型级参数校验。</p>
     */
    private String serializeParams(Object params) {
        if (params == null) {
            return "{}";
        }
        if (params instanceof String text) {
            return text.isBlank() ? "{}" : text;
        }
        try {
            return objectMapper.writeValueAsString(params);
        } catch (JsonProcessingException ex) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "任务草稿 params 无法序列化为 JSON: " + ex.getMessage());
        }
    }

    private String sourceRef(AgentToolExecutionContext context, Map<String, Object> arguments) {
        String explicit = stringValue(firstNonNull(arguments.get("sourceRef"), arguments.get("sourceAuditId")));
        if (explicit != null) {
            return explicit;
        }
        return context.audit().getAuditId();
    }

    private String normalizePriority(Object value) {
        String priority = stringValue(value);
        if (priority == null) {
            return DEFAULT_PRIORITY;
        }
        String normalized = priority.toUpperCase(Locale.ROOT);
        if ("HIGH".equals(normalized) || "MEDIUM".equals(normalized) || "LOW".equals(normalized)) {
            return normalized;
        }
        return DEFAULT_PRIORITY;
    }

    private Integer boundedInteger(Object value, int defaultValue, int minValue, int maxValue) {
        int resolved = defaultValue;
        if (value instanceof Number number) {
            resolved = number.intValue();
        } else if (value instanceof String text && !text.isBlank()) {
            try {
                resolved = Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                resolved = defaultValue;
            }
        }
        return Math.max(minValue, Math.min(resolved, maxValue));
    }

    private String requiredText(Object value, String message) {
        String text = stringValue(value);
        if (text == null) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST, message);
        }
        return text;
    }

    private Long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        String text = stringValue(value);
        if (text == null) {
            return null;
        }
        try {
            return Long.valueOf(text);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Object firstNonNull(Object first, Object second) {
        return first != null ? first : second;
    }

    private String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isBlank() ? null : text;
    }
}
