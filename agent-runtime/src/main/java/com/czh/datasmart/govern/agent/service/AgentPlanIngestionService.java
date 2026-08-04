/**
 * @Author : Cui
 * @Date: 2026/05/24 00:00
 * @Description DataSmart Govern Backend - AgentPlanIngestionService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service;

import com.czh.datasmart.govern.agent.config.AgentRuntimeProperties;
import com.czh.datasmart.govern.agent.controller.dto.AgentRunView;
import com.czh.datasmart.govern.agent.controller.dto.AgentConversationMessageView;
import com.czh.datasmart.govern.agent.controller.dto.AgentDelegationView;
import com.czh.datasmart.govern.agent.controller.dto.AgentSessionView;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolBindingView;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolDefinitionView;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolExecutionAuditView;
import com.czh.datasmart.govern.agent.controller.dto.AgentWorkspaceView;
import com.czh.datasmart.govern.agent.controller.dto.IngestAgentPlanRequest;
import com.czh.datasmart.govern.agent.controller.dto.IngestAgentPlanToolRequest;
import com.czh.datasmart.govern.agent.controller.dto.IngestedAgentPlanView;
import com.czh.datasmart.govern.agent.model.AgentRunState;
import com.czh.datasmart.govern.agent.model.AgentToolBindingStatus;
import com.czh.datasmart.govern.agent.model.AgentToolExecutionMode;
import com.czh.datasmart.govern.agent.model.AgentToolRiskLevel;
import com.czh.datasmart.govern.agent.model.AgentToolType;
import com.czh.datasmart.govern.agent.model.ModelWorkloadType;
import com.czh.datasmart.govern.agent.model.WorkspaceIsolationLevel;
import com.czh.datasmart.govern.agent.service.plan.AgentPlanIngestionIdempotencySupport;
import com.czh.datasmart.govern.agent.service.plan.AgentPlanToolSnapshot;
import com.czh.datasmart.govern.agent.service.session.AgentRunRecord;
import com.czh.datasmart.govern.agent.service.session.AgentConversationMessageRecord;
import com.czh.datasmart.govern.agent.service.session.AgentSessionStore;
import com.czh.datasmart.govern.agent.service.session.AgentSessionRecord;
import com.czh.datasmart.govern.agent.service.session.AgentToolBindingRecord;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Python AgentPlan 接入 Java Agent Runtime 控制面的服务。
 *
 * <p>该服务落地一条非常重要的跨运行时边界：
 * <strong>Python 负责智能规划，Java 负责受控治理。</strong>
 * Python Runtime 可以结合模型网关、Skill、MCP-style 工具描述符、记忆检索和参数校验生成 AgentPlan；
 * 但 AgentPlan 进入真实业务系统前，必须先被 Java 控制面接收、固化为 Run、生成工具审计计划，并根据风险进入审批。
 *
 * <p>当前实现仍使用内存仓储，这是 agent-runtime 第一阶段的工程现实。
 * 但业务语义已经按商业化目标设计：
 * 1. 未注册或禁用工具不允许从 Python 计划进入 Java 审计；
 * 2. Python 的高风险判断会和 Java 工具目录风险合并，取更安全的一侧；
 * 3. 工具计划只会创建审计，不会自动执行；
 * 4. 模型网关治理、记忆检索、参数校验结果都会进入 Run 变量和工具审计，便于后续审批、回放和评估。
 */
@Service
@RequiredArgsConstructor
public class AgentPlanIngestionService {

    private static final String DEFAULT_CHANNEL = "PYTHON_AI_RUNTIME";

    private final AgentRuntimeProperties properties;
    private final AgentSessionStore sessionMemoryStore;
    private final AgentToolRegistryService toolRegistryService;
    private final AgentToolExecutionAuditService auditService;
    private final AgentPlanIngestionIdempotencySupport idempotencySupport;

