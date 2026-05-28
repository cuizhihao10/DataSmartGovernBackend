/**
 * @Author : Cui
 * @Date: 2026/05/13 22:53
 * @Description DataSmart Govern Backend - AgentSessionService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service;

import com.czh.datasmart.govern.agent.config.AgentRuntimeProperties;
import com.czh.datasmart.govern.agent.controller.dto.AgentRunView;
import com.czh.datasmart.govern.agent.controller.dto.AgentSessionView;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolBindingView;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolDefinitionView;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolExecutionAuditView;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolExecutionDecisionRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolExecutionResultView;
import com.czh.datasmart.govern.agent.controller.dto.AgentWorkspaceView;
import com.czh.datasmart.govern.agent.controller.dto.BindAgentToolRequest;
import com.czh.datasmart.govern.agent.controller.dto.CreateAgentSessionRequest;
import com.czh.datasmart.govern.agent.controller.dto.StartAgentRunRequest;
import com.czh.datasmart.govern.agent.model.AgentRunState;
import com.czh.datasmart.govern.agent.model.AgentToolBindingStatus;
import com.czh.datasmart.govern.agent.model.AgentToolType;
import com.czh.datasmart.govern.agent.model.ModelWorkloadType;
import com.czh.datasmart.govern.agent.model.WorkspaceIsolationLevel;
import com.czh.datasmart.govern.agent.service.session.AgentRunRecord;
import com.czh.datasmart.govern.agent.service.session.AgentRunStateCoordinator;
import com.czh.datasmart.govern.agent.service.session.AgentSessionMemoryStore;
import com.czh.datasmart.govern.agent.service.session.AgentSessionRecord;
import com.czh.datasmart.govern.agent.service.session.AgentToolBindingRecord;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Agent 会话服务。
 *
 * <p>该服务是 Java Agent Runtime 的会话控制面，不负责真实模型推理，也不直接执行下游业务工具。
 * 它负责把“用户要让 Agent 做什么”变成可治理的运行时对象：
 * 会话、工作空间、工具绑定、运行记录、状态流转。
 *
 * <p>为什么要单独做这一层：
 * 1. 前端和智能网关需要稳定 API 创建/查询会话；
 * 2. 权限系统需要知道 AI 调用入口对应什么资源和动作；
 * 3. 审计系统后续需要记录会话、运行、工具调用之间的关联；
 * 4. Python Runtime 或 LangGraph 编排器只应接收经过 Java 控制面治理后的请求，而不是直接暴露给前端。
 */
@Service
@RequiredArgsConstructor
public class AgentSessionService {

    private static final String DEFAULT_CHANNEL = "WEB";

    private final AgentRuntimeProperties properties;
    private final AgentSessionMemoryStore memoryStore;
    private final AgentToolRegistryService toolRegistryService;
    private final AgentToolExecutionAuditService toolExecutionAuditService;
    private final AgentToolExecutionService toolExecutionService;
    private final AgentRunStateCoordinator runStateCoordinator;

    /**
     * 创建 Agent 会话。
     *
     * <p>当前会话创建只写入内存仓储，但仍完整校验租户、项目、工具数量和 Runtime 开关。
     * 这些规则未来迁移到数据库仓储后应保持不变，避免 API 契约变化。
     */
    public AgentSessionView createSession(CreateAgentSessionRequest request) {
        ensureRuntimeEnabled();
        WorkspaceIsolationLevel isolationLevel = request.isolationLevel() == null
                ? WorkspaceIsolationLevel.PROJECT
                : request.isolationLevel();
        String sessionId = "ags_" + UUID.randomUUID().toString().replace("-", "");
        LocalDateTime now = LocalDateTime.now();
        AgentSessionRecord session = new AgentSessionRecord(
                sessionId,
                request.tenantId(),
                request.projectId(),
                request.workspaceId(),
                request.actorId(),
                normalizeChannel(request.channel()),
                request.objective(),
                isolationLevel,
                buildWorkspaceKey(isolationLevel, request.tenantId(), request.projectId(), request.workspaceId(), sessionId),
                now
        );
        List<BindAgentToolRequest> toolRequests = request.toolBindings() == null ? List.of() : request.toolBindings();
        ensureToolLimit(session.getToolBindings().size(), toolRequests.size());
        toolRequests.stream()
                .map(this::toBindingRecord)
                .forEach(session::addToolBinding);
        memoryStore.save(session);
        return toSessionView(session);
    }

