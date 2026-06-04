/**
 * @Author : Cui
 * @Date: 2026/05/27 21:20
 * @Description DataSmart Govern Backend - AgentRuntimeEventVisibilitySupport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Agent runtime event 可见性与字段脱敏支撑类。
 *
 * <p>3.89 阶段已经解决“用户只能查到自己数据范围内的事件”这个问题；但真实商业产品还需要继续回答另一个问题：
 * 用户即使有权访问某条事件，是否也应该看到事件里的全部细节？Agent 事件中可能包含 prompt、工具参数、
 * SQL、API Token、异常堆栈、原始输入输出、模型中间推理片段等敏感信息。如果只做路由级 RBAC 或项目级数据范围，
 * 项目负责人、普通用户和审计员仍可能看到超出职责所需的技术细节。</p>
 *
 * <p>因此该类把“事件是否可见”和“字段是否脱敏”从 QueryService 中拆出来，避免查询服务变成巨型 Impl。
 * 当前版本先用代码内置策略固定产品语义，后续可以平滑迁移为数据库策略、租户级配置或 permission-admin 下发的
 * 字段级权限矩阵。</p>
 */
@Component
public class AgentRuntimeEventVisibilitySupport {

    /**
     * 脱敏占位符。
     *
     * <p>这里故意使用稳定英文常量，而不是中文说明，是为了便于前端、日志系统和自动化测试做精确匹配。
     * 前端展示时可以再把它翻译为“已脱敏”。</p>
     */
    public static final String MASKED_VALUE = "***MASKED***";

    /**
     * 脱敏元信息字段。
     *
     * <p>为了暂时不破坏已有 API DTO 结构，本阶段不新增 `maskedFields` 顶层字段，而是把脱敏说明写入 attributes
     * 的保留键中。这样前端仍能通过同一个 attributes 对象知道哪些字段被处理过，后续如果 API 契约稳定下来，
     * 可以再把它提升为 DTO 顶层字段。</p>
     */
    public static final String MASKED_FIELDS_ATTRIBUTE = "_datasmartMaskedFields";

    /**
     * 当前策略等级元信息字段。
     *
     * <p>该字段用于排障和前端提示：当用户认为“为什么我看不到完整字段”时，控制台可以显示当前按 AUDIT、
     * PROJECT 或 BASIC 策略返回，而不是让用户误以为后端丢数据。</p>
     */
    public static final String VISIBILITY_LEVEL_ATTRIBUTE = "_datasmartVisibilityLevel";

    /**
     * 对敏感字段名做启发式识别的正则。
     *
     * <p>Agent 事件 attributes 当前仍是自由 Map，不同工具或模型运行时可能提交不同 key。生产上最理想的是
     * 由工具 schema 明确标注字段 `sensitive=true`，但在 schema 完全统一前，服务端仍需要有保守兜底。
     * 这里覆盖 token、secret、prompt、SQL、原始输入输出、异常堆栈、样本数据等常见风险字段。</p>
     */
    private static final Pattern SENSITIVE_KEY_PATTERN = Pattern.compile(
            ".*(password|passwd|secret|token|authorization|api[-_]?key|credential|prompt|systemprompt|userprompt|"
                    + "sql|query|sample|row|stack|trace|exception|raw|payload|input|output|embedding|vector|"
                    + "kv[-_]?cache|memory|cookie|session[-_]?key).*",
            Pattern.CASE_INSENSITIVE
    );

    /**
     * PROJECT 级角色不直接展示的事件类型片段。
     *
     * <p>项目负责人通常需要看到 Agent 是否调用了工具、是否失败、是否需要审批；但不一定应该看到模型 prompt、
     * 原始上下文、token 流、内部调试事件或 KV cache 细节。把这类事件直接过滤掉，比只脱敏字段更安全。</p>
     */
    private static final Set<String> PROJECT_HIDDEN_EVENT_MARKERS = Set.of(
            "prompt",
            "memory_raw",
            "raw_context",
            "token_stream",
            "debug",
            "internal",
            "kv_cache"
    );

    /**
     * AUDIT 级角色不直接展示的事件类型片段。
     *
     * <p>审计员需要追踪谁在什么时候触发了什么动作、是否经过审批、是否发生异常；但审计通常不需要还原模型的
     * 逐 token 输出或完整 prompt 快照。这里保持审计视角足够宽，同时过滤最容易泄露敏感上下文的事件。</p>
     */
    private static final Set<String> AUDIT_HIDDEN_EVENT_MARKERS = Set.of(
            "token_stream",
            "debug_chunk",
            "model_prompt",
            "prompt_snapshot",
            "memory_raw",
            "raw_context",
            "kv_cache"
    );

