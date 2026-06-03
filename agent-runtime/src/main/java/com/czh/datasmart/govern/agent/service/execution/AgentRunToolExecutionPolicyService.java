/**
 * @Author : Cui
 * @Date: 2026/05/29 18:45
 * @Description DataSmart Govern Backend - AgentRunToolExecutionPolicyService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.execution;

import com.czh.datasmart.govern.agent.config.AgentRuntimeProperties;
import com.czh.datasmart.govern.agent.controller.dto.AgentRunToolExecutionPolicyItemView;
import com.czh.datasmart.govern.agent.controller.dto.AgentRunToolExecutionPolicyView;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolExecutionAuditView;
import com.czh.datasmart.govern.agent.model.AgentRunState;
import com.czh.datasmart.govern.agent.model.AgentToolExecutionMode;
import com.czh.datasmart.govern.agent.model.AgentToolExecutionState;
import com.czh.datasmart.govern.agent.service.AgentToolExecutionAuditService;
import com.czh.datasmart.govern.agent.service.audit.AgentToolExecutionAuditRecord;
import com.czh.datasmart.govern.agent.service.session.AgentRunRecord;
import com.czh.datasmart.govern.agent.service.session.AgentSessionMemoryStore;
import com.czh.datasmart.govern.agent.service.session.AgentSessionRecord;
import com.czh.datasmart.govern.agent.service.tool.sandbox.AgentToolSandboxPolicyService;
import com.czh.datasmart.govern.agent.service.tool.sandbox.AgentToolSandboxVerdict;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Agent Run 级工具执行策略预检服务。
 *
 * <p>这个服务是“从审计状态走向安全自动执行”的中间层。它只读、不执行、不审批、不修改状态，
 * 负责把工具审计记录、Run 状态、参数校验、执行模式、风险等级和幂等性翻译成可消费的策略视图。
 *
 * <p>为什么需要它：
 * 1. Python AI Runtime 需要知道下一轮应该等待人工、补参数、轮询异步任务，还是可以请求 Java 执行低风险同步工具；
 * 2. 前端需要据此控制按钮和提示文案，不能让用户在 WAITING_APPROVAL 时误点执行；
 * 3. 后续自动执行器上线前，需要先稳定“哪些工具允许自动执行”的只读契约，避免一边查询一边产生副作用；
 * 4. 商业化系统必须把失败重试、非幂等阻断、Run 终态保护等规则前置到统一策略层。
 */
@Service
public class AgentRunToolExecutionPolicyService {

    private final AgentRuntimeProperties properties;
    private final AgentSessionMemoryStore sessionMemoryStore;
    private final AgentToolExecutionAuditService auditService;
    private final AgentToolSandboxPolicyService sandboxPolicyService;

    /**
     * Spring 运行时构造函数。
     *
     * <p>生产路径必须注入配置化的沙箱策略服务，使 execution-policy 与真实 execute 入口看到同一套沙箱规则。
     * 这能避免前端看到“可执行”，但真实 execute 被 Guard 拒绝的口径漂移。</p>
     */
    public AgentRunToolExecutionPolicyService(AgentRuntimeProperties properties,
                                              AgentSessionMemoryStore sessionMemoryStore,
                                              AgentToolExecutionAuditService auditService,
                                              AgentToolSandboxPolicyService sandboxPolicyService) {
        this.properties = properties;
        this.sessionMemoryStore = sessionMemoryStore;
        this.auditService = auditService;
        this.sandboxPolicyService = sandboxPolicyService;
    }

    /**
     * 旧单元测试兼容构造函数。
     *
     * <p>仓库里已有不少测试只验证 execution-policy 的原始状态机语义，并未配置工具目录。
     * 为避免这些测试因为“没有工具目录”被沙箱全部阻断，这里提供一个关闭沙箱的兼容路径。
     * 新增沙箱集成测试应显式使用四参数构造函数。</p>
     */
    public AgentRunToolExecutionPolicyService(AgentRuntimeProperties properties,
                                              AgentSessionMemoryStore sessionMemoryStore,
                                              AgentToolExecutionAuditService auditService) {
        this(disableSandbox(properties), sessionMemoryStore, auditService,
                new AgentToolSandboxPolicyService(disableSandbox(properties)));
    }