    /**
     * 接入 Python AgentPlan，并创建 Java 控制面运行记录与工具审计计划。
     *
     * <p>状态流转说明：
     * 1. 如果请求没有 sessionId，创建一个新的 ACTIVE 会话；
     * 2. 如果请求带 sessionId，校验租户、项目、工作空间、actor 与已有会话一致；
     * 3. 将 ToolPlan 逐个与 Java 工具目录合并，拒绝未知工具；
     * 4. 创建 AgentRunRecord。只要计划整体或任一工具需要审批，Run 初始状态就是 WAITING_HUMAN；
     * 5. 为每个 ToolPlan 创建 AgentToolExecutionAuditRecord；
     * 6. 返回会话、运行和审计视图，供前端、审批流或后续执行入口继续使用。
     *
     * @param request Python Runtime 产出的 AgentPlan 快照。
     * @param traceId 平台链路追踪 ID。
     * @return Java 控制面接入结果。
     */
    public IngestedAgentPlanView ingest(IngestAgentPlanRequest request, String traceId) {
        ensureRuntimeEnabled();
        /*
         * 幂等检查必须放在创建会话和 Run 之前。
         * 这样 HTTP 超时重试或未来 Kafka 重复投递时，不会因为同一份 Python AgentPlan 被重复接入而创建多份 Run、
         * 多份工具审批单或多条审计计划。
         */
        var replay = idempotencySupport.findReplay(request);
        if (replay.isPresent()) {
            return replay.get();
        }
        AgentSessionRecord session = resolveSession(request);
        List<AgentPlanToolSnapshot> toolSnapshots = normalizeToolPlans(request);
        synchronized (session) {
            ensureRunCapacityOrSupersedePendingPlan(session);
            bindMissingTools(session, toolSnapshots);
            AgentRunRecord run = createRun(session, request, toolSnapshots);
            session.addRun(run);
            appendConversationMessages(session, run, request);
            sessionMemoryStore.save(session);
            AgentSessionView sessionView = toSessionView(session);
            AgentRunView runView = toRunView(run);
            List<AgentToolExecutionAuditView> audits = auditService.createPlanAuditsFromSnapshots(
                    sessionView,
                    runView,
                    toolSnapshots,
                    traceId
            );
            IngestedAgentPlanView view = new IngestedAgentPlanView(sessionView, runView, audits, controlPlaneNotes(run, audits));
            idempotencySupport.remember(request, view);
            return view;
        }
    }

    /**
     * 把本次计划接入对应的用户输入和 Agent 摘要写入持久会话。
     *
     * <p>两条消息共享 runId，便于历史页面把自然语言上下文与一次具体执行关联。内容上限为 20000 字符，
     * 防止异常模型输出无限放大数据库记录；Agent 消息时间增加 1 纳秒以保证相同数据库精度下仍按用户消息
     * 之后排序。空白输入不会生成噪声消息。</p>
     */
    private void appendConversationMessages(AgentSessionRecord session,
                                            AgentRunRecord run,
                                            IngestAgentPlanRequest request) {
        LocalDateTime now = LocalDateTime.now();
        if (request.userInput() != null && !request.userInput().isBlank()) {
            session.addMessage(new AgentConversationMessageRecord(
                    "agm_" + UUID.randomUUID().toString().replace("-", ""),
                    run.getRunId(), "USER", preview(request.userInput(), 20000), now));
        }
        if (request.responseSummary() != null && !request.responseSummary().isBlank()) {
            session.addMessage(new AgentConversationMessageRecord(
                    "agm_" + UUID.randomUUID().toString().replace("-", ""),
                    run.getRunId(), "AGENT", preview(request.responseSummary(), 20000), now.plusNanos(1)));
        }
    }

    private AgentSessionRecord resolveSession(IngestAgentPlanRequest request) {
        if (request.sessionId() == null || request.sessionId().isBlank()) {
            AgentSessionRecord session = createSession(request);
            sessionMemoryStore.save(session);
            return session;
        }
        AgentSessionRecord session = sessionMemoryStore.findById(request.sessionId())
                .orElseThrow(() -> new PlatformBusinessException(PlatformErrorCode.NOT_FOUND,
                        "Agent 会话不存在，无法接入 Python AgentPlan，sessionId=" + request.sessionId()));
        ensureSameBoundary(session, request);
        return session;
    }

    private AgentSessionRecord createSession(IngestAgentPlanRequest request) {
        WorkspaceIsolationLevel isolationLevel = request.isolationLevel() == null
                ? WorkspaceIsolationLevel.PROJECT
                : request.isolationLevel();
        String sessionId = "ags_" + UUID.randomUUID().toString().replace("-", "");
        LocalDateTime now = LocalDateTime.now();
        return new AgentSessionRecord(
                sessionId,
                request.tenantId(),
                request.projectId(),
                request.workspaceId(),
                request.actorId(),
                request.actorRole(),
                request.actorType(),
                request.authorizedProjectRoles(),
                normalizeChannel(request.channel()),
                request.objective(),
                isolationLevel,
                buildWorkspaceKey(isolationLevel, request.tenantId(), request.projectId(), request.workspaceId(), sessionId),
                now
        );
    }

