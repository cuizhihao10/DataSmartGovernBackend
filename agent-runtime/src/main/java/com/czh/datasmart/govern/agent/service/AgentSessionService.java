/**
 * @Author : Cui
 * @Date: 2026/05/13 22:53
 * @Description DataSmart Govern Backend - AgentSessionService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service;

import com.czh.datasmart.govern.agent.config.AgentRuntimeProperties;
import com.czh.datasmart.govern.agent.controller.dto.AgentRunView;
import com.czh.datasmart.govern.agent.controller.dto.AgentRunPublicVariablesProjector;
import com.czh.datasmart.govern.agent.controller.dto.AgentConversationMessageView;
import com.czh.datasmart.govern.agent.controller.dto.AgentDelegationView;
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
import com.czh.datasmart.govern.agent.service.session.AgentSessionAccessContext;
import com.czh.datasmart.govern.agent.service.session.AgentSessionStore;
import com.czh.datasmart.govern.agent.service.session.AgentSessionRecord;
import com.czh.datasmart.govern.agent.service.session.AgentToolBindingRecord;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

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
    private final AgentSessionStore memoryStore;
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
    public AgentSessionView createSession(CreateAgentSessionRequest request, AgentSessionAccessContext accessContext) {
        ensureCreateAccess(request, accessContext);
        return createSessionWithApplication(request, accessContext.applicationId());
    }

    /**
     * 执行已通过身份校验的会话创建核心流程。
     *
     * <p>包级可见是为了让同模块测试和受控内部流程复用，而外部请求必须调用带 accessContext 的入口。</p>
     */
    AgentSessionView createSession(CreateAgentSessionRequest request) {
        return createSessionWithApplication(request, null);
    }

    /**
     * 创建会话并固定可信应用边界。旧的无上下文内部调用仍保留 null，
     * 但所有 Gateway HTTP 创建请求都会走带 applicationId 的入口。
     */
    private AgentSessionView createSessionWithApplication(CreateAgentSessionRequest request, Long applicationId) {
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
        session.bindApplicationId(applicationId);
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
    public List<AgentSessionView> listSessions(AgentSessionAccessContext accessContext,
                                               String requestedActorId,
                                               boolean archived,
                                               int limit) {
        ensureTrustedContext(accessContext);
        String actorFilter = accessContext.privilegedRead() && hasText(requestedActorId)
                ? requestedActorId.trim()
                : accessContext.actorId();
        return memoryStore.list(accessContext.tenantId(), accessContext.projectId(), actorFilter, archived, limit).stream()
                .filter(session -> canRead(session, accessContext))
                .map(this::toSessionView)
                .toList();
    }

    /**
     * 兼容模块内旧调用方的列表入口。
     *
     * <p>HTTP 接口不得使用该重载，因为它不携带可信访问上下文；新外部链路必须使用上方的访问感知方法。</p>
     */
    List<AgentSessionView> listSessions(Long tenantId, Long projectId, String actorId) {
        ensureRuntimeEnabled();
        return memoryStore.list(tenantId, projectId, actorId).stream()
                .map(this::toSessionView)
                .toList();
    }

    /**
     * 查询会话详情。
     */
    public AgentSessionView getSession(String sessionId, AgentSessionAccessContext accessContext) {
        AgentSessionRecord session = findSession(sessionId);
        ensureReadAccess(session, accessContext);
        return toSessionView(session);
    }

    /**
     * 校验调用方是否可以修改指定会话，但不改变任何会话状态。
     * 供工具自动执行等拆分服务在进入副作用逻辑前复用同一对象归属规则。
     */
    public void requireMutationAccess(String sessionId, AgentSessionAccessContext accessContext) {
        ensureMutationAccess(findSession(sessionId), accessContext);
    }

    /** 供已在同一服务边界内完成授权的内部组件读取会话，不应直接暴露给 Controller。 */
    AgentSessionView getSession(String sessionId) {
        ensureRuntimeEnabled();
        return toSessionView(findSession(sessionId));
    }

    /**
     * 为会话追加工具绑定。
     *
     * <p>工具追加会被同一个会话对象锁保护，避免两个并发请求同时绕过最大工具数量限制。
     * 后续数据库实现应使用乐观锁或唯一键保证同样的并发安全语义。
     */
    public AgentSessionView bindTool(String sessionId,
                                     BindAgentToolRequest request,
                                     AgentSessionAccessContext accessContext) {
        ensureMutationAccess(findSession(sessionId), accessContext);
        return bindTool(sessionId, request);
    }

    AgentSessionView bindTool(String sessionId, BindAgentToolRequest request) {
        ensureRuntimeEnabled();
        return mutateExistingSession(sessionId, session -> {
            ensureSessionCanMutate(session);
            ensureToolLimit(session.getToolBindings().size(), 1);
            session.addToolBinding(toBindingRecord(request));
            return toSessionView(session);
        });
    }

    /**
     * 发起一次 Agent 运行。
     *
     * <p>当前版本不会真正调用模型和工具，而是创建一个 PLANNING 状态的 dry-run 运行。
     * 这样前端、网关、权限、审计规划可以先围绕 runId 工作，后续再把 PLANNING 后面的状态交给真实编排器推进。
     */
    public AgentRunView startRun(String sessionId,
                                 StartAgentRunRequest request,
                                 String traceId,
                                 AgentSessionAccessContext accessContext) {
        ensureMutationAccess(findSession(sessionId), accessContext);
        return startRun(sessionId, request, traceId);
    }

    AgentRunView startRun(String sessionId, StartAgentRunRequest request, String traceId) {
        ensureRuntimeEnabled();
        return mutateExistingSession(sessionId, session -> {
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
        });
    }

    /**
     * 取消一次 Agent 运行。
     *
     * <p>取消只允许作用于非终态运行。
     * 如果未来真实编排器已经把任务发送到 Python Runtime 或下游工具，此处还需要发布取消事件并等待异步确认。
     */
    public AgentRunView cancelRun(String sessionId, String runId, AgentSessionAccessContext accessContext) {
        ensureMutationAccess(findSession(sessionId), accessContext);
        return cancelRun(sessionId, runId);
    }

    AgentRunView cancelRun(String sessionId, String runId) {
        ensureRuntimeEnabled();
        return mutateExistingSession(sessionId, session -> {
            AgentRunRecord run = findRun(session, runId);
            if (run.getState().isTerminal()) {
                throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                        "Agent Run 已进入终态，不能重复取消，runId=" + runId);
            }
            run.cancel("Agent Run 已由控制面取消；当前版本尚未下发真实编排任务，因此无需等待下游确认。");
            return toRunView(run);
        });
    }

    /**
     * 为会话所有者设置置顶状态，并立即持久化排序时间。
     *
     * @param sessionId 要修改的会话编号
     * @param pinned true 置顶，false 取消置顶
     * @param accessContext Gateway 注入的当前用户范围
     * @return 修改后的完整会话视图
     */
    public AgentSessionView setPinned(String sessionId, boolean pinned, AgentSessionAccessContext accessContext) {
        AgentSessionRecord session = findSession(sessionId);
        ensureMutationAccess(session, accessContext);
        return mutateExistingSession(sessionId, currentSession -> {
            currentSession.setPinned(pinned);
            return toSessionView(currentSession);
        });
    }

    /**
     * 归档或恢复会话，同时保留消息、运行、委托和工具审计。
     *
     * @param sessionId 要整理的会话编号
     * @param archived true 移入归档历史，false 恢复到活跃历史
     * @param accessContext 当前会话所有者的可信身份范围
     * @return 修改后的完整会话视图
     */
    public AgentSessionView setArchived(String sessionId, boolean archived, AgentSessionAccessContext accessContext) {
        AgentSessionRecord session = findSession(sessionId);
        ensureMutationAccess(session, accessContext);
        return mutateExistingSession(sessionId, currentSession -> {
            currentSession.setArchived(archived);
            return toSessionView(currentSession);
        });
    }

    /**
     * Executes one existing-session change through the store's atomic aggregate boundary.
     *
     * <p>All callers of this helper change a freshly loaded session. In PostgreSQL mode the store holds the parent
     * and child row locks until the callback and persistence complete; in memory mode it holds the current session
     * monitor. This prevents an older service snapshot from deleting a Run, message, confirmation receipt, or tool
     * binding written by another Runtime instance. New-session creation intentionally keeps using {@code save}
     * because no concurrent caller can yet know its generated session ID.</p>
     *
     * @param sessionId existing session to mutate
     * @param mutation synchronous domain change; do not perform remote network calls inside it
     * @param <T> view returned after the change is durably persisted
     * @return non-null callback result
     * @throws PlatformBusinessException when the session disappeared before the atomic mutation acquired its lock
     */
    private <T> T mutateExistingSession(String sessionId, Function<AgentSessionRecord, T> mutation) {
        return memoryStore.mutateAtomically(sessionId, mutation)
                .orElseThrow(() -> new PlatformBusinessException(
                        PlatformErrorCode.NOT_FOUND,
                        "Agent 会话不存在，sessionId=" + sessionId));
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
                                                            AgentToolExecutionDecisionRequest request,
                                                            AgentSessionAccessContext accessContext) {
        ensureMutationAccess(findSession(sessionId), accessContext);
        return approveToolExecution(sessionId, runId, auditId, request);
    }

    AgentToolExecutionAuditView approveToolExecution(String sessionId,
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
            if (!memoryStore.updateRunAfterToolDecision(sessionId, run)) {
                throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                        "Agent Run changed while approval was being reconciled, runId=" + runId);
            }
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
                                                           AgentToolExecutionDecisionRequest request,
                                                           AgentSessionAccessContext accessContext) {
        ensureMutationAccess(findSession(sessionId), accessContext);
        return rejectToolExecution(sessionId, runId, auditId, request);
    }

    AgentToolExecutionAuditView rejectToolExecution(String sessionId,
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
            if (!memoryStore.updateRunAfterToolDecision(sessionId, run)) {
                throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                        "Agent Run changed while rejection was being reconciled, runId=" + runId);
            }
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
                                                             String traceId,
                                                             AgentSessionAccessContext accessContext) {
        ensureMutationAccess(findSession(sessionId), accessContext);
        return executeToolExecution(sessionId, runId, auditId, traceId);
    }

    AgentToolExecutionResultView executeToolExecution(String sessionId,
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
            AgentToolExecutionResultView result = toolExecutionService.execute(session, run, auditId, traceId);
            // Tool execution mutates the durable audit/output stores, not the session or Run aggregate. Saving the
            // whole session here would only replace child snapshots and could erase a continuation Run appended by
            // another Runtime instance.
            return result;
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
    public AgentToolExecutionResultView getToolExecutionResult(String sessionId,
                                                               String runId,
                                                               String auditId,
                                                               AgentSessionAccessContext accessContext) {
        ensureReadAccess(findSession(sessionId), accessContext);
        return getToolExecutionResult(sessionId, runId, auditId);
    }

    AgentToolExecutionResultView getToolExecutionResult(String sessionId, String runId, String auditId) {
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

    /**
     * 防止请求体伪造租户、项目或 actor。
     *
     * <p>创建会话时三个标识必须与 Gateway 认证后注入的 Header 完全一致；即使平台管理员也不能在请求体中
     * 冒充另一个用户创建可写会话，因为双主体审计必须保留真实发起人。</p>
     */
    private void ensureCreateAccess(CreateAgentSessionRequest request, AgentSessionAccessContext context) {
        ensureTrustedContext(context);
        if (!Objects.equals(request.tenantId(), context.tenantId())
                || !Objects.equals(request.projectId(), context.projectId())
                || !Objects.equals(request.actorId(), context.actorId())) {
            throw new PlatformBusinessException(PlatformErrorCode.FORBIDDEN,
                    "Agent 会话只能以当前登录用户身份在当前租户和项目中创建");
        }
    }

    /** 校验当前主体能否读取会话；普通用户仅能读本人会话，特权角色只能获得受范围限制的只读能力。 */
    private void ensureReadAccess(AgentSessionRecord session, AgentSessionAccessContext context) {
        ensureTrustedContext(context);
        if (!canRead(session, context)) {
            throw new PlatformBusinessException(PlatformErrorCode.FORBIDDEN,
                    "当前用户无权查看该 Agent 会话");
        }
    }

    /**
     * 计算对象级读取权限。
     *
     * <p>平台管理员可跨租户/项目审计，租户或项目特权角色仍受自身范围约束；所有特权读取都不会自动转化为
     * 会话修改权限。</p>
     */
    private boolean canRead(AgentSessionRecord session, AgentSessionAccessContext context) {
        boolean tenantMatches = context.platformAdministrator()
                || Objects.equals(session.getTenantId(), context.tenantId());
        boolean projectMatches = context.platformAdministrator()
                || Objects.equals(session.getProjectId(), context.projectId());
        boolean actorMatches = Objects.equals(session.getActorId(), context.actorId());
        return tenantMatches && projectMatches && (actorMatches || context.privilegedRead());
    }

    /**
     * 要求写操作由原会话用户在原租户和项目中发起。
     *
     * <p>管理员审计角色不享有代写能力，这避免管理员查看历史时意外继续对话或触发 Agent 工具副作用。</p>
     */
    private void ensureMutationAccess(AgentSessionRecord session, AgentSessionAccessContext context) {
        ensureTrustedContext(context);
        if (!Objects.equals(session.getTenantId(), context.tenantId())
                || !Objects.equals(session.getProjectId(), context.projectId())
                || !Objects.equals(session.getActorId(), context.actorId())) {
            throw new PlatformBusinessException(PlatformErrorCode.FORBIDDEN,
                    "只有会话发起人可以在原租户和项目范围内继续对话或修改会话");
        }
    }

    /** 缺少任何核心身份 Header 时按 fail-closed 拒绝，而不是退化成不带范围的全量查询。 */
    private void ensureTrustedContext(AgentSessionAccessContext context) {
        if (context == null || context.tenantId() == null || context.projectId() == null || !hasText(context.actorId())) {
            throw new PlatformBusinessException(PlatformErrorCode.FORBIDDEN,
                    "缺少可信的租户、项目或用户身份上下文");
        }
    }

    /** 判断 actor 等安全字段是否为非空白文本。 */
    private boolean hasText(String value) {
        return value != null && !value.isBlank();
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
                toDelegationView(session),
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

    /**
     * 将内部委托快照转换为可审计视图。
     *
     * <p>只暴露工具、动作、资源范围和生命周期，不暴露任何内部共享凭据或下游认证信息。</p>
     */
    private AgentDelegationView toDelegationView(AgentSessionRecord session) {
        var delegation = session.getDelegation();
        return new AgentDelegationView(
                delegation.getDelegationId(), delegation.getAgentId(), delegation.getUserActorId(),
                delegation.getTenantId(), delegation.getProjectId(), delegation.getToolCodes(),
                delegation.getActions(), delegation.getResourceScopes(), delegation.getStatus(),
                delegation.getIssuedAt(), delegation.getExpiresAt(), delegation.getRevokedAt());
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
                AgentRunPublicVariablesProjector.project(run.getVariables()),
                run.getCreateTime(),
                run.getUpdateTime(),
                run.getFinishTime(),
                run.getMessage()
        );
    }
}
