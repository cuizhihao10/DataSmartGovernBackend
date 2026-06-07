/**
 * @Author : Cui
 * @Date: 2026/06/07 15:59
 * @Description DataSmart Govern Backend - AgentAsyncTaskCommandContractSupport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.support;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Agent 跨服务命令契约支撑类。
 *
 * <p>task-management 当前同时接收两类来自 agent-runtime 的命令：</p>
 * <p>1. 历史 `AGENT_TOOL_ASYNC_TASK_REQUESTED`：表示某个 Agent 工具审计快照已经可以转成后台任务，
 * worker 后续通过 `agent-tool-audit://.../plan-arguments` 回到 agent-runtime 读取参数快照；</p>
 * <p>2. 新增 `AGENT_TOOL_ACTION_CONTROLLED_COMMAND`：表示 MCP/A2A/模型 tool_call 等外部动作已经通过
 * agent-runtime 控制面复核，可以先进入 task-management Inbox 去重和任务台账，但当前阶段还不能交给旧 worker
 * 直接执行，因为它使用的是 `agent-payload:` 新引用协议，后续还需要独立 payload store、执行器和 receipt 回写。</p>
 *
 * <p>把这些规则集中到本类，是为了避免 ConsumerService 在持续演进中变成大而全的 if/else 文件。
 * 这里不读取数据库、不创建任务、不解析真实 payload，只维护“消费侧协议门禁”：
 * 命令类型是否支持、哪个任务类型应该被创建、targetEndpoint 是否允许、payloadReference 是否符合低敏结构。</p>
 */
public final class AgentAsyncTaskCommandContractSupport {

    /**
     * task-management 当前能理解的跨服务命令 schema。
     *
     * <p>注意：新工具动作 writer 的内部 proposal schema 可以是 `agent-tool-action-command.v1`，
     * 但真正投递到 task-management 的 Kafka/HTTP payload 仍必须使用这个统一消费侧 schema，
     * 这样 Inbox、Kafka handler、诊断和 DLQ 能复用同一套解析逻辑。</p>
     */
    public static final String SUPPORTED_SCHEMA_VERSION = "datasmart.agent.async-task-command.v1";

    /**
     * 历史异步工具任务命令类型。
     *
     * <p>该命令会创建 `AGENT_ASYNC_TOOL` 任务，并可被现有 AgentAsyncTool worker 认领。</p>
     */
    public static final String COMMAND_TYPE_ASYNC_TASK_REQUESTED = "AGENT_TOOL_ASYNC_TASK_REQUESTED";

    /**
     * 新工具动作受控命令类型。
     *
     * <p>该命令只进入 Inbox 和任务台账，任务类型为 `AGENT_TOOL_ACTION_CONTROLLED`。
     * 它不会被旧 worker 认领，避免 `agent-payload:` 引用尚未接真实 payload store 时发生误执行。</p>
     */
    public static final String COMMAND_TYPE_TOOL_ACTION_CONTROLLED = "AGENT_TOOL_ACTION_CONTROLLED_COMMAND";

    /**
     * 历史 worker 可认领的任务类型。
     */
    public static final String TASK_TYPE_AGENT_ASYNC_TOOL = "AGENT_ASYNC_TOOL";

    /**
     * 新工具动作控制面任务类型。
     *
     * <p>该类型当前用于“可靠接单 + 后续治理工作台展示”。等后续实现专门的 tool-action executor 后，
     * 再由新的 worker 只认领这个类型，不能让旧适配器矩阵直接处理。</p>
     */
    public static final String TASK_TYPE_AGENT_TOOL_ACTION_CONTROLLED = "AGENT_TOOL_ACTION_CONTROLLED";

    /**
     * 历史审计参数快照引用协议。
     */
    public static final String PAYLOAD_REFERENCE_AGENT_TOOL_AUDIT_PREFIX = "agent-tool-audit://";

