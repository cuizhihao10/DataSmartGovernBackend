/**
 * @Author : Cui
 * @Date: 2026-06-23 01:37
 * @Description DataSmart Govern Backend - AgentToolActionCommandSafetyPrecheckService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.config.AgentCommandSafetyPrecheckProperties;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionCommandSafetyPrecheckRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionCommandSafetyPrecheckResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static com.czh.datasmart.govern.agent.service.runtime.AgentCommandSafetyPrecheckDecisionPolicy.DECISION_ALLOW;
import static com.czh.datasmart.govern.agent.service.runtime.AgentCommandSafetyPrecheckDecisionPolicy.DECISION_APPROVAL;
import static com.czh.datasmart.govern.agent.service.runtime.AgentCommandSafetyPrecheckDecisionPolicy.DECISION_BLOCK;

/**
 * Agent 工具动作命令安全预检服务。
 *
 * <p>本服务是 DataSmart Agent Host 从“能规划命令”走向“能安全运行命令”的第一道控制面门禁。
 * 它只回答一件事：模型或外部协议提交的命令是否具备进入受控 worker 的最低安全条件。</p>
 *
 * <p>它刻意不执行命令、不写 outbox、不创建审批单、不读取 payloadReference、不保存命令正文。
 * 这样做的原因是商业化 Agent 的副作用链路必须分层：</p>
 *
 * <p>1. Python Runtime / MCP / A2A 负责把模型工具意图归一为 ToolPlan；</p>
 * <p>2. Java Agent Runtime 负责 safe-cmd、dangerous-path、审批、outbox 和 worker receipt；</p>
 * <p>3. task-management 或专用 worker 才负责真正执行命令，并把结果作为低敏 receipt 回写。</p>
 *
 * <p>这和 Codex/Claude Code 类 Agent 的设计方向一致：模型可以提议“我要运行什么”，但执行边界、
 * 工作区、命令白名单、联网、写入和人工审批必须由 Host 决定。</p>
 */
@Service
@RequiredArgsConstructor
public class AgentToolActionCommandSafetyPrecheckService {

    private static final String POLICY_VERSION = "datasmart.agent-command-safety-precheck.v1";
    private static final String PAYLOAD_POLICY = "LOW_SENSITIVE_COMMAND_SAFETY_PRECHECK_ONLY";

    private final AgentCommandSafetyPrecheckProperties properties;

    /**
     * 对命令执行意图做安全预检。
     *
     * @param request 命令安全预检请求，可以包含命令行、工作区、路径、联网/写入声明和审批事实引用。
     * @param accessContext gateway 或认证层解析出的可信访问上下文，用于租户/项目范围收口。
     * @return 低敏 verdict；不回显命令正文或真实路径，不产生任何副作用。
     */
    public AgentToolActionCommandSafetyPrecheckResponse precheck(
            AgentToolActionCommandSafetyPrecheckRequest request,
            AgentRuntimeEventQueryAccessContext accessContext) {
        AgentToolActionCommandSafetyPrecheckRequest safeRequest = safeRequest(request);
        List<String> issueCodes = new ArrayList<>();
        List<String> reasonCodes = new ArrayList<>();
        Set<String> pathCategories = new LinkedHashSet<>();

        if (!Boolean.TRUE.equals(properties.getEnabled())) {
            add(issueCodes, reasonCodes, "PRECHECK_POLICY_DISABLED", "COMMAND_PRECHECK_POLICY_DISABLED");
        }

        inspectAccessScope(safeRequest, accessContext, issueCodes, reasonCodes);
        AgentCommandSafetySignals commandSignals = inspectCommand(safeRequest, issueCodes, reasonCodes);
        inspectApproval(safeRequest, commandSignals, issueCodes, reasonCodes);
        inspectWorkspaceAndPaths(safeRequest, issueCodes, reasonCodes, pathCategories);

        Integer normalizedTimeoutSeconds = normalizeTimeout(safeRequest.timeoutSeconds(), issueCodes, reasonCodes);
        Integer normalizedOutputByteLimitBytes =
                normalizeOutputLimit(safeRequest.outputByteLimitBytes(), issueCodes, reasonCodes);
        boolean blocked = AgentCommandSafetyPrecheckDecisionPolicy.blocked(issueCodes);
        boolean requiresApproval = AgentCommandSafetyPrecheckDecisionPolicy.requiresApproval(issueCodes);
        boolean executable = !blocked && !requiresApproval;
        String decision = AgentCommandSafetyPrecheckDecisionPolicy.decision(issueCodes);

        return new AgentToolActionCommandSafetyPrecheckResponse(
                decision,
                executable,
                requiresApproval,
                blocked,
                riskLevel(blocked, requiresApproval, commandSignals),
                PAYLOAD_POLICY,
                POLICY_VERSION,
                Instant.now(),
                normalizedTimeoutSeconds,
                normalizedOutputByteLimitBytes,
                List.copyOf(issueCodes),
                List.copyOf(reasonCodes),
                List.copyOf(pathCategories),
                guardrailNotes(decision),
                recommendedActions(decision, issueCodes),
                false,
                false,
                false
        );
    }

