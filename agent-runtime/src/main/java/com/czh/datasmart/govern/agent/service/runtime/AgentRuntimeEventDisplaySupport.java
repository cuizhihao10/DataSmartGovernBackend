/**
 * @Author : Cui
 * @Date: 2026/05/31 23:51
 * @Description DataSmart Govern Backend - AgentRuntimeEventDisplaySupport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.controller.dto.AgentRuntimeEventDisplayView;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Agent runtime event 展示解释支撑类。
 *
 * <p>runtime event 投影是平台的“事实层”，字段设计偏机器友好；前端时间线、智能网关动作审批面板、
 * 运维控制台更需要“展示层”，例如标题、摘要、状态、是否需要关注、下一步建议。把这层逻辑放在后端有三个好处：</p>
 *
 * <p>1. 前端不需要复制一套 eventType 解释表，避免 Web、移动端、审计台各自解释不一致；</p>
 * <p>2. 展示解释可以复用后端脱敏后的投影记录，确保 UI 不会绕过可见性策略读取敏感字段；</p>
 * <p>3. 后续 WebSocket replay、HTTP replay 和审计导出可以共享同一套展示语义。</p>
 *
 * <p>注意：display 永远不应该替代原始 attributes。它只是给人看的解释层，真正的自动化决策仍应基于
 * eventType、replaySequence、attributes 中的稳定机器字段，以及 permission-admin 的权限判断。</p>
 */
@Component
public class AgentRuntimeEventDisplaySupport {

    /**
     * DAG dry-run 事件类型。
     *
     * <p>这里不直接依赖 execution 包中的 publisher 常量，是为了保持 runtime 查询/展示层对具体执行发布器低耦合。
     * 未来如果 dry-run 事件从 HTTP 服务、WebSocket 或 outbox 多个入口产生，只要事件契约不变，展示层无需依赖发布实现。</p>
     */
    private static final String DAG_DRY_RUN_EVENT_TYPE = "agent.dag_execution.dry_run.completed";
    private static final String TOOL_EXECUTION_EVENT_TYPE = "agent.tool_execution.state_changed";
    private static final String SKILL_VISIBILITY_EVENT_TYPE = "skill_visibility_snapshot_recorded";

    private static final String REPLAY_POLICY_APPEND_AND_ACK = "APPEND_TO_TIMELINE_AND_ALLOW_ACK_CURSOR";
    private static final String REPLAY_POLICY_APPEND_ONLY = "APPEND_TO_TIMELINE";

    /**
     * 为单条事件生成前端展示解释。
     *
     * <p>调用方应传入已经经过 {@link AgentRuntimeEventVisibilitySupport#maskForAccess} 处理的记录。
     * 这很关键：display 中的摘要和指标必须尊重当前访问角色的可见性策略，不能因为“展示方便”重新读取原始敏感字段。</p>
     *
     * @param record 已经过访问控制与字段脱敏处理的事件投影。
     * @return 面向前端和智能网关的轻量展示视图。
     */
    public AgentRuntimeEventDisplayView buildDisplay(AgentRuntimeEventProjectionRecord record) {
        String eventType = normalize(record.eventType());
        if (DAG_DRY_RUN_EVENT_TYPE.equals(eventType)) {
            return buildDagDryRunDisplay(record);
        }
        if (TOOL_EXECUTION_EVENT_TYPE.equals(eventType) && isToolGuardrailEvent(record)) {
            return buildToolGuardrailDisplay(record);
        }
        if (SKILL_VISIBILITY_EVENT_TYPE.equals(eventType)) {
            return AgentSkillVisibilityEventDisplayBuilder.build(record);
        }
        return buildGenericDisplay(record);
    }