    /**
     * 新工具动作受控载荷引用协议。
     */
    public static final String PAYLOAD_REFERENCE_AGENT_PAYLOAD_PREFIX = "agent-payload:";

    private static final String PAYLOAD_KIND_PLAN_ARGUMENTS = "plan-arguments";
    private static final int MAX_PAYLOAD_REFERENCE_LENGTH = 256;
    private static final int MAX_REFERENCE_SEGMENT_LENGTH = 96;

    private AgentAsyncTaskCommandContractSupport() {
    }

    /**
     * 命令业务类别。
     *
     * <p>相比在代码里到处比较字符串，枚举能让后续新增 commandType 时更容易定位需要调整的分支：
     * payloadReference 规则、任务类型、endpoint 必填语义、workspace 必填语义和 worker 路由。</p>
     */
    public enum CommandKind {
        ASYNC_TASK_REQUESTED,
        TOOL_ACTION_CONTROLLED
    }

    /**
     * 解析并校验命令类型。
     *
     * @param commandType 请求中的 commandType。
     * @return 归一化后的命令类别。
     */
    public static CommandKind requireSupportedCommandType(String commandType) {
        String normalized = safeText(commandType);
        if (COMMAND_TYPE_ASYNC_TASK_REQUESTED.equals(normalized)) {
            return CommandKind.ASYNC_TASK_REQUESTED;
        }
        if (COMMAND_TYPE_TOOL_ACTION_CONTROLLED.equals(normalized)) {
            return CommandKind.TOOL_ACTION_CONTROLLED;
        }
        throw new IllegalArgumentException("不支持的 Agent 异步命令 commandType: " + commandType);
    }

    /**
     * 判断指定命令是否必须携带 targetEndpoint。
     *
     * <p>历史 async-task 命令的参数快照里仍会保存工具目录声明的 targetEndpoint，worker 会用它做一致性校验，
     * 但不会直接按 endpoint 发 HTTP。新 tool-action controlled command 则反过来要求不携带 endpoint：
     * 它只是一条控制面命令，真实业务目标必须由后续受控执行器重新解析，不能让调用方把内部路径塞进消息。</p>
     */
    public static boolean targetEndpointRequired(CommandKind commandKind) {
        return CommandKind.ASYNC_TASK_REQUESTED.equals(commandKind);
    }

    /**
     * 判断指定命令是否必须携带 workspaceId。
     *
     * <p>历史 worker 读取 agent-tool-audit 参数快照时需要 workspaceId 做一致性校验；新受控工具动作当前只从
     * runtime event 图和项目范围形成命令，workspace 可能尚未物化，所以允许为空，并在后续 payload store 阶段补强。</p>
     */
    public static boolean workspaceRequired(CommandKind commandKind) {
        return CommandKind.ASYNC_TASK_REQUESTED.equals(commandKind);
    }

    /**
     * 根据命令类型返回 task-management 中应创建的任务类型。
     */
    public static String taskType(CommandKind commandKind) {
        if (CommandKind.TOOL_ACTION_CONTROLLED.equals(commandKind)) {
            return TASK_TYPE_AGENT_TOOL_ACTION_CONTROLLED;
        }
        return TASK_TYPE_AGENT_ASYNC_TOOL;
    }

    /**
     * 返回命令对应的 payloadReference 类型摘要。
     *
     * <p>该值会写入 task.params，便于后续运营台或 worker 一眼判断当前任务引用的是历史审计快照，
     * 还是新工具动作 payload store 引用。</p>
     */
    public static String payloadReferenceType(CommandKind commandKind) {
        if (CommandKind.TOOL_ACTION_CONTROLLED.equals(commandKind)) {
            return "AGENT_PAYLOAD";
        }
        return "AGENT_TOOL_AUDIT";
    }