    /**
     * 构造空请求兜底。
     *
     * <p>Controller 允许 body 为空，是为了让调用方在联调阶段获得结构化错误，而不是直接得到 400/500。
     * 但服务层必须把空请求视为缺少命令与工作区，不能给出允许执行的结论。</p>
     */
    private AgentToolActionCommandSafetyPrecheckRequest safeRequest(AgentToolActionCommandSafetyPrecheckRequest request) {
        if (request != null) {
            return request;
        }
        return new AgentToolActionCommandSafetyPrecheckRequest(
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null
        );
    }

    /**
     * 校验可信 Header 和请求体声明的租户/项目范围是否一致。
     *
     * <p>命令预检虽然不执行命令，但它是进入执行链路前的重要安全事实。如果允许调用方用 A 租户 Header
     * 给 B 租户命令生成“可执行”verdict，后续 outbox/worker 很容易被错误事实污染。</p>
     */
    private void inspectAccessScope(AgentToolActionCommandSafetyPrecheckRequest request,
                                    AgentRuntimeEventQueryAccessContext accessContext,
                                    List<String> issueCodes,
                                    List<String> reasonCodes) {
        Long requestTenantId = parseLong(request.tenantId());
        if (accessContext != null && accessContext.tenantId() != null
                && requestTenantId != null
                && !accessContext.tenantId().equals(requestTenantId)) {
            add(issueCodes, reasonCodes, "TENANT_SCOPE_MISMATCH", "REQUEST_TENANT_NOT_MATCH_TRUSTED_HEADER");
        }

        Long requestProjectId = parseLong(request.projectId());
        if (accessContext != null
                && accessContext.explicitProjectScope()
                && requestProjectId != null
                && !accessContext.authorizedProjectIdsAsStrings().contains(String.valueOf(requestProjectId))) {
            add(issueCodes, reasonCodes, "PROJECT_SCOPE_NOT_AUTHORIZED", "REQUEST_PROJECT_NOT_IN_AUTHORIZED_SCOPE");
        }
    }

    /**
     * 检查命令文本本身。
     *
     * <p>这里不会尝试做完整 shell 解析，因为 PowerShell、cmd、bash、zsh、Python -c 等语法差异很大。
     * 控制面先做保守的片段匹配：危险片段直接阻断，联网/写入/未知命令进入审批，真正执行器仍应使用更严格的
     * 参数化命令或容器沙箱。</p>
     */
    private AgentCommandSafetySignals inspectCommand(AgentToolActionCommandSafetyPrecheckRequest request,
                                                     List<String> issueCodes,
                                                     List<String> reasonCodes) {
        String commandLine = text(request.commandLine());
        if (commandLine == null) {
            add(issueCodes, reasonCodes, "COMMAND_LINE_REQUIRED", "COMMAND_LINE_IS_EMPTY");
            return new AgentCommandSafetySignals(false, false, false, false);
        }
        if (commandLine.length() > safeMaxCommandChars()) {
            add(issueCodes, reasonCodes, "COMMAND_LINE_TOO_LONG", "COMMAND_LINE_EXCEEDS_CONTROL_PLANE_LIMIT");
        }
        String normalized = normalize(commandLine);
        boolean dangerous = containsFragment(normalized, properties.getDangerousCommandFragments());
        boolean network = Boolean.TRUE.equals(request.networkRequested())
                || containsFragment(normalized, properties.getNetworkCommandFragments());
        boolean write = Boolean.TRUE.equals(request.writeRequested())
                || containsFragment(normalized, properties.getWriteCommandFragments());
        boolean knownSafe = startsWithAllowedPrefix(normalized);

        if (dangerous) {
            add(issueCodes, reasonCodes, "DANGEROUS_COMMAND_FRAGMENT", "COMMAND_MATCHED_DESTRUCTIVE_PATTERN");
        }
        if (!knownSafe && Boolean.TRUE.equals(properties.getApprovalRequiredForUnknownCommand())) {
            add(issueCodes, reasonCodes, "UNKNOWN_COMMAND_REQUIRES_APPROVAL", "COMMAND_NOT_IN_SAFE_ALLOWLIST");
        }
        if (write && Boolean.TRUE.equals(properties.getApprovalRequiredForWrite())) {
            add(issueCodes, reasonCodes, "WRITE_COMMAND_REQUIRES_APPROVAL", "WRITE_OR_STATE_CHANGING_COMMAND");
        }
        if (network && Boolean.TRUE.equals(properties.getApprovalRequiredForNetwork())) {
            add(issueCodes, reasonCodes, "NETWORK_COMMAND_REQUIRES_APPROVAL", "NETWORK_OR_DOWNLOAD_COMMAND");
        }
        if (!dangerous && knownSafe && !write && !network) {
            reasonCodes.add("COMMAND_MATCHED_SAFE_ALLOWLIST");
        }
        return new AgentCommandSafetySignals(knownSafe, dangerous, write, network);
    }