    /**
     * 生成 Agent 工具执行 guardrail 事件的展示解释。
     *
     * <p>这类事件来自 Java task-management worker 的执行前保护链路，例如 confirmation 回查不一致、
     * permission-admin 拒绝、策略版本漂移、权限中心暂时不可用等。它们和普通“工具执行失败”不同：
     * 很多时候真实副作用并没有发生，系统是在执行前主动 fail-closed 或 defer。因此前端需要明确告诉用户
     * “这是保护机制生效”，而不是误解成下游业务接口失败。</p>
     */
    private AgentRuntimeEventDisplayView buildToolGuardrailDisplay(AgentRuntimeEventProjectionRecord record) {
        Map<String, Object> attributes = safeAttributes(record);
        String errorCode = textAttribute(attributes, "errorCode");
        String message = record.message() == null || record.message().isBlank()
                ? "Agent worker 执行前保护机制已触发。"
                : record.message();
        boolean deferred = isDeferredGuardrail(errorCode, message, record.stage());
        Map<String, Object> metrics = new LinkedHashMap<>();
        putIfPresent(metrics, "errorCode", errorCode);
      putIfPresent(metrics, "toolCode", textAttribute(attributes, "toolCode"));
      putIfPresent(metrics, "currentState", textAttribute(attributes, "currentState"));
      putIfPresent(metrics, "targetService", textAttribute(attributes, "targetService"));
      putIfPresent(metrics, "preCheckDecision", textAttribute(attributes, "preCheckDecision"));
      metrics.put("issueCodeCount", listSize(attributes.get("issueCodes")));
      metrics.put("sideEffectPrevented", !deferred);

        return new AgentRuntimeEventDisplayView(
                "AGENT_TOOL_GUARDRAIL",
                toolGuardrailTitle(errorCode, message),
                message,
                deferred ? "DEFERRED_WAITING_RETRY" : "BLOCKED_BEFORE_SIDE_EFFECT",
                "guardrail",
                true,
                REPLAY_POLICY_APPEND_AND_ACK,
                toolGuardrailRecommendedActions(errorCode, message),
                Collections.unmodifiableMap(metrics)
        );
    }

    private AgentRuntimeEventDisplayView buildDagDryRunDisplay(AgentRuntimeEventProjectionRecord record) {
        Map<String, Object> attributes = safeAttributes(record);
        boolean detailsMasked = isBasicMasked(attributes);
        if (detailsMasked) {
            return new AgentRuntimeEventDisplayView(
                    "DAG_DRY_RUN",
                    "DAG 执行预案已生成",
                    "当前角色只能查看脱敏后的 dry-run 事件，可联系项目负责人或审计员查看节点级摘要。",
                    "SUMMARY_MASKED",
                    "dag-dry-run",
                    false,
                    REPLAY_POLICY_APPEND_AND_ACK,
                    List.of("如需确认或排查具体节点，请切换到具备项目或审计数据范围的账号。"),
                    Map.of("detailsMasked", true)
            );
        }

        int syncCount = intAttribute(attributes, "syncDryRunCandidateCount");
        int asyncCount = intAttribute(attributes, "asyncEnqueuePreviewCount");
        int blockedCount = intAttribute(attributes, "blockedCount");
        int notFoundCount = intAttribute(attributes, "notFoundCount");
        int batchLimitCount = intAttribute(attributes, "batchLimitReachedCount");
        int selectedCount = intAttribute(attributes, "selectedCount");
        int sandboxRejectedCount = intAttribute(attributes, "sandboxRejectedCount");
        int runtimeProtectionRejectedCount = intAttribute(attributes, "runtimeProtectionRejectedCount");
        int runtimeCircuitOpenCount = intAttribute(attributes, "runtimeCircuitOpenCount");
        int runtimeCapacityRejectedCount = intAttribute(attributes, "runtimeCapacityRejectedCount");
        String status = dryRunStatus(syncCount, asyncCount, blockedCount, notFoundCount, batchLimitCount);

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("selectedCount", selectedCount);
        metrics.put("syncDryRunCandidateCount", syncCount);
        metrics.put("asyncEnqueuePreviewCount", asyncCount);
        metrics.put("blockedCount", blockedCount);
        metrics.put("notFoundCount", notFoundCount);
        metrics.put("batchLimitReachedCount", batchLimitCount);
        /*
         * display.metrics 只放“低风险、低基数”的可展示指标。
         *
         * 这些字段不会暴露具体工具参数、SQL、接口 payload 或用户输入，却能回答运营上非常关键的问题：
         * - 阻断是普通依赖/审批问题，还是安全沙箱拒绝；
         * - 运行时保护是容量限制，还是目标服务已经熔断；
         * - 当前事件是否值得平台管理员介入，而不是只让业务用户反复点击重试。
         */
        metrics.put("sandboxRejectedCount", sandboxRejectedCount);
        metrics.put("sandboxIssueCodeCount", listSize(attributes.get("sandboxIssueCodes")));
        metrics.put("runtimeProtectionRejectedCount", runtimeProtectionRejectedCount);
        metrics.put("runtimeProtectionIssueCodeCount", listSize(attributes.get("runtimeProtectionIssueCodes")));
        metrics.put("runtimeCircuitOpenCount", runtimeCircuitOpenCount);
        metrics.put("runtimeCapacityRejectedCount", runtimeCapacityRejectedCount);
        metrics.put("dryRunOnly", true);
        metrics.put("itemCount", listSize(attributes.get("items")));

        return new AgentRuntimeEventDisplayView(
                "DAG_DRY_RUN",
                "DAG 执行预案已生成",
                dryRunSummary(syncCount, asyncCount, blockedCount, notFoundCount,
                        sandboxRejectedCount, runtimeProtectionRejectedCount),
                status,
                "dag-dry-run",
                shouldRequireAttention(syncCount, asyncCount, blockedCount, notFoundCount, batchLimitCount,
                        sandboxRejectedCount, runtimeProtectionRejectedCount),
                REPLAY_POLICY_APPEND_AND_ACK,
                dryRunRecommendedActions(syncCount, asyncCount, blockedCount, notFoundCount, batchLimitCount,
                        sandboxRejectedCount, runtimeProtectionRejectedCount, runtimeCircuitOpenCount, runtimeCapacityRejectedCount),
                Collections.unmodifiableMap(metrics)
        );
    }