    /**
     * 规范化 targetEndpoint。
     *
     * @param commandKind 命令类别。
     * @param targetEndpoint 请求携带的 endpoint。
     * @return 可写入 Inbox/task.params 的 endpoint；新受控工具动作会返回 null。
     */
    public static String normalizeTargetEndpoint(CommandKind commandKind, String targetEndpoint) {
        String normalized = safeText(targetEndpoint);
        if (targetEndpointRequired(commandKind)) {
            if (normalized == null) {
                throw new IllegalArgumentException("targetEndpoint 不能为空");
            }
            return normalized;
        }
        if (normalized != null) {
            throw new IllegalArgumentException("AGENT_TOOL_ACTION_CONTROLLED_COMMAND 不能携带 targetEndpoint，"
                    + "真实业务目标必须由后续受控执行器重新解析");
        }
        return null;
    }

    /**
     * 校验目标服务语义。
     *
     * <p>新受控工具动作当前必须回到 agent-runtime 控制面继续推进，不能直接声明 data-sync、data-quality 等业务模块。
     * 这样可以防止调用方把“工具动作控制面命令”伪装成“直接调用某个业务服务”。</p>
     */
    public static void validateTargetService(CommandKind commandKind, String targetService) {
        String normalized = safeText(targetService);
        if (normalized == null) {
            throw new IllegalArgumentException("targetService 不能为空");
        }
        if (CommandKind.TOOL_ACTION_CONTROLLED.equals(commandKind)
                && !"agent-runtime".equals(normalized)) {
            throw new IllegalArgumentException("AGENT_TOOL_ACTION_CONTROLLED_COMMAND 的 targetService 必须是 agent-runtime");
        }
    }

    /**
     * 校验 payloadReference 是否符合当前命令类型要求。
     *
     * <p>这里仍然不读取真实 payload，只做低敏结构校验。真实执行前还必须由 worker 或 tool-action executor
     * 回到服务端 payload store/审计 store 做存在性、租户、项目、工作空间、审批事实和策略版本复核。</p>
     */
    public static void validatePayloadReference(CommandKind commandKind,
                                                String payloadReference,
                                                String sessionId,
                                                String runId,
                                                String auditId) {
        String reference = safeText(payloadReference);
        if (reference == null) {
            throw new IllegalArgumentException("payloadReference 不能为空");
        }
        List<String> risks = commonReferenceRisks(reference);
        if (!risks.isEmpty()) {
            throw new IllegalArgumentException("payloadReference 包含不允许的高风险片段: " + String.join(",", risks));
        }
        if (CommandKind.TOOL_ACTION_CONTROLLED.equals(commandKind)) {
            validateAgentPayloadReference(reference, runId);
            return;
        }
        validateAgentToolAuditReference(reference, sessionId, runId, auditId);
    }

    /**
     * 校验历史 agent-tool-audit 引用。
     */
    private static void validateAgentToolAuditReference(String reference,
                                                        String sessionId,
                                                        String runId,
                                                        String auditId) {
        if (!reference.startsWith(PAYLOAD_REFERENCE_AGENT_TOOL_AUDIT_PREFIX)) {
            throw new IllegalArgumentException("payloadReference 必须使用受控 agent-tool-audit:// 协议");
        }
        String[] parts = reference.substring(PAYLOAD_REFERENCE_AGENT_TOOL_AUDIT_PREFIX.length()).split("/", -1);
        List<String> issues = new ArrayList<>();
        if (parts.length != 4) {
            issues.add("AGENT_TOOL_AUDIT_REFERENCE_REQUIRES_FOUR_SEGMENTS");
        }
        addSegmentIssues(parts, issues);
        if (parts.length == 4 && !PAYLOAD_KIND_PLAN_ARGUMENTS.equals(parts[3])) {
            issues.add("AGENT_TOOL_AUDIT_PAYLOAD_KIND_UNSUPPORTED");
        }
        if (parts.length >= 1 && !parts[0].equals(safeText(sessionId))) {
            issues.add("PAYLOAD_REFERENCE_SESSION_ID_MISMATCH");
        }
        if (parts.length >= 2 && !parts[1].equals(safeText(runId))) {
            issues.add("PAYLOAD_REFERENCE_RUN_ID_MISMATCH");
        }
        if (parts.length >= 3 && !parts[2].equals(safeText(auditId))) {
            issues.add("PAYLOAD_REFERENCE_AUDIT_ID_MISMATCH");
        }
        if (!issues.isEmpty()) {
            throw new IllegalArgumentException("agent-tool-audit 引用不满足消费侧契约: " + String.join(",", issues));
        }
    }