    /**
     * 检查人工审批事实引用。
     *
     * <p>预检只验证“是否需要审批”和“审批引用形态是否安全”。它不直接采信 approvalFactId，后续 worker
     * 必须回查 permission-admin 或 Java 事实仓储，确认审批没有过期、撤销、跨租户或跨 run。</p>
     */
    private void inspectApproval(AgentToolActionCommandSafetyPrecheckRequest request,
                                 AgentCommandSafetySignals commandSignals,
                                 List<String> issueCodes,
                                 List<String> reasonCodes) {
        boolean approvalNeeded = commandSignals.write()
                || commandSignals.network()
                || !commandSignals.knownSafe();
        if (!approvalNeeded) {
            return;
        }
        if (!Boolean.TRUE.equals(request.approvalConfirmed())) {
            return;
        }
        String approvalFactId = text(request.approvalFactId());
        if (approvalFactId == null || !isSafeFactReference(approvalFactId)) {
            add(issueCodes, reasonCodes, "APPROVAL_FACT_UNSAFE", "APPROVAL_FACT_REFERENCE_NOT_SAFE");
            return;
        }
        /*
         * 已提供安全形态的审批引用时，去掉“缺审批会阻断自动执行”的问题码，但保留服务端回查要求。
         * 这样调用方知道下一步可以进入 outbox/worker 复核，而不是误以为预检已经完成审批验证。
         */
        issueCodes.remove("WRITE_COMMAND_REQUIRES_APPROVAL");
        issueCodes.remove("NETWORK_COMMAND_REQUIRES_APPROVAL");
        issueCodes.remove("UNKNOWN_COMMAND_REQUIRES_APPROVAL");
        add(issueCodes, reasonCodes,
                "APPROVAL_FACT_REQUIRES_SERVER_VERIFICATION",
                "APPROVAL_FACT_PRESENT_BUT_MUST_BE_VERIFIED_BY_HOST");
    }

    /**
     * 检查 workspaceRoot、workingDirectory 和 referencedPaths。
     *
     * <p>路径检查是 safe-cmd 的核心之一。即使命令本身是 `cat`、`type` 或 `rg`，只要读取了 .ssh、
     * .aws、.git、系统目录或工作区外路径，都不应该进入普通 Agent worker。</p>
     */
    private void inspectWorkspaceAndPaths(AgentToolActionCommandSafetyPrecheckRequest request,
                                          List<String> issueCodes,
                                          List<String> reasonCodes,
                                          Set<String> pathCategories) {
        Path workspaceRoot = normalizePath(request.workspaceRoot(), "WORKSPACE_ROOT", issueCodes, reasonCodes);
        if (workspaceRoot == null) {
            if (Boolean.TRUE.equals(properties.getWorkspaceRootRequired())) {
                add(issueCodes, reasonCodes, "WORKSPACE_ROOT_REQUIRED", "WORKSPACE_ROOT_IS_REQUIRED_FOR_COMMAND");
            }
            return;
        }
        pathCategories.add("WORKSPACE_ROOT_PRESENT");
        inspectSinglePath("WORKING_DIRECTORY", request.workingDirectory(), workspaceRoot,
                true, issueCodes, reasonCodes, pathCategories);
        for (String path : safeList(request.referencedPaths())) {
            inspectSinglePath("REFERENCED_PATH", path, workspaceRoot,
                    false, issueCodes, reasonCodes, pathCategories);
        }
    }