    private AgentRuntimeEventDisplayView buildGenericDisplay(AgentRuntimeEventProjectionRecord record) {
        String category = genericCategory(record.eventType());
        String title = readableTitle(record);
        String severity = normalize(record.severity());
        boolean requiresAttention = "error".equals(severity) || normalize(record.eventType()).contains("approval");
        return new AgentRuntimeEventDisplayView(
                category,
                title,
                "事件类型：" + Objects.toString(record.eventType(), "UNKNOWN")
                        + "，阶段：" + Objects.toString(record.stage(), "UNKNOWN") + "。",
                genericStatus(record),
                iconKey(category),
                requiresAttention,
                REPLAY_POLICY_APPEND_ONLY,
                genericRecommendedActions(record, requiresAttention),
                Map.of()
        );
    }

    private String dryRunStatus(int syncCount,
                                int asyncCount,
                                int blockedCount,
                                int notFoundCount,
                                int batchLimitCount) {
        if (notFoundCount > 0 || blockedCount > 0 || batchLimitCount > 0) {
            return "NEEDS_REVIEW";
        }
        if (syncCount + asyncCount > 0) {
            return "READY_FOR_CONFIRMATION";
        }
        return "NO_EXECUTABLE_NODE";
    }

    private boolean shouldRequireAttention(int syncCount,
                                           int asyncCount,
                                           int blockedCount,
                                           int notFoundCount,
                                           int batchLimitCount,
                                           int sandboxRejectedCount,
                                           int runtimeProtectionRejectedCount) {
        return blockedCount > 0
                || notFoundCount > 0
                || batchLimitCount > 0
                || sandboxRejectedCount > 0
                || runtimeProtectionRejectedCount > 0
                || syncCount + asyncCount == 0;
    }

    private List<String> dryRunRecommendedActions(int syncCount,
                                                  int asyncCount,
                                                  int blockedCount,
                                                  int notFoundCount,
                                                  int batchLimitCount,
                                                  int sandboxRejectedCount,
                                                  int runtimeProtectionRejectedCount,
                                                  int runtimeCircuitOpenCount,
                                                  int runtimeCapacityRejectedCount) {
        List<String> actions = new ArrayList<>();
        if (syncCount + asyncCount > 0) {
            actions.add("在动作审批面板确认选中节点后，再进入同步 dry-run 二次确认或受控异步 outbox。");
        }
        if (blockedCount > 0) {
            actions.add("查看节点级阻断原因，补齐审批、参数、依赖或服务间授权后重新 dry-run。");
        }
        if (sandboxRejectedCount > 0) {
            actions.add("优先查看 sandboxIssueCodes：沙箱拒绝通常表示工具目录、参数体积、幂等策略或目标服务边界需要修正。");
        }
        if (runtimeProtectionRejectedCount > 0) {
            actions.add("优先查看 runtimeProtectionIssueCodes：运行时保护拒绝通常表示需要拆批、等待并发释放或处理目标服务健康问题。");
        }
        if (runtimeCapacityRejectedCount > 0) {
            actions.add("存在容量类拒绝时，不建议由 Agent 自动重试，应采用退避、队列化或降低本批次并发。");
        }
        if (runtimeCircuitOpenCount > 0) {
            actions.add("存在目标服务熔断时，应先排查下游健康、接口变更和最近失败码，冷却期结束前不要继续扩大调用。");
        }
        if (notFoundCount > 0) {
            actions.add("核对请求中的 nodeId/auditId，避免前端或 Python Runtime 使用过期 DAG selector。");
        }
        if (batchLimitCount > 0) {
            actions.add("缩小本次选择范围，或由管理员评估后提高 maxNodes 批量上限。");
        }
        if (actions.isEmpty()) {
            actions.add("当前没有可推进节点，可继续等待依赖完成或重新生成工具计划。");
        }
        return List.copyOf(actions);
    }