    /**
     * 查询会话列表。
     *
     * <p>列表接口支持按租户、项目和操作者过滤。
     * 当前过滤发生在内存中，后续切换到数据库时应下沉到 SQL，并补充分页、时间范围、状态过滤和审计导出。
     */
    public List<AgentSessionView> listSessions(Long tenantId, Long projectId, String actorId) {
        ensureRuntimeEnabled();
        return memoryStore.list(tenantId, projectId, actorId).stream()
                .map(this::toSessionView)
                .toList();
    }

    /**
     * 查询会话详情。
     */
    public AgentSessionView getSession(String sessionId) {
        ensureRuntimeEnabled();
        return toSessionView(findSession(sessionId));
    }

    /**
     * 为会话追加工具绑定。
     *
     * <p>工具追加会被同一个会话对象锁保护，避免两个并发请求同时绕过最大工具数量限制。
     * 后续数据库实现应使用乐观锁或唯一键保证同样的并发安全语义。
     */
    public AgentSessionView bindTool(String sessionId, BindAgentToolRequest request) {
        ensureRuntimeEnabled();
        AgentSessionRecord session = findSession(sessionId);
        synchronized (session) {
            ensureSessionCanMutate(session);
            ensureToolLimit(session.getToolBindings().size(), 1);
            session.addToolBinding(toBindingRecord(request));
            return toSessionView(session);
        }
    }

    /**
     * 发起一次 Agent 运行。
     *
     * <p>当前版本不会真正调用模型和工具，而是创建一个 PLANNING 状态的 dry-run 运行。
     * 这样前端、网关、权限、审计规划可以先围绕 runId 工作，后续再把 PLANNING 后面的状态交给真实编排器推进。
     */
    public AgentRunView startRun(String sessionId, StartAgentRunRequest request, String traceId) {
        ensureRuntimeEnabled();
        AgentSessionRecord session = findSession(sessionId);
        synchronized (session) {
            ensureSessionCanMutate(session);
            ensureRunLimit(session);
            boolean explicitHumanApproval = Boolean.TRUE.equals(request.requireHumanApproval());
            boolean toolApprovalRequired = runStateCoordinator.hasApprovalRequiredTool(session);
            boolean runRequiresHumanApproval = explicitHumanApproval || toolApprovalRequired;
            AgentRunState initialState = runStateCoordinator.initialState(explicitHumanApproval, toolApprovalRequired);
            AgentRunRecord run = new AgentRunRecord(
                    "agr_" + UUID.randomUUID().toString().replace("-", ""),
                    sessionId,
                    initialState,
                    ModelWorkloadType.fromValue(request.workloadType()).name(),
                    preview(request.userInput(), 500),
                    true,
                    runRequiresHumanApproval,
                    runStateCoordinator.buildDryRunNextActions(session, request, explicitHumanApproval, toolApprovalRequired),
                    request.variables() == null ? Map.of() : request.variables(),
                    LocalDateTime.now(),
                    runStateCoordinator.buildRunCreatedMessage(explicitHumanApproval, toolApprovalRequired)
            );
            session.addRun(run);
            toolExecutionAuditService.createPlanAudits(session, run, traceId);
            return toRunView(run);
        }
    }

