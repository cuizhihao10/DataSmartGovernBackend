/**
 * @Author : Cui
 * @Date: 2026/06/07 13:39
 * @Description DataSmart Govern Backend - AgentToolActionIntakeEventDisplayBuilder.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.controller.dto.AgentRuntimeEventDisplayView;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 工具动作入口事件展示构建器。
 *
 * <p>`tool_action_intake_recorded` 表示外部工具动作意图已经进入 DataSmart host-level intake 治理。
 * 该事件不代表工具执行成功，也不代表 outbox/审批已经创建；它只说明平台已经完成“入口识别、工具可见性、
 * readiness 预检和执行前图分支”这一步。</p>
 *
 * <p>展示层只返回低敏计数和状态，不展开 arguments、prompt、SQL、payload 或完整 decision summaries。
 * 真正的自动化判断仍应使用 eventType、attributes 稳定字段和 permission-admin 策略，而不是 display 文案。</p>
 */
final class AgentToolActionIntakeEventDisplayBuilder {

    private AgentToolActionIntakeEventDisplayBuilder() {
    }

    /**
     * 把低敏入口事件转换为时间线卡片。
     *
     * <p>状态选择遵循“风险优先”顺序：readiness 前拒绝优先级最高，其次是阻断、审批、澄清、限流，最后才是可执行。
     * 这样管理台不会因为同一个事件里存在少量可执行工具，就忽略另一部分已被拒绝或需要人工处理的工具。</p>
     */
    static AgentRuntimeEventDisplayView build(AgentRuntimeEventProjectionRecord record) {
        Map<String, Object> attributes = safeAttributes(record);
        int acceptedCount = intAttribute(attributes, "acceptedToolPlanCount");
        int rejectedCount = intAttribute(attributes, "rejectedBeforeReadinessCount");
        int executableCount = intAttribute(attributes, "readinessExecutableCount");
        int approvalCount = intAttribute(attributes, "readinessApprovalRequiredCount");
        int clarificationCount = intAttribute(attributes, "readinessClarificationRequiredCount");
        int blockedCount = intAttribute(attributes, "readinessBlockedCount");
        int draftCount = intAttribute(attributes, "readinessDraftOnlyCount");
        int throttledCount = intAttribute(attributes, "readinessThrottledCount");
        String status = status(rejectedCount, executableCount, approvalCount, clarificationCount, blockedCount, throttledCount);

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("acceptedToolPlanCount", acceptedCount);
        metrics.put("rejectedBeforeReadinessCount", rejectedCount);
        metrics.put("readinessExecutableCount", executableCount);
        metrics.put("readinessApprovalRequiredCount", approvalCount);
        metrics.put("readinessClarificationRequiredCount", clarificationCount);
        metrics.put("readinessDraftOnlyCount", draftCount);
        metrics.put("readinessBlockedCount", blockedCount);
        metrics.put("readinessThrottledCount", throttledCount);
        metrics.put("toolNameCount", listSize(attributes.get("toolNames")));
        metrics.put("issueCodeCount", listSize(attributes.get("issueCodes")));
        metrics.put("nextActionCount", listSize(attributes.get("readinessNextActions")));
        metrics.put("graphBranchCount", mapSize(attributes.get("graphBranchCounts")));
        metrics.put("graphToolExecuted", boolAttribute(attributes, "graphToolExecuted"));
        metrics.put("graphOutboxWritten", boolAttribute(attributes, "graphOutboxWritten"));
        metrics.put("graphApprovalCreated", boolAttribute(attributes, "graphApprovalCreated"));
        metrics.put("productionReadyForExecution", boolAttribute(attributes, "productionReadyForExecution"));
        putIfPresent(metrics, "protocolFamily", textAttribute(attributes, "protocolFamily"));
        putIfPresent(metrics, "intakeSource", textAttribute(attributes, "source"));

        return new AgentRuntimeEventDisplayView(
                "TOOL_ACTION_INTAKE",
                title(status),
                summary(acceptedCount, rejectedCount, executableCount, approvalCount, clarificationCount, draftCount, blockedCount),
                status,
                "tool-intake",
                requiresAttention(rejectedCount, approvalCount, clarificationCount, blockedCount, throttledCount),
                "APPEND_TO_TIMELINE_AND_ALLOW_ACK_CURSOR",
                recommendedActions(rejectedCount, executableCount, approvalCount, clarificationCount, blockedCount, throttledCount),
                Map.copyOf(metrics)
        );
    }

