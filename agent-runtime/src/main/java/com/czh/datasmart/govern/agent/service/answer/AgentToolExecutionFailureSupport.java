/**
 * @Author : Cui
 * @Date: 2026/07/31 00:00
 * @Description DataSmart Govern Backend - AgentToolExecutionFailureSupport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.answer;

import com.czh.datasmart.govern.agent.controller.dto.AgentToolExecutionAuditView;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolExecutionFailureView;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolExecutionResultView;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 从 Java 控制面的审计和结构化工具输出中提炼用户可理解的失败事实。
 *
 * <p>工具输出可能包含元数据、样本或下游响应，因此这里不把整个 Map 直接提升到普通错误弹窗。
 * 只读取约定的诊断字段，并限制条数和单条长度；完整受治理输出仍保留在工具结果和审计详情中。</p>
 */
public final class AgentToolExecutionFailureSupport {

    private static final int MAX_ITEMS_PER_SECTION = 12;
    private static final int MAX_ITEM_LENGTH = 360;
    private static final Set<String> DETAIL_KEYS = Set.of(
            "detail", "details", "reason", "reasons", "issue", "issues", "issuecodes",
            "fielderrors", "validationerrors", "errors", "missingparameters", "conflicts", "failedchecks"
    );
    private static final Set<String> SUGGESTION_KEYS = Set.of(
            "suggestion", "suggestions", "recommendedaction", "recommendedactions", "recommendation",
            "recommendations", "nextaction", "nextactions", "resolution", "resolutions", "solutions"
    );

    private AgentToolExecutionFailureSupport() {
    }

    /**
     * 生成本批次全部 FAILED 节点的稳定详情，按审计顺序保留且按 auditId 去重。
     */
    public static List<AgentToolExecutionFailureView> failures(
            List<AgentToolExecutionAuditView> audits,
            List<AgentToolExecutionResultView> results) {
        Map<String, Map<String, Object>> outputsByAuditId = new LinkedHashMap<>();
        if (results != null) {
            for (AgentToolExecutionResultView result : results) {
                if (result != null && result.audit() != null && result.audit().auditId() != null) {
                    outputsByAuditId.put(result.audit().auditId(), result.output());
                }
            }
        }

        LinkedHashMap<String, AgentToolExecutionAuditView> failedAudits = new LinkedHashMap<>();
        addFailedAudits(failedAudits, audits);
        if (results != null) {
            addFailedAudits(failedAudits, results.stream()
                    .filter(Objects::nonNull)
                    .map(AgentToolExecutionResultView::audit)
                    .toList());
        }

        return failedAudits.values().stream()
                .map(audit -> toFailure(audit, outputsByAuditId.get(audit.auditId())))
                .toList();
    }

    /**
     * 将结构化失败详情压缩成兜底回答。模型不可用时，用户仍能直接看到具体原因和解决方向。
     */
    public static String assistantSummary(List<AgentToolExecutionFailureView> failures) {
        if (failures == null || failures.isEmpty()) {
            return "工具执行失败，但控制面没有取得具体失败详情。请使用 Run ID 查询审计记录后再重试。";
        }
        List<String> summaries = failures.stream().limit(3).map(failure -> {
            String errorCode = text(failure.errorCode(), "UNCLASSIFIED_TOOL_FAILURE");
            String message = text(failure.message(), "工具未返回具体错误说明");
            String detail = failure.details().isEmpty() ? "" : "；问题项：" + String.join("、", failure.details());
            String suggestion = failure.suggestions().isEmpty()
                    ? ""
                    : "；建议：" + String.join("、", failure.suggestions());
            return failure.toolCode() + "（" + errorCode + "）：" + message + detail + suggestion;
        }).toList();
        return "具体失败原因：" + String.join("；", summaries) + "。";
    }

    private static void addFailedAudits(
            Map<String, AgentToolExecutionAuditView> target,
            List<AgentToolExecutionAuditView> audits) {
        if (audits == null) {
            return;
        }
        for (AgentToolExecutionAuditView audit : audits) {
            if (audit != null && "FAILED".equalsIgnoreCase(audit.state())) {
                target.putIfAbsent(text(audit.auditId(), audit.toolCode()), audit);
            }
        }
    }

    private static AgentToolExecutionFailureView toFailure(
            AgentToolExecutionAuditView audit,
            Map<String, Object> output) {
        List<String> details = extract(output, DETAIL_KEYS);
        List<String> suggestions = extract(output, SUGGESTION_KEYS);
        if (isDuplicateTaskNameFailure(audit) && details.isEmpty()) {
            details = List.of("当前项目已存在同名同步任务，本次草稿没有保存，后续发布和运行也没有发生");
        }
        if (suggestions.isEmpty()) {
            suggestions = List.of(defaultSuggestion(audit.errorCode(), audit.toolCode(), audit.message()));
        }
        return new AgentToolExecutionFailureView(
                audit.auditId(),
                text(audit.toolCode(), "unknown.tool"),
                text(audit.errorCode(), "UNCLASSIFIED_TOOL_FAILURE"),
                text(audit.message(), "工具执行失败，但未返回具体错误说明"),
                audit.outputSummary(),
                details,
                suggestions
        );
    }

