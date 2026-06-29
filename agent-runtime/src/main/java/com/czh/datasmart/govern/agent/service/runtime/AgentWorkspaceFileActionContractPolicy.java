/**
 * @Author : Cui
 * @Date: 2026/06/29 23:10
 * @Description DataSmart Govern Backend - AgentWorkspaceFileActionContractPolicy.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * workspace 文件工具进入 durable action 前的契约增强策略。
 *
 * <p>Python Runtime 已经补齐 `workspace.file.read` 与 `workspace.file.write` 的受控工具计划和
 * 本地 workspace 校验，但 Java 控制面不能因此直接恢复文件路径、文件正文或写入内容。原因很简单：
 * `tool_action_intake_recorded` 是长期进入 replay、投影、审计和前端 timeline 的低敏事件，里面只能保存
 * 工具名、决策、reason code、issue code 等控制面事实，不能保存真实路径、文件正文、prompt、SQL 或工具参数值。</p>
 *
 * <p>因此本策略类只做“契约增强”，不做执行：
 * 它把 workspace 文件工具识别成独立 commandType，并把路径引用、内容引用、artifact grant、DLP/恶意内容扫描、
 * worker receipt 等要求显式写进 requiredEvidence/missingRequirements/guardrailNotes。
 * 这样后续 outbox writer、task-management worker 或 Python executor 接手时，可以清楚知道哪些证据必须由
 * 服务端重新校验，而不会误把低敏 projection 当作可执行文件操作。</p>
 */
final class AgentWorkspaceFileActionContractPolicy {

    private static final String READ_TOOL_NAME = "workspace.file.read";
    private static final String WRITE_TOOL_NAME = "workspace.file.write";
    private static final String READ_COMMAND_TYPE = "AGENT_WORKSPACE_FILE_READ_COMMAND";
    private static final String WRITE_COMMAND_TYPE = "AGENT_WORKSPACE_FILE_WRITE_COMMAND";

    /**
     * 根据工具名返回 workspace 文件工具的专用 commandType。
     *
     * <p>commandType 只表达“未来如果进入 outbox，应该由哪类 worker/handler 消费”。它不是执行许可，
     * 也不会携带路径或正文。真正是否允许写 outbox，仍由 durableActionState、payloadReference、审批事实、
     * 容量保护和后续 writer 复核共同决定。</p>
     */
    String commandTypeFor(String toolName) {
        String normalized = normalize(toolName);
        if (READ_TOOL_NAME.equals(normalized)) {
            return READ_COMMAND_TYPE;
        }
        if (WRITE_TOOL_NAME.equals(normalized)) {
            return WRITE_COMMAND_TYPE;
        }
        return null;
    }

    /**
     * 给 workspace 文件工具追加生产级必备证据。
     *
     * <p>这里的证据名称都是低敏枚举，不包含文件路径和文件内容。读文件与写文件都需要 workspace scope、路径引用、
     * payloadReference 和 worker receipt；写文件额外需要内容引用、覆盖/冲突策略和 DLP/恶意内容扫描结果。
     * 这样可以把“能规划文件工具”与“能在生产中安全执行文件副作用”区分开。</p>
     */
    void addRequiredEvidence(String toolName, List<String> evidence) {
        if (!isWorkspaceFileTool(toolName)) {
            return;
        }
        evidence.add("WORKSPACE_SCOPE_AUTHORIZATION");
        evidence.add("WORKSPACE_ROOT_ALLOWLIST");
        evidence.add("WORKSPACE_FILE_PATH_REFERENCE");
        evidence.add("WORKSPACE_FILE_ARTIFACT_GRANT");
        evidence.add("WORKSPACE_FILE_RESULT_RECEIPT");
        if (isWriteTool(toolName)) {
            evidence.add("WORKSPACE_FILE_CONTENT_REFERENCE");
            evidence.add("WORKSPACE_FILE_WRITE_CONFLICT_POLICY");
            evidence.add("WORKSPACE_FILE_DLP_OR_MALWARE_SCAN");
            evidence.add("HUMAN_APPROVAL_OR_POLICY_ALLOWANCE");
        }
    }

    /**
     * 给 workspace 文件工具追加当前仍缺失的生产化条件。
     *
     * <p>这些缺口代表 Java 控制面当前还没有从服务端事实中验证到对应证据，因此必须 fail-closed。
     * 即使 Python 侧已经能在受控 workspace 内执行 read/write，也不能跳过 Java 的 outbox、artifact grant、
     * worker receipt 和审计链路。</p>
     */
    void addMissingRequirements(String toolName, String durableActionState, Set<String> missing) {
        if (!isWorkspaceFileTool(toolName) || isStopState(durableActionState)) {
            return;
        }
        missing.add("WORKSPACE_SCOPE_AUTHORIZATION_REQUIRED");
        missing.add("WORKSPACE_ROOT_ALLOWLIST_REQUIRED");
        missing.add("WORKSPACE_FILE_PATH_REFERENCE_REQUIRED");
        missing.add("WORKSPACE_FILE_ARTIFACT_GRANT_REQUIRED");
        missing.add("WORKSPACE_FILE_WORKER_RECEIPT_REQUIRED");
        if (isWriteTool(toolName)) {
            missing.add("WORKSPACE_FILE_CONTENT_REFERENCE_REQUIRED");
            missing.add("WORKSPACE_FILE_WRITE_CONFLICT_POLICY_REQUIRED");
            missing.add("WORKSPACE_FILE_DLP_OR_MALWARE_SCAN_REQUIRED");
            missing.add("WORKSPACE_FILE_WRITE_APPROVAL_OR_POLICY_ALLOWANCE_REQUIRED");
        }
    }

    /**
     * 给控制台和前端确认页补充 workspace 文件工具的防护说明。
     *
     * <p>说明文本刻意只讲原则，不回显路径、正文、参数或内部 endpoint。这样既能帮助学习和排障，又不会让
     * timeline/projection 变成敏感信息扩散渠道。</p>
     */
    void addGuardrailNotes(String toolName, List<String> notes) {
        if (!isWorkspaceFileTool(toolName)) {
            return;
        }
        notes.add("workspace 文件工具必须由服务端根据 payloadReference 重新读取受控参数，projection 不允许恢复真实路径或文件正文。");
        notes.add("文件读写只能发生在租户/项目/workspace allowlist 内，worker 必须再次校验路径逃逸、隐藏文件、凭据文件和高风险后缀。");
        notes.add("文件结果正文应进入受控 artifact 或脱敏引用；runtime event、projection、timeline 和 command payload 只能保存摘要、hash 与 grant 状态。");
        if (isWriteTool(toolName)) {
            notes.add("workspace.file.write 属于副作用工具，必须校验内容引用、覆盖冲突策略、DLP/恶意内容扫描和人工审批或策略豁免事实。");
        }
    }

    private boolean isWorkspaceFileTool(String toolName) {
        String normalized = normalize(toolName);
        return READ_TOOL_NAME.equals(normalized) || WRITE_TOOL_NAME.equals(normalized);
    }

    private boolean isWriteTool(String toolName) {
        return WRITE_TOOL_NAME.equals(normalize(toolName));
    }

    private boolean isStopState(String durableActionState) {
        String state = normalize(durableActionState);
        return "blocked_before_execution".equals(state) || "rejected_before_readiness".equals(state);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