    private static String status(int rejectedCount,
                                 int executableCount,
                                 int approvalCount,
                                 int clarificationCount,
                                 int blockedCount,
                                 int throttledCount) {
        if (rejectedCount > 0) {
            return "REJECTED_BEFORE_READINESS";
        }
        if (blockedCount > 0) {
            return "BLOCKED_BEFORE_EXECUTION";
        }
        if (approvalCount > 0) {
            return "WAITING_APPROVAL";
        }
        if (clarificationCount > 0) {
            return "NEEDS_CLARIFICATION";
        }
        if (throttledCount > 0) {
            return "WAITING_TOOL_BUDGET";
        }
        if (executableCount > 0) {
            return "READY_FOR_CONTROLLED_EXECUTION";
        }
        return "INTAKE_RECORDED";
    }

    private static String title(String status) {
        return switch (normalize(status)) {
            case "rejected_before_readiness" -> "工具动作入口已在准备度前拒绝";
            case "blocked_before_execution" -> "工具动作入口存在执行前阻断";
            case "waiting_approval" -> "工具动作入口等待审批";
            case "needs_clarification" -> "工具动作入口需要参数澄清";
            case "waiting_tool_budget" -> "工具动作入口等待预算恢复";
            case "ready_for_controlled_execution" -> "工具动作入口已通过预检";
            default -> "工具动作入口已记录";
        };
    }

    private static String summary(int acceptedCount,
                                  int rejectedCount,
                                  int executableCount,
                                  int approvalCount,
                                  int clarificationCount,
                                  int draftCount,
                                  int blockedCount) {
        return "入口接收 " + acceptedCount + " 个，准备度前拒绝 " + rejectedCount + " 个；可执行 "
                + executableCount + " 个，等待审批 " + approvalCount + " 个，需要澄清 "
                + clarificationCount + " 个，草稿 " + draftCount + " 个，阻断 " + blockedCount + " 个。";
    }

    private static boolean requiresAttention(int rejectedCount,
                                             int approvalCount,
                                             int clarificationCount,
                                             int blockedCount,
                                             int throttledCount) {
        return rejectedCount > 0 || approvalCount > 0 || clarificationCount > 0 || blockedCount > 0 || throttledCount > 0;
    }

    private static List<String> recommendedActions(int rejectedCount,
                                                   int executableCount,
                                                   int approvalCount,
                                                   int clarificationCount,
                                                   int blockedCount,
                                                   int throttledCount) {
        if (rejectedCount > 0) {
            return List.of("检查 MCP/A2A/确认页工具名、可见工具集合、协议 method 和参数 JSON object 形态；被拒绝项不能进入执行器。");
        }
        if (blockedCount > 0) {
            return List.of("查看 issueCodes 和 readinessReasonCodes，确认是否为高风险工具、权限策略或控制面阻断。");
        }
        if (approvalCount > 0) {
            return List.of("进入审批或确认面板完成人工授权，审批通过前不要写 outbox 或触发 worker。");
        }
        if (clarificationCount > 0) {
            return List.of("向用户或上游 Agent 澄清缺失参数，避免模型自行猜测数据源、SQL、导出路径或审批理由。");
        }
        if (throttledCount > 0) {
            return List.of("等待工具预算、目标服务并发或 worker backlog 恢复，不建议立即连续重试。");
        }
        if (executableCount > 0) {
            return List.of("可执行分支仍需由 Java 控制面接管权限、幂等、outbox、worker receipt 和结果脱敏。");
        }
        return List.of("当前只记录入口事实，后续可按 runId/sessionId 继续观察 readiness、outbox 和工具结果事件。");
    }

    private static Map<String, Object> safeAttributes(AgentRuntimeEventProjectionRecord record) {
        if (record.attributes() == null || record.attributes().isEmpty()) {
            return Map.of();
        }
        return record.attributes();
    }

    private static int intAttribute(Map<String, Object> attributes, String key) {
        Object value = attributes.get(key);
        if (value instanceof Number number) {
            return Math.max(0, number.intValue());
        }
        if (value instanceof String text) {
            try {
                return Math.max(0, Integer.parseInt(text.trim()));
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private static boolean boolAttribute(Map<String, Object> attributes, String key) {
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

    private static int mapSize(Object value) {
        if (value instanceof Map<?, ?> map) {
            return map.size();
        }
        return 0;
    }

    private static String textAttribute(Map<String, Object> attributes, String key) {
        Object value = attributes.get(key);
        return value == null ? "" : Objects.toString(value, "").trim();
    }

    private static void putIfPresent(Map<String, Object> values, String key, String value) {
        if (value != null && !value.isBlank()) {
            values.put(key, value);
        }
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