    private void ensureSameBoundary(AgentSessionRecord session, IngestAgentPlanRequest request) {
        if (!Objects.equals(session.getTenantId(), request.tenantId())
                || !Objects.equals(session.getProjectId(), request.projectId())
                || !Objects.equals(session.getWorkspaceId(), request.workspaceId())
                || !Objects.equals(session.getActorId(), request.actorId())) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "Python AgentPlan 的租户/项目/工作空间/actor 与已有会话不一致，拒绝接入以避免跨边界混写。");
        }
    }

    private List<AgentPlanToolSnapshot> normalizeToolPlans(IngestAgentPlanRequest request) {
        List<IngestAgentPlanToolRequest> toolPlans = request.toolPlans() == null ? List.of() : request.toolPlans();
        List<AgentPlanToolSnapshot> snapshots = new ArrayList<>();
        for (int index = 0; index < toolPlans.size(); index++) {
            IngestAgentPlanToolRequest plan = toolPlans.get(index);
            AgentToolDefinitionView definition = resolveToolDefinition(plan);
            String mergedRiskLevel = higherRisk(definition.riskLevel(), plan.riskLevel());
            boolean requiresApproval = Boolean.TRUE.equals(definition.requiresApproval())
                    || Boolean.TRUE.equals(plan.requiresHumanApproval())
                    || isHighRisk(mergedRiskLevel);
            snapshots.add(new AgentPlanToolSnapshot(
                    index + 1,
                    definition.toolCode(),
                    definition.toolType(),
                    definition.targetService(),
                    definition.targetEndpoint(),
                    plan.targetResourceId(),
                    definition.readOnly(),
                    mergedRiskLevel,
                    executionMode(definition.executionMode(), plan.executionMode(), requiresApproval),
                    requiresApproval,
                    definition.idempotent(),
                    definition.allowedActions(),
                    plan.reason(),
                    safeMap(plan.arguments()),
                    safeMap(plan.governanceHints()),
                    safeMap(plan.parameterValidation())
            ));
        }
        return snapshots;
    }

    /**
     * Resolve a static platform tool or a dynamically discovered outbound MCP tool.
     *
     * <p>MCP tools cannot be copied into {@code application.yml}: their catalog is
     * discovered by the Python MCP host at runtime.  Java still fail-closes the
     * trust boundary by accepting only the namespaced {@code mcp.*} form and a
     * fixed Python consumer.  The model supplied endpoint is never used by Java;
     * the dispatcher sends only the internal name and Python validates it against
     * its latest discovered catalog before {@code tools/call}.</p>
     */
    private AgentToolDefinitionView resolveToolDefinition(IngestAgentPlanToolRequest plan) {
        var registered = toolRegistryService.findTool(plan.toolCode());
        if (registered.isPresent()) {
            AgentToolDefinitionView definition = registered.get();
            if (!Boolean.TRUE.equals(definition.enabled())) {
                throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                        "Agent 工具已禁用，toolCode=" + plan.toolCode());
            }
            return definition;
        }
        if (!isGovernedMcpTool(plan)) {
            throw new PlatformBusinessException(PlatformErrorCode.NOT_FOUND,
                    "Agent 工具未注册，toolCode=" + plan.toolCode());
        }
        boolean readOnly = Boolean.TRUE.equals(plan.governanceHints().get("readOnly"));
        boolean idempotent = Boolean.TRUE.equals(plan.governanceHints().get("idempotent"));
        return new AgentToolDefinitionView(
                plan.toolCode(),
                true,
                AgentToolType.MCP_EXTERNAL_TOOL.name(),
                plan.toolCode(),
                "Python MCP Host 动态发现的受治理外部工具",
                "python-ai-runtime-mcp-client",
                null,
                readOnly,
                AgentToolRiskLevel.HIGH.name(),
                AgentToolExecutionMode.ASYNC_TASK.name(),
                true,
                idempotent,
                60_000L,
                idempotent ? 1 : 0,
                List.of("MCP_TOOLS_CALL"),
                List.of()
        );
    }

    private boolean isGovernedMcpTool(IngestAgentPlanToolRequest plan) {
        if (plan.toolCode() == null || !plan.toolCode().matches("mcp\\.[a-z0-9][a-z0-9_.-]{2,124}")) {
            return false;
        }
        Map<String, Object> hints = plan.governanceHints();
        if (hints == null) {
            return false;
        }
        return "MCP".equalsIgnoreCase(String.valueOf(hints.get("protocolHint")))
                && "MCP_REMOTE_TOOL".equalsIgnoreCase(String.valueOf(hints.get("descriptorType")))
                && "python-ai-runtime-mcp-client".equals(hints.get("targetService"));
    }

    private void bindMissingTools(AgentSessionRecord session, List<AgentPlanToolSnapshot> toolSnapshots) {
        List<AgentToolBindingRecord> existingBindings = session.getToolBindings();
        List<AgentPlanToolSnapshot> missing = toolSnapshots.stream()
                .filter(plan -> existingBindings.stream().noneMatch(binding -> sameBinding(binding, plan)))
                .toList();
        ensureToolLimit(existingBindings.size(), missing.size());
        missing.forEach(plan -> session.addToolBinding(toBindingRecord(plan)));
    }

    private AgentRunRecord createRun(AgentSessionRecord session,
                                     IngestAgentPlanRequest request,
                                     List<AgentPlanToolSnapshot> toolSnapshots) {
        boolean toolApprovalRequired = toolSnapshots.stream().anyMatch(auditService::requiresApprovalBeforeExecution);
        boolean explicitApprovalRequired = Boolean.TRUE.equals(request.requiresHumanApproval());
        boolean requireHumanApproval = explicitApprovalRequired || toolApprovalRequired;
        AgentRunState initialState = requireHumanApproval ? AgentRunState.WAITING_HUMAN : AgentRunState.PLANNING;
        return new AgentRunRecord(
                "agr_" + UUID.randomUUID().toString().replace("-", ""),
                session.getSessionId(),
                initialState,
                ModelWorkloadType.fromValue(request.workloadType()).name(),
                preview(request.userInput(), 500),
                true,
                requireHumanApproval,
                nextActions(session, request, toolSnapshots, requireHumanApproval),
                runVariables(request, toolSnapshots),
                LocalDateTime.now(),
                runMessage(requireHumanApproval, toolApprovalRequired)
        );
    }

    private Map<String, Object> runVariables(IngestAgentPlanRequest request, List<AgentPlanToolSnapshot> toolSnapshots) {
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("source", "PYTHON_AI_RUNTIME_AGENT_PLAN");
        variables.put("idempotencyKey", request.idempotencyKey());
        variables.put("pythonRequestId", request.pythonRequestId());
        variables.put("stateTrace", request.stateTrace() == null ? List.of() : request.stateTrace());
        variables.put("responseSummary", request.responseSummary());
        variables.put("modelGatewayGovernance", safeMap(request.modelGatewayGovernance()));
        variables.put("memoryPlan", safeMap(request.memoryPlan()));
        variables.put("memoryRetrievalReport", safeMap(request.memoryRetrievalReport()));
        variables.put("toolPlans", toolSnapshots.stream().map(this::compactToolPlan).toList());
        return variables;
    }

    private Map<String, Object> compactToolPlan(AgentPlanToolSnapshot plan) {
        Map<String, Object> compact = new LinkedHashMap<>();
        compact.put("sequence", plan.sequence());
        compact.put("toolCode", plan.toolCode());
        compact.put("riskLevel", plan.riskLevel());
        compact.put("executionMode", plan.executionMode());
        compact.put("requiresApproval", plan.requiresApproval());
        compact.put("reason", plan.reason());
        compact.put("arguments", plan.arguments());
        compact.put("governanceHints", plan.governanceHints());
        compact.put("parameterValidation", plan.parameterValidation());
        return compact;
    }

    private List<String> nextActions(AgentSessionRecord session,
                                     IngestAgentPlanRequest request,
                                     List<AgentPlanToolSnapshot> toolSnapshots,
                                     boolean requireHumanApproval) {
        List<String> actions = new ArrayList<>();
        actions.add("Python AgentPlan 已进入 Java 控制面，runId 将作为后续审批、工具执行和事件回放的主关联键。");
        actions.add("本次计划包含 " + toolSnapshots.size() + " 个工具节点，均已按 Java 工具目录完成注册校验。");
        actions.add("模型网关治理摘要已写入 Run 变量，后续可用于 provider fallback、预算、缓存命中和成本分析。");
        actions.add("记忆计划与检索报告已写入 Run 变量，后续应继续补项目级长期记忆和审计留痕。");
        if (requireHumanApproval) {
            actions.add("当前计划包含审批要求或高风险工具，Run 已进入 WAITING_HUMAN，不会自动执行工具。");
        } else {
            actions.add("当前计划暂未触发审批门，但真实工具执行前仍必须经过 Java 权限、项目范围、幂等和下游健康校验。");
        }
        actions.add("会话工作空间为 " + session.getWorkspaceKey() + "，所有后续工具执行必须继承该隔离边界。");
        return actions;
    }

    private List<String> controlPlaneNotes(AgentRunRecord run, List<AgentToolExecutionAuditView> audits) {
        return List.of(
                "Python AgentPlan 已被转换为 Java Agent Run 和工具审计计划，但没有触发真实工具执行。",
                "后续工具执行必须通过 /agent-runtime/tool-executions/** 审批与执行入口，不能由 Python Runtime 直接绕过。",
                "当前 Run 状态为 " + run.getState().name() + "，工具审计数量为 " + audits.size() + "。"
        );
    }

    private AgentToolBindingRecord toBindingRecord(AgentPlanToolSnapshot plan) {
        return new AgentToolBindingRecord(
                "agt_" + UUID.randomUUID().toString().replace("-", ""),
                plan.toolCode(),
                AgentToolType.valueOf(plan.toolType()),
                plan.toolCode(),
                plan.targetService(),
                plan.targetEndpoint(),
                plan.targetResourceId(),
                plan.readOnly(),
                plan.riskLevel(),
                plan.executionMode(),
                plan.requiresApproval(),
                plan.idempotent(),
                AgentToolBindingStatus.ENABLED,
                plan.allowedActions(),
                LocalDateTime.now()
        );
    }

    private boolean sameBinding(AgentToolBindingRecord binding, AgentPlanToolSnapshot plan) {
        return binding.toolCode().equals(plan.toolCode()) && Objects.equals(binding.targetResourceId(), plan.targetResourceId());
    }

    private void ensureRuntimeEnabled() {
        if (!Boolean.TRUE.equals(properties.getEnabled())) {
            throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT, "Agent Runtime 当前未启用");
        }
    }

    /**
     * 为本轮 Python AgentPlan 取得一个可用 Run 槽位，必要时安全替代上一轮尚未执行的计划。
     *
     * <p>自然语言 Agent 的补参和纠偏本质上是同一会话的新一轮规划。上一轮如果正在等待用户选择数据源、确认
     * 字段映射或批准工具，它仍会停留在 PLANNING/WAITING_HUMAN；若这里只按“非终态 Run 数量”硬拒绝，用户
     * 填完表单后永远无法把新配置送回控制面。正确规则是：</p>
     *
     * <ol>
     *   <li>PLANNING 或 WAITING_HUMAN 的旧 Run 才可能被替代；</li>
     *   <li>未执行工具可以是 PLANNED/WAITING_APPROVAL；已经完成的目录检索、连接测试、元数据读取等只读工具
     *       也允许保留结果后继续补参，因为它们不会创建任务或修改业务资源；</li>
     *   <li>替代时取消旧 Run 与其中尚未执行的工具审计，阻止旧写入参数被审批页或自动执行器再次消费；</li>
     *   <li>WAITING_MODEL、TOOL_CALLING、EXECUTING，以及任何已产生写副作用的结果绝不自动覆盖，调用方应等待
     *       本轮结束或新建会话。</li>
     * </ol>
     *
     * @param session 当前用户、租户和项目边界内的 Agent 会话聚合。
     */
    private void ensureRunCapacityOrSupersedePendingPlan(AgentSessionRecord session) {
        int maxRuns = properties.getMaxRunsPerSession() == null ? 200 : properties.getMaxRunsPerSession();
        if (session.getRuns().size() >= maxRuns) {
            throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                    "单个 Agent 会话最多保留 " + maxRuns + " 次运行记录");
        }
        List<AgentRunRecord> activeRuns = session.getRuns().stream()
                .filter(item -> !item.getState().isTerminal())
                .toList();
        int maxActive = properties.getMaxActiveRunsPerSession() == null ? 1 : properties.getMaxActiveRunsPerSession();
        if (activeRuns.size() < maxActive) {
            return;
        }

        int slotsToRelease = activeRuns.size() - maxActive + 1;
        List<AgentRunRecord> supersededRuns = activeRuns.stream()
                .filter(this::isPendingHumanConfigurationRun)
                .sorted(Comparator.comparing(AgentRunRecord::getCreateTime).reversed())
                .limit(slotsToRelease)
                .toList();
        if (supersededRuns.size() < slotsToRelease) {
            throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                    "当前会话已有正在调用模型或工具的 Agent Run，不能用新的补参计划覆盖。请等待本轮结束，或新建会话后重试。");
        }

        // 先完成所有候选 Run 的无副作用校验，再修改任何状态，避免多活配置下出现只取消一半的计划。
        supersededRuns.forEach(run -> auditService.ensureRunPlanCanBeSuperseded(session.getSessionId(), run.getRunId()));
        supersededRuns.forEach(run -> {
            String reason = "旧工具计划已被用户补参或纠偏后的新计划替代；已完成的只读核对结果保留，未执行的旧计划已取消。";
            auditService.cancelRunPlanBeforeExecution(session.getSessionId(), run.getRunId(), reason);
            run.cancel("当前 Agent Run 已被用户补参或纠偏后的新计划替代，旧配置不会继续执行。");
        });
    }

    /**
     * 判断旧 Run 是否仍处在“等待用户完善配置”的可替代编排阶段。
     *
     * <p>PLANNING 覆盖普通缺参规划，WAITING_HUMAN 覆盖待确认字段映射、WHERE、SQL 或高风险工具的计划。
     * 这里只判断 Run 粗粒度阶段；工具是否真的执行过仍由审计服务做第二层证据校验。</p>
     */
    private boolean isPendingHumanConfigurationRun(AgentRunRecord run) {
        return run.getState() == AgentRunState.PLANNING || run.getState() == AgentRunState.WAITING_HUMAN;
    }

    private void ensureToolLimit(int currentSize, int appendSize) {
        int max = properties.getMaxToolBindingsPerSession() == null ? 20 : properties.getMaxToolBindingsPerSession();
        if (currentSize + appendSize > max) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "单个 Agent 会话最多绑定 " + max + " 个工具");
        }
    }

    private String higherRisk(String registryRisk, String planRisk) {
        AgentToolRiskLevel registry = parseRisk(registryRisk, AgentToolRiskLevel.LOW);
        AgentToolRiskLevel plan = parseRisk(planRisk, registry);
        return registry.ordinal() >= plan.ordinal() ? registry.name() : plan.name();
    }

    private AgentToolRiskLevel parseRisk(String value, AgentToolRiskLevel defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return AgentToolRiskLevel.valueOf(value.trim().replace('-', '_').toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "Python AgentPlan 传入了不支持的工具风险等级，riskLevel=" + value);
        }
    }

    private boolean isHighRisk(String riskLevel) {
        return "HIGH".equals(riskLevel) || "CRITICAL".equals(riskLevel);
    }

    private String executionMode(String registryMode, String planMode, boolean requiresApproval) {
        // Approval is an execution gate, not a replacement for the durable
        // transport.  An MCP/long-running ASYNC_TASK must remain asynchronous
        // after approval so it can enter command outbox, dispatcher and receipt.
        if (requiresApproval && !AgentToolExecutionMode.ASYNC_TASK.name().equals(registryMode)) {
            return AgentToolExecutionMode.APPROVAL_REQUIRED.name();
        }
        if (registryMode != null && !registryMode.isBlank()) {
            return registryMode;
        }
        if (planMode != null && !planMode.isBlank()) {
            try {
                return AgentToolExecutionMode.valueOf(planMode.trim().replace('-', '_').toUpperCase()).name();
            } catch (IllegalArgumentException ex) {
                throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                        "Python AgentPlan 传入了不支持的工具执行模式，executionMode=" + planMode);
            }
        }
        return AgentToolExecutionMode.SYNC.name();
    }

    private String buildWorkspaceKey(WorkspaceIsolationLevel level,
                                     Long tenantId,
                                     Long projectId,
                                     Long workspaceId,
                                     String sessionId) {
        String workspacePart = workspaceId == null ? "default" : String.valueOf(workspaceId);
        return switch (level) {
            case TENANT -> "tenant:" + tenantId;
            case PROJECT -> "tenant:" + tenantId + ":project:" + projectId;
            case WORKSPACE -> "tenant:" + tenantId + ":project:" + projectId + ":workspace:" + workspacePart;
            case SESSION -> "tenant:" + tenantId + ":project:" + projectId + ":workspace:" + workspacePart + ":session:" + sessionId;
        };
    }

    private String normalizeChannel(String channel) {
        return channel == null || channel.isBlank() ? DEFAULT_CHANNEL : channel.trim().toUpperCase();
    }

    private Map<String, Object> safeMap(Map<String, Object> value) {
        if (value == null || value.isEmpty()) {
            return Map.of();
        }
        // JSON governance snapshots legitimately use null for optional values.
        // Map.copyOf rejects null entries, so retain JSON semantics while making
        // the top-level control-plane snapshot immutable after ingestion.
        return Collections.unmodifiableMap(new LinkedHashMap<>(value));
    }

    private String preview(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }

    private String runMessage(boolean requireHumanApproval, boolean toolApprovalRequired) {
        if (toolApprovalRequired) {
            return "Python AgentPlan 已接入 Java 控制面；计划包含高风险或审批型工具，Run 已进入 WAITING_HUMAN。";
        }
        if (requireHumanApproval) {
            return "Python AgentPlan 已接入 Java 控制面；计划整体要求人工确认，Run 已进入 WAITING_HUMAN。";
        }
        return "Python AgentPlan 已接入 Java 控制面；当前仅生成受控 Run 与工具审计计划，尚未执行工具。";
    }

    private AgentSessionView toSessionView(AgentSessionRecord session) {
        AgentWorkspaceView workspace = new AgentWorkspaceView(
                session.getWorkspaceKey(),
                session.getIsolationLevel().name(),
                session.getTenantId(),
                session.getProjectId(),
                session.getWorkspaceId(),
                session.getSessionId()
        );
        return new AgentSessionView(
                session.getSessionId(),
                session.getAgentId(),
                session.getTenantId(),
                session.getProjectId(),
                session.getWorkspaceId(),
                session.getActorId(),
                session.getChannel(),
                session.getObjective(),
                session.getState().name(),
                workspace,
                session.getToolBindings().stream().map(this::toToolView).toList(),
                session.getRuns().stream().map(this::toRunView).toList(),
                new AgentDelegationView(
                        session.getDelegation().getDelegationId(), session.getDelegation().getAgentId(),
                        session.getDelegation().getUserActorId(), session.getDelegation().getTenantId(),
                        session.getDelegation().getProjectId(), session.getDelegation().getToolCodes(),
                        session.getDelegation().getActions(), session.getDelegation().getResourceScopes(),
                        session.getDelegation().getStatus(), session.getDelegation().getIssuedAt(),
                        session.getDelegation().getExpiresAt(), session.getDelegation().getRevokedAt()),
                session.getMessages().stream().map(message -> new AgentConversationMessageView(
                        message.messageId(), message.runId(), message.role(), message.content(), message.createTime())).toList(),
                session.isPinned(),
                session.isArchived(),
                session.getArchivedAt(),
                session.getLastMessageAt(),
                session.getCreateTime(),
                session.getUpdateTime()
        );
    }

    private AgentToolBindingView toToolView(AgentToolBindingRecord binding) {
        return new AgentToolBindingView(
                binding.bindingId(),
                binding.toolCode(),
                binding.toolType().name(),
                binding.displayName(),
                binding.targetService(),
                binding.targetEndpoint(),
                binding.targetResourceId(),
                binding.readOnly(),
                binding.riskLevel(),
                binding.executionMode(),
                binding.requiresApproval(),
                binding.idempotent(),
                binding.status().name(),
                binding.allowedActions(),
                binding.createTime()
        );
    }

    private AgentRunView toRunView(AgentRunRecord run) {
        return new AgentRunView(
                run.getRunId(),
                run.getSessionId(),
                run.getState().name(),
                run.getWorkloadType(),
                run.getUserInputPreview(),
                run.getDryRun(),
                run.getRequireHumanApproval(),
                run.getNextActions(),
                run.getVariables(),
                run.getCreateTime(),
                run.getUpdateTime(),
                run.getFinishTime(),
                run.getMessage()
        );
    }
}