    private static List<String> extract(Map<String, Object> output, Set<String> acceptedKeys) {
        if (output == null || output.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> values = new LinkedHashSet<>();
        collectAccepted(output, acceptedKeys, values, 0);
        return values.stream().limit(MAX_ITEMS_PER_SECTION).toList();
    }

    private static void collectAccepted(
            Map<?, ?> source,
            Set<String> acceptedKeys,
            LinkedHashSet<String> target,
            int depth) {
        if (depth > 3 || target.size() >= MAX_ITEMS_PER_SECTION) {
            return;
        }
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            String key = Objects.toString(entry.getKey(), "").replace("_", "")
                    .toLowerCase(Locale.ROOT);
            Object value = entry.getValue();
            if (acceptedKeys.contains(key)) {
                flatten(value, target, depth + 1);
            } else if (value instanceof Map<?, ?> nested) {
                collectAccepted(nested, acceptedKeys, target, depth + 1);
            }
            if (target.size() >= MAX_ITEMS_PER_SECTION) {
                return;
            }
        }
    }

    private static void flatten(Object value, LinkedHashSet<String> target, int depth) {
        if (value == null || depth > 4 || target.size() >= MAX_ITEMS_PER_SECTION) {
            return;
        }
        if (value instanceof Collection<?> collection) {
            for (Object item : collection) {
                flatten(item, target, depth + 1);
            }
            return;
        }
        if (value instanceof Map<?, ?> map) {
            List<String> parts = new ArrayList<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (parts.size() >= 6) {
                    break;
                }
                parts.add(Objects.toString(entry.getKey(), "字段") + "=" + compact(entry.getValue()));
            }
            add(target, String.join("，", parts));
            return;
        }
        add(target, Objects.toString(value, ""));
    }

    private static void add(LinkedHashSet<String> target, String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            return;
        }
        target.add(normalized.length() > MAX_ITEM_LENGTH
                ? normalized.substring(0, MAX_ITEM_LENGTH) + "..."
                : normalized);
    }

    private static String compact(Object value) {
        if (value instanceof Collection<?> collection) {
            return collection.stream().limit(5).map(Objects::toString).toList().toString();
        }
        return Objects.toString(value, "");
    }

    private static String defaultSuggestion(String errorCode, String toolCode, String message) {
        String normalized = (text(errorCode, "") + " " + text(message, "")).toUpperCase(Locale.ROOT);
        if (normalized.contains("DUPLICATE_OPERATION")
                && (normalized.contains("TASK") || normalized.contains("任务"))) {
            return "Agent 可以建议一个带唯一后缀的新任务名称；保存、预检查、发布和执行前会展示原名称与新名称并再次等待确认";
        }
        if (normalized.contains("PERMISSION") || normalized.contains("FORBIDDEN")
                || normalized.contains("UNAUTHORIZED")) {
            return "检查当前项目角色和数据源授权；Agent 只能在获得授权后重新执行该动作";
        }
        if (normalized.contains("NOT_FOUND") || normalized.contains("MISSING")) {
            return "重新读取真实数据源和元数据，选择实际存在的 schema、表或任务后再生成修复方案";
        }
        if (normalized.contains("PRECHECK") || normalized.contains("VALIDATION")
                || normalized.contains("BAD_REQUEST") || normalized.contains("MAPPING")) {
            return "根据问题项返回对应配置步骤修正；修正后的写操作必须重新预览并确认";
        }
        if (normalized.contains("TIMEOUT") || normalized.contains("DOWNSTREAM")
                || normalized.contains("UNAVAILABLE")) {
            return "先用只读连接和服务健康检查确认依赖恢复，再决定是否重试失败节点";
        }
        if (toolCode != null && toolCode.startsWith("sync.")) {
            return "Agent 将结合任务配置、预检查或运行日志继续诊断；任何数据或任务修改都会再次请求确认";
        }
        return "Agent 将先执行只读诊断；如果需要修改业务状态，会展示修复方案并重新请求确认";
    }

    private static boolean isDuplicateTaskNameFailure(AgentToolExecutionAuditView audit) {
        String evidence = (text(audit.errorCode(), "") + " " + text(audit.message(), ""))
                .toUpperCase(Locale.ROOT);
        return "sync.task.draft.save".equals(audit.toolCode())
                && evidence.contains("DUPLICATE_OPERATION");
    }

    private static String text(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