    /**
     * 查询某个 Run 的工具执行策略预检。
     *
     * <p>该方法首先校验 Agent Runtime 是否启用、session 是否存在、run 是否属于该 session。
     * 之后读取当前工具审计快照，并为每条工具生成策略项。整个过程没有任何写操作，因此可以被前端频繁刷新、
     * 被 Python Runtime 在多步推理前调用，也可以被未来自动执行器作为执行前的第一道只读检查。
     *
     * @param sessionId Agent 会话 ID，用于限定工作空间和防止跨会话读取。
     * @param runId Agent Run ID，用于限定本次编排尝试。
     * @return Run 级策略预检视图。
     */
    public AgentRunToolExecutionPolicyView inspectRunPolicy(String sessionId, String runId) {
        ensureRuntimeEnabled();
        AgentSessionRecord session = requireSession(sessionId);
        AgentRunRecord run = requireRun(session, runId);
        List<AgentRunToolExecutionPolicyItemView> items = auditService.listByRun(sessionId, runId).stream()
                .map(audit -> inspectTool(session, run, audit))
                .toList();
        return toRunView(sessionId, run, items);
    }

    /**
     * 把单条工具审计快照转换为策略项。
     *
     * <p>这里刻意使用显式分支，而不是压缩成复杂表达式。原因是这些分支正是 Agent 产品最重要的治理规则：
     * 审批等待、参数缺失、执行模式、失败重试和终态保护都应该能被学习者直接读懂，也方便后续接入租户策略、
     * permission-admin 权限、工具健康状态和队列容量时继续扩展。
     */
    private AgentRunToolExecutionPolicyItemView inspectTool(AgentSessionRecord session,
                                                            AgentRunRecord run,
                                                            AgentToolExecutionAuditView audit) {
        List<String> reasons = new ArrayList<>();
        List<String> actions = new ArrayList<>();
        AgentRunToolExecutionDecision decision;
        boolean autoExecutable = false;
        boolean requiresHumanAction = false;
        boolean blocksRun = false;

        AgentToolExecutionState state = parseExecutionState(audit.state());
        AgentToolExecutionMode mode = parseExecutionMode(audit.executionMode());
        AgentToolSandboxVerdict sandbox = inspectSandbox(session, run, audit);

        if (run.getState().isTerminal()) {
            decision = AgentRunToolExecutionDecision.RUN_TERMINAL_BLOCKED;
            blocksRun = true;
            reasons.add("所属 Agent Run 已进入终态，不能再推进任何工具执行，避免历史审计事实被回写或重放。");
            actions.add("如需继续处理，请在同一会话中创建新的 Agent Run，而不是复用终态 Run。");
        } else if (state == AgentToolExecutionState.WAITING_APPROVAL) {
            decision = AgentRunToolExecutionDecision.WAITING_APPROVAL;
            requiresHumanAction = true;
            blocksRun = true;
            reasons.add("工具处于 WAITING_APPROVAL，说明风险较高或工具目录显式要求人工确认。");
            actions.add("由项目负责人、运营人员或具备审批权限的用户确认后，再允许进入执行候选。");
        } else if (hasMissingParameters(audit.parameterValidation())) {
            decision = AgentRunToolExecutionDecision.WAITING_PARAMETER_COMPLETION;
            requiresHumanAction = true;
            blocksRun = true;
            reasons.add("参数校验结果仍包含 missingFields，当前工具没有足够输入，不能安全调用下游服务。");
            actions.add("让用户补充缺失字段，或由 Agent 重新规划并从上下文、记忆、数据源元数据中补齐参数。");
        } else if (state == AgentToolExecutionState.PLANNED && !Boolean.TRUE.equals(sandbox.allowed())) {
            decision = AgentRunToolExecutionDecision.BLOCKED_BY_POLICY;
            requiresHumanAction = true;
            blocksRun = true;
            reasons.add("工具处于 PLANNED，但工具调用沙箱预检未通过，不能进入同步、异步或人工绕过执行入口。");
            reasons.addAll(sandbox.reasons());
            actions.add("先处理沙箱 issueCodes=" + sandbox.issueCodes() + " 对应的配置、审批、参数或目标服务问题，再重新预检。");
            actions.addAll(sandbox.recommendedActions());
        } else if (state == AgentToolExecutionState.PLANNED) {
            ToolPlannedPolicy plannedPolicy = inspectPlannedTool(mode, audit, reasons, actions);
            decision = plannedPolicy.decision();
            autoExecutable = plannedPolicy.autoExecutable();
            requiresHumanAction = plannedPolicy.requiresHumanAction();
            blocksRun = plannedPolicy.blocksRun();
        } else if (state == AgentToolExecutionState.EXECUTING) {
            decision = AgentRunToolExecutionDecision.ALREADY_EXECUTING;
            reasons.add("工具已经处于 EXECUTING，重复触发会造成并发调用或重复写入风险。");
            actions.add("等待工具执行结果事件或轮询结果查询接口，不要再次调用 execute。");
        } else if (state == AgentToolExecutionState.SUCCEEDED) {
            decision = AgentRunToolExecutionDecision.ALREADY_SUCCEEDED;
            reasons.add("工具已经成功完成，可以把安全输出摘要或资源引用回填给模型进入二轮推理。");
            actions.add("读取工具结果快照，按资源准入和字段级过滤策略构造模型 tool result。");
        } else if (state == AgentToolExecutionState.FAILED) {
            FailedPolicy failedPolicy = inspectFailedTool(audit, reasons, actions);
            decision = failedPolicy.decision();
            requiresHumanAction = failedPolicy.requiresHumanAction();
            blocksRun = failedPolicy.blocksRun();
        } else if (state == AgentToolExecutionState.SKIPPED) {
            decision = AgentRunToolExecutionDecision.SKIPPED_TERMINAL;
            blocksRun = true;
            reasons.add("工具已被跳过，通常表示人工拒绝、策略禁止或前置条件不满足。");
            actions.add("向用户解释被跳过工具的影响范围，并根据业务目标决定是否重新规划。");
        } else if (state == AgentToolExecutionState.CANCELLED) {
            decision = AgentRunToolExecutionDecision.CANCELLED_TERMINAL;
            blocksRun = true;
            reasons.add("工具已取消，不应继续执行或重放。");
            actions.add("如仍需执行，请创建新的工具计划和审计记录。");
        } else {
            decision = AgentRunToolExecutionDecision.BLOCKED_BY_POLICY;
            requiresHumanAction = true;
            blocksRun = true;
            reasons.add("工具状态或执行模式组合尚未被策略层识别，商业化环境应默认保守阻断。");
            actions.add("补充策略规则或人工复核后再继续。");
        }

        return new AgentRunToolExecutionPolicyItemView(
                audit.auditId(),
                audit.toolCode(),
                audit.state(),
                audit.executionMode(),
                audit.riskLevel(),
                audit.requiresApproval(),
                audit.readOnly(),
                audit.idempotent(),
                decision.name(),
                autoExecutable,
                requiresHumanAction,
                blocksRun,
                sandbox.allowed(),
                sandbox.isolationMode(),
                sandbox.issueCodes(),
                sandbox.reasons(),
                sandbox.recommendedActions(),
                List.copyOf(reasons),
                List.copyOf(actions)
        );
    }

