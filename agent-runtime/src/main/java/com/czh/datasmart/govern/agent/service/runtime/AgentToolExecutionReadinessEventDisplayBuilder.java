/**
 * @Author : Cui
 * @Date: 2026/06/06 00:00
 * @Description DataSmart Govern Backend - AgentToolExecutionReadinessEventDisplayBuilder.java
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
 * 工具执行准备度事件展示构建器。
 *
 * <p>该 builder 把 `tool_execution_readiness_recorded` 事件转换为 timeline 卡片。它关注的是“执行前治理”
 * 而不是“工具执行结果”：可执行、等待审批、等待澄清、草案、异步入队、限流和阻断都属于执行前状态。
 * 真实副作用是否发生，仍以后续 Java tool execution / outbox / worker 事件为准。</p>
 */
final class AgentToolExecutionReadinessEventDisplayBuilder {

    private AgentToolExecutionReadinessEventDisplayBuilder() {
    }

    static AgentRuntimeEventDisplayView build(AgentRuntimeEventProjectionRecord record) {
        Map<String, Object> attributes = safeAttributes(record);
        int executableCount = intAttribute(attributes, "executableCount");
        int approvalCount = intAttribute(attributes, "approvalRequiredCount");
        int clarificationCount = intAttribute(attributes, "clarificationRequiredCount");
        int draftCount = intAttribute(attributes, "draftOnlyCount");
        int queuedAsyncCount = intAttribute(attributes, "queuedAsyncCount");
        int throttledCount = intAttribute(attributes, "throttledCount");
        int blockedCount = intAttribute(attributes, "blockedCount");
        String status = status(executableCount, approvalCount, clarificationCount, throttledCount, blockedCount);

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("totalCount", intAttribute(attributes, "totalCount"));
        metrics.put("executableCount", executableCount);
        metrics.put("approvalRequiredCount", approvalCount);
        metrics.put("clarificationRequiredCount", clarificationCount);
        metrics.put("draftOnlyCount", draftCount);
        metrics.put("queuedAsyncCount", queuedAsyncCount);
        metrics.put("throttledCount", throttledCount);
        metrics.put("blockedCount", blockedCount);
        metrics.put("toolNameCount", listSize(attributes.get("toolNames")));
        metrics.put("nextActionCount", listSize(attributes.get("nextActions")));
        metrics.put("decisionSummaryCount", listSize(attributes.get("decisionSummaries")));
        /*
         * readiness graph 指标用于解释“本次执行前治理图谱有多复杂、路由到哪些分支”。
         * timeline 只展示低敏计数和边界布尔值，不展开 graph nodes/edges，避免把工具参数或编排细节
         * 复制到通用时间线卡片中。
         */
        metrics.put("graphNodeCount", intAttribute(attributes, "graphNodeCount"));
        metrics.put("graphEdgeCount", intAttribute(attributes, "graphEdgeCount"));
        metrics.put("graphBranchCount", mapSize(attributes.get("graphBranchCounts")));
        metrics.put("graphToolExecuted", boolAttribute(attributes, "graphToolExecuted"));
        metrics.put("graphOutboxWritten", boolAttribute(attributes, "graphOutboxWritten"));
        metrics.put("graphApprovalCreated", boolAttribute(attributes, "graphApprovalCreated"));
        metrics.put("graphWorkerReceiptRequiredForSideEffects",
                boolAttribute(attributes, "graphWorkerReceiptRequiredForSideEffects"));

        return new AgentRuntimeEventDisplayView(
                "TOOL_EXECUTION_READINESS",
                title(status),
                summary(executableCount, approvalCount, clarificationCount, draftCount, queuedAsyncCount, throttledCount, blockedCount),
                status,
                "tool-readiness",
                blockedCount > 0 || approvalCount > 0 || clarificationCount > 0 || throttledCount > 0,
                "APPEND_TO_TIMELINE_AND_ALLOW_ACK_CURSOR",
                recommendedActions(approvalCount, clarificationCount, draftCount, queuedAsyncCount, throttledCount, blockedCount),
                Map.copyOf(metrics)
        );
    }

    private static String status(int executableCount,
                                 int approvalCount,
                                 int clarificationCount,
                                 int throttledCount,
                                 int blockedCount) {
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
            return "READY_TO_EXECUTE";
        }
        return "DRAFT_OR_NO_EXECUTION";
    }

    private static String title(String status) {
        return switch (normalize(status)) {
            case "blocked_before_execution" -> "工具执行准备度存在阻断";
            case "waiting_approval" -> "工具等待人工审批";
            case "needs_clarification" -> "工具参数需要澄清";
            case "waiting_tool_budget" -> "工具等待预算或队列恢复";
            case "ready_to_execute" -> "工具已具备执行准备度";
            default -> "工具准备度已记录";
        };
    }

    private static String summary(int executableCount,
                                  int approvalCount,
                                  int clarificationCount,
                                  int draftCount,
                                  int queuedAsyncCount,
                                  int throttledCount,
                                  int blockedCount) {
        return "可执行 " + executableCount + " 个，等待审批 " + approvalCount + " 个，需要澄清 "
                + clarificationCount + " 个，草案 " + draftCount + " 个，异步入队 " + queuedAsyncCount
                + " 个，限流 " + throttledCount + " 个，阻断 " + blockedCount + " 个。";
    }

    private static List<String> recommendedActions(int approvalCount,
                                                   int clarificationCount,
                                                   int draftCount,
                                                   int queuedAsyncCount,
                                                   int throttledCount,
                                                   int blockedCount) {
        if (blockedCount > 0) {
            return List.of("先查看 reasonCodes 和 issueCodes，确认是否为 CRITICAL 风险、权限缺口、预算阻断或控制面策略拒绝。");
        }
        if (approvalCount > 0) {
            return List.of("进入审批面板确认高风险或写操作工具，审批通过后再由 Java 控制面推进 outbox/worker。");
        }
        if (clarificationCount > 0) {
            return List.of("向用户澄清缺失参数，或通过上下文检索补齐 CAN_FILL_FROM_CONTEXT 字段后重新生成计划。");
        }
        if (throttledCount > 0) {
            return List.of("等待工具预算、worker backlog 或目标服务并发恢复，不建议由 Agent 立即连续重试。");
        }
        if (queuedAsyncCount > 0) {
            return List.of("将异步工具交给 task-management/outbox 管理，后续通过任务状态和 worker receipt 回放。");
        }
        if (draftCount > 0) {
            return List.of("展示草案给用户或审核员复核，草案不代表工具已经产生真实副作用。");
        }
        return List.of("可执行工具仍需经过 Java 控制面的权限、幂等、限流和审计检查。");
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

    private static String normalize(String value) {
        return Objects.toString(value, "").trim().toLowerCase(Locale.ROOT);
    }
}
