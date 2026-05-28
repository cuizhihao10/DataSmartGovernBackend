/**
 * @Author : Cui
 * @Date: 2026/05/13 23:50
 * @Description DataSmart Govern Backend - AgentToolExecutionAuditService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service;

import com.czh.datasmart.govern.agent.controller.dto.AgentRunView;
import com.czh.datasmart.govern.agent.controller.dto.AgentSessionView;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolExecutionAuditView;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolExecutionDecisionRequest;
import com.czh.datasmart.govern.agent.event.AgentToolExecutionEventPublisher;
import com.czh.datasmart.govern.agent.event.AgentToolExecutionRequiredEventSinkException;
import com.czh.datasmart.govern.agent.model.AgentToolExecutionState;
import com.czh.datasmart.govern.agent.persistence.AgentRuntimeJdbcConnectionManager;
import com.czh.datasmart.govern.agent.service.plan.AgentPlanToolSnapshot;
import com.czh.datasmart.govern.agent.service.audit.AgentToolExecutionAuditRecord;
import com.czh.datasmart.govern.agent.service.audit.AgentToolExecutionAuditStore;
import com.czh.datasmart.govern.agent.service.session.AgentRunRecord;
import com.czh.datasmart.govern.agent.service.session.AgentSessionRecord;
import com.czh.datasmart.govern.agent.service.session.AgentToolBindingRecord;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Agent 工具执行审计服务。
 *
 * <p>该服务当前只生成“计划审计”，不执行真实工具。
 * 它的价值是让每一次 Agent Run 在真正执行前，就能留下可查询的工具计划证据：
 * 哪些工具会被考虑、风险多高、是否需要审批、属于哪个租户/项目、由哪个 actor 发起、traceId 是什么。
 *
 * <p>后续真实工具适配器上线时，应在同一审计链路上追加 EXECUTING、SUCCEEDED、FAILED 等状态变化，
 * 而不是重新设计一套工具调用日志。
 */
@Slf4j
@Service
public class AgentToolExecutionAuditService {

    private final AgentToolExecutionAuditStore auditStore;
    private final AgentToolExecutionEventPublisher eventPublisher;
    private final Optional<AgentRuntimeJdbcConnectionManager> jdbcConnectionManager;

    /** Spring 运行时构造函数：数据库连接管理器只在 MySQL 持久化启用时存在，默认 memory 模式保持轻量。 */
    @Autowired
    public AgentToolExecutionAuditService(AgentToolExecutionAuditStore auditStore,
                                          AgentToolExecutionEventPublisher eventPublisher,
                                          Optional<AgentRuntimeJdbcConnectionManager> jdbcConnectionManager) {
        this.auditStore = auditStore;
        this.eventPublisher = eventPublisher;
        this.jdbcConnectionManager = jdbcConnectionManager == null ? Optional.empty() : jdbcConnectionManager;
    }

    /** 单元测试与内存模式便捷构造函数：测试只关心状态机语义时不需要手工传入 Optional.empty()。 */
    public AgentToolExecutionAuditService(AgentToolExecutionAuditStore auditStore,
                                          AgentToolExecutionEventPublisher eventPublisher) {
        this(auditStore, eventPublisher, Optional.empty());
    }

    /**
     * 为一次 Agent Run 创建工具计划审计。
     *
     * <p>如果会话没有绑定工具，则不会创建记录。
     * 如果工具需要审批或风险等级为 HIGH/CRITICAL，则初始状态为 WAITING_APPROVAL；
     * 其他工具先进入 PLANNED，表示后续编排器可以在二次权限校验后执行。
     */
    public List<AgentToolExecutionAuditView> createPlanAudits(AgentSessionRecord session,
                                                              AgentRunRecord run,
                                                              String traceId) {
        List<AgentToolExecutionAuditRecord> records = session.getToolBindings().stream()
                .map(binding -> toAuditRecord(session, run, binding, traceId))
                .toList();
        executeInPersistenceBoundary(() -> {
            auditStore.saveAll(records);
            publishInitialPlanEvents(records);
        });
        return records.stream().map(this::toView).toList();
    }