    /**
     * 读取真实审计记录并执行工具沙箱预检。
     *
     * <p>execution-policy 原本只使用 {@link AgentToolExecutionAuditView}，但沙箱需要读取审批人、计划参数、
     * governance hints 等完整审计事实。这里复用审计服务已有的 requireExecutionAuditRecord(...)，不新增审计服务方法，
     * 避免继续扩大已经较重的审计服务文件。</p>
     */
    private AgentToolSandboxVerdict inspectSandbox(AgentSessionRecord session,
                                                   AgentRunRecord run,
                                                   AgentToolExecutionAuditView audit) {
        AgentToolExecutionAuditRecord record = auditService.requireExecutionAuditRecord(
                session.getSessionId(),
                run.getRunId(),
                audit.auditId()
        );
        return sandboxPolicyService.inspect(session, run, record);
    }

    /**
     * 处理 PLANNED 状态工具。
     *
     * <p>PLANNED 只是“已经生成计划”，不代表所有工具都能同步自动执行。
     * 本方法按执行模式进一步拆分：SYNC 可以进入自动执行候选；ASYNC_TASK 应交给队列或任务中心；
     * DRAFT_ONLY 只允许形成草稿；APPROVAL_REQUIRED 理论上应在创建审计时进入 WAITING_APPROVAL，若仍停留在 PLANNED，
     * 策略层会保守要求人工复核，避免审批状态遗漏。</p>
     */
    private ToolPlannedPolicy inspectPlannedTool(AgentToolExecutionMode mode,
                                                 AgentToolExecutionAuditView audit,
                                                 List<String> reasons,
                                                 List<String> actions) {
        if (mode == AgentToolExecutionMode.SYNC) {
            reasons.add("工具处于 PLANNED 且 executionMode=SYNC，适合同步短耗时调用。");
            reasons.add("当前查询只标记为自动执行候选，真实执行前仍需经过工具执行守卫和下游适配器校验。");
            actions.add("自动执行器可以按限流、并发和优先级策略选择该工具进入 execute 流程。");
            return new ToolPlannedPolicy(AgentRunToolExecutionDecision.AUTO_EXECUTABLE, true, false, false);
        }
        if (mode == AgentToolExecutionMode.ASYNC_TASK) {
            reasons.add("工具是 ASYNC_TASK，通常代表长耗时或批量任务，不应阻塞 Agent HTTP 请求线程。");
            actions.add("创建 task-management 任务或发送 Kafka command，由异步执行器推进状态。");
            return new ToolPlannedPolicy(AgentRunToolExecutionDecision.WAITING_ASYNC_EXECUTOR, false, false, false);
        }
        if (mode == AgentToolExecutionMode.DRAFT_ONLY) {
            reasons.add("工具是 DRAFT_ONLY，只允许生成草稿、建议或可审查方案，不允许 Agent 自动提交。");
            actions.add("把草稿展示给用户确认，或由后续审批流程转成可执行任务。");
            return new ToolPlannedPolicy(AgentRunToolExecutionDecision.DRAFT_ONLY_REVIEW, false, true, true);
        }
        if (mode == AgentToolExecutionMode.APPROVAL_REQUIRED || Boolean.TRUE.equals(audit.requiresApproval())) {
            reasons.add("工具声明 APPROVAL_REQUIRED 或 requiresApproval=true，但当前审计状态仍是 PLANNED，需要人工复核状态一致性。");
            actions.add("检查审计创建逻辑、工具目录风险配置和审批记录，确认后再继续执行。");
            return new ToolPlannedPolicy(AgentRunToolExecutionDecision.WAITING_APPROVAL, false, true, true);
        }
        reasons.add("工具执行模式为空或未知，策略层无法判断是否可以安全执行。");
        actions.add("补齐工具目录 executionMode 配置后重新预检。");
        return new ToolPlannedPolicy(AgentRunToolExecutionDecision.BLOCKED_BY_POLICY, false, true, true);
    }