    private String dryRunSummary(int syncCount,
                                 int asyncCount,
                                 int blockedCount,
                                 int notFoundCount,
                                 int sandboxRejectedCount,
                                 int runtimeProtectionRejectedCount) {
        String summary = "同步候选 " + syncCount + " 个，异步预案 " + asyncCount + " 个，阻断 "
                + blockedCount + " 个，未命中 " + notFoundCount + " 个。";
        if (sandboxRejectedCount <= 0 && runtimeProtectionRejectedCount <= 0) {
            return summary;
        }
        return summary + " 其中沙箱拒绝 " + sandboxRejectedCount
                + " 个，运行时保护暂缓 " + runtimeProtectionRejectedCount + " 个。";
    }

    private List<String> genericRecommendedActions(AgentRuntimeEventProjectionRecord record, boolean requiresAttention) {
        if (!requiresAttention) {
            return List.of();
        }
        String eventType = normalize(record.eventType());
        if (eventType.contains("approval")) {
            return List.of("进入审批面板查看待确认动作，并按权限策略决定通过或拒绝。");
        }
        if ("error".equals(normalize(record.severity()))) {
            return List.of("查看同一 runId 的前后事件，并结合 requestId 排查上游调用或工具执行失败原因。");
        }
        return List.of("查看事件详情并确认是否需要人工处理。");
    }

    private boolean isToolGuardrailEvent(AgentRuntimeEventProjectionRecord record) {
        Map<String, Object> attributes = safeAttributes(record);
        String errorCode = textAttribute(attributes, "errorCode");
        String message = normalize(record.message());
        return errorCode.startsWith("AGENT_ASYNC_TOOL_")
                || message.contains("permission-admin")
                || message.contains("confirmation")
                || message.contains("执行前复核")
                || message.contains("worker 已阻止副作用");
    }

    private String toolGuardrailTitle(String errorCode, String message) {
        return switch (normalize(errorCode)) {
            case "agent_async_tool_permission_denied" -> "Agent 工具被权限策略阻断";
            case "agent_async_tool_approval_required" -> "Agent 工具仍需审批";
            case "agent_async_tool_policy_version_drift" -> "Agent 工具策略版本已变化";
            case "agent_async_tool_confirmation_rejected" -> "Agent 工具确认记录复核失败";
            case "agent_async_tool_permission_unavailable" -> "权限中心暂不可用，工具等待重试";
          case "agent_async_tool_confirmation_unavailable" -> "确认记录暂不可用，工具等待重试";
          case "agent_async_tool_not_whitelisted" -> "Agent 工具未配置白名单适配器";
          case "agent_async_tool_audit_state_rejected" -> "Agent 工具状态不允许执行";
          case "agent_async_tool_precheck_blocked" -> "Agent 异步命令执行前复核阻断";
          case "agent_async_tool_precheck_deferred" -> "Agent 异步命令执行前复核暂缓";
          default -> normalize(message).contains("暂时不可用")
                  ? "Agent 工具执行前复核暂不可用"
                  : "Agent 工具执行前保护已触发";
        };
    }

