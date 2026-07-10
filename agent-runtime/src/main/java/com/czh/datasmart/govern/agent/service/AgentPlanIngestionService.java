/**
 * @Author : Cui
 * @Date: 2026/05/24 00:00
 * @Description DataSmart Govern Backend - AgentPlanIngestionService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service;

import com.czh.datasmart.govern.agent.config.AgentRuntimeProperties;
import com.czh.datasmart.govern.agent.controller.dto.AgentRunView;
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
import com.czh.datasmart.govern.agent.service.session.AgentSessionMemoryStore;
import com.czh.datasmart.govern.agent.service.session.AgentSessionRecord;
import com.czh.datasmart.govern.agent.service.session.AgentToolBindingRecord;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
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
    private final AgentSessionMemoryStore sessionMemoryStore;
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
            ensureRunLimit(session);
            bindMissingTools(session, toolSnapshots);
            AgentRunRecord run = createRun(session, request, toolSnapshots);
            session.addRun(run);
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
            AgentToolDefinitionView definition = toolRegistryService.requireEnabledTool(plan.toolCode());
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

    private void ensureRunLimit(AgentSessionRecord session) {
        int maxRuns = properties.getMaxRunsPerSession() == null ? 200 : properties.getMaxRunsPerSession();
        if (session.getRuns().size() >= maxRuns) {
            throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                    "单个 Agent 会话最多保留 " + maxRuns + " 次运行记录");
        }
        long activeRuns = session.getRuns().stream().filter(item -> !item.getState().isTerminal()).count();
        int maxActive = properties.getMaxActiveRunsPerSession() == null ? 1 : properties.getMaxActiveRunsPerSession();
        if (activeRuns >= maxActive) {
            throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                    "当前会话已有未完成 Agent Run，暂不能继续接入新的 Python AgentPlan。");
        }
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
        if (requiresApproval) {
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