    /**
     * 取消一次 Agent 运行。
     *
     * <p>取消只允许作用于非终态运行。
     * 如果未来真实编排器已经把任务发送到 Python Runtime 或下游工具，此处还需要发布取消事件并等待异步确认。
     */
    public AgentRunView cancelRun(String sessionId, String runId) {
        ensureRuntimeEnabled();
        AgentSessionRecord session = findSession(sessionId);
        synchronized (session) {
            AgentRunRecord run = findRun(session, runId);
            if (run.getState().isTerminal()) {
                throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                        "Agent Run 已进入终态，不能重复取消，runId=" + runId);
            }
            run.cancel("Agent Run 已由控制面取消；当前版本尚未下发真实编排任务，因此无需等待下游确认。");
            return toRunView(run);
        }
    }

    /**
     * 人工确认某个高风险工具计划，并同步评估 Run 是否可以恢复规划。
     *
     * <p>该方法放在会话服务里，而不是只放在工具审计服务里，是为了保证两个状态层级保持一致：
     * 工具级状态记录“某个工具计划是否被确认/拒绝”，Run 级状态记录“整个 Agent 编排是否还能继续”。
     * 如果只改工具审计，不回写 Run，前端会看到工具已确认但 Run 仍卡在 WAITING_HUMAN，形成产品体验和状态机矛盾。
     */
    public AgentToolExecutionAuditView approveToolExecution(String sessionId,
                                                            String runId,
                                                            String auditId,
                                                            AgentToolExecutionDecisionRequest request) {
        ensureRuntimeEnabled();
        AgentSessionRecord session = findSession(sessionId);
        synchronized (session) {
            AgentRunRecord run = findRun(session, runId);
            ensureRunCanAcceptToolDecision(run);
            AgentToolExecutionAuditView decision = toolExecutionAuditService.approve(sessionId, runId, auditId, request);
            runStateCoordinator.reconcileAfterToolDecision(session, run);
            return decision;
        }
    }

    /**
     * 人工拒绝某个高风险工具计划，并同步终止当前 Run。
     *
     * <p>当前阶段还没有“关键工具/可选工具”的结构化字段。
     * 为了避免误把已被拒绝的高风险动作继续交给模型规划，本阶段采用更安全的策略：
     * 只要有审批型工具被拒绝，就把整个 Run 标记为 REJECTED。
     * 后续可以在工具目录中增加 requiredForRun、alternativeToolCodes 或 planNodeId，再实现更细的局部跳过。
     */
    public AgentToolExecutionAuditView rejectToolExecution(String sessionId,
                                                           String runId,
                                                           String auditId,
                                                           AgentToolExecutionDecisionRequest request) {
        ensureRuntimeEnabled();
        AgentSessionRecord session = findSession(sessionId);
        synchronized (session) {
            AgentRunRecord run = findRun(session, runId);
            ensureRunCanAcceptToolDecision(run);
            AgentToolExecutionAuditView decision = toolExecutionAuditService.reject(sessionId, runId, auditId, request);
            runStateCoordinator.reconcileAfterToolDecision(session, run);
            return decision;
        }
    }

    /**
     * 执行某个已规划且已满足审批条件的工具。
     *
     * <p>这里仍然通过会话对象加锁，原因是工具执行会改变审计状态。
     * 当前是内存仓储阶段，如果两个请求同时执行同一个 auditId，可能导致重复调用下游服务。
     * 先用会话锁保证同一会话内串行执行；后续迁移数据库后，应使用审计记录状态条件更新或幂等键防止重复执行。
     */
    public AgentToolExecutionResultView executeToolExecution(String sessionId,
                                                             String runId,
                                                             String auditId,
                                                             String traceId) {
        ensureRuntimeEnabled();
        AgentSessionRecord session = findSession(sessionId);
        synchronized (session) {
            AgentRunRecord run = findRun(session, runId);
            if (run.getState().isTerminal()) {
                throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                        "Agent Run 已进入终态，不能继续执行工具，runId=" + runId);
            }
            if (run.getState() == AgentRunState.WAITING_HUMAN) {
                throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                        "Agent Run 正在等待人工确认，不能执行工具，runId=" + runId);
            }
            return toolExecutionService.execute(session, run, auditId, traceId);
        }
    }

    /**
     * 查询某个工具执行的当前结果快照。
     *
     * <p>该方法只读，不会触发工具执行，也不会改变 Run 或 Audit 状态。
     * 它面向两个核心场景：</p>
     * <p>1. 前端/审计页面刷新工具执行结果；</p>
     * <p>2. Python AI Runtime 在模型第二轮推理前读取 Java 控制面的真实工具反馈。</p>
     *
     * <p>即使工具尚未执行完成，也允许查询当前快照。这样调用方可以得到 WAITING_APPROVAL、
     * PLANNED、EXECUTING、FAILED 或 SKIPPED 等状态，而不是只能在成功时拿到结果。</p>
     */
    public AgentToolExecutionResultView getToolExecutionResult(String sessionId, String runId, String auditId) {
        ensureRuntimeEnabled();
        AgentSessionRecord session = findSession(sessionId);
        synchronized (session) {
            findRun(session, runId);
            return toolExecutionService.getResult(sessionId, runId, auditId);
        }
    }

    private void ensureRuntimeEnabled() {
        if (!Boolean.TRUE.equals(properties.getEnabled())) {
            throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT, "Agent Runtime 当前未启用");
        }
    }

    private AgentSessionRecord findSession(String sessionId) {
        return memoryStore.findById(sessionId)
                .orElseThrow(() -> new PlatformBusinessException(PlatformErrorCode.NOT_FOUND,
                        "Agent 会话不存在，sessionId=" + sessionId));
    }

    private AgentRunRecord findRun(AgentSessionRecord session, String runId) {
        return session.getRuns().stream()
                .filter(item -> item.getRunId().equals(runId))
                .findFirst()
                .orElseThrow(() -> new PlatformBusinessException(PlatformErrorCode.NOT_FOUND,
                        "Agent Run 不存在，runId=" + runId));
    }

    private void ensureSessionCanMutate(AgentSessionRecord session) {
        if (!session.getState().canStartRun()) {
            throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                    "Agent 会话当前状态不允许继续操作，state=" + session.getState().name());
        }
    }

    private void ensureToolLimit(int currentSize, int appendSize) {
        int max = properties.getMaxToolBindingsPerSession() == null ? 20 : properties.getMaxToolBindingsPerSession();
        if (currentSize + appendSize > max) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "单个 Agent 会话最多绑定 " + max + " 个工具");
        }
    }

    private void ensureRunLimit(AgentSessionRecord session) {
        int maxRuns = properties.getMaxRunsPerSession() == null ? 200 : properties.getMaxRunsPerSession();
        if (session.getRuns().size() >= maxRuns) {
            throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                    "单个 Agent 会话最多保留 " + maxRuns + " 次运行记录");
        }
        long activeRuns = session.getRuns().stream()
                .filter(item -> !item.getState().isTerminal())
                .count();
        int maxActive = properties.getMaxActiveRunsPerSession() == null ? 1 : properties.getMaxActiveRunsPerSession();
        if (activeRuns >= maxActive) {
            throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                    "当前会话已有未完成 Agent Run，请完成或取消后再发起新的运行");
        }
    }

    private void ensureRunCanAcceptToolDecision(AgentRunRecord run) {
        if (run.getState().isTerminal()) {
            throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                    "Agent Run 已进入终态，不能继续处理工具人工决策，runId=" + run.getRunId());
        }
    }

    private AgentToolBindingRecord toBindingRecord(BindAgentToolRequest request) {
        LocalDateTime now = LocalDateTime.now();
        AgentToolDefinitionView toolDefinition = resolveToolDefinition(request);
        return new AgentToolBindingRecord(
                "agt_" + UUID.randomUUID().toString().replace("-", ""),
                toolDefinition == null ? request.toolCode() : toolDefinition.toolCode(),
                resolveToolType(request, toolDefinition),
                resolveDisplayName(request, toolDefinition),
                toolDefinition == null ? request.targetService() : toolDefinition.targetService(),
                toolDefinition == null ? null : toolDefinition.targetEndpoint(),
                request.targetResourceId(),
                toolDefinition == null ? Boolean.TRUE.equals(request.readOnly()) : toolDefinition.readOnly(),
                toolDefinition == null ? null : toolDefinition.riskLevel(),
                toolDefinition == null ? null : toolDefinition.executionMode(),
                toolDefinition == null ? false : toolDefinition.requiresApproval(),
                toolDefinition == null ? true : toolDefinition.idempotent(),
                AgentToolBindingStatus.ENABLED,
                resolveAllowedActions(request, toolDefinition),
                now
        );
    }

    /**
     * 解析工具目录定义。
     *
     * <p>当 strictToolRegistryBinding=true 时，绑定工具必须来自启用工具目录。
     * 这是从“演示型 Agent”走向“商业化 Agent”的重要边界：调用方不能伪造 targetService、riskLevel 或审批要求。
     * 如果严格模式关闭，则仍优先使用目录元数据，目录不存在时才退回请求体字段，方便本地研发临时实验。
     */
    private AgentToolDefinitionView resolveToolDefinition(BindAgentToolRequest request) {
        if (Boolean.TRUE.equals(properties.getStrictToolRegistryBinding())) {
            return toolRegistryService.requireEnabledTool(request.toolCode());
        }
        return toolRegistryService.findTool(request.toolCode())
                .filter(item -> Boolean.TRUE.equals(item.enabled()))
                .orElse(null);
    }

    private String resolveDisplayName(BindAgentToolRequest request, AgentToolDefinitionView toolDefinition) {
        if (toolDefinition != null && toolDefinition.displayName() != null && !toolDefinition.displayName().isBlank()) {
            return toolDefinition.displayName();
        }
        return request.displayName() == null || request.displayName().isBlank() ? request.toolCode() : request.displayName();
    }

    private AgentToolType resolveToolType(BindAgentToolRequest request, AgentToolDefinitionView toolDefinition) {
        if (toolDefinition != null && toolDefinition.toolType() != null && !toolDefinition.toolType().isBlank()) {
            return AgentToolType.valueOf(toolDefinition.toolType());
        }
        return request.toolType() == null ? AgentToolType.KNOWLEDGE_RETRIEVAL : request.toolType();
    }

    private List<String> resolveAllowedActions(BindAgentToolRequest request, AgentToolDefinitionView toolDefinition) {
        if (toolDefinition != null && toolDefinition.allowedActions() != null && !toolDefinition.allowedActions().isEmpty()) {
            return toolDefinition.allowedActions();
        }
        return request.allowedActions() == null ? List.of("VIEW") : request.allowedActions();
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

    private String preview(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
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