    /**
     * 处理失败工具的后续策略。
     *
     * <p>失败后的核心问题是“能不能自动重试”。幂等工具通常可重试，但仍要结合错误码、下游健康和重试次数；
     * 非幂等工具可能已经在下游产生了部分副作用，不能因为 Java 控制面只看到 FAILED 就盲目再次调用。
     */
    private FailedPolicy inspectFailedTool(AgentToolExecutionAuditView audit,
                                           List<String> reasons,
                                           List<String> actions) {
        if (Boolean.TRUE.equals(audit.idempotent())) {
            reasons.add("工具已失败，但工具目录声明 idempotent=true，具备进入受控重试候选的基础条件。");
            actions.add("检查错误码、下游健康和重试次数；满足策略后可由执行器创建重试尝试。");
            return new FailedPolicy(AgentRunToolExecutionDecision.FAILED_CAN_RETRY, true, false);
        }
        reasons.add("工具已失败且不是幂等工具，重复执行可能造成重复写入、重复任务或不一致副作用。");
        actions.add("需要人工复核下游实际状态，再决定补偿、跳过、重试或新建 Run。");
        return new FailedPolicy(AgentRunToolExecutionDecision.FAILED_BLOCKS_RUN, true, true);
    }

    /**
     * 生成 Run 级汇总视图。
     *
     * <p>汇总层把多条工具策略压缩成前端和编排器最关心的几个布尔值：
     * 是否有可自动执行工具、是否需要人工动作、是否阻塞 Run。这样调用方不必每次自己遍历工具列表推断全局状态。</p>
     */
    private AgentRunToolExecutionPolicyView toRunView(String sessionId,
                                                      AgentRunRecord run,
                                                      List<AgentRunToolExecutionPolicyItemView> items) {
        int autoExecutableCount = (int) items.stream().filter(AgentRunToolExecutionPolicyItemView::autoExecutable).count();
        int humanActionCount = (int) items.stream().filter(AgentRunToolExecutionPolicyItemView::requiresHumanAction).count();
        int blockingCount = (int) items.stream().filter(AgentRunToolExecutionPolicyItemView::blocksRun).count();
        List<String> summaryReasons = buildSummaryReasons(run.getState(), items, autoExecutableCount, humanActionCount, blockingCount);
        List<String> recommendedActions = buildRunActions(items, autoExecutableCount, humanActionCount, blockingCount);
        return new AgentRunToolExecutionPolicyView(
                sessionId,
                run.getRunId(),
                run.getState().name(),
                run.getState().isTerminal(),
                items.size(),
                autoExecutableCount,
                humanActionCount,
                blockingCount,
                autoExecutableCount > 0,
                humanActionCount > 0,
                blockingCount > 0,
                summaryReasons,
                recommendedActions,
                items
        );
    }