    /**
     * 校验新工具动作 agent-payload 引用。
     */
    private static void validateAgentPayloadReference(String reference, String runId) {
        if (!reference.startsWith(PAYLOAD_REFERENCE_AGENT_PAYLOAD_PREFIX)) {
            throw new IllegalArgumentException("AGENT_TOOL_ACTION_CONTROLLED_COMMAND 的 payloadReference 必须使用 agent-payload: 协议");
        }
        String[] parts = reference.substring(PAYLOAD_REFERENCE_AGENT_PAYLOAD_PREFIX.length()).split("/", -1);
        List<String> issues = new ArrayList<>();
        if (parts.length < 2) {
            issues.add("AGENT_PAYLOAD_REFERENCE_REQUIRES_RUN_AND_KEY");
        }
        addSegmentIssues(parts, issues);
        if (parts.length >= 1 && !parts[0].equals(safeText(runId))) {
            issues.add("PAYLOAD_REFERENCE_RUN_ID_MISMATCH");
        }
        if (!issues.isEmpty()) {
            throw new IllegalArgumentException("agent-payload 引用不满足消费侧契约: " + String.join(",", issues));
        }
    }

    private static void addSegmentIssues(String[] parts, List<String> issues) {
        for (String part : parts) {
            String text = safeText(part);
            if (text == null) {
                issues.add("PAYLOAD_REFERENCE_EMPTY_SEGMENT");
                continue;
            }
            if (text.length() > MAX_REFERENCE_SEGMENT_LENGTH) {
                issues.add("PAYLOAD_REFERENCE_SEGMENT_TOO_LONG");
            }
            if (!isSafeSegment(text)) {
                issues.add("PAYLOAD_REFERENCE_SEGMENT_UNSAFE_CHARACTERS");
            }
        }
    }

    private static List<String> commonReferenceRisks(String reference) {
        List<String> risks = new ArrayList<>();
        String normalized = reference.toLowerCase(Locale.ROOT);
        if (reference.length() > MAX_PAYLOAD_REFERENCE_LENGTH) {
            risks.add("PAYLOAD_REFERENCE_TOO_LONG");
        }
        if (normalized.contains("http://") || normalized.contains("https://")) {
            risks.add("PAYLOAD_REFERENCE_URL_NOT_ALLOWED");
        }
        if (normalized.contains("\n") || normalized.contains("\r")) {
            risks.add("PAYLOAD_REFERENCE_CONTROL_CHARACTER_NOT_ALLOWED");
        }
        if (normalized.contains("select *") || normalized.contains("password=")
                || normalized.contains("authorization:") || normalized.contains("bearer ")
                || normalized.contains("prompt:")) {
            risks.add("PAYLOAD_REFERENCE_INLINE_SECRET_SQL_OR_PROMPT");
        }
        if (normalized.contains("{") || normalized.contains("}") || normalized.contains("[") || normalized.contains("]")) {
            risks.add("PAYLOAD_REFERENCE_INLINE_PAYLOAD_NOT_ALLOWED");
        }
        return risks;
    }

    private static boolean isSafeSegment(String value) {
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            boolean allowed = Character.isLetterOrDigit(current)
                    || current == '-'
                    || current == '_'
                    || current == '.'
                    || current == ':';
            if (!allowed) {
                return false;
            }
        }
        return true;
    }

    private static String safeText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