    /**
     * 根据 Python AgentPlan 的工具计划快照创建审计记录。
     *
     * <p>这个方法是“Python 负责规划，Java 负责治理”的关键落点：
     * Python Runtime 可以根据模型、Skill、记忆和工具描述符生成 ToolPlan，但它不能直接执行工具；
     * Java 控制面会把 ToolPlan 固化成审计记录，并根据风险等级、审批标记决定是否进入 WAITING_APPROVAL。
     *
     * <p>注意：这里接收的是已经由 `AgentPlanIngestionService` 校验过的内部快照，
     * 因此工具目录继承、未知工具拒绝、风险归一化等规则不放在审计服务里，避免该类承担过多入口协议职责。
     *
     * @param sessionView 会话视图，提供租户、项目、工作空间和操作者边界。
     * @param runView Java 控制面创建的运行记录。
     * @param toolPlans Python AgentPlan 中的工具计划快照。
     * @param traceId 链路追踪 ID，用于关联 Python 请求、Java 审计和后续下游工具执行。
     * @return 已创建的工具审计视图列表，供 Controller 返回或测试断言。
     */
    public List<AgentToolExecutionAuditView> createPlanAuditsFromSnapshots(AgentSessionView sessionView,
                                                                           AgentRunView runView,
                                                                           List<AgentPlanToolSnapshot> toolPlans,
                                                                           String traceId) {
        List<AgentToolExecutionAuditRecord> records = toolPlans.stream()
                .map(toolPlan -> toAuditRecord(sessionView, runView, toolPlan, traceId))
                .toList();
        executeInPersistenceBoundary(() -> {
            auditStore.saveAll(records);
            publishInitialPlanEvents(records);
        });
        return records.stream().map(this::toView).toList();
    }

    /**
     * 查询某次运行的工具审计记录。
     */
    public List<AgentToolExecutionAuditView> listByRun(String sessionId, String runId) {
        return auditStore.list(sessionId, runId).stream()
                .map(this::toView)
                .toList();
    }

    /**
     * 查询单个工具执行审计记录。
     *
     * <p>该方法主要服务 Python AI Runtime 的工具结果回填链路：
     * Python 在模型第二轮推理前，需要知道某个 auditId 对应的工具到底是 SUCCEEDED、FAILED、
     * WAITING_APPROVAL 还是 SKIPPED。这里返回的是审计视图，不暴露内存记录对象，避免外部调用方
     * 绕过审计服务直接修改状态。</p>
     *
     * @param sessionId Agent 会话 ID，用于防止跨会话读取审计记录。
     * @param runId Agent Run ID，用于防止跨运行读取审计记录。
     * @param auditId 工具执行审计 ID。
     * @return 单个工具执行审计视图。
     */
    public AgentToolExecutionAuditView getExecutionAudit(String sessionId, String runId, String auditId) {
        return toView(requireAudit(sessionId, runId, auditId));
    }

    /**
     * 人工确认某个高风险工具计划。
     *
     * <p>确认后，工具审计记录会从 WAITING_APPROVAL 回到 PLANNED。
     * 注意：这并不代表立即调用下游服务，只代表“审批门”通过；
     * 后续真实工具执行器仍必须做二次权限校验、目标资源校验、幂等校验、超时/重试控制和审计状态推进。
     */
    public AgentToolExecutionAuditView approve(String sessionId,
                                               String runId,
                                               String auditId,
                                               AgentToolExecutionDecisionRequest request) {
        AgentToolExecutionAuditRecord record = requireWaitingApprovalAudit(sessionId, runId, auditId);
        AgentToolExecutionState previousState = record.getState();
        record.approve(requireOperatorId(request), normalizeComment(request.comment()));
        persistThenPublishStateChanged(previousState, record);
        return toView(record);
    }

    /**
     * 人工拒绝某个高风险工具计划。
     *
     * <p>拒绝后进入 SKIPPED，表示该工具没有执行，也不应继续进入下游调用。
     * 后续真实编排器在读取工具计划时，应把 SKIPPED 作为“用户拒绝执行”的业务事实处理，
     * 例如给出替代方案、只执行低风险工具，或终止整个 Run。
     */
    public AgentToolExecutionAuditView reject(String sessionId,
                                              String runId,
                                              String auditId,
                                              AgentToolExecutionDecisionRequest request) {
        AgentToolExecutionAuditRecord record = requireWaitingApprovalAudit(sessionId, runId, auditId);
        AgentToolExecutionState previousState = record.getState();
        record.reject(requireOperatorId(request), normalizeComment(request.comment()));
        persistThenPublishStateChanged(previousState, record);
        return toView(record);
    }