    /**
     * 检查单个路径是否位于工作区内、是否包含危险片段、是否使用父目录跳转。
     */
    private void inspectSinglePath(String role,
                                   String value,
                                   Path workspaceRoot,
                                   boolean required,
                                   List<String> issueCodes,
                                   List<String> reasonCodes,
                                   Set<String> pathCategories) {
        String raw = text(value);
        if (raw == null) {
            if (required) {
                pathCategories.add(role + "_MISSING");
            }
            return;
        }
        if (containsFragment(normalize(raw), properties.getBlockedPathFragments())) {
            pathCategories.add("BLOCKED_PATH_FRAGMENT");
            add(issueCodes, reasonCodes, "BLOCKED_PATH_FRAGMENT", role + "_MATCHED_BLOCKED_PATH_FRAGMENT");
        }
        if (raw.contains("..")) {
            pathCategories.add("PATH_PARENT_SEGMENT");
            add(issueCodes, reasonCodes,
                    "PATH_PARENT_SEGMENT_REQUIRES_REVIEW",
                    role + "_CONTAINS_PARENT_SEGMENT");
        }
        Path normalizedPath = normalizePath(raw, role, issueCodes, reasonCodes);
        if (normalizedPath == null) {
            return;
        }
        if (!normalizedPath.startsWith(workspaceRoot)) {
            pathCategories.add(role + "_OUTSIDE_WORKSPACE");
            add(issueCodes, reasonCodes,
                    "WORKING_DIRECTORY".equals(role)
                            ? "WORKING_DIRECTORY_OUTSIDE_WORKSPACE"
                            : "REFERENCED_PATH_OUTSIDE_WORKSPACE",
                    role + "_ESCAPED_WORKSPACE");
            return;
        }
        pathCategories.add(role + "_INSIDE_WORKSPACE");
    }

    private Integer normalizeTimeout(Integer timeoutSeconds,
                                     List<String> issueCodes,
                                     List<String> reasonCodes) {
        int requested = timeoutSeconds == null ? safeDefaultTimeoutSeconds() : timeoutSeconds;
        if (requested <= 0) {
            reasonCodes.add("TIMEOUT_DEFAULTED");
            return safeDefaultTimeoutSeconds();
        }
        if (requested > safeMaxTimeoutSeconds()) {
            issueCodes.add("TIMEOUT_CAPPED_TO_POLICY_LIMIT");
            reasonCodes.add("TIMEOUT_EXCEEDS_POLICY_LIMIT");
            return safeMaxTimeoutSeconds();
        }
        return requested;
    }

    private Integer normalizeOutputLimit(Integer outputByteLimitBytes,
                                         List<String> issueCodes,
                                         List<String> reasonCodes) {
        int requested = outputByteLimitBytes == null ? safeDefaultOutputByteLimit() : outputByteLimitBytes;
        if (requested <= 0) {
            reasonCodes.add("OUTPUT_LIMIT_DEFAULTED");
            return safeDefaultOutputByteLimit();
        }
        if (requested > safeMaxOutputByteLimit()) {
            issueCodes.add("OUTPUT_LIMIT_CAPPED_TO_POLICY_LIMIT");
            reasonCodes.add("OUTPUT_LIMIT_EXCEEDS_POLICY_LIMIT");
            return safeMaxOutputByteLimit();
        }
        return requested;
    }

    /**
     * 根据阻断、审批、联网/写入信号计算展示风险等级。
     */
    private String riskLevel(boolean blocked, boolean requiresApproval, AgentCommandSafetySignals signals) {
        if (blocked || signals.dangerous()) {
            return "CRITICAL";
        }
        if (signals.network() || signals.write() || requiresApproval) {
            return "HIGH";
        }
        if (!signals.knownSafe()) {
            return "MEDIUM";
        }
        return "LOW";
    }

    private List<String> guardrailNotes(String decision) {
        List<String> notes = new ArrayList<>();
        notes.add("命令安全预检只生成控制面 verdict，不执行命令、不写文件、不联网、不写 outbox。");
        notes.add("响应不会回显 commandLine、真实路径、stdout/stderr、脚本正文、prompt、SQL、凭据或内部 endpoint。");
        notes.add("真实 worker 必须再次执行 workspace、审批、输出裁剪、超时、权限和 receipt 回写校验。");
        if (DECISION_ALLOW.equals(decision)) {
            notes.add("当前命令只满足进入受控执行链路的最低条件，不代表已经产生任何副作用。");
        }
        return List.copyOf(notes);
    }