    private List<String> toolGuardrailRecommendedActions(String errorCode, String message) {
        String normalizedCode = normalize(errorCode);
        String normalizedMessage = normalize(message);
        if (normalizedCode.contains("permission_denied")) {
            return List.of("检查 permission-admin 路由策略、服务账号委托关系和用户项目权限。");
        }
        if (normalizedCode.contains("approval_required")) {
            return List.of("进入审批面板完成当前工具动作审批，审批通过后重新确认或重试。");
        }
        if (normalizedCode.contains("policy_version_drift")) {
            return List.of("重新执行 dry-run/selected-node confirmation，使用最新策略版本生成新的执行证据。");
        }
      if (normalizedCode.contains("confirmation_rejected")) {
          return List.of("核对 confirmationId、sessionId、runId、auditId 和 commandId 是否来自同一次 DAG 确认。");
      }
      if (normalizedCode.contains("precheck")) {
          return List.of("检查 selected-node confirmation、policyVersion、sandboxIssueCodes 和 runtimeProtectionIssueCodes，确认应重新确认、人工补偿还是等待退避重试。");
      }
      if (normalizedCode.contains("permission_unavailable") || normalizedMessage.contains("permission-admin")) {
          return List.of("等待 permission-admin 恢复后由 worker 自动重试；若持续出现，请检查服务发现、超时和权限中心健康。");
      }
        if (normalizedCode.contains("confirmation_unavailable") || normalizedMessage.contains("confirmation")) {
            return List.of("等待 agent-runtime confirmation 查询恢复后由 worker 自动重试；若持续出现，请检查运行时控制面健康。");
        }
        if (normalizedCode.contains("not_whitelisted")) {
            return List.of("为该 toolCode 增加受控 Java 工具适配器，并确认不会按任意 targetEndpoint 转发。");
        }
        return List.of("查看同一 runId 的前后事件，确认是配置、权限、审批、状态还是控制面依赖导致。");
    }

    private boolean isDeferredGuardrail(String errorCode, String message, String stage) {
        String normalizedCode = normalize(errorCode);
        String normalizedMessage = normalize(message);
        String normalizedStage = normalize(stage);
        return normalizedCode.contains("unavailable")
              || normalizedMessage.contains("暂时不可用")
              || normalizedMessage.contains("等待重试")
              || normalizedStage.contains("deferred")
              || "tool_executing".equals(normalizedStage);
  }

    private String genericCategory(String eventType) {
        String normalized = normalize(eventType);
        if (normalized.contains("approval")) {
            return "APPROVAL";
        }
        if (normalized.contains("tool")) {
            return "TOOL_EXECUTION";
        }
        if (normalized.contains("memory")) {
            return "MEMORY";
        }
        if (normalized.contains("run")) {
            return "RUN";
        }
        if (normalized.contains("dag")) {
            return "DAG";
        }
        return "SYSTEM";
    }

    private String genericStatus(AgentRuntimeEventProjectionRecord record) {
        String severity = normalize(record.severity());
        if ("error".equals(severity)) {
            return "ERROR";
        }
        if ("audit".equals(severity)) {
            return "AUDIT";
        }
        return "INFO";
    }

    private String iconKey(String category) {
        return switch (category) {
            case "APPROVAL" -> "approval";
            case "TOOL_EXECUTION" -> "tool";
            case "MEMORY" -> "memory";
            case "RUN" -> "run";
            case "DAG" -> "dag";
            default -> "event";
        };
    }

    private String readableTitle(AgentRuntimeEventProjectionRecord record) {
        String message = record.message();
        if (message != null && !message.isBlank()) {
            return message;
        }
        String stage = record.stage();
        if (stage != null && !stage.isBlank()) {
            return stage;
        }
        return Objects.toString(record.eventType(), "Agent runtime event");
    }

    private Map<String, Object> safeAttributes(AgentRuntimeEventProjectionRecord record) {
        if (record.attributes() == null || record.attributes().isEmpty()) {
            return Map.of();
        }
        return record.attributes();
    }

    private boolean isBasicMasked(Map<String, Object> attributes) {
        Object visibilityLevel = attributes.get(AgentRuntimeEventVisibilitySupport.VISIBILITY_LEVEL_ATTRIBUTE);
        return "BASIC".equals(Objects.toString(visibilityLevel, ""));
    }

    private int intAttribute(Map<String, Object> attributes, String key) {
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

    private int listSize(Object value) {
        if (value instanceof List<?> list) {
            return list.size();
        }
        return 0;
    }

    private String textAttribute(Map<String, Object> attributes, String key) {
        Object value = attributes == null ? null : attributes.get(key);
        return value == null ? "" : Objects.toString(value, "").trim();
    }

    private void putIfPresent(Map<String, Object> values, String key, String value) {
        if (value != null && !value.isBlank()) {
            values.put(key, value);
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