    /**
     * BASIC 级用户允许看到的事件类型片段。
     *
     * <p>普通用户主要关心“任务有没有开始、是否完成、是否失败、是否需要我审批、工具是否进入计划/完成状态”。
     * 他们不应看到大量内部推理与工具参数细节，所以这里采用白名单，而不是黑名单。</p>
     */
    private static final Set<String> BASIC_VISIBLE_EVENT_MARKERS = Set.of(
            "started",
            "completed",
            "failed",
            "cancelled",
            "canceled",
            "progress",
            "status",
            "approval_required",
            "approval_waiting",
            "skill_visibility_snapshot_recorded",
            "tool_execution",
            "tool_planned",
            "tool_completed",
            "run_started",
            "run_completed",
            "run_failed"
    );

    /**
     * 根据访问上下文过滤事件集合。
     *
     * <p>该方法只做“条目级可见性”判断，不做字段脱敏。拆成两步是为了让调用链更清晰：
     * 1. AccessSupport 先做租户/项目/本人数据范围收口；
     * 2. VisibilitySupport 再按角色和事件类型过滤；
     * 3. QueryService 最后把剩余事件转换成 API 视图并做字段脱敏。</p>
     *
     * @param records 已经通过数据范围过滤的事件列表
     * @param accessContext 当前访问上下文，主要使用角色决定可见级别
     * @return 当前角色允许看到的事件列表
     */
    public List<AgentRuntimeEventProjectionRecord> filterVisibleRecords(List<AgentRuntimeEventProjectionRecord> records,
                                                                        AgentRuntimeEventQueryAccessContext accessContext) {
        VisibilityLevel level = resolveLevel(accessContext);
        return records.stream()
                .filter(record -> isEventVisible(record, level))
                .toList();
    }

    /**
     * 对事件记录做字段级脱敏。
     *
     * <p>脱敏只应用于 HTTP 安全入口。内部系统任务、诊断测试或未来持久化写入仍可以使用原始投影记录，
     * 否则会把脱敏后的数据误写回审计事实，导致平台管理员也无法还原问题。</p>
     *
     * @param record 原始事件投影记录
     * @param accessContext 当前访问上下文
     * @return 按角色策略处理后的只读投影记录
     */
    public AgentRuntimeEventProjectionRecord maskForAccess(AgentRuntimeEventProjectionRecord record,
                                                           AgentRuntimeEventQueryAccessContext accessContext) {
        VisibilityLevel level = resolveLevel(accessContext);
        if (level == VisibilityLevel.FULL) {
            return record;
        }

        MaskingResult maskingResult = maskAttributes(record.attributes(), level);
        String visibleStage = maskTextWhenNeeded(record.stage(), record.eventType(), level);
        String visibleMessage = maskTextWhenNeeded(record.message(), record.eventType(), level);

        return new AgentRuntimeEventProjectionRecord(
                record.identityKey(),
                record.schemaVersion(),
                record.source(),
                record.eventType(),
                visibleStage,
                visibleMessage,
                record.severity(),
                record.tenantId(),
                record.projectId(),
                record.actorId(),
                record.requestId(),
                record.runId(),
                record.sessionId(),
                record.sequence(),
                record.replaySequence(),
                record.createdAt(),
                record.publishedAt(),
                record.consumedAt(),
                maskingResult.attributes()
        );
    }

    private boolean isEventVisible(AgentRuntimeEventProjectionRecord record, VisibilityLevel level) {
        String eventType = normalize(record.eventType());
        if (level == VisibilityLevel.FULL) {
            return true;
        }
        if (level == VisibilityLevel.AUDIT) {
            return AUDIT_HIDDEN_EVENT_MARKERS.stream().noneMatch(eventType::contains);
        }
        if (level == VisibilityLevel.PROJECT) {
            return PROJECT_HIDDEN_EVENT_MARKERS.stream().noneMatch(eventType::contains);
        }
        return BASIC_VISIBLE_EVENT_MARKERS.stream().anyMatch(eventType::contains);
    }

