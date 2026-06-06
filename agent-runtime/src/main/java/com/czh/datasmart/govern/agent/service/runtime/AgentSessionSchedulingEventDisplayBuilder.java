/**
 * @Author : Cui
 * @Date: 2026/06/06 00:00
 * @Description DataSmart Govern Backend - AgentSessionSchedulingEventDisplayBuilder.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.controller.dto.AgentRuntimeEventDisplayView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 多 Agent 会话调度事件的展示解释构建器。
 *
 * <p>Python Runtime 会把 `agentSessionScheduling` 压缩为 `agent_session_scheduling_recorded` runtime event。
 * Java projection 查询可以返回强类型 DTO；而 WebSocket timeline、HTTP replay 和通用事件列表还需要一个
 * “人能直接看懂”的 display 解释。本类专门负责这层解释，避免继续把逻辑塞进已经接近 500 行的
 * `AgentRuntimeEventDisplaySupport`。</p>
 *
 * <p>展示层仍然遵守低敏原则：只展示调度状态、参与 Agent 计数、handoff、A2A planning mode/state 和
 * guardrail 计数，不展示 task id、prompt、工具参数、SQL、artifact 正文、模型输出或内部 endpoint。</p>
 */
final class AgentSessionSchedulingEventDisplayBuilder {

    private static final String REPLAY_POLICY_APPEND_AND_ACK = "APPEND_TO_TIMELINE_AND_ALLOW_ACK_CURSOR";

    private AgentSessionSchedulingEventDisplayBuilder() {
    }

    static AgentRuntimeEventDisplayView build(AgentRuntimeEventProjectionRecord record) {
        Map<String, Object> attributes = safeAttributes(record);
        if (isBasicMasked(attributes)) {
            return new AgentRuntimeEventDisplayView(
                    "AGENT_SESSION_SCHEDULING",
                    "多 Agent 会话调度已记录",
                    "当前角色只能查看脱敏后的会话调度进度，可联系项目负责人或审计员查看 Agent 角色和 A2A 状态摘要。",
                    "SUMMARY_MASKED",
                    "agent-session",
                    false,
                    REPLAY_POLICY_APPEND_AND_ACK,
                    List.of("如需排查 handoff、权限等待或 A2A task 诊断，请使用具备项目或审计数据范围的账号查看详情。"),
                    Map.of("detailsMasked", true)
            );
        }

        String schedulingStatus = defaultedText(attributes, "status", "UNKNOWN");
        String a2aMode = textAttribute(attributes, "a2aTaskPlanningMode");
        String a2aState = textAttribute(attributes, "a2aTaskState");
        boolean handoffRequired = booleanAttribute(attributes, "handoffRequired");
        boolean a2aAvailable = booleanAttribute(attributes, "a2aTaskPlanningAvailable");

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("schedulingStatus", schedulingStatus);
        metrics.put("participatingAgentCount", intAttribute(attributes, "participatingAgentCount"));
        metrics.put("handoffRequired", handoffRequired);
        metrics.put("handoffAgentRoleCount", listSize(attributes.get("handoffAgentRoles")));
        metrics.put("plannedToolNameCount", listSize(attributes.get("plannedToolNames")));
        metrics.put("selectedSkillCodeCount", listSize(attributes.get("selectedSkillCodes")));
        metrics.put("a2aTaskPlanningAvailable", a2aAvailable);
        putIfPresent(metrics, "a2aTaskPlanningMode", a2aMode);
        putIfPresent(metrics, "a2aTaskState", a2aState);
        putIfPresent(metrics, "a2aTaskInternalPhase", textAttribute(attributes, "a2aTaskInternalPhase"));
        metrics.put("a2aTaskShouldWaitForHuman", booleanAttribute(attributes, "a2aTaskShouldWaitForHuman"));
        metrics.put("a2aTaskGuardrailCodeCount", listSize(attributes.get("a2aTaskGuardrailCodes")));
        metrics.put("a2aTaskSuggestedActionCount", listSize(attributes.get("a2aTaskSuggestedActions")));
        metrics.put("a2aTaskSensitiveFieldIgnoredCount", intAttribute(attributes, "a2aTaskSensitiveFieldIgnoredCount"));

        return new AgentRuntimeEventDisplayView(
                "AGENT_SESSION_SCHEDULING",
                title(schedulingStatus, a2aMode, handoffRequired),
                summary(schedulingStatus, a2aMode, a2aState, attributes),
                status(schedulingStatus, a2aMode),
                "agent-session",
                requiresAttention(schedulingStatus, a2aMode, handoffRequired),
                REPLAY_POLICY_APPEND_AND_ACK,
                recommendedActions(schedulingStatus, a2aMode, a2aAvailable),
                Collections.unmodifiableMap(metrics)
        );
    }

    private static String title(String schedulingStatus, String a2aMode, boolean handoffRequired) {
        String mode = normalizeCode(a2aMode);
        if ("WAIT_FOR_AUTHORIZATION".equals(mode)) {
            return "A2A 任务等待授权";
        }
        if ("WAIT_FOR_USER_INPUT".equals(mode)) {
            return "A2A 任务等待用户输入";
        }
        if ("REJECTED_OR_DIAGNOSTIC".equals(mode)) {
            return "A2A 任务进入诊断阻断";
        }
        if (handoffRequired) {
            return "多 Agent 会话需要 handoff";
        }
        if ("BLOCKED".equals(normalizeCode(schedulingStatus))) {
            return "多 Agent 会话调度被阻断";
        }
        return "多 Agent 会话调度已记录";
    }