    private List<String> recommendedActions(String decision, List<String> issueCodes) {
        List<String> actions = new ArrayList<>();
        if (DECISION_BLOCK.equals(decision)) {
            actions.add("请改写命令，移除危险片段、工作区外路径或被阻断路径后重新预检。");
        } else if (DECISION_APPROVAL.equals(decision)) {
            actions.add("请进入 Human-in-the-loop 审批流程，并由 worker 回查审批事实后再执行。");
        } else {
            actions.add("下一步可进入受控 outbox/worker，但 worker 仍必须按同一策略复核并回写 receipt。");
        }
        if (issueCodes.contains("TIMEOUT_CAPPED_TO_POLICY_LIMIT")
                || issueCodes.contains("OUTPUT_LIMIT_CAPPED_TO_POLICY_LIMIT")) {
            actions.add("命令预算已被服务端裁剪，若任务确实很重，应转为异步任务、分块执行或 artifact 输出。");
        }
        if (issueCodes.contains("APPROVAL_FACT_REQUIRES_SERVER_VERIFICATION")) {
            actions.add("审批引用只通过形态检查，后续必须回查 permission-admin 或 Java 审批事实仓储。");
        }
        return List.copyOf(actions);
    }

    private Path normalizePath(String value,
                               String role,
                               List<String> issueCodes,
                               List<String> reasonCodes) {
        String text = text(value);
        if (text == null) {
            return null;
        }
        try {
            return Path.of(text).toAbsolutePath().normalize();
        } catch (InvalidPathException exception) {
            add(issueCodes, reasonCodes, "PATH_NORMALIZATION_FAILED", role + "_CANNOT_BE_NORMALIZED");
            return null;
        }
    }

    private boolean startsWithAllowedPrefix(String normalizedCommand) {
        for (String prefix : safeList(properties.getSafeCommandPrefixes())) {
            String normalizedPrefix = normalize(prefix);
            if (!normalizedPrefix.isBlank()
                    && (normalizedCommand.equals(normalizedPrefix)
                    || normalizedCommand.startsWith(normalizedPrefix + " "))) {
                return true;
            }
        }
        return false;
    }

    private boolean containsFragment(String normalizedValue, List<String> fragments) {
        for (String fragment : safeList(fragments)) {
            String normalizedFragment = normalize(fragment);
            if (!normalizedFragment.isBlank() && normalizedValue.contains(normalizedFragment)) {
                return true;
            }
        }
        return false;
    }

    private boolean isSafeFactReference(String value) {
        String normalized = normalize(value);
        if (value.length() > 128) {
            return false;
        }
        if (normalized.contains("http://") || normalized.contains("https://")
                || normalized.contains("{") || normalized.contains("}")
                || normalized.contains("\n") || normalized.contains("\r")) {
            return false;
        }
        return normalized.startsWith("approval-fact:")
                || normalized.startsWith("confirmation:")
                || normalized.startsWith("human-approval:")
                || normalized.matches("[a-z0-9:_\\-./]+");
    }

    private void add(List<String> issueCodes, List<String> reasonCodes, String issueCode, String reasonCode) {
        if (!issueCodes.contains(issueCode)) {
            issueCodes.add(issueCode);
        }
        if (!reasonCodes.contains(reasonCode)) {
            reasonCodes.add(reasonCode);
        }
    }

    private Long parseLong(String value) {
        String text = text(value);
        if (text == null) {
            return null;
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private String text(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    private int safeMaxCommandChars() {
        return properties.getMaxCommandChars() == null || properties.getMaxCommandChars() <= 0
                ? 4096
                : properties.getMaxCommandChars();
    }

    private int safeDefaultTimeoutSeconds() {
        return properties.getDefaultTimeoutSeconds() == null || properties.getDefaultTimeoutSeconds() <= 0
                ? 30
                : properties.getDefaultTimeoutSeconds();
    }

    private int safeMaxTimeoutSeconds() {
        return properties.getMaxTimeoutSeconds() == null || properties.getMaxTimeoutSeconds() <= 0
                ? 120
                : properties.getMaxTimeoutSeconds();
    }

    private int safeDefaultOutputByteLimit() {
        return properties.getDefaultOutputByteLimit() == null || properties.getDefaultOutputByteLimit() <= 0
                ? 16 * 1024
                : properties.getDefaultOutputByteLimit();
    }

    private int safeMaxOutputByteLimit() {
        return properties.getMaxOutputByteLimit() == null || properties.getMaxOutputByteLimit() <= 0
                ? 64 * 1024
                : properties.getMaxOutputByteLimit();
    }

}