    /**
     * 将工具计划推进到 EXECUTING。
     *
     * <p>真实工具执行必须从 PLANNED 开始。
     * 如果仍处于 WAITING_APPROVAL，说明审批未完成；如果已经 SUCCEEDED/FAILED/SKIPPED，
     * 说明该工具计划已经有结果，不允许重复执行。
     */
    public AgentToolExecutionAuditRecord requirePlannedExecutionAudit(String sessionId, String runId, String auditId) {
        AgentToolExecutionAuditRecord record = requireAudit(sessionId, runId, auditId);
        if (record.getState() != AgentToolExecutionState.PLANNED) {
            throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                    "当前工具计划不处于 PLANNED，不能执行，state=" + record.getState().name());
        }
        return record;
    }

    /**
     * 将工具计划推进到 EXECUTING。
     *
     * <p>该方法只做状态推进，不承载所有执行前安全检查。
     * 执行框架应先调用 `requirePlannedExecutionAudit(...)` 读取待执行审计，再通过工具执行守卫校验项目边界、
     * 参数完整性和非只读审批状态，全部通过后才调用本方法进入 EXECUTING。
     */
    public AgentToolExecutionAuditRecord startExecution(String sessionId, String runId, String auditId) {
        AgentToolExecutionAuditRecord record = requirePlannedExecutionAudit(sessionId, runId, auditId);
        AgentToolExecutionState previousState = record.getState();
        record.startExecution("工具计划已进入 EXECUTING，正在调用受控工具适配器。");
        persistThenPublishStateChanged(previousState, record);
        return record;
    }

    /**
     * 标记工具执行成功。
     */
    public AgentToolExecutionAuditView succeedExecution(AgentToolExecutionAuditRecord record,
                                                        String message,
                                                        String outputSummary) {
        AgentToolExecutionState previousState = record.getState();
        record.succeed(message, outputSummary);
        persistThenPublishStateChanged(previousState, record);
        return toView(record);
    }

    /**
     * 标记工具执行失败。
     */
    public AgentToolExecutionAuditView failExecution(AgentToolExecutionAuditRecord record,
                                                     String errorCode,
                                                     String message) {
        AgentToolExecutionState previousState = record.getState();
        record.fail(errorCode, message);
        persistThenPublishStateChanged(previousState, record);
        return toView(record);
    }

    /**
     * 发布初始工具计划事件。
     *
     * <p>创建审计记录本身也是一个重要事实：前端需要知道某个工具已经被模型/编排器计划出来，审批中心需要知道它是否等待审批，
     * Python Runtime 后续也可能根据这些初始事件决定是否进入等待、跳过或二次推理。这里使用 previousState=null，
     * 在事件契约中会被规范化为 NONE，表达“该审计记录刚进入状态机”。</p>
     */
    private void publishInitialPlanEvents(List<AgentToolExecutionAuditRecord> records) {
        for (AgentToolExecutionAuditRecord record : records) {
            publishStateChanged(null, record);
        }
    }

    /** 安全发布状态变更事件：普通 sink fail-open；事务 outbox 等必达 sink 在具备同事务条件时会向上抛出并触发回滚。 */
    private void publishStateChanged(AgentToolExecutionState previousState, AgentToolExecutionAuditRecord record) {
        try {
            eventPublisher.publishStateChanged(previousState, record);
        } catch (AgentToolExecutionRequiredEventSinkException exception) {
            if (isStateAndOutboxTransactionAvailable()) {
                throw exception;
            }
            log.warn("Agent 工具执行必达事件 sink 发布失败，但当前尚未启用 audit+outbox MySQL 同事务，按 fail-open 降级，auditId={}, previousState={}, currentState={}, error={}",
                    record.getAuditId(), previousState, record.getState(), exception.getMessage());
        } catch (RuntimeException exception) {
            log.warn("Agent 工具执行状态事件发布失败，auditId={}, previousState={}, currentState={}, error={}",
                    record.getAuditId(), previousState, record.getState(), exception.getMessage());
        }
    }

    /** 先保存业务状态，再发布状态事件；MySQL 双仓储模式下该组合会被外层 JDBC 事务包裹。 */
    private void persistThenPublishStateChanged(AgentToolExecutionState previousState,
                                                AgentToolExecutionAuditRecord record) {
        executeInPersistenceBoundary(() -> {
            auditStore.save(record);
            publishStateChanged(previousState, record);
        });
    }

    /**
     * 执行工具审计持久化边界。
     *
     * <p>只有 audit/outbox 都切到 MySQL 时才启用事务；半数据库化配置仍保持原调用方式，
     * 避免把“只能回滚部分事实”的链路误认为完整事务 outbox。</p>
     */
    private void executeInPersistenceBoundary(Runnable operation) {
        Optional<AgentRuntimeJdbcConnectionManager> manager = jdbcConnectionManager
                .filter(AgentRuntimeJdbcConnectionManager::isStateAndOutboxTransactionAvailable);
        if (manager.isEmpty()) {
            operation.run();
            return;
        }
        manager.get().executeInTransaction(connection -> {
            operation.run();
            return null;
        });
    }

    private boolean isStateAndOutboxTransactionAvailable() {
        return jdbcConnectionManager
                .map(AgentRuntimeJdbcConnectionManager::isStateAndOutboxTransactionAvailable)
                .orElse(false);
    }

    private AgentToolExecutionAuditRecord toAuditRecord(AgentSessionRecord session,
                                                        AgentRunRecord run,
                                                        AgentToolBindingRecord binding,
                                                        String traceId) {
        AgentToolExecutionState state = requiresApprovalBeforeExecution(binding)
                ? AgentToolExecutionState.WAITING_APPROVAL
                : AgentToolExecutionState.PLANNED;
        String message = state == AgentToolExecutionState.WAITING_APPROVAL
                ? "工具风险较高或要求审批，真实执行前必须进入人工确认/审批流程。"
                : "工具调用计划已生成，真实执行前仍需进行权限、项目范围和下游服务校验。";
        LocalDateTime now = LocalDateTime.now();
        return new AgentToolExecutionAuditRecord(
                "atea_" + UUID.randomUUID().toString().replace("-", ""),
                session.getSessionId(),
                run.getRunId(),
                binding.bindingId(),
                binding.toolCode(),
                binding.toolType().name(),
                binding.targetService(),
                binding.targetEndpoint(),
                binding.targetResourceId(),
                session.getTenantId(),
                session.getProjectId(),
                session.getWorkspaceId(),
                session.getActorId(),
                binding.riskLevel(),
                binding.executionMode(),
                binding.requiresApproval(),
                binding.readOnly(),
                binding.idempotent(),
                binding.allowedActions(),
                state,
                traceId,
                message,
                now
        );
    }

    private AgentToolExecutionAuditRecord toAuditRecord(AgentSessionView session,
                                                        AgentRunView run,
                                                        AgentPlanToolSnapshot toolPlan,
                                                        String traceId) {
        AgentToolExecutionState state = requiresApprovalBeforeExecution(toolPlan)
                ? AgentToolExecutionState.WAITING_APPROVAL
                : AgentToolExecutionState.PLANNED;
        String message = state == AgentToolExecutionState.WAITING_APPROVAL
                ? "Python AgentPlan 中的工具计划已进入 Java 控制面；该工具风险较高或显式要求审批，真实执行前必须先完成人工确认。"
                : "Python AgentPlan 中的工具计划已进入 Java 控制面；真实执行前仍需经过 Java 权限、项目范围、幂等和下游健康校验。";
        LocalDateTime now = LocalDateTime.now();
        return new AgentToolExecutionAuditRecord(
                "atea_" + UUID.randomUUID().toString().replace("-", ""),
                session.sessionId(),
                run.runId(),
                "plan:" + run.runId() + ":" + toolPlan.sequence(),
                toolPlan.toolCode(),
                toolPlan.toolType(),
                toolPlan.targetService(),
                toolPlan.targetEndpoint(),
                toolPlan.targetResourceId(),
                session.tenantId(),
                session.projectId(),
                session.workspaceId(),
                session.actorId(),
                toolPlan.riskLevel(),
                toolPlan.executionMode(),
                toolPlan.requiresApproval(),
                toolPlan.readOnly(),
                toolPlan.idempotent(),
                toolPlan.allowedActions() == null ? List.of() : toolPlan.allowedActions(),
                toolPlan.reason(),
                toolPlan.arguments() == null ? Map.of() : toolPlan.arguments(),
                toolPlan.governanceHints() == null ? Map.of() : toolPlan.governanceHints(),
                toolPlan.parameterValidation() == null ? Map.of() : toolPlan.parameterValidation(),
                state,
                traceId,
                message,
                now
        );
    }

    /**
     * 判断工具是否必须先进入人工确认/审批，再允许真实执行。
     *
     * <p>这里刻意做成服务层的公开业务规则，而不是只放在审计记录创建的私有方法里，
     * 是因为“哪些工具需要审批”会同时影响多个产品面：
     * 1. 工具执行审计初始状态：需要审批的工具应进入 WAITING_APPROVAL；
     * 2. Agent Run 初始状态：只要本次运行涉及高风险工具，Run 本身就不能继续自动规划到执行；
     * 3. 前端交互：需要在会话/运行详情中给用户明确提示，而不是等工具执行失败后才发现缺审批；
     * 4. 后续租户策略：未来可以在这里叠加租户安全策略、项目敏感级别、操作时间窗口、数据分级等规则。
     *
     * <p>当前阶段采用最小但安全的商业化基线：
     * - 工具目录显式 requiresApproval=true 时，一定需要审批；
     * - riskLevel=HIGH/CRITICAL 时，即使没有单独声明 requiresApproval，也按高风险工具处理。
     */
    public boolean requiresApprovalBeforeExecution(AgentToolBindingRecord binding) {
        if (Boolean.TRUE.equals(binding.requiresApproval())) {
            return true;
        }
        return "HIGH".equals(binding.riskLevel()) || "CRITICAL".equals(binding.riskLevel());
    }

    /**
     * 判断 Python AgentPlan 的工具计划是否需要审批。
     *
     * <p>这里使用与绑定型工具相同的安全底线：显式要求审批、HIGH 或 CRITICAL 都必须先进入 WAITING_APPROVAL。
     * 这样可以避免 Python 计划虽然把 run 标记为高风险，但具体工具审计却仍处于 PLANNED 的状态不一致。
     */
    public boolean requiresApprovalBeforeExecution(AgentPlanToolSnapshot toolPlan) {
        if (Boolean.TRUE.equals(toolPlan.requiresApproval())) {
            return true;
        }
        return "HIGH".equals(toolPlan.riskLevel()) || "CRITICAL".equals(toolPlan.riskLevel());
    }

    private AgentToolExecutionAuditRecord requireWaitingApprovalAudit(String sessionId,
                                                                      String runId,
                                                                      String auditId) {
        AgentToolExecutionAuditRecord record = auditStore.findById(auditId)
                .orElseThrow(() -> new PlatformBusinessException(PlatformErrorCode.NOT_FOUND,
                        "工具执行审计记录不存在，auditId=" + auditId));
        if (!sessionId.equals(record.getSessionId()) || !runId.equals(record.getRunId())) {
            throw new PlatformBusinessException(PlatformErrorCode.NOT_FOUND,
                    "工具执行审计记录不属于当前 Agent Run，auditId=" + auditId);
        }
        if (record.getState() != AgentToolExecutionState.WAITING_APPROVAL) {
            throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                    "当前工具计划不处于 WAITING_APPROVAL，不能执行人工确认/拒绝，state=" + record.getState().name());
        }
        return record;
    }

    private AgentToolExecutionAuditRecord requireAudit(String sessionId, String runId, String auditId) {
        AgentToolExecutionAuditRecord record = auditStore.findById(auditId)
                .orElseThrow(() -> new PlatformBusinessException(PlatformErrorCode.NOT_FOUND,
                        "工具执行审计记录不存在，auditId=" + auditId));
        if (!sessionId.equals(record.getSessionId()) || !runId.equals(record.getRunId())) {
            throw new PlatformBusinessException(PlatformErrorCode.NOT_FOUND,
                    "工具执行审计记录不属于当前 Agent Run，auditId=" + auditId);
        }
        return record;
    }

    private String requireOperatorId(AgentToolExecutionDecisionRequest request) {
        if (request == null || request.operatorId() == null || request.operatorId().isBlank()) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST, "人工决策人不能为空");
        }
        return request.operatorId().trim();
    }

    private String normalizeComment(String comment) {
        return comment == null ? "" : comment.trim();
    }

    private AgentToolExecutionAuditView toView(AgentToolExecutionAuditRecord record) {
        return new AgentToolExecutionAuditView(
                record.getAuditId(),
                record.getSessionId(),
                record.getRunId(),
                record.getBindingId(),
                record.getToolCode(),
                record.getToolType(),
                record.getTargetService(),
                record.getTargetEndpoint(),
                record.getTargetResourceId(),
                record.getTenantId(),
                record.getProjectId(),
                record.getWorkspaceId(),
                record.getActorId(),
                record.getRiskLevel(),
                record.getExecutionMode(),
                record.getRequiresApproval(),
                record.getReadOnly(),
                record.getIdempotent(),
                record.getAllowedActions(),
                record.getPlanReason(),
                record.getPlanArguments(),
                record.getGovernanceHints(),
                record.getParameterValidation(),
                record.getState().name(),
                record.getTraceId(),
                record.getMessage(),
                record.getApprovalOperatorId(),
                record.getApprovalComment(),
                record.getApprovalTime(),
                record.getExecutionStartTime(),
                record.getExecutionFinishTime(),
                record.getOutputSummary(),
                record.getErrorCode(),
                record.getCreateTime(),
                record.getUpdateTime()
        );
    }
}