    private static String summary(String schedulingStatus,
                                  String a2aMode,
                                  String a2aState,
                                  Map<String, Object> attributes) {
        StringBuilder builder = new StringBuilder();
        builder.append("调度状态：").append(schedulingStatus)
                .append("，参与 Agent 数：").append(intAttribute(attributes, "participatingAgentCount"))
                .append("，handoff：").append(booleanAttribute(attributes, "handoffRequired"));
        if (!a2aMode.isBlank()) {
            builder.append("，A2A mode：").append(a2aMode);
        }
        if (!a2aState.isBlank()) {
            builder.append("，A2A state：").append(a2aState);
        }
        builder.append("。");
        return builder.toString();
    }

    private static String status(String schedulingStatus, String a2aMode) {
        String mode = normalizeCode(a2aMode);
        if ("WAIT_FOR_AUTHORIZATION".equals(mode)) {
            return "A2A_WAITING_AUTHORIZATION";
        }
        if ("WAIT_FOR_USER_INPUT".equals(mode)) {
            return "A2A_WAITING_USER_INPUT";
        }
        if ("REJECTED_OR_DIAGNOSTIC".equals(mode)) {
            return "A2A_DIAGNOSTIC_BLOCKED";
        }
        if ("PRECHECK_REQUIRED".equals(mode)) {
            return "A2A_PRECHECK_REQUIRED";
        }
        return normalizeCode(schedulingStatus).isBlank() ? "UNKNOWN" : normalizeCode(schedulingStatus);
    }

    private static boolean requiresAttention(String schedulingStatus, String a2aMode, boolean handoffRequired) {
        String normalizedStatus = normalizeCode(schedulingStatus);
        String mode = normalizeCode(a2aMode);
        return handoffRequired
                || "BLOCKED".equals(normalizedStatus)
                || "DEGRADED".equals(normalizedStatus)
                || "APPROVAL_REQUIRED".equals(normalizedStatus)
                || "WAIT_FOR_AUTHORIZATION".equals(mode)
                || "WAIT_FOR_USER_INPUT".equals(mode)
                || "REJECTED_OR_DIAGNOSTIC".equals(mode);
    }

    private static List<String> recommendedActions(String schedulingStatus,
                                                   String a2aMode,
                                                   boolean a2aAvailable) {
        List<String> actions = new ArrayList<>();
        String mode = normalizeCode(a2aMode);
        if ("WAIT_FOR_AUTHORIZATION".equals(mode)) {
            actions.add("等待 permission-admin、审批单或租户能力包返回新的授权事实，不要让 Agent 自行推进 worker。");
        } else if ("WAIT_FOR_USER_INPUT".equals(mode)) {
            actions.add("让 Master Agent 生成低敏追问，等待用户补充输入后再提交新的 continuation。");
        } else if ("REJECTED_OR_DIAGNOSTIC".equals(mode)) {
            actions.add("优先检查 A2A 协议版本、Java task fact 和 gateway adapter，未知状态必须 fail-closed。");
        } else if ("PRECHECK_REQUIRED".equals(mode)) {
            actions.add("先完成权限、幂等、租户配额和 worker pre-check，再考虑任何副作用执行。");
        } else if (a2aAvailable) {
            actions.add("结合 A2A task history 与 artifact metadata 判断是否只展示终态或规划 worker pre-check。");
        }
        if ("BLOCKED".equals(normalizeCode(schedulingStatus))) {
            actions.add("调度已阻断，应先恢复模型网关、权限、预算或协议状态，再允许下一步自动化。");
        }
        if (actions.isEmpty()) {
            actions.add("可将该调度事件与 Skill 可见性、模型路由和 handoff DAG 一起用于会话治理回放。");
        }
        return List.copyOf(actions);
    }

    private static Map<String, Object> safeAttributes(AgentRuntimeEventProjectionRecord record) {
        if (record.attributes() == null || record.attributes().isEmpty()) {
            return Map.of();
        }
        return record.attributes();
    }

    private static boolean isBasicMasked(Map<String, Object> attributes) {
        Object visibilityLevel = attributes.get(AgentRuntimeEventVisibilitySupport.VISIBILITY_LEVEL_ATTRIBUTE);
        return "BASIC".equals(Objects.toString(visibilityLevel, ""));
    }

    private static int intAttribute(Map<String, Object> attributes, String key) {
        Object value = attributes.get(key);
        if (value instanceof Number number) {
            return Math.max(0, number.intValue());
        }
        if (value instanceof String stringValue) {
            try {
                return Math.max(0, Integer.parseInt(stringValue));
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private static boolean booleanAttribute(Map<String, Object> attributes, String key) {
        Object value = attributes.get(key);
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        if (value == null) {
            return false;
        }
        return switch (Objects.toString(value, "").trim().toLowerCase(Locale.ROOT)) {
            case "1", "true", "yes", "on", "enabled" -> true;
            default -> false;
        };
    }

    private static int listSize(Object value) {
        if (value instanceof List<?> list) {
            return list.size();
        }
        return 0;
    }

    private static String textAttribute(Map<String, Object> attributes, String key) {
        Object value = attributes == null ? null : attributes.get(key);
        return value == null ? "" : Objects.toString(value, "").trim();
    }

    private static String defaultedText(Map<String, Object> attributes, String key, String defaultValue) {
        String value = textAttribute(attributes, key);
        return value.isBlank() ? defaultValue : value;
    }

    private static void putIfPresent(Map<String, Object> values, String key, String value) {
        if (value != null && !value.isBlank()) {
            values.put(key, value);
        }
    }

    private static String normalizeCode(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT).replace("-", "_").replace(" ", "_");
    }
}