    private MaskingResult maskAttributes(Map<String, Object> attributes, VisibilityLevel level) {
        if (attributes == null || attributes.isEmpty()) {
            return new MaskingResult(Map.of(VISIBILITY_LEVEL_ATTRIBUTE, level.name()), List.of());
        }

        Map<String, Object> masked = new LinkedHashMap<>();
        List<String> maskedFields = new ArrayList<>();
        for (Map.Entry<String, Object> entry : attributes.entrySet()) {
            String key = entry.getKey();
            if (isSensitiveKey(key) || shouldHideAllDetails(level)) {
                masked.put(key, MASKED_VALUE);
                maskedFields.add(key);
            } else {
                masked.put(key, maskNestedValue(key, entry.getValue(), maskedFields, 0));
            }
        }
        if (!maskedFields.isEmpty()) {
            masked.put(MASKED_FIELDS_ATTRIBUTE, List.copyOf(maskedFields));
        }
        masked.put(VISIBILITY_LEVEL_ATTRIBUTE, level.name());
        /*
         * 不使用 Map.copyOf，是因为第三方工具上报的 attributes 可能包含 null 值。
         * Map.copyOf 会在遇到 null 时抛出 NPE，反而让“展示事件详情”影响主查询可用性；
         * unmodifiableMap 可以保持只读语义，同时兼容更宽松的 JSON payload。
         */
        return new MaskingResult(Collections.unmodifiableMap(masked), List.copyOf(maskedFields));
    }

    @SuppressWarnings("unchecked")
    private Object maskNestedValue(String path, Object value, List<String> maskedFields, int depth) {
        if (value == null || depth >= 4) {
            return value;
        }
        if (value instanceof Map<?, ?> nestedMap) {
            Map<String, Object> masked = new LinkedHashMap<>();
            for (Map.Entry<?, ?> nestedEntry : nestedMap.entrySet()) {
                String nestedKey = Objects.toString(nestedEntry.getKey(), "");
                String nestedPath = path + "." + nestedKey;
                if (isSensitiveKey(nestedKey)) {
                    masked.put(nestedKey, MASKED_VALUE);
                    maskedFields.add(nestedPath);
                } else {
                    masked.put(nestedKey, maskNestedValue(nestedPath, nestedEntry.getValue(), maskedFields, depth + 1));
                }
            }
            return Collections.unmodifiableMap(masked);
        }
        if (value instanceof List<?> list) {
            return list.stream()
                    .map(item -> maskNestedValue(path + "[]", item, maskedFields, depth + 1))
                    .toList();
        }
        return value;
    }

    private boolean shouldHideAllDetails(VisibilityLevel level) {
        return level == VisibilityLevel.BASIC;
    }

    private String maskTextWhenNeeded(String value, String eventType, VisibilityLevel level) {
        if (value == null || value.isBlank()) {
            return value;
        }
        if (level == VisibilityLevel.BASIC || isSensitiveEventType(eventType)) {
            return "事件详情已按当前角色权限脱敏";
        }
        return value;
    }

    private boolean isSensitiveEventType(String eventType) {
        String normalizedEventType = normalize(eventType);
        return PROJECT_HIDDEN_EVENT_MARKERS.stream().anyMatch(normalizedEventType::contains)
                || AUDIT_HIDDEN_EVENT_MARKERS.stream().anyMatch(normalizedEventType::contains);
    }

    private boolean isSensitiveKey(String key) {
        return key != null && SENSITIVE_KEY_PATTERN.matcher(key).matches();
    }

    private VisibilityLevel resolveLevel(AgentRuntimeEventQueryAccessContext accessContext) {
        if (accessContext == null) {
            return VisibilityLevel.FULL;
        }
        return switch (accessContext.normalizedRole()) {
            case "PLATFORM_ADMINISTRATOR", "PLATFORM_ADMIN", "TENANT_ADMIN", "OPERATOR", "SERVICE_ACCOUNT" ->
                    VisibilityLevel.FULL;
            case "AUDITOR" -> VisibilityLevel.AUDIT;
            case "PROJECT_OWNER" -> VisibilityLevel.PROJECT;
            default -> VisibilityLevel.BASIC;
        };
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private record MaskingResult(Map<String, Object> attributes, List<String> maskedFields) {
    }

    /**
     * 事件细节可见级别。
     *
     * <p>枚举顺序从“最完整”到“最保守”。这里不是权限角色本身，而是角色映射后的事件细节策略。
     * 例如平台管理员和运维员都可以映射到 FULL，而项目负责人映射到 PROJECT。</p>
     */
    private enum VisibilityLevel {
        FULL,
        AUDIT,
        PROJECT,
        BASIC
    }
}