    private List<String> buildSummaryReasons(AgentRunState runState,
                                             List<AgentRunToolExecutionPolicyItemView> items,
                                             int autoExecutableCount,
                                             int humanActionCount,
                                             int blockingCount) {
        List<String> reasons = new ArrayList<>();
        if (items.isEmpty()) {
            reasons.add("当前 Run 尚未产生工具审计记录，可能仍在模型规划阶段，或本次目标不需要调用平台工具。");
        }
        if (runState.isTerminal()) {
            reasons.add("Run 已是终态，所有工具自动推进都被策略层阻断。");
        }
        if (autoExecutableCount > 0) {
            reasons.add("存在可进入同步自动执行候选的工具，但查询接口不会产生任何执行副作用。");
        }
        if (humanActionCount > 0) {
            reasons.add("存在需要人工审批、补参数或复核失败的工具。");
        }
        if (blockingCount > 0) {
            reasons.add("存在阻塞 Run 自动继续推进的工具，需要先处理阻断项。");
        }
        if (!items.isEmpty() && autoExecutableCount == 0 && humanActionCount == 0 && blockingCount == 0) {
            reasons.add("当前没有人工阻断项；如果所有工具都已成功，可进入结果回填和二轮推理。");
        }
        return reasons;
    }

    private List<String> buildRunActions(List<AgentRunToolExecutionPolicyItemView> items,
                                         int autoExecutableCount,
                                         int humanActionCount,
                                         int blockingCount) {
        List<String> actions = new ArrayList<>();
        if (items.isEmpty()) {
            actions.add("等待模型规划或 AgentPlan ingestion 生成工具审计记录。");
        }
        if (humanActionCount > 0) {
            actions.add("优先处理审批、参数补全或失败复核，避免 Agent 在不完整上下文下继续调用工具。");
        }
        if (autoExecutableCount > 0 && blockingCount == 0) {
            actions.add("可由后续自动执行器按并发、限流、幂等和权限策略挑选候选工具执行。");
        }
        boolean hasAsync = items.stream()
                .anyMatch(item -> AgentRunToolExecutionDecision.WAITING_ASYNC_EXECUTOR.name().equals(item.decision()));
        if (hasAsync) {
            actions.add("将异步工具交给 task-management、Kafka command 或专用 worker，并通过事件回写状态。");
        }
        boolean allSucceeded = !items.isEmpty() && items.stream()
                .allMatch(item -> AgentRunToolExecutionDecision.ALREADY_SUCCEEDED.name().equals(item.decision()));
        if (allSucceeded) {
            actions.add("所有工具均已成功，可读取结果快照并按资源准入策略回填模型上下文。");
        }
        return actions;
    }

    private boolean hasMissingParameters(Map<String, Object> parameterValidation) {
        if (parameterValidation == null || parameterValidation.isEmpty()) {
            return false;
        }
        Object missingFields = parameterValidation.get("missingFields");
        return missingFields instanceof Collection<?> collection && !collection.isEmpty();
    }

    private AgentToolExecutionState parseExecutionState(String state) {
        try {
            return AgentToolExecutionState.valueOf(normalizeEnumName(state));
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private AgentToolExecutionMode parseExecutionMode(String executionMode) {
        try {
            return AgentToolExecutionMode.valueOf(normalizeEnumName(executionMode));
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private String normalizeEnumName(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private void ensureRuntimeEnabled() {
        if (!Boolean.TRUE.equals(properties.getEnabled())) {
            throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT, "Agent Runtime 当前未启用");
        }
    }

    private static AgentRuntimeProperties disableSandbox(AgentRuntimeProperties properties) {
        AgentRuntimeProperties safeProperties = properties == null ? new AgentRuntimeProperties() : properties;
        safeProperties.getToolSandbox().setEnabled(false);
        return safeProperties;
    }

    private AgentSessionRecord requireSession(String sessionId) {
        return sessionMemoryStore.findById(sessionId)
                .orElseThrow(() -> new PlatformBusinessException(
                        PlatformErrorCode.NOT_FOUND,
                        "Agent 会话不存在，sessionId=" + sessionId
                ));
    }

    private AgentRunRecord requireRun(AgentSessionRecord session, String runId) {
        return session.getRuns().stream()
                .filter(run -> run.getRunId().equals(runId))
                .findFirst()
                .orElseThrow(() -> new PlatformBusinessException(
                        PlatformErrorCode.NOT_FOUND,
                        "Agent Run 不属于当前会话或不存在，runId=" + runId
                ));
    }

    private record ToolPlannedPolicy(AgentRunToolExecutionDecision decision,
                                     boolean autoExecutable,
                                     boolean requiresHumanAction,
                                     boolean blocksRun) {
    }

    private record FailedPolicy(AgentRunToolExecutionDecision decision,
                                boolean requiresHumanAction,
                                boolean blocksRun) {
    }
}
